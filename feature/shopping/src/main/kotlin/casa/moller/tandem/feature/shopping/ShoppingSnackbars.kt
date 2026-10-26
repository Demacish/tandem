package casa.moller.tandem.feature.shopping

import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import casa.moller.tandem.core.model.shopping.ShoppingChange
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

// fix-ui (Phase 2 review): other household members' changes are shown without taking the user's
// "Ångra" away. They are collected only while the screen is STARTED (the list itself shows what
// changed while it was hidden), the changes of one read become ONE message, and such a message
// never replaces or queues in front of a snackbar with an action.

/** How long the changes of one read are gathered into one message. */
internal val CHANGE_BURST_WINDOW: Duration = 1.seconds

/**
 * Gathers the values that arrive within [window] after the first one into one list (one read of
 * the list reports each deleted entry on its own: a partner's "Rensa" of 30 rows is one message).
 */
internal fun <T> Flow<T>.inBursts(window: Duration = CHANGE_BURST_WINDOW): Flow<List<T>> = channelFlow {
    val pending = Channel<T>(Channel.UNLIMITED)
    launch {
        try {
            collect { pending.send(it) }
        } finally {
            pending.close()
        }
    }
    for (first in pending) {
        val burst = mutableListOf(first)
        delay(window)
        while (true) burst += pending.tryReceive().getOrNull() ?: break
        send(burst.toList())
    }
}

/**
 * The shopping screens' snackbars. The user's own messages replace whatever is shown (a newer
 * "Ångra" wins); other members' changes are dropped while a snackbar with an action is shown, and
 * a pending one is cancelled as soon as the user's own message comes.
 */
internal class ShoppingSnackbars(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    private var other: Job? = null

    /** The user's own message; [actionLabel] ("Ångra") calls [onAction]. */
    fun showOwn(text: String, actionLabel: String?, onAction: () -> Unit) {
        other?.cancel()
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = text,
                actionLabel = actionLabel,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    /** Someone else changed the list: shown only while no snackbar with an action is up. */
    fun showOther(text: String) {
        if (host.currentSnackbarData?.visuals?.actionLabel != null) return
        other?.cancel()
        other = scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message = text, duration = SnackbarDuration.Short)
        }
    }

    /** Shows a ViewModel effect: [ShoppingEffect.Show] and [ShoppingEffect.Failed] as own messages. */
    fun show(effect: ShoppingEffect, resources: Resources, onUndo: () -> Unit) {
        when (effect) {
            is ShoppingEffect.Show -> showOwn(
                text = resources.messageText(effect.message),
                actionLabel = if (effect.undoable) resources.getString(R.string.shopping_undo) else null,
                onAction = onUndo,
            )

            is ShoppingEffect.Failed -> showOwn(resources.errorText(effect.error), actionLabel = null, onAction = {})

            is ShoppingEffect.OtherChange -> showOther(resources.changesText(effect.changes))
        }
    }
}

/** One change as before ("Anna lade till mjölk"), several as their number ("3 ändringar från andra"). */
internal fun Resources.changesText(changes: List<ShoppingChange>): String =
    changes.singleOrNull()?.let { changeText(it) } ?: getQuantityString(R.plurals.shopping_change_many, changes.size, changes.size)
