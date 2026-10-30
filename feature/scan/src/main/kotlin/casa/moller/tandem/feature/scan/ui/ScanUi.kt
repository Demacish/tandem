package casa.moller.tandem.feature.scan.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.Search
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.model.scan.ScanReviewItem
import casa.moller.tandem.feature.scan.R
import casa.moller.tandem.feature.scan.ScanSaver
import casa.moller.tandem.feature.scan.camera.CameraAccess
import casa.moller.tandem.feature.scan.camera.openAppSettings
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate

// Fas S (skanning lane): the pieces the barcode scanner, the receipt scanner and the shared review
// list have in common (plan §8.8): numbers and dates in the app's language, the place and expiry
// quick choices, the food picker, the row status and the camera permission panel.

/** Numbers ("1,5" in Swedish), amounts ("5 dl") and best-before days ("5 nov.") in the app's language. */
class ScanFormat(private val locale: Locale) {
    private val numbers = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 3
        isGroupingUsed = false
    }
    private val day = DateTimeFormatter.ofPattern("d MMM", locale)

    fun number(value: Double): String = numbers.format(value)

    /** "5 dl", or "1 st" with [defaultUnit] when the row has neither. */
    fun amount(amount: Double?, unit: String?, defaultUnit: String): String =
        "${number(amount?.takeIf { it > 0.0 } ?: 1.0)} ${unit?.takeIf { it.isNotBlank() } ?: defaultUnit}"

    fun date(date: LocalDate): String = day.format(date.toJavaLocalDate())

    /** The amount the user typed ("1,5", "1.5", "2"), null when it is not a positive number. */
    fun parseAmount(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }

    /** [value] as the text field shows it. */
    fun amountText(value: Double?): String = value?.let(::number).orEmpty()
}

/** [ScanFormat] for the current configuration. */
@Composable
internal fun rememberScanFormat(): ScanFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { ScanFormat(locale) }
}

/** How a row found its food, as the status chip and TalkBack say it. */
enum class ScanRowStatus(@StringRes val label: Int, val tone: TandemTone) {
    LEARNED(R.string.scan_status_learned, TandemTone.Success),
    SUGGESTED(R.string.scan_status_suggested, TandemTone.Accent),
    CHOSEN(R.string.scan_status_chosen, TandemTone.Success),
    NEW(R.string.scan_status_new, TandemTone.Accent),
    UNKNOWN(R.string.scan_status_unknown, TandemTone.Warning),
    SKIPPED(R.string.scan_status_skipped, TandemTone.Neutral),
    LOOKING_UP(R.string.scan_status_looking_up, TandemTone.Neutral),
    NOT_FOUND(R.string.scan_status_not_found, TandemTone.Warning),
    OFFLINE(R.string.scan_status_offline, TandemTone.Warning),
    FAILED(R.string.scan_status_failed, TandemTone.Error),
    ;

    companion object {
        /** The status of a review row from its match alone. */
        fun of(item: ScanReviewItem): ScanRowStatus = when {
            !item.include -> SKIPPED

            else -> when (item.food?.via) {
                FoodMatchVia.ALIAS -> LEARNED
                FoodMatchVia.SEARCH -> SUGGESTED
                FoodMatchVia.MANUAL -> CHOSEN
                FoodMatchVia.NEW -> NEW
                null -> UNKNOWN
            }
        }
    }
}

/** The label of an expiry quick choice. */
@StringRes
internal fun ExpiryChoice.label(): Int = when (this) {
    ExpiryChoice.NONE -> R.string.scan_expiry_none
    ExpiryChoice.PLUS_3_DAYS -> R.string.scan_expiry_3_days
    ExpiryChoice.PLUS_1_WEEK -> R.string.scan_expiry_1_week
    ExpiryChoice.PLUS_1_MONTH -> R.string.scan_expiry_1_month
    ExpiryChoice.PLUS_3_MONTHS -> R.string.scan_expiry_3_months
}

/** A small heading inside a card or sheet. */
@Composable
internal fun ScanSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}

/** The household's places as chips ("Kyl", "Frys", "Skafferi"); [selectedId] null = none chosen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocationChips(
    locations: List<InventoryLocation>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm)) {
        locations.forEach { location ->
            FilterChip(
                selected = location.id == selectedId,
                onClick = { onSelect(location.id) },
                label = { Text(location.name) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
            )
        }
    }
}

/**
 * The expiry quick choices for [location] (plan §8.8: +3 dagar, +1 vecka, +1 månad or none; the
 * freezer gets longer ones). [selected] is the row's day, matched against each choice from [today].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpiryChips(
    location: InventoryLocation?,
    selected: LocalDate?,
    today: LocalDate,
    onSelect: (ExpiryChoice) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm)) {
        ExpiryChoice.forLocation(location).forEach { choice ->
            FilterChip(
                selected = choice.dateFrom(today) == selected,
                onClick = { onSelect(choice) },
                label = { Text(stringResource(choice.label())) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
            )
        }
    }
}

/** What the food picker shows: the typed [query], the foods that contain it and the suggestions for the scanned text. */
data class FoodPickerUi(val query: String = "", val results: List<Food> = emptyList(), val suggestions: List<Food> = emptyList()) {
    /** "Skapa «query»" is offered when no stored food has exactly that name. */
    val canCreate: Boolean
        get() = query.isNotBlank() && results.none { it.name.equals(query.trim(), ignoreCase = true) }
}

/** Test tag of the food picker's search field. */
const val SCAN_FOOD_SEARCH_TAG = "scan:food-search"

/**
 * Pick or create the food for a row (plan §8.8: "välj eller skapa"): the suggestions for the
 * scanned text as chips, a search field over the space's foods, and "Skapa «…»" for a new one.
 * The [current] food and the suggestions are not repeated among the results (one button per food,
 * so TalkBack never reads the same name twice).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FoodPicker(
    picker: FoodPickerUi,
    onQuery: (String) -> Unit,
    onPick: (Food) -> Unit,
    onCreate: (String) -> Unit,
    current: String?,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    val suggestions = picker.suggestions.filterNot { it.name.equals(current, ignoreCase = true) }
    val results = picker.results.filterNot { food ->
        food.name.equals(current, ignoreCase = true) || suggestions.any { it.id == food.id }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        if (suggestions.isNotEmpty()) {
            ScanSectionTitle(stringResource(R.string.scan_food_suggestions))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                suggestions.forEach { food ->
                    FilterChip(
                        selected = false,
                        onClick = { onPick(food) },
                        label = { Text(food.name) },
                        modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
                    )
                }
            }
        }
        TandemTextField(
            value = picker.query,
            onValueChange = onQuery,
            label = stringResource(R.string.scan_food_search),
            leadingIcon = TandemIcons.Search,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SCAN_FOOD_SEARCH_TAG),
        )
        results.forEach { food ->
            TandemTextButton(text = food.name, onClick = { onPick(food) }, modifier = Modifier.fillMaxWidth())
        }
        if (picker.canCreate) {
            TandemTextButton(
                text = stringResource(R.string.scan_food_create, picker.query.trim()),
                onClick = { onCreate(picker.query.trim()) },
                leadingIcon = TandemIcons.Add,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The camera area when the preview cannot run: why the camera is needed (asked only now, in
 * context) and "Tillåt kameran". Refused for good = a link to the app's settings; no camera = only
 * the text. The photo picker, which needs no permission, is the screen's own button next to this
 * panel (always shown, so it is not repeated here).
 */
@Composable
internal fun CameraAccessPanel(access: CameraAccess, @StringRes rationale: Int, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    val context = LocalContext.current
    // Fas S review (fix-ui): the camera area has a fixed height (about 40 % of a phone), so at font
    // scale 2 or in landscape the text and "Tillåt kameran" did not fit and the button got no
    // height. The content scrolls when it is taller (the Box still centres it when it fits), and
    // the hero icon is left out in a short area.
    BoxWithConstraints(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val roomy = maxHeight >= HERO_MIN_HEIGHT
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = TandemTheme.sizes.maxContentWidth)
                .padding(spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (roomy) Icon(ScanIcons.Camera, contentDescription = null, modifier = Modifier.size(TandemTheme.sizes.iconHero))
            Text(
                text = stringResource(
                    when (access) {
                        CameraAccess.UNAVAILABLE -> R.string.scan_camera_none_title
                        CameraAccess.BLOCKED, CameraAccess.DENIED -> R.string.scan_camera_denied_title
                        else -> R.string.scan_camera_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(
                    when (access) {
                        CameraAccess.UNAVAILABLE -> R.string.scan_camera_none_body
                        CameraAccess.BLOCKED -> R.string.scan_camera_blocked_body
                        else -> rationale
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            when (access) {
                CameraAccess.NOT_ASKED, CameraAccess.DENIED -> TandemPrimaryButton(
                    text = stringResource(R.string.scan_camera_allow),
                    onClick = onAllow,
                    leadingIcon = ScanIcons.Camera,
                )

                CameraAccess.BLOCKED -> TandemPrimaryButton(
                    text = stringResource(R.string.scan_camera_settings),
                    onClick = { context.openAppSettings() },
                )

                CameraAccess.GRANTED, CameraAccess.UNAVAILABLE -> Unit
            }
        }
    }
}

/**
 * A modifier that scrolls its element into view whenever [key] (a problem) appears or changes:
 * the scanners' problem cards sit below the tips in a panel under the camera (Fas S review, fix-ui).
 */
@Composable
internal fun rememberBringIntoView(key: Any): Modifier {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(key) {
        // After the frame that laid the card out.
        withFrameNanos { }
        requester.bringIntoView()
    }
    return Modifier.bringIntoViewRequester(requester)
}

/** The camera permission panel shows its hero icon only when it has at least this height. */
private val HERO_MIN_HEIGHT = 300.dp

/** The camera area in screenshot tests and previews (the real preview needs a device camera). */
@Composable
fun CameraPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.inverseSurface), contentAlignment = Alignment.Center) {
        Icon(
            ScanIcons.Camera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.size(TandemTheme.sizes.iconHero),
        )
    }
}

/** The snackbar text for a failed save. */
internal fun Resources.scanErrorText(error: TandemError): String = when {
    error.dataProblem() == DataProblem.READ_ONLY -> getString(R.string.scan_error_read_only)
    (error as? TandemError.Unknown)?.detail == ScanSaver.NO_LOCATION -> getString(R.string.scan_error_no_location)
    error.isConnectivityProblem -> getString(R.string.scan_error_offline)
    else -> getString(R.string.scan_error_save)
}
