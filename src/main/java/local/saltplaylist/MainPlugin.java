package local.saltplaylist;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.PluginPermission;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.hook.HookHandle;
import com.xuncorp.spw.workshop.api.hook.MethodHook;
import com.xuncorp.spw.workshop.api.hook.MethodHookParam;
import kotlinx.coroutines.CompletableJob;
import kotlinx.coroutines.JobKt;
import local.saltplaylist.resolver.HostSymbolResolver;
import local.saltplaylist.resolver.HostSymbols;

import javax.swing.SwingUtilities;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Official configuration button entry. Resolution, UI hooks and database access start on demand. */
public final class MainPlugin extends SpwPlugin {
    private static final AtomicReference<MainPlugin> ACTIVE = new AtomicReference<>();
    private volatile boolean enabled;
    private volatile Session session;
    private HostSymbolResolver resolver;

    public MainPlugin(PluginContext context) { super(context); }

    @Override public void start() {
        resolver = new HostSymbolResolver(getPluginContext().getPluginVersion());
        enabled = true;
        ACTIVE.set(this);
    }

    @Override public void stop() {
        enabled = false;
        ACTIVE.compareAndSet(this, null);
        Session current = session;
        if (current != null) current.cancel();
        resolver = null;
    }

    private Path dataDirectory() {
        return WorkshopApi.manager().createConfigManager().getConfig().getConfigPath().getParent();
    }

    private HostSymbols.Resolved resolveSymbols(Path directory) throws Exception {
        HostSymbolResolver current = resolver;
        if (current == null) throw new CancellationException(UiText.DISABLED);
        Path hostJar = Path.of(WorkshopApi.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return current.resolve(hostJar, WorkshopApi.class.getClassLoader(), directory, MainPlugin::log);
    }

    public static void openImporter() {
        MainPlugin plugin = ACTIVE.get();
        SwingUtilities.invokeLater(() -> {
            if (plugin == null || !plugin.enabled) { alert(UiText.ENABLE_FIRST); return; }
            try { plugin.checkAllowed(); }
            catch (RuntimeException failure) { alert(failure.getMessage()); return; }
            if (plugin.session != null) {
                if (plugin.session.dialog != null) plugin.session.dialog.focus();
                return;
            }
            Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            if (owner == null || !owner.isShowing()) { alert(UiText.NO_WINDOW); return; }
            Session current = plugin.new Session(owner);
            plugin.session = current;
            WorkshopApi.ui().toast(UiText.LOADING, WorkshopApi.Ui.ToastType.Success);
            current.run(current::selectSource, source -> {
                if (source == null) { current.cancel(); return; }
                current.source = source;
                current.run(current::load, current::showPreview);
            });
        });
    }

    private void checkAllowed() {
        if (!enabled || ACTIVE.get() != this) throw new CancellationException(UiText.DISABLED);
        if (!HostSymbolResolver.SUPPORTED_HOST.equals(getPluginContext().getSpwVersion())) {
            throw new IllegalStateException(UiText.SUPPORTED_VERSION.formatted(HostSymbolResolver.SUPPORTED_HOST));
        }
        var manager = WorkshopApi.manager();
        if (!manager.isPermissionGranted(PluginPermission.LIBRARY_READ)
                || !manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            throw new SecurityException(UiText.NEED_PERMISSIONS);
        }
    }

    private record Preview(List<PlaylistMatcher.Song> library, List<PlaylistMatcher.Match> matches) {}

    private final class Session {
        private final CompletableJob job = JobKt.Job(null);
        private volatile boolean cancelled;
        private volatile Thread worker;
        private HookHandle capture;
        private ImportDialog dialog;
        private ImportUi uiHook;
        private NativeFilePicker picker;
        private final Window owner;
        private Path source;
        private HostSymbols.Resolved symbols;
        private Object database;
        private Path runtimeStatus;
        private boolean statusWritable = true;

        private Session(Window owner) { this.owner = owner; }

        private boolean alive() { return !cancelled && enabled && session == this && ACTIVE.get() == MainPlugin.this; }

        private Path selectSource() throws Exception {
            checkAllowed();
            Path dataDirectory = dataDirectory();
            runtimeStatus = dataDirectory.resolve("runtime-status.txt");
            try {
                symbols = resolveSymbols(dataDirectory);
            } catch (Exception failure) {
                throw new IllegalStateException(UiText.RESOLUTION_FAILED.formatted(failure.getMessage()), failure);
            }
            checkAllowed();
            picker = new NativeFilePicker(symbols.picker(), owner);
            return picker.open(UiText.PICK_PLAYLIST, Set.of("txt"), job);
        }

        private Preview load() throws Exception {
            checkAllowed();
            List<PlaylistMatcher.Entry> entries = PlaylistMatcher.read(source);
            AtomicReference<Object> observed = new AtomicReference<>();
            var provider = symbols.descriptors().databaseProvider();
            Class<?> databaseType = symbols.databaseType();
            HookHandle handle = WorkshopApi.hookRegistrar().hookMethod(provider.owner(), provider.name(), List.of(), new MethodHook() {
                @Override public void after(MethodHookParam call) {
                    Object result = call.getResult();
                    if (alive() && databaseType.isInstance(result)) observed.compareAndSet(null, result);
                }
            });
            synchronized (this) {
                if (!alive()) { handle.close(); throw new CancellationException(UiText.DISABLED); }
                capture = handle;
            }
            status("HookPoint[DatabaseCapture] state=FOUND runtime=installed");
            try {
                var items = WorkshopApi.library().getAllTracks().toCompletableFuture().get();
                checkAllowed();
                database = observed.get();
                if (database == null) throw new IllegalStateException(UiText.CAPTURE_FAILED);
                status("HookPoint[DatabaseCapture] state=FOUND runtime=captured");
                List<PlaylistMatcher.Song> library = items.stream().map(item ->
                        new PlaylistMatcher.Song(item.getId(), item.getPath(), item.getReadable())).toList();
                return new Preview(library, PlaylistMatcher.match(entries, library));
            } finally {
                synchronized (this) {
                    handle.close();
                    if (capture == handle) capture = null;
                }
            }
        }

        private void showPreview(Preview preview) {
            String title = source.getFileName().toString().replaceFirst("(?i)\\.txt$", "");
            if (title.isBlank()) title = UiText.DEFAULT_TITLE;
            try {
                dialog = new ImportDialog(title, preview.matches(), preview.library(), symbols.ui(), owner,
                        this::pickTrack, this::importSelection, this::cancel);
                uiHook = new ImportUi(symbols.ui(), dialog,
                        () -> log("HookPoint[ImportUi] state=FOUND runtime=drawn"));
                checkAllowed();
                uiHook.install();
                log("HookPoint[ImportUi] state=FOUND runtime=installed api=afterComposable");
            } catch (RuntimeException failure) {
                log("HookPoint[ImportUi] state=ERROR runtime=stopped reason=" + failure);
                alert(UiText.FAILED.formatted(failure.getMessage()));
                cancel();
            }
        }

        private void pickTrack(Consumer<Path> selected) {
            run(() -> {
                checkAllowed();
                return picker.open(UiText.PICK_TRACK, null, job);
            }, selected);
        }

        private void importSelection(String title, PlaylistMatcher.Selection selected) {
            run(() -> {
                checkAllowed();
                NativePlaylistWriter.Result result = new NativePlaylistWriter(symbols, database)
                        .create(title, selected.songs(), job, MainPlugin.this::checkAllowed, this::status);
                status("HookPoint[CreatePlaylist] state=FOUND runtime=committed id=" + result.id() + " tracks=" + selected.songs().size()
                        + "\nHookPoint[PlaylistCover] state=" + (result.coverWarning() != null ? "ERROR" : result.coverGenerated() ? "FOUND" : "OPTIONAL"));
                return result;
            }, result -> {
                log("Import completed: playlist=" + result.id() + " tracks=" + selected.songs().size()
                        + " unresolved=" + selected.unresolved() + " duplicates=" + selected.duplicates());
                String message = UiText.DONE.formatted(title, selected.songs().size(), selected.unresolved(), selected.duplicates());
                if (result.coverWarning() != null) message += "\n" + result.coverWarning();
                else if (!result.coverGenerated()) message += "\n" + UiText.NO_ARTWORK;
                WorkshopApi.ui().toast(message, result.coverWarning() == null ? WorkshopApi.Ui.ToastType.Success : WorkshopApi.Ui.ToastType.Warning);
                job.complete();
                close();
            });
        }

        private <T> void run(Callable<T> work, Consumer<T> success) {
            Thread next = Thread.ofVirtual().name("salt-android-playlist-import").unstarted(() -> {
                try {
                    if (!alive()) return;
                    T result = work.call();
                    SwingUtilities.invokeLater(() -> { if (alive()) success.accept(result); });
                } catch (Throwable failure) {
                    Throwable cause = failure;
                    while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    status("HookPoint[AndroidPlaylistImport] state=ERROR runtime=stopped reason=" + cause);
                    String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                    SwingUtilities.invokeLater(() -> {
                        if (alive()) { alert(UiText.FAILED.formatted(message)); cancel(); }
                    });
                }
            });
            worker = next;
            if (alive()) next.start();
        }

        private void cancel() {
            cancelled = true;
            job.cancel(new CancellationException("Plugin or import session stopped"));
            Thread running = worker;
            if (running != null) running.interrupt();
            synchronized (this) {
                if (capture != null) { capture.close(); capture = null; }
            }
            SwingUtilities.invokeLater(this::close);
        }

        private void close() {
            picker = null;
            if (uiHook != null) {
                uiHook.close();
                uiHook = null;
                log("HookPoint[ImportUi] state=OPTIONAL runtime=unhooked");
            }
            if (dialog != null) { dialog.dispose(); dialog = null; }
            if (session == this) session = null;
            database = null;
        }

        // Only the explicit-action worker writes status; the hook callback performs no I/O.
        private void status(String message) {
            log(message);
            if (runtimeStatus == null || !statusWritable || !alive()) return;
            try {
                Files.createDirectories(runtimeStatus.getParent());
                Files.writeString(runtimeStatus, message.substring(0, Math.min(message.length(), 4096)) + "\n", StandardCharsets.UTF_8);
            } catch (java.io.IOException failure) {
                statusWritable = false;
                log("Runtime status persistence disabled for this action: " + failure);
            }
        }
    }

    private static void alert(String message) { WorkshopApi.ui().toast(message, WorkshopApi.Ui.ToastType.Warning); }
    private static void log(String message) { System.out.println("[AndroidPlaylistImport] " + message); }
}
