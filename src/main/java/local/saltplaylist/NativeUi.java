package local.saltplaylist;

import androidx.compose.foundation.lazy.LazyListState;
import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import local.saltplaylist.resolver.ImportUiSymbols;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Calls only pre-resolved Salt components. The Kotlin caller owns Compose state and groups. */
public final class NativeUi {
    private final ImportUiSymbols.Resolved symbols;

    public NativeUi(ImportUiSymbols.Resolved symbols) { this.symbols = symbols; }

    public void dialog(Runnable dismiss, Runnable confirm, String title, boolean cancelEnabled, boolean confirmEnabled,
                       String cancelText, String confirmText, Object content, Composer composer) {
        invoke(symbols.dialog(), action(dismiss), action(confirm), title, cancelEnabled, confirmEnabled,
                cancelText, confirmText, content, composer, 0, 0);
    }

    public void text(String text, Modifier modifier, int maxLines, Composer composer) {
        int defaults = ((1 << 17) - 1) & ~(1 | 2 | (1 << 13));
        invoke(symbols.text(), text, modifier, 0L, 0L, null, null, null, 0L, null, null,
                0L, 0, true, maxLines, 1, null, null, composer, 0, 0, defaults);
    }

    public void item(Runnable click, String title, String subtitle, String actionText, Modifier modifier, boolean enabled, Composer composer) {
        int defaults = ((1 << 13) - 1) & ~(1 | 2 | 4 | 8 | (1 << 8) | (1 << 11));
        invoke(symbols.item(), action(click), title, modifier, enabled, null, null, null, 0L,
                subtitle, 0L, null, actionText, null, composer, 0, 0, defaults);
    }

    public void edit(String value, Consumer<String> change, String hint, Modifier modifier, boolean readOnly, Composer composer) {
        Function1<String, Unit> changed = text -> { change.accept(text); return Unit.INSTANCE; };
        int defaults = ((1 << 10) - 1) & ~(1 | 2 | 4 | 8 | (1 << 5));
        invoke(symbols.edit(), value, changed, modifier, hint, 0L, readOnly, null, null, null, null, composer, 0, defaults);
    }

    public void list(Modifier modifier, LazyListState state, boolean enabled, Object content, Composer composer) {
        int defaults = ((1 << 10) - 1) & ~(1 | 2 | (1 << 7) | (1 << 9));
        invoke(symbols.lazyList(), modifier, state, null, false, null, null, null, enabled, null, content, composer, 0, defaults);
    }

    private static Function0<Unit> action(Runnable action) { return () -> { action.run(); return Unit.INSTANCE; }; }

    private static void invoke(Method method, Object... arguments) {
        try { method.invoke(null, arguments); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Host UI component failed: " + method, cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Host UI component unavailable: " + method, failure);
        }
    }
}
