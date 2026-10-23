package local.saltplaylist;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Offline matcher check: feed a TSV dump of the library and a playlist TXT, print the
 * same summary the dialog shows plus every row that still needs attention.
 *
 * <pre>
 *   java -cp ... local.saltplaylist.MatcherCheck library.tsv playlist.txt
 * </pre>
 */
public final class MatcherCheck {
    private MatcherCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) {
            System.err.println("usage: MatcherCheck library.tsv playlist.txt [selection.tsv] [report.txt]");
            System.exit(2);
        }
        if (args.length == 4) {
            System.setOut(new java.io.PrintStream(Files.newOutputStream(Path.of(args[3])), true,
                    StandardCharsets.UTF_8));
        }
        var library = new ArrayList<PlaylistMatcher.Song>();
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\t", 5);
            if (fields.length != 5) throw new IllegalArgumentException("bad library row: " + line);
            library.add(new PlaylistMatcher.Song(fields[0], fields[1], Boolean.parseBoolean(fields[2]),
                    fields[3], fields[4]));
        }
        List<PlaylistMatcher.Entry> entries = PlaylistMatcher.read(Path.of(args[1]));
        List<PlaylistMatcher.Match> matches = PlaylistMatcher.match(entries, library);
        PlaylistMatcher.Selection selection = PlaylistMatcher.selection(matches);

        Map<PlaylistMatcher.Status, Integer> counts = new EnumMap<>(PlaylistMatcher.Status.class);
        for (PlaylistMatcher.Match match : matches) {
            counts.merge(match.status(), 1, Integer::sum);
        }
        System.out.println("library=" + library.size() + " entries=" + entries.size()
                + " importable=" + selection.songs().size() + " unresolved=" + selection.unresolved()
                + " duplicates=" + selection.duplicates() + " review=" + PlaylistMatcher.reviewCount(matches));
        System.out.println("statuses=" + counts);
        System.out.println("--- rows needing attention ---");
        for (PlaylistMatcher.Match match : PlaylistMatcher.previewOrder(matches)) {
            PlaylistMatcher.Song selected = match.selected();
            String target = selected == null ? "-" : selected.title() + " / " + selected.artist() + " [" + selected.id() + "]";
            System.out.println(match.entry().line() + "\t" + match.status() + "\t" + match.note()
                    + "\t" + match.entry().display() + "\t=> " + target);
        }
        if (args.length > 2) {
            var dump = new ArrayList<String>();
            for (PlaylistMatcher.Song song : selection.songs()) {
                dump.add(song.id() + "\t" + song.path());
            }
            Files.write(Path.of(args[2]), dump, StandardCharsets.UTF_8);
        }
    }
}
