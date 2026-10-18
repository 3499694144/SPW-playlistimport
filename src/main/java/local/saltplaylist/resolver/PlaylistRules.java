package local.saltplaylist.resolver;

import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Playlist database, snapshot and transaction rules. No obfuscated names or rankings. */
public final class PlaylistRules {
    static final String TRACK_CLASS = "com.xuncorp.voxzen.data.entity.Track";
    static final String PLAYLIST_CLASS = "com.xuncorp.voxzen.data.entity.Playlist";
    private static final String DUPLICATE_GUARD = "Imported playlist contains duplicate tracks";
    private static final String TRACK_QUERY = "SELECT * FROM Track ORDER BY id COLLATE BINARY ASC";
    private static final String CONTINUATION = "Lkotlin/coroutines/Continuation;";
    private static final String CREATE_TYPE = "(Ljava/lang/String;Ljava/lang/String;Ljava/util/List;" + CONTINUATION + ")Ljava/lang/Object;";
    private static final String READ_TYPE = "(" + CONTINUATION + ")Ljava/lang/Object;";

    private PlaylistRules() {}

    public static HostSymbols scan(Path hostJar, Consumer<String> log) throws IOException {
        try (ZipFile zip = new ZipFile(hostJar.toFile())) {
            var providers = new ArrayList<ClassModel>();
            var playlistDaos = new ArrayList<ClassModel>();
            var transactionClasses = new ArrayList<ClassModel>();
            var entries = zip.entries();
            // Filter bytes before parsing; never retain a model for every host/dependency class.
            while (entries.hasMoreElements()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Symbol scan cancelled");
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                byte[] bytes;
                try (var input = zip.getInputStream(entry)) { bytes = input.readAllBytes(); }
                String ascii = new String(bytes, StandardCharsets.ISO_8859_1);
                if (ascii.contains("spw.db")) providers.add(ClassFile.of().parse(bytes));
                if (ascii.contains(DUPLICATE_GUARD)) playlistDaos.add(ClassFile.of().parse(bytes));
                if (ascii.contains("performInTransactionSuspending") && ascii.contains("Playlist")) {
                    transactionClasses.add(ClassFile.of().parse(bytes));
                }
            }

            var providerMethods = new ArrayList<MethodModel>();
            for (ClassModel model : providers) {
                boolean buildsRoom = model.methods().stream().anyMatch(method ->
                        HostBytecode.strings(method).contains("spw.db")
                                && HostBytecode.calls(method, "androidx/room/RoomDatabase$Builder", "build"));
                if (!buildsRoom) continue;
                for (MethodModel method : model.methods()) {
                    if (method.flags().has(AccessFlag.PUBLIC) && method.flags().has(AccessFlag.STATIC)
                            && method.methodTypeSymbol().parameterCount() == 0
                            && HostBytecode.calls(method, "kotlin/Lazy", "getValue")) {
                        String returnType = method.methodTypeSymbol().returnType().descriptorString();
                        if (!returnType.startsWith("L")) continue;
                        ClassModel database = read(zip, returnType.substring(1, returnType.length() - 1));
                        if (database.superclass().map(c -> c.asInternalName().equals("androidx/room/RoomDatabase")).orElse(false)) {
                            providerMethods.add(method);
                        }
                    }
                }
            }
            MethodModel provider = HostBytecode.unique("DatabaseCapture", providerMethods, log);
            String databaseDescriptor = provider.methodTypeSymbol().returnType().descriptorString();
            ClassModel database = read(zip, databaseDescriptor.substring(1, databaseDescriptor.length() - 1));

            var createMethods = new ArrayList<MethodModel>();
            for (ClassModel dao : playlistDaos) {
                if (!dao.flags().has(AccessFlag.INTERFACE) || !annotated(dao, "Landroidx/room/Dao;")) continue;
                for (MethodModel method : dao.methods()) {
                    if (!method.flags().has(AccessFlag.STATIC) && method.methodType().equalsString(CREATE_TYPE)
                            && annotated(method, "Landroidx/room/Transaction;")) {
                        boolean reachesGuard = HostBytecode.calls(method).stream().anyMatch(call -> {
                            if (!call.owner().asInternalName().equals(dao.thisClass().asInternalName())) return false;
                            return dao.methods().stream().anyMatch(body ->
                                    body.methodName().equalsString(call.name().stringValue())
                                            && body.methodType().equalsString(call.type().stringValue())
                                            && HostBytecode.strings(body).contains(DUPLICATE_GUARD)
                                            && HostBytecode.calls(body, PLAYLIST_CLASS.replace('.', '/'), "<init>")
                                            && HostBytecode.calls(body, "com/xuncorp/voxzen/data/entity/PlaylistTrack", "<init>"));
                        });
                        if (reachesGuard) createMethods.add(method);
                    }
                }
            }
            MethodModel create = HostBytecode.unique("CreatePlaylist", createMethods, log);
            String playlistDaoName = create.parent().orElseThrow().thisClass().asInternalName();
            MethodModel playlistGetter = daoGetter(database, playlistDaoName, "PlaylistDao", log);

            var snapshots = new ArrayList<MethodModel>();
            for (MethodModel getter : database.methods()) {
                if (!getter.flags().has(AccessFlag.ABSTRACT) || getter.methodTypeSymbol().parameterCount() != 0) continue;
                String result = getter.methodTypeSymbol().returnType().descriptorString();
                if (!result.startsWith("L")) continue;
                ClassModel dao = read(zip, result.substring(1, result.length() - 1));
                if (!dao.flags().has(AccessFlag.INTERFACE) || !annotated(dao, "Landroidx/room/Dao;")) continue;
                for (MethodModel method : dao.methods()) {
                    if (method.methodType().equalsString(READ_TYPE)
                            && TRACK_QUERY.equals(annotationString(method, "Landroidx/room/Query;", "value"))) {
                        snapshots.add(method);
                    }
                }
            }
            MethodModel snapshot = HostBytecode.unique("TrackSnapshot", snapshots, log);
            String trackDaoName = snapshot.parent().orElseThrow().thisClass().asInternalName();
            MethodModel trackGetter = daoGetter(database, trackDaoName, "TrackDao", log);

            // Room's generated implementation must wrap the entire import in its transaction API.
            ClassModel databaseImpl = read(zip, database.thisClass().asInternalName() + "_Impl");
            exactMethod(databaseImpl, playlistGetter);
            var implementations = new ArrayList<ClassModel>();
            for (ClassModel candidate : transactionClasses) {
                if (candidate.interfaces().stream().anyMatch(type -> type.asInternalName().equals(playlistDaoName))
                        && databaseImpl.methods().stream().anyMatch(method ->
                        HostBytecode.calls(method, candidate.thisClass().asInternalName(), "<init>"))) {
                    implementations.add(candidate);
                }
            }
            ClassModel implementation = uniqueClass("PlaylistTransactionImplementation", implementations, log);
            MethodModel generatedCreate = exactMethod(implementation, create);
            if (!HostBytecode.calls(generatedCreate, "androidx/room/util/DBUtil", "performInTransactionSuspending")) {
                throw new IOException("CreatePlaylist does not use Room's suspending transaction");
            }
            return new HostSymbols(HostBytecode.ref(provider), HostBytecode.ref(playlistGetter), HostBytecode.ref(trackGetter),
                    HostBytecode.ref(snapshot), HostBytecode.ref(create));
        } catch (RuntimeException failure) {
            log.accept("Scan Errors: " + failure);
            throw new IOException("Host symbol scan failed: " + failure.getMessage(), failure);
        }
    }

    private static MethodModel daoGetter(ClassModel database, String dao, String point, Consumer<String> log) throws IOException {
        return HostBytecode.unique(point, database.methods().stream().filter(m -> m.flags().has(AccessFlag.PUBLIC)
                && m.flags().has(AccessFlag.ABSTRACT) && m.methodType().equalsString("()L" + dao + ";")).toList(), log);
    }

    private static MethodModel exactMethod(ClassModel owner, MethodModel method) throws IOException {
        var matches = owner.methods().stream().filter(candidate -> candidate.methodName().equalsString(method.methodName().stringValue())
                && candidate.methodType().equalsString(method.methodType().stringValue())).toList();
        if (matches.size() != 1) throw new IOException("Generated method missing/ambiguous: " + HostBytecode.ref(method));
        return matches.getFirst();
    }

    static ClassModel read(ZipFile zip, String internalName) throws IOException {
        var entry = zip.getEntry(internalName + ".class");
        if (entry == null) throw new IOException("Class missing: " + internalName);
        try (var input = zip.getInputStream(entry)) { return ClassFile.of().parse(input.readAllBytes()); }
    }

    private static ClassModel uniqueClass(String point, List<ClassModel> candidates, Consumer<String> log) throws IOException {
        if (candidates.size() != 1) {
            log.accept("HookPoint[" + point + "] state=MISSING candidates=" + candidates.stream().map(c -> c.thisClass().asInternalName()).toList());
            throw new IOException(point + ": expected one implementation, found " + candidates.size());
        }
        return candidates.getFirst();
    }

    private static List<Annotation> annotations(AttributedElement element) {
        var result = new ArrayList<Annotation>();
        element.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a -> result.addAll(a.annotations()));
        element.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(a -> result.addAll(a.annotations()));
        return result;
    }

    private static boolean annotated(AttributedElement element, String type) {
        return annotations(element).stream().anyMatch(a -> a.className().equalsString(type));
    }

    static String annotationString(AttributedElement element, String type, String key) {
        for (Annotation annotation : annotations(element)) {
            if (!annotation.className().equalsString(type)) continue;
            for (var pair : annotation.elements()) {
                if (pair.name().equalsString(key) && pair.value() instanceof AnnotationValue.OfString value) {
                    return value.stringValue().strip().replaceAll("\\s+", " ");
                }
            }
        }
        return null;
    }

}
