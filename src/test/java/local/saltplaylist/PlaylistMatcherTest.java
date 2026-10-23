package local.saltplaylist;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

public class PlaylistMatcherTest {
    @Test public void parsesTitleArtistWithHeaderAndLegacyPaths() throws Exception {
        var entries = PlaylistMatcher.parse("\uFEFF网易歌单\n慢热 - 满舒克\nAngel\t尹美莱 / Tiger JK\n标题 | 歌手\n只有歌名\n"
                + "/storage/emulated/0/Music/花 - a.flac\n");
        assertEquals(5, entries.size());
        assertEquals("慢热", entries.get(0).title());
        assertEquals("满舒克", entries.get(0).artist());
        assertEquals("Angel", entries.get(1).title());
        assertEquals("尹美莱 / Tiger JK", entries.get(1).artist());
        assertEquals("标题", entries.get(2).title());
        assertEquals("歌手", entries.get(2).artist());
        assertEquals("只有歌名", entries.get(3).title());
        assertTrue(entries.get(3).artist().isEmpty());
        assertTrue(entries.get(4).pathEntry());
        assertEquals("花 - a.flac", entries.get(4).fileName());
    }

    @Test public void skipsOnlyTheLeadingPlaylistHeader() throws Exception {
        var entries = PlaylistMatcher.parse("我的歌单\nA - B\n网易歌单\nC - D");
        assertEquals(3, entries.size());
        assertEquals("A", entries.get(0).title());
        assertEquals("网易歌单", entries.get(1).title());
    }

    @Test public void acceptsDashAndPipeSeparators() throws Exception {
        var entries = PlaylistMatcher.parse("晴天 – 周杰伦\n稻香 — 周杰伦\n七里香 | 周杰伦");
        assertEquals(3, entries.size());
        assertEquals("晴天", entries.get(0).title());
        assertEquals("周杰伦", entries.get(0).artist());
        assertEquals("稻香", entries.get(1).title());
        assertEquals("七里香", entries.get(2).title());
    }

    @Test public void exactTitleAndArtistMatchesDirectly() throws Exception {
        var song = song("1", "C:/a.flac", "慢热", "满舒克");
        var match = matchOne("慢热 - 满舒克", List.of(song));
        assertEquals(PlaylistMatcher.Status.MATCHED, match.status());
        assertEquals(song, match.selected());
    }

    @Test public void versionBracketsMatchButAskForReview() throws Exception {
        var song = song("1", "C:/a.flac", "Stronger (What Doesn't Kill You)", "Kelly Clarkson");
        var match = matchOne("Stronger - Kelly Clarkson", List.of(song));
        assertEquals(PlaylistMatcher.Status.REVIEW, match.status());
        assertEquals(song, match.selected());
        assertEquals(List.of(match), PlaylistMatcher.previewOrder(List.of(match)));
    }

    @Test public void sameTitleWithDifferentArtistIsNeverGuessed() throws Exception {
        var song = song("1", "C:/a.flac", "谁", "蔡健雅");
        var match = matchOne("谁 (Live版) - 廖俊涛", List.of(song));
        assertEquals(PlaylistMatcher.Status.AMBIGUOUS, match.status());
        assertNull(match.selected());
        assertEquals(PlaylistMatcher.Status.MANUAL, match.choose(song).status());
    }

    @Test public void titleOnlyLineUsesUniqueTitle() throws Exception {
        var song = song("1", "C:/a.flac", "晴天", "周杰伦");
        var match = matchOne("晴天", List.of(song));
        assertEquals(PlaylistMatcher.Status.MATCHED, match.status());
        assertEquals(song, match.selected());
    }

    @Test public void legacyFileNameMatchingStillWorks() throws Exception {
        var first = song("1", "C:/Music/same.flac", "A", "B");
        var second = song("2", "D:/Music/same.flac", "C", "D");
        var ambiguous = PlaylistMatcher.match(PlaylistMatcher.parse("/android/same.flac"),
                List.of(first, second), ignored -> true).getFirst();
        assertEquals(PlaylistMatcher.Status.AMBIGUOUS, ambiguous.status());
        assertNull(ambiguous.selected());

        var matched = PlaylistMatcher.match(PlaylistMatcher.parse("/android/same.flac"),
                List.of(first), ignored -> true).getFirst();
        assertEquals(PlaylistMatcher.Status.MATCHED, matched.status());
    }

    @Test public void unavailableFileIsNotSelected() throws Exception {
        var song = song("1", "C:/a.flac", "慢热", "满舒克");
        var match = PlaylistMatcher.match(PlaylistMatcher.parse("慢热 - 满舒克"), List.of(song),
                ignored -> false).getFirst();
        assertEquals(PlaylistMatcher.Status.UNAVAILABLE, match.status());
        assertNull(match.selected());
        assertEquals(1, PlaylistMatcher.selection(List.of(match)).unresolved());
    }

    @Test public void duplicateTracksAreCountedAndFirstPositionWins() throws Exception {
        var a = song("a", "C:/a.flac", "A", "X");
        var b = song("b", "C:/b.flac", "B", "Y");
        var matches = PlaylistMatcher.match(PlaylistMatcher.parse("A - X\nA - X\nB - Y"), List.of(a, b),
                ignored -> true);
        var selected = PlaylistMatcher.selection(matches);
        assertEquals(List.of(a, b), selected.songs());
        assertEquals(1, selected.duplicates());
        assertEquals(0, selected.unresolved());
    }

    @Test public void previewShowsOnlyAttentionRowsAndManualChoiceClearsThem() throws Exception {
        var a = song("a", "C:/a.flac", "A", "X");
        var b = song("b", "C:/b.flac", "B", "Y");
        var matches = PlaylistMatcher.match(PlaylistMatcher.parse("A - X\nmissing - nobody\nB - Z"),
                List.of(a, b), ignored -> true);
        var preview = PlaylistMatcher.previewOrder(matches);
        assertEquals(List.of(2, 3), preview.stream().map(match -> match.entry().line()).toList());
        matches.set(2, matches.get(2).choose(b));
        assertEquals(List.of(2), PlaylistMatcher.previewOrder(matches).stream()
                .map(match -> match.entry().line()).toList());
        assertEquals(List.of(a, b), PlaylistMatcher.selection(matches).songs());
    }

    @Test public void rejectsUnreadableOrEmptyInput() {
        for (String input : List.of("", "\n  \n", "\0bad")) {
            assertThrows(IOException.class, () -> PlaylistMatcher.parse(input));
        }
    }

    private static PlaylistMatcher.Match matchOne(String text, List<PlaylistMatcher.Song> library) throws Exception {
        return PlaylistMatcher.match(PlaylistMatcher.parse(text), library, ignored -> true).getFirst();
    }

    private static PlaylistMatcher.Song song(String id, String path, String title, String artist) {
        return new PlaylistMatcher.Song(id, path, true, title, artist);
    }
}
