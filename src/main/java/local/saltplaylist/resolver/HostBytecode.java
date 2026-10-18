package local.saltplaylist.resolver;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Shared class-file operations for the feature rules. */
final class HostBytecode {
    private HostBytecode() {}

    static List<ClassModel> containing(ZipFile zip, String... anchors) throws IOException {
        var result = new ArrayList<ClassModel>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Symbol scan cancelled");
            var entry = entries.nextElement();
            if (!entry.getName().endsWith(".class")) continue;
            byte[] bytes;
            try (var input = zip.getInputStream(entry)) { bytes = input.readAllBytes(); }
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            for (String anchor : anchors) {
                if (text.contains(anchor)) { result.add(ClassFile.of().parse(bytes)); break; }
            }
        }
        return result;
    }

    static List<String> strings(MethodModel method) {
        var result = new ArrayList<String>();
        method.code().ifPresent(code -> {
            for (var element : code) {
                if (element instanceof ConstantInstruction constant && constant.constantValue() instanceof String value) {
                    result.add(value);
                }
            }
        });
        return result;
    }

    static List<InvokeInstruction> calls(MethodModel method) {
        var result = new ArrayList<InvokeInstruction>();
        method.code().ifPresent(code -> { for (var element : code) if (element instanceof InvokeInstruction call) result.add(call); });
        return result;
    }

    static boolean calls(MethodModel method, String owner, String name) {
        return calls(method).stream().anyMatch(call -> call.owner().asInternalName().equals(owner) && call.name().equalsString(name));
    }

    static boolean calls(MethodModel method, HostSymbols.Ref target) {
        return calls(method).stream().anyMatch(call -> call.owner().asInternalName().replace('/', '.').equals(target.owner())
                && call.name().equalsString(target.name()) && call.type().equalsString(target.descriptor()));
    }

    static boolean publicStatic(MethodModel method) {
        return method.flags().has(AccessFlag.PUBLIC) && method.flags().has(AccessFlag.STATIC);
    }

    static HostSymbols.Ref ref(MethodModel method) {
        return new HostSymbols.Ref(method.parent().orElseThrow().thisClass().asInternalName().replace('/', '.'),
                method.methodName().stringValue(), method.methodType().stringValue());
    }

    static MethodModel unique(String point, List<MethodModel> methods, Consumer<String> log) throws IOException {
        if (methods.size() != 1) {
            log.accept("HookPoint[" + point + "] state=MISSING candidates=" + methods.stream().map(HostBytecode::ref).toList());
            throw new IOException(point + ": expected one candidate, found " + methods.size());
        }
        log.accept("HookPoint[" + point + "] state=FOUND source=scan target=" + ref(methods.getFirst()));
        return methods.getFirst();
    }

    static MethodModel trace(String point, List<ClassModel> classes, String anchor,
                             java.util.function.Predicate<MethodModel> signature, Consumer<String> log) throws IOException {
        var methods = classes.stream().flatMap(model -> model.methods().stream()).filter(HostBytecode::publicStatic)
                .filter(signature).filter(method -> strings(method).stream().anyMatch(value -> value.startsWith(anchor + " (")))
                .toList();
        return unique(point, methods, log);
    }
}
