package local.saltplaylist.resolver;

import java.io.IOException;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Identifies FileKit's Windows COM picker and the AWT parent/settings contract. */
final class FilePickerRules {
    private static final String SHARED_PICKER = "Lio/github/vinceglb/filekit/dialogs/platform/PlatformFilePicker$Companion;";

    static FilePickerSymbols scan(Path hostJar, Consumer<String> log) throws IOException {
        try (var zip = new ZipFile(hostJar.toFile())) {
            var classes = HostBytecode.containing(zip, "WindowsFilePicker", "FileKitDialogParent", "FileKitDialogSettings(title=", SHARED_PICKER);
            var opens = new ArrayList<MethodModel>();
            for (var model : classes) {
                boolean windows = false;
                for (var constant : model.constantPool()) {
                    if (constant instanceof Utf8Entry value && value.stringValue().contains("io/github/vinceglb/filekit/dialogs/platform/windows/WindowsFilePicker")) windows = true;
                }
                if (!windows) continue;
                model.methods().stream().filter(method -> method.methodName().equalsString("openFilePicker")
                        && method.flags().has(AccessFlag.PUBLIC) && method.code().isPresent()
                        && method.methodTypeSymbol().parameterCount() == 4
                        && method.methodType().stringValue().startsWith("(Ljava/util/Set;Lio/github/vinceglb/filekit/PlatformFile;")
                        && method.methodType().stringValue().endsWith("Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"))
                        .forEach(opens::add);
            }
            var open = HostBytecode.unique("OpenFilePicker", opens, log);
            var windows = open.parent().orElseThrow();
            var factories = new ArrayList<MethodModel>();
            for (var model : classes) {
                boolean companion = false;
                for (var constant : model.constantPool()) {
                    if (constant instanceof Utf8Entry value && value.equalsString(SHARED_PICKER)) companion = true;
                }
                if (!companion) continue;
                model.methods().stream().filter(method -> HostBytecode.publicStatic(method)
                        && method.methodTypeSymbol().parameterCount() == 0 && method.code().isPresent()
                        && windows.interfaces().stream().anyMatch(type -> type.asSymbol().equals(method.methodTypeSymbol().returnType()))
                        && HostBytecode.calls(method, "kotlin/Lazy", "getValue")
                        && sharedLazyInitializesWindows(model, method, windows)).forEach(factories::add);
            }
            var picker = HostBytecode.unique("FilePicker", factories, log);
            String descriptor = open.methodTypeSymbol().parameterType(2).descriptorString();
            var settingsType = PlaylistRules.read(zip, descriptor.substring(1, descriptor.length() - 1));
            boolean settingsAnchor = false;
            for (var constant : settingsType.constantPool()) {
                if (constant instanceof Utf8Entry value && value.stringValue().startsWith("FileKitDialogSettings(title=")) settingsAnchor = true;
            }
            if (!settingsAnchor) throw new IOException("FileKit dialog settings semantic anchor missing");
            var settings = HostBytecode.unique("FilePickerSettings", settingsType.methods().stream()
                    .filter(method -> constructor(method) && method.methodTypeSymbol().parameterCount() == 4
                            && method.methodTypeSymbol().parameterType(0).descriptorString().equals("Ljava/lang/String;")
                            && method.methodTypeSymbol().parameterType(3).descriptorString().equals("I")).toList(), log);
            String parentType = settings.methodTypeSymbol().parameterType(1).descriptorString();
            var parents = new ArrayList<MethodModel>();
            for (var model : classes) {
                if (model.superclass().isEmpty() || !model.superclass().get().asSymbol().descriptorString().equals(parentType)) continue;
                model.methods().stream().filter(method -> constructor(method)
                        && method.methodType().equalsString("(Ljava/awt/Window;)V")).forEach(parents::add);
            }
            return new FilePickerSymbols(HostBytecode.ref(picker), HostBytecode.ref(settings),
                    HostBytecode.ref(HostBytecode.unique("FilePickerParent", parents, log)), HostBytecode.ref(open));
        }
    }

    private static boolean constructor(MethodModel method) {
        return method.methodName().equalsString("<init>") && method.flags().has(AccessFlag.PUBLIC);
    }

    /** Bind the getter's Lazy field to the host's platform initializer, without resolving by names or order. */
    private static boolean sharedLazyInitializesWindows(ClassModel owner, MethodModel getter, ClassModel windows) {
        var reads = new ArrayList<FieldInstruction>();
        for (var element : getter.code().orElseThrow()) {
            if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETSTATIC
                    && field.owner().asInternalName().equals(owner.thisClass().asInternalName())
                    && field.type().equalsString("Lkotlin/Lazy;")) reads.add(field);
        }
        if (reads.size() != 1) return false;
        var lazy = reads.getFirst();
        if (owner.fields().stream().filter(field -> field.fieldName().equalsString(lazy.name().stringValue())
                && field.fieldType().equalsString(lazy.type().stringValue())
                && field.flags().has(AccessFlag.STATIC) && field.flags().has(AccessFlag.FINAL)).count() != 1) return false;
        var initializers = owner.methods().stream().filter(method -> method.methodName().equalsString("<clinit>")).toList();
        if (initializers.size() != 1 || initializers.getFirst().code().isEmpty()) return false;
        var code = new ArrayList<Instruction>();
        for (var element : initializers.getFirst().code().orElseThrow()) {
            if (element instanceof Instruction instruction) code.add(instruction);
        }
        int writes = 0;
        int bound = 0;
        for (int i = 0; i < code.size(); i++) {
            if (!(code.get(i) instanceof FieldInstruction field) || field.opcode() != Opcode.PUTSTATIC
                    || !field.owner().asInternalName().equals(lazy.owner().asInternalName())
                    || !field.name().equalsString(lazy.name().stringValue()) || !field.type().equalsString(lazy.type().stringValue())) continue;
            writes++;
            if (i < 2 || !(code.get(i - 1) instanceof InvokeInstruction call)
                    || !call.owner().asInternalName().equals("kotlin/LazyKt") || !call.name().equalsString("lazy")
                    || !call.type().equalsString("(Lkotlin/jvm/functions/Function0;)Lkotlin/Lazy;")
                    || !(code.get(i - 2) instanceof InvokeDynamicInstruction lambda)
                    || !lambda.type().equalsString("()Lkotlin/jvm/functions/Function0;")
                    || !lambda.bootstrapMethod().owner().descriptorString().equals("Ljava/lang/invoke/LambdaMetafactory;")) continue;
            int matchingTargets = 0;
            for (var argument : lambda.bootstrapArgs()) {
                if (!(argument instanceof DirectMethodHandleDesc target)
                        || !target.owner().equals(owner.thisClass().asSymbol())
                        || !target.lookupDescriptor().equals(getter.methodType().stringValue())) continue;
                var bodies = owner.methods().stream().filter(method -> method.methodName().equalsString(target.methodName())
                        && method.methodType().equalsString(target.lookupDescriptor()) && method.flags().has(AccessFlag.STATIC)).toList();
                if (bodies.size() != 1 || bodies.getFirst().code().isEmpty()) continue;
                var body = bodies.getFirst();
                int allocations = 0;
                for (var operation : body.code().orElseThrow()) {
                    if (operation instanceof NewObjectInstruction allocation
                            && allocation.className().asInternalName().equals(windows.thisClass().asInternalName())) allocations++;
                }
                if (allocations == 1 && HostBytecode.calls(body, windows.thisClass().asInternalName(), "<init>")) matchingTargets++;
            }
            if (matchingTargets == 1) bound++;
        }
        return writes == 1 && bound == 1;
    }
}
