package casa.moller.tandem.navigation

import android.content.Intent
import casa.moller.tandem.core.timer.TimerIntents
import casa.moller.tandem.feature.shell.navigation.TimerOpenRequest

/**
 * Reads core:timer's "open the app at this timer" intent ([TimerIntents.openTimer]: a timer
 * notification, the alarm notification, the lock-screen alarm and the system clock UI's alarm
 * entry). `MainActivity` is the launcher activity and therefore exported, so any app may send
 * this action: the extras are only ids of local data, invalid values are dropped, and an extra
 * that cannot be read ends as "just open the app".
 */
internal object TimerLaunch {
    /** Steps beyond this are no real step (Tandoor recipes have a few dozen at most). */
    private const val MAX_STEP_INDEX = 999

    /** Longer than any account id or "demo". */
    private const val MAX_SOURCE_LENGTH = 200

    /** The request in [intent], or null when it is not a timer intent. */
    fun requestFrom(intent: Intent?): TimerOpenRequest? {
        if (intent?.action != TimerIntents.ACTION_OPEN_TIMER) return null
        // A foreign sender's malformed extras (Bundle.unparcel) must not crash the launch.
        val extras = runCatching { intent.extras }.getOrNull()
        val recipeId = extras?.takeIf { it.containsKey(TimerIntents.EXTRA_RECIPE_ID) }?.getLong(TimerIntents.EXTRA_RECIPE_ID)
        val stepIndex = extras?.takeIf { it.containsKey(TimerIntents.EXTRA_STEP_INDEX) }?.getInt(TimerIntents.EXTRA_STEP_INDEX)
        val sourceKey = runCatching { extras?.getString(TimerIntents.EXTRA_SOURCE_KEY) }.getOrNull()
        return requestOf(recipeId, stepIndex, sourceKey)
    }

    /**
     * A recipe id must be positive and a step 0-based; anything else is left out. The source
     * (fix-ui, 0.2.0 review) only matters with a recipe and is capped in length.
     */
    fun requestOf(recipeId: Long?, stepIndex: Int?, sourceKey: String? = null): TimerOpenRequest {
        val recipe = recipeId?.takeIf { it > 0 }
        return TimerOpenRequest(
            recipeId = recipe,
            stepIndex = stepIndex?.takeIf { recipe != null && it in 0..MAX_STEP_INDEX },
            sourceKey = sourceKey?.takeIf { recipe != null && it.isNotBlank() && it.length <= MAX_SOURCE_LENGTH },
        )
    }
}
