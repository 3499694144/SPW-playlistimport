@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package local.saltplaylist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import local.saltplaylist.resolver.ImportUiSymbols
import java.awt.Window
import java.nio.file.Path
import java.util.function.BiConsumer
import java.util.function.Consumer

/** Drawn by the official UI hook, with the host's current theme and composition locals. */
class ImportDialog(
    playlistName: String,
    matches: List<PlaylistMatcher.Match>,
    library: List<PlaylistMatcher.Song>,
    symbols: ImportUiSymbols.Resolved,
    private val owner: Window,
    private val onPick: Consumer<Consumer<Path?>>,
    private val onImport: BiConsumer<String, PlaylistMatcher.Selection>,
    private val onCancel: Runnable
) {
    /** The playlist is named after the TXT file; it is shown, not edited. */
    private val title = playlistName.trim().ifBlank { UiText.DEFAULT_TITLE }
    private val original = matches.toMutableList()
    private val libraryByPath = library.groupBy { PlaylistMatcher.pathKey(it.path()) }
    private val ui = NativeUi(symbols)
    private val listState = LazyListState()
    private var rows by mutableStateOf(PlaylistMatcher.previewOrder(original))
    private var selection by mutableStateOf(PlaylistMatcher.selection(original))
    private var visible by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var confirming by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    fun focus() { owner.toFront(); owner.requestFocus() }

    fun dispose() {
        visible = false
    }

    @Composable fun Render() {
        if (!visible) return
        val dismiss = Runnable {
            if (!busy) {
                if (confirming) confirming = false else onCancel.run()
            }
        }
        val confirm = Runnable {
            if (!busy) {
                when {
                    title.isEmpty() -> message = UiText.TITLE_REQUIRED
                    selection.songs().isEmpty() -> message = UiText.NO_MATCHES
                    !confirming -> { message = null; confirming = true }
                    else -> { busy = true; onImport.accept(title, selection) }
                }
            }
        }
        val content: @Composable ColumnScope.() -> Unit = {
            Column(Modifier.fillMaxWidth()) {
                if (confirming) {
                    Text(UiText.CONFIRM.format(title, selection.songs().size, selection.unresolved(), selection.duplicates()), 5)
                    if (busy) Text(UiText.IMPORTING, 2)
                } else {
                    Text(UiText.PLAYLIST_NAME.format(title), 2)
                    Text(UiText.SUMMARY.format(original.size, selection.songs().size, selection.unresolved(), selection.duplicates()), 3)
                    val review = PlaylistMatcher.reviewCount(original)
                    if (review > 0) Text(UiText.REVIEW_HINT.format(review), 3)
                    if (rows.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        TrackList()
                    }
                    message?.let { Text(it, 3) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        ui.dialog(dismiss, confirm, UiText.APP, !busy, !busy && selection.songs().isNotEmpty(),
            if (confirming) UiText.BACK else UiText.CANCEL,
            if (busy) UiText.IMPORTING else if (confirming) UiText.CONFIRM_IMPORT else UiText.IMPORT,
            content, currentComposer)
    }

    @Composable private fun Text(value: String, lines: Int = 1) {
        ui.text(value, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), lines, currentComposer)
    }

    @Composable private fun TrackList() {
        val content: LazyListScope.() -> Unit = {
            items(rows.size, key = { index -> rows[index].entry().line() }) { index ->
                val row = rows[index]
                ui.item({ chooseFile(row) }, row.entry().display(), UiText.status(row.status(), row.note()), UiText.MANUAL,
                    Modifier.fillMaxWidth(), !busy, currentComposer)
            }
        }
        ui.list(Modifier.fillMaxWidth().heightIn(max = 260.dp), listState, !busy, content, currentComposer)
    }

    private fun chooseFile(match: PlaylistMatcher.Match) {
        if (busy) return
        busy = true
        message = null
        onPick.accept(Consumer { path ->
            if (visible) {
                busy = false
                if (path != null) {
                    val songs = libraryByPath[PlaylistMatcher.pathKey(path.toString())].orEmpty()
                    when {
                        songs.size != 1 -> message = UiText.NOT_IN_LIBRARY
                        !PlaylistMatcher.isAvailable(songs.single()) -> message = UiText.NOT_READABLE
                        else -> replace(match.choose(songs.single()))
                    }
                }
            }
        })
    }

    private fun replace(match: PlaylistMatcher.Match) {
        val index = original.indexOfFirst { it.entry().line() == match.entry().line() }
        if (index < 0) return
        original[index] = match
        rows = PlaylistMatcher.previewOrder(original)
        selection = PlaylistMatcher.selection(original)
        message = null
    }
}
