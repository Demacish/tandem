package casa.moller.tandem.feature.pantry

import android.content.res.Resources
import androidx.compose.runtime.getValue
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.sync.SyncPause
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale

// Fas S (hemma-ui lane): what the pantry screens share (plan §8.8; patterns of Handla, Phase 2).

/**
 * Why the pantry may be out of date (plan 5.4): changes are always kept on the device and sent
 * later. The expired login has the shell's own banner, so it is not repeated here.
 */
enum class PantrySyncNotice {
    /** No network: changes wait ("3 ändringar väntar"). */
    OFFLINE,

    /** The server, something in front of it, or Tandem's own circuit breaker holds requests back. */
    PAUSED,

    /** Untested server version: the pantry is read, changes wait (plan 3.6). */
    SAFE_MODE,
    ;

    companion object {
        fun of(pause: SyncPause?): PantrySyncNotice? = when (pause) {
            SyncPause.OFFLINE -> OFFLINE
            SyncPause.BLOCKED, SyncPause.CIRCUIT_BREAKER -> PAUSED
            SyncPause.SAFE_MODE -> SAFE_MODE
            SyncPause.UNAUTHORIZED, SyncPause.IMPORT_RUNNING, null -> null
        }
    }
}

/** A refused write or a failed refresh as one line for a snackbar. */
internal fun Resources.pantryErrorText(error: TandemError): String = when {
    error.dataProblem() == DataProblem.READ_ONLY -> getString(R.string.pantry_error_read_only)
    error.isConnectivityProblem -> getString(R.string.pantry_error_refresh)
    else -> getString(R.string.pantry_error_save)
}

/** Unsent pantry changes on the device: entries and places marked pending. */
internal fun pendingCount(entries: Collection<InventoryEntry>, pendingLocations: Int): Int = entries.count { it.pending } + pendingLocations

/** A drink is a recipe with the keyword "Dryck" (B20); it has no ingredients and so always "has all" (FACTS "Fas S a"). */
internal fun RecipeOverview.isDrink(): Boolean = keywords.any { it.name.equals(DrinkKeywords.DRINK, ignoreCase = true) }

/**
 * An amount typed by the user ("2", "1,5", "0.75") in [locale]; null when it is not a positive
 * number. Both the locale's decimal separator and a point are accepted.
 */
internal fun parseAmount(text: String, locale: Locale): Double? {
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    val normalized = text.trim().replace(separator, '.').replace(',', '.').replace(" ", "")
    if (normalized.isEmpty()) return null
    val value = runCatching { BigDecimal(normalized) }.getOrNull() ?: return null
    return value.toDouble().takeIf { it > 0.0 && it.isFinite() }
}

/** [amount] for an editable field: "2", "1,5" (no trailing zeros), in [locale]. */
internal fun amountFieldText(amount: Double, locale: Locale): String {
    val plain = BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    return plain.replace('.', separator)
}
