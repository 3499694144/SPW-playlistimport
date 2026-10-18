package local.saltplaylist.resolver;

import java.io.IOException;
import java.lang.classfile.MethodModel;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Salt UI trace anchors plus the callable component signatures. */
final class ImportUiRules {
    private static final String COMPOSER = "Landroidx/compose/runtime/Composer;";
    private static final String MODIFIER = "Landroidx/compose/ui/Modifier;";
    private static final String FUNCTION0 = "Lkotlin/jvm/functions/Function0;";
    private static final String STRING = "Ljava/lang/String;";

    static ImportUiSymbols scan(Path hostJar, Consumer<String> log) throws IOException {
        try (var zip = new ZipFile(hostJar.toFile())) {
            var classes = HostBytecode.containing(zip, "com.xuncorp.voxzen.ui.navigation.AppNavigation", "NavigationContent.desktop.kt",
                    "com.moriafly.salt.ui", "androidx.compose.foundation.lazy.LazyColumn");
            var anchor = HostBytecode.ref(HostBytecode.trace("ImportUiAnchor", classes,
                    "com.xuncorp.voxzen.ui.navigation.AppNavigation",
                    method -> method.methodType().equalsString("(" + COMPOSER + "I)V"), log));
            var caller = HostBytecode.ref(HostBytecode.unique("ImportUiCaller", classes.stream()
                    .flatMap(model -> model.methods().stream())
                    .filter(method -> method.methodType().equalsString("(Landroidx/compose/foundation/layout/BoxScope;"
                            + COMPOSER + "I)Lkotlin/Unit;"))
                    .filter(method -> HostBytecode.strings(method).stream().anyMatch(value ->
                            value.startsWith("com.xuncorp.voxzen.ui.main.") && value.contains(" (NavigationContent.desktop.kt:")))
                    .filter(method -> HostBytecode.calls(method, anchor)).toList(), log));
            var callerClass = PlaylistRules.read(zip, caller.owner().replace('.', '/'));
            long calls = callerClass.methods().stream().flatMap(method -> HostBytecode.calls(method).stream())
                    .filter(call -> call.owner().asInternalName().replace('/', '.').equals(anchor.owner())
                            && call.name().equalsString(anchor.name()) && call.type().equalsString(anchor.descriptor())).count();
            if (calls != 1) throw new IOException("UI Hook requires exactly one AppNavigation call in its caller class; found " + calls);
            var dialog = HostBytecode.ref(HostBytecode.trace("ImportDialog", classes, "com.moriafly.salt.ui.dialog.ColumnDialog",
                    method -> method.methodType().equalsString("(" + FUNCTION0 + FUNCTION0 + STRING + "ZZ" + STRING + STRING
                            + "Lkotlin/jvm/functions/Function3;" + COMPOSER + "II)V"), log));
            var text = HostBytecode.ref(HostBytecode.trace("ImportText", classes, "com.moriafly.salt.ui.Text",
                    method -> parameters(method, 21) && method.methodType().stringValue().startsWith("(" + STRING + MODIFIER + "JJ"), log));
            var item = HostBytecode.ref(HostBytecode.trace("ImportItem", classes, "com.moriafly.salt.ui.Item",
                    method -> parameters(method, 17) && method.methodType().stringValue().startsWith("(" + FUNCTION0 + STRING + MODIFIER + "Z"), log));
            var edit = HostBytecode.ref(HostBytecode.trace("ImportEdit", classes, "com.moriafly.salt.ui.ItemEdit",
                    method -> parameters(method, 13) && method.methodType().stringValue().startsWith("(" + STRING
                            + "Lkotlin/jvm/functions/Function1;" + MODIFIER + STRING + "JZ"), log));
            var list = HostBytecode.ref(HostBytecode.trace("ImportLazyList", classes, "androidx.compose.foundation.lazy.LazyColumn",
                    method -> parameters(method, 13) && method.methodType().stringValue().startsWith("(" + MODIFIER
                            + "Landroidx/compose/foundation/lazy/LazyListState;"), log));
            return new ImportUiSymbols(anchor, caller, dialog, text, item, edit, list);
        }
    }

    private static boolean parameters(MethodModel method, int count) {
        return method.methodTypeSymbol().parameterCount() == count && method.methodTypeSymbol().returnType().descriptorString().equals("V");
    }
}
