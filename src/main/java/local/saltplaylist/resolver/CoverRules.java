package local.saltplaylist.resolver;

import java.io.IOException;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Follows PlaylistManager's image read and the playlist_cover field assignment. */
final class CoverRules {
    private static final String UPDATE = "UPDATE Playlist SET coverModifiedTime = :time WHERE id = :playlistId";
    static final String PREPARED_UPDATE = "UPDATE Playlist SET coverModifiedTime = ? WHERE id = ?";
    private static final String READ_IMAGE = "(Ljava/lang/String;II)Ljava/lang/Object;";
    private static final String BLOCK_TYPE = "(Ljava/lang/String;JLjava/lang/String;Landroidx/sqlite/SQLiteConnection;)Lkotlin/Unit;";
    private static final String PERFORM_TYPE = "(Landroidx/room/RoomDatabase;ZZLkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;";

    static CoverSymbols scan(Path hostJar, Consumer<String> log) throws IOException {
        try (var zip = new ZipFile(hostJar.toFile())) {
            var classes = HostBytecode.containing(zip, "playlist_cover", "PlaylistManager.kt", UPDATE, PREPARED_UPDATE);
            var directories = new ArrayList<MethodModel>();
            var readers = new ArrayList<MethodModel>();
            var updates = new ArrayList<MethodModel>();
            for (ClassModel model : classes) {
                for (MethodModel method : model.methods()) {
                    if (method.methodName().equalsString("<clinit>") && HostBytecode.strings(method).contains("playlist_cover")) {
                        boolean afterAnchor = false;
                        boolean resolvesFile = false;
                        for (var instruction : method.code().orElseThrow()) {
                            if (instruction instanceof ConstantInstruction constant && constant.constantValue() instanceof String value) {
                                if (afterAnchor) break;
                                afterAnchor = value.equals("playlist_cover");
                            }
                            if (!afterAnchor) continue;
                            if (instruction instanceof java.lang.classfile.instruction.InvokeInstruction call
                                    && call.owner().asInternalName().equals("kotlin/io/FilesKt") && call.name().equalsString("resolve")) {
                                resolvesFile = true;
                            }
                            if (instruction instanceof FieldInstruction field && field.opcode() == Opcode.PUTSTATIC) {
                                if (!resolvesFile || !field.type().equalsString("Ljava/io/File;")) {
                                    throw new IOException("playlist_cover is no longer a resolved File field");
                                }
                                for (MethodModel getter : model.methods()) {
                                    if (!HostBytecode.publicStatic(getter) || !getter.methodType().equalsString("()Ljava/io/File;")) continue;
                                    if (getter.code().isEmpty()) continue;
                                    for (var operation : getter.code().get()) {
                                        if (operation instanceof FieldInstruction read && read.opcode() == Opcode.GETSTATIC
                                                && read.owner().equals(field.owner()) && read.name().equals(field.name())) directories.add(getter);
                                    }
                                }
                                break;
                            }
                        }
                    }
                    if (HostBytecode.strings(method).contains("png")
                            && HostBytecode.calls(method, "javax/imageio/ImageIO", "write")
                            && HostBytecode.calls(method, PlaylistRules.PLAYLIST_CLASS.replace('.', '/'), "getId")) {
                        int dimensions = 0;
                        for (var operation : method.code().orElseThrow()) {
                            if (operation instanceof ConstantInstruction constant && Integer.valueOf(500).equals(constant.constantValue())) dimensions++;
                        }
                        if (dimensions != 2) throw new IOException("PlaylistManager's 500x500 image contract changed");
                        for (var call : HostBytecode.calls(method)) {
                            if (call.owner().asInternalName().equals("com/xuncorp/voxzen/tag/TagParser") && call.type().equalsString(READ_IMAGE)) {
                                ClassModel parser = PlaylistRules.read(zip, call.owner().asInternalName());
                                parser.methods().stream().filter(m -> m.methodName().equalsString(call.name().stringValue())
                                        && m.methodType().equalsString(READ_IMAGE) && m.flags().has(AccessFlag.PUBLIC)
                                        && HostBytecode.calls(m, "com/xuncorp/voxzen/util/ImageBitmapUtil", "decodeToExactSize$default"))
                                        .forEach(readers::add);
                            }
                        }
                    }
                    if (method.methodTypeSymbol().parameterCount() == 3 && method.methodType().stringValue().startsWith("(Ljava/lang/String;J")
                            && method.flags().has(AccessFlag.ABSTRACT)
                            && UPDATE.equals(PlaylistRules.annotationString(method, "Landroidx/room/Query;", "value"))) updates.add(method);
                }
            }
            var directory = HostBytecode.ref(HostBytecode.unique("PlaylistCoverDirectory", directories, log));
            var reader = HostBytecode.ref(HostBytecode.unique("PlaylistCoverImage", readers, log));
            var timestamp = HostBytecode.ref(HostBytecode.unique("PlaylistCoverTimestamp", updates, log));
            var blocks = new ArrayList<MethodModel>();
            for (var model : classes) {
                if (model.interfaces().stream().noneMatch(type -> type.asInternalName().replace('/', '.').equals(timestamp.owner()))) continue;
                for (var method : model.methods()) {
                    if (!method.methodName().equalsString(timestamp.name()) || !method.methodType().equalsString(timestamp.descriptor())
                            || !HostBytecode.strings(method).contains(PREPARED_UPDATE)
                            || !HostBytecode.calls(method, "androidx/room/util/DBUtil", "performSuspending")) continue;
                    for (var operation : method.code().orElseThrow()) {
                        if (!(operation instanceof InvokeDynamicInstruction lambda)
                                || !lambda.bootstrapMethod().owner().descriptorString().equals("Ljava/lang/invoke/LambdaMetafactory;")) continue;
                        for (var argument : lambda.bootstrapArgs()) {
                            if (!(argument instanceof DirectMethodHandleDesc target)
                                    || !target.owner().equals(model.thisClass().asSymbol()) || !target.lookupDescriptor().equals(BLOCK_TYPE)) continue;
                            model.methods().stream().filter(block -> block.methodName().equalsString(target.methodName())
                                    && block.methodType().equalsString(BLOCK_TYPE)
                                    && HostBytecode.calls(block, "androidx/sqlite/SQLiteConnection", "prepare")
                                    && HostBytecode.calls(block, "androidx/sqlite/SQLiteStatement", "bindLong")
                                    && HostBytecode.calls(block, "androidx/sqlite/SQLiteStatement", "bindText")
                                    && HostBytecode.calls(block, "androidx/sqlite/SQLiteStatement", "step"))
                                    .forEach(blocks::add);
                        }
                    }
                }
            }
            var block = HostBytecode.ref(HostBytecode.unique("PlaylistCoverUpdateBlock", blocks, log));
            var util = PlaylistRules.read(zip, "androidx/room/util/DBUtil");
            var transaction = HostBytecode.ref(HostBytecode.unique("PlaylistCoverTransaction", util.methods().stream()
                    .filter(method -> method.methodName().equalsString("performSuspending") && method.methodType().equalsString(PERFORM_TYPE)
                            && HostBytecode.publicStatic(method)).toList(), log));
            return new CoverSymbols(directory, reader, timestamp, block, transaction);
        }
    }
}
