package local.saltplaylist;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Filename matching is deliberately exact; punctuation and extensions are significant. */
public final class PlaylistMatcher {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_ENTRIES = 50_000;

    public enum Status { MATCHED, MANUAL, MISSING, AMBIGUOUS, UNAVAILABLE }
    public record Entry(int line, String androidPath) {
        public String fileName() { return androidPath.substring(androidPath.lastIndexOf('/') + 1); }
    }
    public record Song(String id, String path, boolean readable) {}
    public record Match(Entry entry, Status status, Song selected) {
        public Match choose(Song song) { return new Match(entry, Status.MANUAL, song); }
    }
    public record Selection(List<Song> songs, int unresolved, int duplicates) {
        public Selection { songs = List.copyOf(songs); }
    }

    private PlaylistMatcher() {}

    public static List<Entry> read(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException(UiText.TOO_LARGE);
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes((int) MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException(UiText.TOO_LARGE);
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IOException(UiText.INVALID_ENCODING, failure);
        }
        return parse(text);
    }

    public static List<Entry> parse(String text) throws IOException {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        var result = new ArrayList<Entry>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            if (!line.startsWith("/") || line.endsWith("/") || line.indexOf('\0') >= 0
                    || line.indexOf('\\') >= 0) {
                throw new IOException(UiText.INVALID_LINE.formatted(i + 1));
            }
            result.add(new Entry(i + 1, line));
            if (result.size() > MAX_ENTRIES) throw new IOException(UiText.TOO_LARGE);
        }
        if (result.isEmpty()) throw new IOException(UiText.EMPTY_FILE);
        return List.copyOf(result);
    }

    public static List<Match> match(List<Entry> entries, List<Song> library) {
        return match(entries, library, PlaylistMatcher::isAvailable);
    }

    static List<Match> match(List<Entry> entries, List<Song> library, Predicate<Song> available) {
        Map<String, List<Song>> byName = new HashMap<>();
        for (Song song : library) {
            if (song.id().isBlank()) continue;
            String path = song.path().replace('\\', '/');
            String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            byName.computeIfAbsent(name, unused -> new ArrayList<>()).add(song);
        }
        var matches = new ArrayList<Match>();
        for (Entry entry : entries) {
            List<Song> candidates = byName.getOrDefault(entry.fileName().toLowerCase(Locale.ROOT), List.of());
            Status status = candidates.isEmpty() ? Status.MISSING
                    : candidates.size() > 1 ? Status.AMBIGUOUS
                    : !available.test(candidates.getFirst()) ? Status.UNAVAILABLE : Status.MATCHED;
            matches.add(new Match(entry, status, status == Status.MATCHED ? candidates.getFirst() : null));
        }
        return matches;
    }

    public static boolean isAvailable(Song song) {
        try {
            return song.readable() && !song.id().isBlank() && Files.isRegularFile(Path.of(song.path()));
        } catch (InvalidPathException failure) {
            return false;
        }
    }

    public static String pathKey(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    public static Selection selection(List<Match> matches) {
        Map<String, Song> unique = new LinkedHashMap<>();
        int unresolved = 0;
        int duplicates = 0;
        for (Match match : matches) {
            if (match.selected() == null) unresolved++;
            else if (unique.putIfAbsent(match.selected().id(), match.selected()) != null) duplicates++;
        }
        return new Selection(new ArrayList<>(unique.values()), unresolved, duplicates);
    }

    /** Only unresolved rows are displayed; selection() must still receive the full original list. */
    public static List<Match> previewOrder(List<Match> matches) {
        return matches.stream().filter(match -> match.selected() == null).toList();
    }
}
