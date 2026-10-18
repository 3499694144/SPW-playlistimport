package local.saltplaylist;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

public class PlaylistMatcherTest {
    @Test public void acceptsAndroidBomAndWindowsLineEndingsWithoutChangingNames() throws Exception {
        var entries = PlaylistMatcher.parse("\uFEFF/storage/emulated/0/Music/花 - a # b%.flac\r\n\r\n/storage/emulated/0/Music/Next.mp3\r\n");
        assertEquals(2, entries.size());
        assertEquals("花 - a # b%.flac", entries.get(0).fileName());
        assertEquals(3, entries.get(1).line());
    }

    @Test public void preservesOriginalOrderAcrossDifferentFolders() throws Exception {
        var entries = PlaylistMatcher.parse("/android/old/B.flac\n/android/other/A.flac");
        var a = song("a", "C:\\Music\\A.flac");
        var b = song("b", "C:\\Music\\B.flac");
        var matches = PlaylistMatcher.match(entries, List.of(a, b), ignored -> true);
        assertEquals(List.of(b, a), PlaylistMatcher.selection(matches).songs());
    }

    @Test public void ambiguousNameNeverChoosesFirstEvenIfOtherFileIsUnavailable() throws Exception {
        var first = song("a", "C:\\Music\\same.flac");
        var second = song("b", "D:\\Music\\same.flac");
        var match = PlaylistMatcher.match(PlaylistMatcher.parse("/android/same.flac"),
                List.of(first, second), candidate -> candidate == first).getFirst();
        assertEquals(PlaylistMatcher.Status.AMBIGUOUS, match.status());
        assertNull(match.selected());
    }

    @Test public void doesNotGuessRenamedPunctuationOrDifferentExtension() throws Exception {
        var entries = PlaylistMatcher.parse("/android/en;Dolphin.flac\n/android/track.mp3");
        var matches = PlaylistMatcher.match(entries, List.of(song("a", "C:/en_Dolphin.flac"),
                song("b", "C:/track.flac")), ignored -> true);
        assertTrue(matches.stream().allMatch(match -> match.status() == PlaylistMatcher.Status.MISSING));
    }

    @Test public void duplicateTracksAreExplicitlyCountedAndFirstPositionWins() throws Exception {
        var entries = PlaylistMatcher.parse("/one/a.mp3\n/two/a.mp3\n/one/b.mp3");
        var a = song("a", "C:/a.mp3");
        var b = song("b", "C:/b.mp3");
        var selected = PlaylistMatcher.selection(PlaylistMatcher.match(entries, List.of(a, b), ignored -> true));
        assertEquals(List.of(a, b), selected.songs());
        assertEquals(1, selected.duplicates());
        assertEquals(0, selected.unresolved());
    }

    @Test public void inaccessibleFilesAreNotSelectedAndManualChoiceIsExplicit() throws Exception {
        var entry = PlaylistMatcher.parse("/android/a.mp3");
        var song = song("a", "C:/a.mp3");
        var match = PlaylistMatcher.match(entry, List.of(song), ignored -> false).getFirst();
        assertEquals(PlaylistMatcher.Status.UNAVAILABLE, match.status());
        assertNull(match.selected());
        assertEquals(PlaylistMatcher.Status.MANUAL, match.choose(song).status());
        assertEquals(1, PlaylistMatcher.selection(List.of(match)).unresolved());
    }

    @Test public void usesWindowsCaseInsensitivityButPreservesUnicode() throws Exception {
        var song = song("a", "C:/音乐/花 - TRACK.FLAC");
        var result = PlaylistMatcher.match(PlaylistMatcher.parse("/android/花 - track.flac"), List.of(song), ignored -> true);
        assertEquals(song, result.getFirst().selected());
    }

    @Test public void rejectsInvalidOrEmptyInput() {
        for (String input : List.of("", "\n  \n", "relative.mp3", "/folder/", "/a\0b.mp3")) {
            assertThrows(IOException.class, () -> PlaylistMatcher.parse(input));
        }
    }

    @Test public void previewShowsOnlyUnresolvedWithoutChangingImportOrder() throws Exception {
        var a = song("a", "C:/a.mp3");
        var b = song("b", "C:/b.mp3");
        var original = PlaylistMatcher.match(PlaylistMatcher.parse("/a.mp3\n/missing.mp3\n/b.mp3\n/also-missing.mp3"),
                List.of(a, b), ignored -> true);
        var preview = PlaylistMatcher.previewOrder(original);
        assertEquals(List.of(2, 4), preview.stream().map(match -> match.entry().line()).toList());
        assertEquals(List.of(1, 2, 3, 4), original.stream().map(match -> match.entry().line()).toList());
        assertEquals(List.of(a, b), PlaylistMatcher.selection(original).songs());
    }

    @Test public void manualMatchLeavesPreviewButKeepsItsOriginalImportPosition() throws Exception {
        var a = song("a", "C:/a.mp3");
        var b = song("b", "C:/b.mp3");
        var original = PlaylistMatcher.match(PlaylistMatcher.parse("/renamed.mp3\n/a.mp3\n/missing.mp3\n/other.mp3"),
                List.of(a), ignored -> true);
        original.set(0, original.get(0).choose(b));
        var preview = PlaylistMatcher.previewOrder(original);
        assertEquals(List.of(3, 4), preview.stream().map(match -> match.entry().line()).toList());
        assertEquals(List.of(b, a), PlaylistMatcher.selection(original).songs());
        assertEquals(2, PlaylistMatcher.selection(original).unresolved());
    }

    @Test public void previewIncludesMissingAmbiguousAndUnreadableAndCanBecomeEmpty() throws Exception {
        var a = song("a", "C:/a.mp3");
        var first = song("first", "C:/same.mp3");
        var second = song("second", "D:/same.mp3");
        var unreadable = song("unreadable", "C:/unreadable.mp3");
        var original = PlaylistMatcher.match(PlaylistMatcher.parse("/a.mp3\n/missing.mp3\n/same.mp3\n/unreadable.mp3"),
                List.of(a, first, second, unreadable), candidate -> candidate != unreadable);
        assertEquals(List.of(PlaylistMatcher.Status.MISSING, PlaylistMatcher.Status.AMBIGUOUS, PlaylistMatcher.Status.UNAVAILABLE),
                PlaylistMatcher.previewOrder(original).stream().map(PlaylistMatcher.Match::status).toList());
        assertEquals(List.of(a), PlaylistMatcher.selection(original).songs());
        assertEquals(3, PlaylistMatcher.selection(original).unresolved());
        for (int i = 1; i < original.size(); i++) original.set(i, original.get(i).choose(song("fixed" + i, "C:/fixed" + i + ".mp3")));
        assertTrue(PlaylistMatcher.previewOrder(original).isEmpty());
        assertEquals(4, PlaylistMatcher.selection(original).songs().size());
    }

    private static PlaylistMatcher.Song song(String id, String path) { return new PlaylistMatcher.Song(id, path, true); }
}
