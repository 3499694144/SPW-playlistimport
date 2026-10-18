package local.saltplaylist;

import androidx.compose.ui.graphics.DesktopImageConverters_desktopKt;
import androidx.compose.ui.graphics.ImageBitmap;
import androidx.compose.ui.graphics.SkiaImageAsset_skikoKt;
import kotlin.ResultKt;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobKt;
import local.saltplaylist.resolver.CoverSymbols;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Mirrors the host's first-added-track, 500x500 PNG cover contract. Runs on the import worker. */
final class NativePlaylistCover implements AutoCloseable {
    private final CoverSymbols.Resolved symbols;
    private final Consumer<String> log;
    private Path temporary;
    private Path directory;
    private boolean available;

    NativePlaylistCover(CoverSymbols.Resolved symbols, Consumer<String> log) {
        this.symbols = symbols;
        this.log = log;
    }

    void prepare(PlaylistMatcher.Song first, Job job) throws Exception {
        JobKt.ensureActive(job);
        Object result = symbols.image().invoke(symbols.imageReader(), first.path(), 500, 500);
        try { ResultKt.throwOnFailure(result); }
        catch (IllegalArgumentException noArtwork) {
            log.accept("HookPoint[PlaylistCover] state=OPTIONAL reason=first track has no decodable artwork: " + noArtwork);
            return;
        }
        if (!(result instanceof ImageBitmap bitmap)) throw new IOException("Unexpected host cover image result");
        try {
            directory = ((File) symbols.directory().invoke(null)).toPath().toAbsolutePath().normalize();
            Files.createDirectories(directory);
            JobKt.ensureActive(job);
            temporary = Files.createTempFile(directory, "androidimport-", ".png");
            if (!ImageIO.write(DesktopImageConverters_desktopKt.toAwtImage(bitmap), "png", temporary.toFile())) {
                throw new IOException("PNG encoder unavailable");
            }
            available = true;
        } finally {
            SkiaImageAsset_skikoKt.asSkiaBitmap(bitmap).close();
        }
    }

    boolean attach(String playlistId, Object database, Job job, Runnable checkAllowed) throws Exception {
        JobKt.ensureActive(job);
        checkAllowed.run();
        if (available) {
            Path destination = directory.resolve(playlistId).normalize();
            if (!directory.equals(destination.getParent())) throw new IOException("Invalid playlist cover path");
            Files.move(temporary, destination);
            temporary = null;
        }
        long time = System.currentTimeMillis();
        kotlin.jvm.functions.Function1<Object, Object> update = connection -> {
            try { return symbols.updateBlock().invoke(null, symbols.sql(), time, playlistId, connection); }
            catch (java.lang.reflect.InvocationTargetException failure) {
                throw new IllegalStateException("Native cover update failed", failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native cover update unavailable", failure);
            }
        };
        SuspendCall.invoke(symbols.transaction(), null, job, database, false, true, update);
        log.accept("HookPoint[PlaylistCover] state=" + (available ? "FOUND" : "OPTIONAL")
                + " runtime=" + (available ? "generated" : "no-artwork") + " id=" + playlistId);
        return available;
    }

    @Override public void close() {
        if (temporary == null) return;
        try { Files.deleteIfExists(temporary); }
        catch (IOException failure) { log.accept("Cover temporary-file cleanup failed (not retried): " + failure); }
    }
}
