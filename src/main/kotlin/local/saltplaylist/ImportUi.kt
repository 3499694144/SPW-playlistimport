@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package local.saltplaylist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import local.saltplaylist.resolver.ImportUiSymbols

/** Official UI hook draws under the host's theme, dialog host and blur composition locals. */
class ImportUi(
    private val symbols: ImportUiSymbols.Resolved,
    private val dialog: ImportDialog,
    private val onShown: Runnable
) : ComposableHook(), AutoCloseable {
    private var active by mutableStateOf(true)
    private var registration: HookHandle? = null

    fun install() {
        check(registration == null && active)
        val target = symbols.descriptors().anchor()
        val caller = symbols.descriptors().caller()
        registration = WorkshopApi.hookRegistrar.afterComposable(
            target.owner(), target.name(), caller.owner(), emptyList(), 50, this
        )
    }

    @Composable override fun Content(call: UiHookCall) {
        if (active) {
            dialog.Render()
            LaunchedEffect(Unit) { onShown.run() }
        }
    }

    override fun close() {
        active = false
        dialog.dispose()
        registration?.close()
        registration = null
    }
}
