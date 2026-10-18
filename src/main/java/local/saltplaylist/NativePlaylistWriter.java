package local.saltplaylist;

import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobKt;
import local.saltplaylist.resolver.HostSymbols;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Consumes validated handles and calls SPW's existing atomic playlist import operation. */
public final class NativePlaylistWriter {
    public record Result(String id, boolean coverGenerated, String coverWarning) {}
    private final HostSymbols.Resolved symbols;
    private final Object database;

    public NativePlaylistWriter(HostSymbols.Resolved symbols, Object database) {
        if (!symbols.databaseType().isInstance(database)) throw new IllegalArgumentException("Unexpected database instance");
        this.symbols = symbols;
        this.database = database;
    }

    public Result create(String title, List<PlaylistMatcher.Song> selected, Job job, Runnable checkAllowed,
                         java.util.function.Consumer<String> log) throws Exception {
        checkAllowed.run();
        if (selected.isEmpty()) throw new IllegalArgumentException(UiText.NO_MATCHES);
        Object trackDao = symbols.trackDao().invoke(database);
        Object snapshot = SuspendCall.invoke(symbols.readTracks(), trackDao, job);
        if (!(snapshot instanceof List<?> rows)) throw new IllegalStateException("Invalid native track snapshot");
        var byId = new HashMap<String, Object>();
        for (Object row : rows) {
            if (!symbols.trackType().isInstance(row)) throw new IllegalStateException("Unexpected native track type");
            String id = (String) symbols.trackId().invoke(row);
            if (byId.putIfAbsent(id, row) != null) throw new IllegalStateException("Duplicate native track ID");
        }
        var nativeTracks = new ArrayList<Object>();
        var unique = new HashSet<String>();
        for (PlaylistMatcher.Song song : selected) {
            if (!unique.add(song.id())) throw new IllegalArgumentException("Duplicate selection ID");
            Object row = byId.get(song.id());
            if (row == null || !Boolean.TRUE.equals(symbols.trackReadable().invoke(row))
                    || !PlaylistMatcher.isAvailable(song)) {
                throw new IllegalStateException(UiText.CHANGED_LIBRARY.formatted(song.path()));
            }
            String stored = (String) symbols.trackPath().invoke(row);
            // SPW stores a raw file VRI: spaces, '#' and '%' are literal filename characters.
            if (!stored.startsWith("file:///")) throw new IllegalStateException(UiText.CHANGED_LIBRARY.formatted(song.path()));
            String current = PlaylistMatcher.pathKey(stored.substring("file:///".length()));
            if (!current.equals(PlaylistMatcher.pathKey(song.path()))) {
                throw new IllegalStateException(UiText.CHANGED_LIBRARY.formatted(song.path()));
            }
            nativeTracks.add(row);
        }
        JobKt.ensureActive(job);
        checkAllowed.run();
        Object dao = symbols.playlistDao().invoke(database);
        try (var cover = new NativePlaylistCover(symbols.cover(), log)) {
            cover.prepare(selected.getFirst(), job);
            JobKt.ensureActive(job);
            checkAllowed.run();
            Object playlist = SuspendCall.invoke(symbols.createPlaylist(), dao, job,
                    title, UiText.DESCRIPTION, nativeTracks);
            if (!symbols.playlistType().isInstance(playlist)) throw new IllegalStateException(UiText.NO_LIVE_WRITE);
            String id = (String) symbols.playlistId().invoke(playlist);
            // The transaction has committed. A later cover error must never invite re-importing it.
            try { return new Result(id, cover.attach(id, database, job, checkAllowed), null); }
            catch (Exception failure) {
                log.accept("HookPoint[PlaylistCover] state=ERROR runtime=playlist-already-created id=" + id + " reason=" + failure);
                return new Result(id, false, UiText.COVER_FAILED);
            }
        }
    }
}
