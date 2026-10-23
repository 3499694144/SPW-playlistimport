package local.saltplaylist;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Matches "title - artist" playlist text against the local library.
 *
 * <p>The mobile export format (one absolute path per line) is still accepted, but the primary
 * format is one song per line: {@code 歌名 - 歌手}, with several artists separated by
 * {@code / & , ;} etc. Matching is deliberately conservative: a title match without artist
 * evidence is never selected automatically, it is reported for manual confirmation instead.
 */
public final class PlaylistMatcher {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_ENTRIES = 50_000;
    private static final String SEPARATOR = " - ";
    private static final String PIPE_SEPARATOR = " | ";
    private static final String[] TITLE_ARTIST_SEPARATORS = {SEPARATOR, " – ", " — ", PIPE_SEPARATOR};
    private static final String FULL_KEY_SEPARATOR = "\u0001";

    /** A trailing version marker such as "(Live)", "（现场版）" or "[Remix]". */
    private static final Pattern BRACKETS = Pattern.compile("[（(\\[【{][^）)\\]】}]*[）)\\]】}]");
    private static final Pattern ARTIST_SEPARATOR = Pattern.compile(
            "\\s*(?:[/\\\\|、&,;，；＆×#]|\\bfeat\\.?|\\bft\\.?|\\bwith\\b)\\s*",
            Pattern.CASE_INSENSITIVE);

    /** Library artist tags that carry no identifying information. */
    private static final Set<String> GENERIC_ARTISTS = Set.of(
            "群星", "variousartists", "va", "unknown", "未知", "未知艺术家", "佚名");

    public enum Status { MATCHED, REVIEW, MANUAL, MISSING, AMBIGUOUS, UNAVAILABLE }

    /**
     * One non-empty playlist line. {@code title == null} means the legacy absolute-path entry;
     * otherwise {@code title} is always non-blank and {@code artist} may be empty.
     */
    public record Entry(int line, String text, String title, String artist) {
        public boolean pathEntry() { return title == null; }

        public String fileName() {
            String value = text.replace('\\', '/');
            return value.substring(value.lastIndexOf('/') + 1);
        }

        public String display() {
            if (pathEntry()) return fileName();
            if (artist == null || artist.isBlank()) return title;
            return title + SEPARATOR + artist;
        }
    }

    public record Song(String id, String path, boolean readable, String title, String artist) {
        public Song {
            title = title == null ? "" : title;
            artist = artist == null ? "" : artist;
        }
    }

    public record Match(Entry entry, Status status, Song selected, String note) {
        public Match choose(Song song) { return new Match(entry, Status.MANUAL, song, note); }
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
        boolean firstLine = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            if (firstLine) {
                firstLine = false;
                // NetEase exports start with a platform title such as "网易歌单"; it is not a song.
                if (looksLikeHeader(line)) continue;
            }
            if (line.indexOf('\0') >= 0) throw new IOException(UiText.INVALID_LINE.formatted(i + 1));
            Entry entry = pathEntry(i + 1, line);
            if (entry == null) {
                int tab = line.indexOf('\t');
                String title;
                String artist;
                if (tab > 0) {
                    title = line.substring(0, tab).strip();
                    artist = line.substring(tab + 1).strip();
                } else {
                    int split = -1;
                    String delimiter = null;
                    for (String candidate : TITLE_ARTIST_SEPARATORS) {
                        int index = line.lastIndexOf(candidate);
                        if (index > split) {
                            split = index;
                            delimiter = candidate;
                        }
                    }
                    if (split > 0) {
                        title = line.substring(0, split).strip();
                        artist = line.substring(split + delimiter.length()).strip();
                    } else {
                        title = line;
                        artist = "";
                    }
                }
                if (title.isEmpty()) throw new IOException(UiText.INVALID_LINE.formatted(i + 1));
                entry = new Entry(i + 1, line, title, artist);
            }
            result.add(entry);
            if (result.size() > MAX_ENTRIES) throw new IOException(UiText.TOO_LARGE);
        }
        if (result.isEmpty()) throw new IOException(UiText.EMPTY_FILE);
        return List.copyOf(result);
    }

    private static boolean looksLikeHeader(String line) {
        if (line.indexOf('\t') >= 0) return false;
        for (String separator : TITLE_ARTIST_SEPARATORS) {
            if (line.contains(separator)) return false;
        }
        return line.endsWith("歌单") || line.endsWith("播放列表") || line.startsWith("#");
    }

    private static Entry pathEntry(int lineNumber, String line) {
        boolean windows = line.length() > 2 && Character.isLetter(line.charAt(0))
                && line.charAt(1) == ':' && (line.charAt(2) == '\\' || line.charAt(2) == '/');
        if (!line.startsWith("/") && !windows) return null;
        return new Entry(lineNumber, line, null, "");
    }

    public static List<Match> match(List<Entry> entries, List<Song> library) {
        return match(entries, library, PlaylistMatcher::isAvailable);
    }

    static List<Match> match(List<Entry> entries, List<Song> library, Predicate<Song> available) {
        Index index = new Index(library);
        var matches = new ArrayList<Match>(entries.size());
        for (Entry entry : entries) {
            matches.add(entry.pathEntry() ? matchPath(entry, index, available) : matchSong(entry, index, available));
        }
        return matches;
    }

    private static Match matchPath(Entry entry, Index index, Predicate<Song> available) {
        List<Item> candidates = index.byName.getOrDefault(normalizeName(entry.fileName()), List.of());
        if (candidates.isEmpty()) return new Match(entry, Status.MISSING, null, null);
        if (candidates.size() > 1) return new Match(entry, Status.AMBIGUOUS, null, UiText.NOTE_MULTIPLE_FILES);
        return resolved(entry, pick(candidates, available, Set.of()), Status.MATCHED, null, available);
    }

    private static Match matchSong(Entry entry, Index index, Predicate<Song> available) {
        Set<String> wanted = artists(entry.artist());
        String titleKey = key(entry.title());
        String looseKey = looseKey(entry.title());

        if (!wanted.isEmpty()) {
            List<Item> full = index.byFull.get(fullKey(titleKey, wanted));
            if (full != null) {
                return resolved(entry, pick(full, available, wanted), Status.MATCHED, UiText.NOTE_EXACT, available);
            }

            List<Item> exact = filterArtists(index.byTitle.get(titleKey), wanted);
            if (!exact.isEmpty()) {
                return resolved(entry, pick(exact, available, wanted), Status.MATCHED, UiText.NOTE_TITLE_ARTIST, available);
            }

            List<Item> loose = filterArtists(index.byLoose.get(looseKey), wanted);
            if (!loose.isEmpty()) {
                return resolved(entry, pick(loose, available, wanted), Status.REVIEW, UiText.NOTE_LOOSE, available);
            }

            List<Item> prefix = prefixCandidates(index, looseKey, wanted);
            if (!prefix.isEmpty()) {
                return resolved(entry, pick(prefix, available, wanted), Status.REVIEW, UiText.NOTE_PREFIX, available);
            }

            List<Item> embedded = artistInTitle(index, looseKey, wanted);
            if (!embedded.isEmpty()) {
                return resolved(entry, pick(embedded, available, wanted), Status.REVIEW, UiText.NOTE_ARTIST_IN_TITLE, available);
            }

            List<Item> fuzzy = fuzzyCandidates(index, looseKey, wanted);
            if (!fuzzy.isEmpty()) {
                return resolved(entry, pick(fuzzy, available, wanted), Status.REVIEW, UiText.NOTE_FUZZY, available);
            }
        }

        List<Item> titleCandidates = index.byTitle.getOrDefault(titleKey, List.of());
        boolean exactTitle = !titleCandidates.isEmpty();
        if (!exactTitle) titleCandidates = index.byLoose.getOrDefault(looseKey, List.of());
        if (titleCandidates.isEmpty()) return new Match(entry, Status.MISSING, null, null);

        if (titleCandidates.size() == 1) {
            Item only = titleCandidates.getFirst();
            if (wanted.isEmpty()) {
                return resolved(entry, only, exactTitle ? Status.MATCHED : Status.REVIEW,
                        UiText.NOTE_TITLE_ONLY, available);
            }
            if (only.genericArtist()) {
                return resolved(entry, only, Status.REVIEW, UiText.NOTE_TITLE_ONLY, available);
            }
        }
        return new Match(entry, Status.AMBIGUOUS, null, UiText.NOTE_ARTIST_MISMATCH);
    }

    private static Match resolved(Entry entry, Item item, Status status, String note, Predicate<Song> available) {
        if (!available.test(item.song())) return new Match(entry, Status.UNAVAILABLE, null, note);
        return new Match(entry, status, item.song(), note);
    }

    private static List<Item> filterArtists(List<Item> candidates, Set<String> wanted) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        var result = new ArrayList<Item>();
        for (Item item : candidates) {
            if (overlap(item, wanted) > 0) result.add(item);
        }
        return result;
    }

    /** Candidate titles that are a prefix of the requested title, e.g. "カラノココロ" for a TV-size name. */
    private static List<Item> prefixCandidates(Index index, String looseKey, Set<String> wanted) {
        if (looseKey.length() < 3) return List.of();
        for (int length = looseKey.length() - 1; length >= 3; length--) {
            List<Item> candidates = filterArtists(index.byLoose.get(looseKey.substring(0, length)), wanted);
            if (!candidates.isEmpty()) return candidates;
        }
        return List.of();
    }

    /** Titles such as "Apologize-OneRepublic" that carry the artist inside the title tag. */
    private static List<Item> artistInTitle(Index index, String looseKey, Set<String> wanted) {
        if (looseKey.length() < 4) return List.of();
        var result = new ArrayList<Item>();
        NavigableMap<String, List<Item>> tail = index.sortedLoose.subMap(looseKey, true, looseKey + '\uffff', false);
        for (var bucket : tail.entrySet()) {
            String candidateKey = bucket.getKey();
            if (candidateKey.length() <= looseKey.length() || !candidateKey.startsWith(looseKey)) continue;
            String rest = candidateKey.substring(looseKey.length());
            boolean carriesArtist = wanted.stream().anyMatch(artist -> artist.length() >= 2 && rest.contains(artist));
            if (carriesArtist) result.addAll(bucket.getValue());
        }
        return result;
    }

    /** Last resort: a one-character spelling difference is common in imported metadata. */
    private static List<Item> fuzzyCandidates(Index index, String looseKey, Set<String> wanted) {
        if (looseKey.length() < 4) return List.of();
        double best = 0.0;
        var result = new ArrayList<Item>();
        int shortest = Math.max(4, looseKey.length() - 2);
        for (int length = shortest; length <= looseKey.length() + 2; length++) {
            List<Map.Entry<String, List<Item>>> candidates = index.looseByLength.get(length);
            if (candidates == null) continue;
            for (var bucket : candidates) {
                String candidateKey = bucket.getKey();
                double score = similarity(looseKey, candidateKey);
                if (score < 0.86) continue;
                List<Item> overlap = filterArtists(bucket.getValue(), wanted);
                if (overlap.isEmpty()) continue;
                if (score > best) {
                    best = score;
                    result.clear();
                    result.addAll(overlap);
                } else if (Math.abs(score - best) < 1e-9) {
                    result.addAll(overlap);
                }
            }
        }
        return result;
    }

    private static int overlap(Item item, Set<String> wanted) {
        int count = 0;
        for (String artist : wanted) {
            if (item.artists.contains(artist)) count++;
        }
        return count;
    }

    /** Prefer playable files, then the candidate sharing most artists, then the shortest stable path. */
    private static Item pick(List<Item> candidates, Predicate<Song> available, Set<String> wanted) {
        Item best = null;
        boolean bestAvailable = false;
        int bestOverlap = -1;
        for (Item candidate : candidates) {
            boolean playable = available.test(candidate.song());
            int artists = overlap(candidate, wanted);
            if (best == null
                    || (playable && !bestAvailable)
                    || (playable == bestAvailable && artists > bestOverlap)
                    || (playable == bestAvailable && artists == bestOverlap && shorter(candidate, best))) {
                best = candidate;
                bestAvailable = playable;
                bestOverlap = artists;
            }
        }
        return best;
    }

    private static boolean shorter(Item candidate, Item current) {
        int left = candidate.song().path().length();
        int right = current.song().path().length();
        if (left != right) return left < right;
        return candidate.song().id().compareTo(current.song().id()) < 0;
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

    public static int reviewCount(List<Match> matches) {
        return (int) matches.stream().filter(match -> match.status() == Status.REVIEW).count();
    }

    /**
     * Rows that still need attention: unresolved ones, plus low-confidence automatic matches.
     * A manually confirmed row disappears even though it stays in {@code selection()}.
     */
    public static List<Match> previewOrder(List<Match> matches) {
        return matches.stream().filter(match -> match.selected() == null || match.status() == Status.REVIEW).toList();
    }

    // ---------------------------------------------------------------- normalization

    static String normalizeName(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    static String key(String value) {
        String folded = fold(value);
        var result = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); ) {
            int codePoint = folded.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) result.appendCodePoint(codePoint);
            i += Character.charCount(codePoint);
        }
        return result.toString();
    }

    static String looseKey(String value) {
        return key(BRACKETS.matcher(value).replaceAll(" "));
    }

    static Set<String> artists(String value) {
        var result = new LinkedHashSet<String>();
        if (value == null || value.isBlank()) return result;
        for (String part : ARTIST_SEPARATOR.split(value)) {
            String normalized = key(part);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }

    /** Case, width, accent and punctuation insensitive comparison form. */
    static String fold(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(normalized, Normalizer.Form.NFD);
        var result = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); ) {
            int codePoint = decomposed.codePointAt(i);
            int type = Character.getType(codePoint);
            if (type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK
                    && type != Character.COMBINING_SPACING_MARK) {
                result.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static String fullKey(String titleKey, Set<String> wanted) {
        var sorted = new ArrayList<>(wanted);
        Collections.sort(sorted);
        return titleKey + FULL_KEY_SEPARATOR + String.join(FULL_KEY_SEPARATOR, sorted);
    }

    /** Normalized Levenshtein similarity in [0, 1]. */
    static double similarity(String left, String right) {
        if (left.equals(right)) return 1.0;
        int leftLength = left.length();
        int rightLength = right.length();
        if (leftLength == 0 || rightLength == 0) return 0.0;
        if (Math.abs(leftLength - rightLength) > 2) return 0.0;
        int[] previous = new int[rightLength + 1];
        int[] current = new int[rightLength + 1];
        for (int j = 0; j <= rightLength; j++) previous[j] = j;
        for (int i = 1; i <= leftLength; i++) {
            current[0] = i;
            for (int j = 1; j <= rightLength; j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1.0 - (double) previous[rightLength] / Math.max(leftLength, rightLength);
    }

    // ---------------------------------------------------------------- index

    private record Item(Song song, String titleKey, String looseKey, Set<String> artists,
                        String nameKey, boolean genericArtist) {}

    private static final class Index {
        private final Map<String, List<Item>> byName = new HashMap<>();
        private final Map<String, List<Item>> byTitle = new HashMap<>();
        private final Map<String, List<Item>> byLoose = new HashMap<>();
        private final Map<String, List<Item>> byFull = new HashMap<>();
        private final NavigableMap<String, List<Item>> sortedLoose = new TreeMap<>();
        private final Map<Integer, List<Map.Entry<String, List<Item>>>> looseByLength = new HashMap<>();

        private Index(List<Song> songs) {
            for (Song song : songs) {
                if (song.id().isBlank()) continue;
                String name = normalizeName(fileName(song.path()));
                Set<String> itemArtists = artists(song.artist());
                boolean generic = itemArtists.isEmpty()
                        || itemArtists.stream().allMatch(GENERIC_ARTISTS::contains);
                Item item = new Item(song, key(song.title()), looseKey(song.title()), itemArtists, name, generic);
                if (!name.isEmpty()) byName.computeIfAbsent(name, unused -> new ArrayList<>()).add(item);
                if (!item.titleKey().isEmpty()) {
                    byTitle.computeIfAbsent(item.titleKey(), unused -> new ArrayList<>()).add(item);
                    byFull.computeIfAbsent(fullKey(item.titleKey(), item.artists()), unused -> new ArrayList<>()).add(item);
                }
                if (!item.looseKey().isEmpty()) {
                    byLoose.computeIfAbsent(item.looseKey(), unused -> new ArrayList<>()).add(item);
                    sortedLoose.computeIfAbsent(item.looseKey(), unused -> new ArrayList<>()).add(item);
                }
            }
            for (var bucket : byLoose.entrySet()) {
                looseByLength.computeIfAbsent(bucket.getKey().length(), unused -> new ArrayList<>())
                        .add(Map.entry(bucket.getKey(), bucket.getValue()));
            }
        }
    }

    private static String fileName(String path) {
        String value = path.replace('\\', '/');
        return value.substring(value.lastIndexOf('/') + 1);
    }
}
