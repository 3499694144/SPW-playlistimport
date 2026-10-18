package local.saltplaylist;

import kotlinx.coroutines.Job;
import local.saltplaylist.resolver.FilePickerSymbols;

import java.awt.Window;
import java.io.File;
import java.nio.file.Path;
import java.util.Set;

/** Reuses the host's shared FileKit picker and executor; the host owns their lifetime. */
public final class NativeFilePicker {
    private final FilePickerSymbols.Resolved symbols;
    private final Window owner;

    public NativeFilePicker(FilePickerSymbols.Resolved symbols, Window owner) {
        this.symbols = symbols;
        this.owner = owner;
    }

    public Path open(String title, Set<String> extensions, Job job) throws Exception {
        Object parent = symbols.parent().newInstance(owner);
        Object settings = symbols.settings().newInstance(title, parent, null, 4);
        Object picker = symbols.picker().invoke(null);
        if (!symbols.open().getDeclaringClass().isInstance(picker)) throw new IllegalStateException("Unexpected shared FileKit picker");
        Object result = SuspendCall.invoke(symbols.open(), picker, job, extensions, null, settings);
        if (result == null) return null;
        if (!(result instanceof File file)) throw new IllegalStateException("Unexpected FileKit result");
        return file.toPath();
    }
}
