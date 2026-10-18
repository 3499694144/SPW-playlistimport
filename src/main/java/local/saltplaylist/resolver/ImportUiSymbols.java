package local.saltplaylist.resolver;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public record ImportUiSymbols(HostSymbols.Ref anchor, HostSymbols.Ref caller,
                              HostSymbols.Ref dialog, HostSymbols.Ref text, HostSymbols.Ref item, HostSymbols.Ref edit,
                              HostSymbols.Ref lazyList) {
    public record Resolved(ImportUiSymbols descriptors, Method dialog, Method text, Method item,
                           Method edit, Method lazyList) {}

    public Resolved restore(ClassLoader loader) throws ReflectiveOperationException {
        Method target = anchor.restore(loader);
        Method location = caller.restoreDeclared(loader);
        if (!Modifier.isStatic(target.getModifiers()) || target.getReturnType() != void.class
                || !java.util.Arrays.equals(target.getParameterTypes(), new Class<?>[]{androidx.compose.runtime.Composer.class, int.class})
                || !Modifier.isStatic(location.getModifiers()) || location.getReturnType() != kotlin.Unit.class
                || !java.util.Arrays.equals(location.getParameterTypes(), new Class<?>[]{androidx.compose.foundation.layout.BoxScope.class,
                        androidx.compose.runtime.Composer.class, int.class})) {
            throw new IllegalStateException("UI Hook anchor/caller contract changed");
        }
        return new Resolved(this, component(dialog, loader, 11), component(text, loader, 21),
                component(item, loader, 17), component(edit, loader, 13),
                component(lazyList, loader, 13));
    }

    private static Method component(HostSymbols.Ref ref, ClassLoader loader, int parameters) throws ReflectiveOperationException {
        Method method = ref.restore(loader);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                || method.getParameterCount() != parameters) throw new IllegalStateException("Invalid UI component: " + ref);
        return method;
    }

    Map<String, HostSymbols.Ref> points() {
        var points = new LinkedHashMap<String, HostSymbols.Ref>();
        points.put("ImportUiAnchor", anchor);
        points.put("ImportUiCaller", caller);
        points.put("ImportDialog", dialog);
        points.put("ImportText", text);
        points.put("ImportItem", item);
        points.put("ImportEdit", edit);
        points.put("ImportLazyList", lazyList);
        return points;
    }

    static ImportUiSymbols load(Properties properties) {
        return new ImportUiSymbols(HostSymbols.loadRef(properties, "ImportUiAnchor"), HostSymbols.loadRef(properties, "ImportUiCaller"),
                HostSymbols.loadRef(properties, "ImportDialog"), HostSymbols.loadRef(properties, "ImportText"),
                HostSymbols.loadRef(properties, "ImportItem"), HostSymbols.loadRef(properties, "ImportEdit"),
                HostSymbols.loadRef(properties, "ImportLazyList"));
    }
}
