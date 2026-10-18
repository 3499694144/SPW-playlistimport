package local.saltplaylist.resolver;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public record FilePickerSymbols(HostSymbols.Ref picker, HostSymbols.Ref settings, HostSymbols.Ref parent, HostSymbols.Ref open) {
    public record Resolved(Method picker, Constructor<?> settings, Constructor<?> parent, Method open) {}

    public Resolved restore(ClassLoader loader) throws ReflectiveOperationException {
        var shared = picker.restore(loader);
        var options = settings.restoreConstructor(loader);
        var owner = parent.restoreConstructor(loader);
        var method = open.restore(loader);
        if (!Modifier.isStatic(shared.getModifiers()) || shared.getParameterCount() != 0
                || !shared.getReturnType().isInterface() || !shared.getReturnType().isAssignableFrom(method.getDeclaringClass())
                || Modifier.isStatic(method.getModifiers())
                || options.getParameterCount() != 4 || method.getParameterCount() != 4
                || method.getParameterTypes()[2] != options.getDeclaringClass()
                || !options.getParameterTypes()[1].isAssignableFrom(owner.getDeclaringClass())
                || owner.getParameterCount() != 1 || owner.getParameterTypes()[0] != java.awt.Window.class) {
            throw new IllegalStateException("FileKit picker/settings/owner relationships changed");
        }
        // R8 keeps public constructors on package-private nested owner classes.
        owner.setAccessible(true);
        options.setAccessible(true);
        shared.setAccessible(true);
        method.setAccessible(true);
        return new Resolved(shared, options, owner, method);
    }

    Map<String, HostSymbols.Ref> points() {
        var points = new LinkedHashMap<String, HostSymbols.Ref>();
        points.put("FilePicker", picker);
        points.put("FilePickerSettings", settings);
        points.put("FilePickerParent", parent);
        points.put("OpenFilePicker", open);
        return points;
    }

    static FilePickerSymbols load(Properties properties) {
        return new FilePickerSymbols(HostSymbols.loadRef(properties, "FilePicker"), HostSymbols.loadRef(properties, "FilePickerSettings"),
                HostSymbols.loadRef(properties, "FilePickerParent"), HostSymbols.loadRef(properties, "OpenFilePicker"));
    }
}
