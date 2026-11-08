package casa.moller.tandem.feature.cook.handsfree

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.containerColor
import casa.moller.tandem.core.designsystem.component.contentColor
import casa.moller.tandem.core.designsystem.icon.Mic
import casa.moller.tandem.core.designsystem.icon.MicOff
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.VolumeUp
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.domain.voice.VoiceCommand
import casa.moller.tandem.feature.cook.R
import java.util.Locale

/**
 * Fas 5 (plan §8.1 "Handsfree", #32): the handsfree button beside "Föregående"/"Nästa" (64 dp).
 * It opens the handsfree sheet; its tonal fill and its state say whether handsfree is on.
 */
@Composable
internal fun HandsfreeButton(state: HandsfreeUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.cook_handsfree)
    val status = stringResource(if (state.enabled) R.string.cook_handsfree_state_on else R.string.cook_handsfree_state_off)
    val buttonModifier = modifier
        .size(TandemTheme.sizes.kitchenTouchTarget)
        .testTag(COOK_HANDSFREE_BUTTON_TAG)
        .semantics { stateDescription = status }
    val icon = if (state.settings.listens) TandemIcons.Mic else TandemIcons.MicOff
    if (state.enabled) {
        FilledTonalIconButton(onClick = onClick, modifier = buttonModifier) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(TandemTheme.sizes.iconLarge))
        }
    } else {
        IconButton(onClick = onClick, modifier = buttonModifier) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(TandemTheme.sizes.iconLarge))
        }
    }
}

/**
 * The listening indicator under cook mode's header while handsfree is on: what handsfree does now
 * (the volume keys, listening, reading aloud, or why the voice cannot listen) with the one button
 * that helps ("Tillåt mikrofonen", "Hämta", "Försök igen") and "Läs upp". Colour is never the only
 * signal: the icon and the text say it too (plan 6.6).
 *
 * @param compact a short window (split screen, a phone in landscape): one line, no explanation.
 */
@Composable
internal fun HandsfreeBar(
    state: HandsfreeUiState,
    onAction: (HandsfreeAction) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (!state.enabled) return
    val spacing = TandemTheme.spacing
    val text = barText(state)
    val tone = when (state.voice) {
        VoiceStatus.LISTENING, VoiceStatus.SPEAKING -> TandemTone.Accent
        VoiceStatus.NEEDS_PERMISSION, VoiceStatus.DOWNLOADABLE, VoiceStatus.UNAVAILABLE, VoiceStatus.FAILED -> TandemTone.Warning
        else -> TandemTone.Neutral
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.xs)
            .testTag(COOK_HANDSFREE_BAR_TAG),
        shape = MaterialTheme.shapes.large,
        color = tone.containerColor(),
        contentColor = tone.contentColor(),
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.lg, end = spacing.xs, top = spacing.xs, bottom = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(imageVector = barIcon(state), contentDescription = null, modifier = Modifier.size(TandemTheme.sizes.icon))
            // One TalkBack item: "Lyssnar. Säg nästa, tillbaka eller upprepa."
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(
                    text = text.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact && text.body != null) {
                    Text(text = text.body, style = MaterialTheme.typography.bodySmall)
                }
            }
            barAction(state)?.let { (label, action) ->
                TandemPrimaryButton(text = label, onClick = { onAction(action) }, size = TandemButtonSize.Kitchen)
            }
            IconButton(
                onClick = { onAction(HandsfreeAction.ReadAloud) },
                modifier = Modifier
                    .size(TandemTheme.sizes.kitchenTouchTarget)
                    .testTag(COOK_HANDSFREE_READ_TAG),
            ) {
                Icon(
                    imageVector = TandemIcons.VolumeUp,
                    contentDescription = stringResource(R.string.cook_handsfree_read_aloud),
                    modifier = Modifier.size(TandemTheme.sizes.iconLarge),
                )
            }
        }
    }
}

private data class BarText(val title: String, val body: String?)

@Composable
private fun barText(state: HandsfreeUiState): BarText {
    val language = languageName(state.languageTag)
    val keys = stringResource(R.string.cook_handsfree_keys_body)
    val base = when (state.voice) {
        VoiceStatus.OFF -> BarText(stringResource(R.string.cook_handsfree_keys_title), keys)

        VoiceStatus.STARTING -> BarText(stringResource(R.string.cook_handsfree_starting), keys)

        VoiceStatus.LISTENING -> BarText(
            state.heard?.let { stringResource(R.string.cook_handsfree_heard, commandWord(it)) }
                ?: stringResource(R.string.cook_handsfree_listening),
            stringResource(R.string.cook_handsfree_listening_body),
        )

        VoiceStatus.SPEAKING -> BarText(stringResource(R.string.cook_handsfree_speaking), null)

        VoiceStatus.NEEDS_PERMISSION -> BarText(stringResource(R.string.cook_handsfree_microphone_title), keys)

        VoiceStatus.DOWNLOADABLE -> BarText(
            stringResource(R.string.cook_handsfree_download_title, language),
            stringResource(R.string.cook_handsfree_download_body),
        )

        VoiceStatus.UNAVAILABLE -> BarText(
            stringResource(R.string.cook_handsfree_unavailable_title, language),
            stringResource(R.string.cook_handsfree_unavailable_body),
        )

        VoiceStatus.RETRYING -> BarText(stringResource(R.string.cook_handsfree_retrying), keys)

        VoiceStatus.FAILED -> BarText(stringResource(R.string.cook_handsfree_failed), keys)
    }
    if (!state.speechUnavailable) return base
    val missing = stringResource(R.string.cook_handsfree_speech_unavailable)
    return base.copy(body = listOfNotNull(base.body, missing).joinToString("\n"))
}

@Composable
private fun barIcon(state: HandsfreeUiState): ImageVector = when (state.voice) {
    VoiceStatus.LISTENING, VoiceStatus.STARTING, VoiceStatus.RETRYING -> TandemIcons.Mic
    VoiceStatus.SPEAKING -> TandemIcons.VolumeUp
    VoiceStatus.OFF,
    VoiceStatus.NEEDS_PERMISSION,
    VoiceStatus.DOWNLOADABLE,
    VoiceStatus.UNAVAILABLE,
    VoiceStatus.FAILED,
    -> TandemIcons.MicOff
}

@Composable
private fun barAction(state: HandsfreeUiState): Pair<String, HandsfreeAction>? = when (state.voice) {
    VoiceStatus.NEEDS_PERMISSION -> stringResource(R.string.cook_handsfree_allow_microphone) to HandsfreeAction.AllowMicrophone
    VoiceStatus.DOWNLOADABLE -> stringResource(R.string.cook_handsfree_download) to HandsfreeAction.DownloadLanguage
    VoiceStatus.FAILED -> stringResource(R.string.cook_handsfree_try_again) to HandsfreeAction.Retry
    else -> null
}

/** The command as the user says it in the UI's language ("nästa", "next"). */
@Composable
internal fun commandWord(command: VoiceCommand): String = stringResource(
    when (command) {
        VoiceCommand.NEXT -> R.string.cook_handsfree_word_next
        VoiceCommand.PREVIOUS -> R.string.cook_handsfree_word_back
        VoiceCommand.REPEAT -> R.string.cook_handsfree_word_repeat
    },
)

/** "svenska", "English": the language's own name in the UI's language. */
@Composable
private fun languageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    val ui: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return locale.getDisplayLanguage(ui).ifEmpty { tag }
}

/** The handsfree sheet over cook mode ([HandsfreeSheetContent]) while [HandsfreeUiState.sheetOpen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HandsfreeSheet(state: HandsfreeUiState, onAction: (HandsfreeAction) -> Unit) {
    if (!state.sheetOpen) return
    ModalBottomSheet(
        onDismissRequest = { onAction(HandsfreeAction.CloseSheet) },
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        HandsfreeSheetContent(state = state, onAction = onAction)
    }
}

/**
 * Handsfree's three parts (Stitch handsfree_kokl_ge_r_st_gester, without the wake word and the
 * camera gestures, which the plan does not have): the volume keys (the master switch), voice
 * commands on the device, reading every step aloud; then what to say. "Klar" stays below the
 * scrolling rows (like the recipe's shopping sheet), so it is reachable at font scale 2.
 */
@Composable
internal fun HandsfreeSheetContent(state: HandsfreeUiState, onAction: (HandsfreeAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.fillMaxWidth().testTag(COOK_HANDSFREE_SHEET_TAG)) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.cook_handsfree),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.cook_handsfree_sheet_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HandsfreeSwitch(
                title = stringResource(R.string.cook_handsfree),
                body = stringResource(R.string.cook_handsfree_switch_keys_body),
                checked = state.enabled,
                onChange = { onAction(HandsfreeAction.SetEnabled(it)) },
                tag = COOK_HANDSFREE_SWITCH_TAG,
            )
            HorizontalDivider()
            HandsfreeSwitch(
                title = stringResource(R.string.cook_handsfree_switch_voice),
                body = stringResource(R.string.cook_handsfree_switch_voice_body),
                checked = state.settings.voice,
                onChange = { onAction(HandsfreeAction.SetVoice(it)) },
                enabled = state.enabled,
                tag = COOK_HANDSFREE_VOICE_TAG,
            )
            HorizontalDivider()
            HandsfreeSwitch(
                title = stringResource(R.string.cook_handsfree_switch_read),
                body = stringResource(R.string.cook_handsfree_switch_read_body),
                checked = state.settings.readSteps,
                onChange = { onAction(HandsfreeAction.SetReadSteps(it)) },
                enabled = state.enabled,
                tag = COOK_HANDSFREE_READ_SWITCH_TAG,
            )
            Text(
                text = stringResource(R.string.cook_handsfree_say_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(top = spacing.md)
                    .semantics { heading() },
            )
            Phrase(VoiceCommand.NEXT, stringResource(R.string.cook_handsfree_say_next))
            Phrase(VoiceCommand.PREVIOUS, stringResource(R.string.cook_handsfree_say_back))
            Phrase(VoiceCommand.REPEAT, stringResource(R.string.cook_handsfree_say_repeat))
        }
        TandemTonalButton(
            text = stringResource(R.string.cook_handsfree_done),
            onClick = { onAction(HandsfreeAction.CloseSheet) },
            size = TandemButtonSize.Kitchen,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.lg),
        )
    }
}

/** One switch row: the whole row toggles, one TalkBack item with the switch's state. */
@Composable
private fun HandsfreeSwitch(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .testTag(tag)
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Column(Modifier.weight(1f)) {
            val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = color)
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** "”Nästa” – Nästa steg" as one TalkBack item. */
@Composable
private fun Phrase(command: VoiceCommand, meaning: String) {
    val spacing = TandemTheme.spacing
    val word = commandWord(command)
    val quoted = stringResource(R.string.cook_handsfree_quoted, word)
    val description = stringResource(R.string.cook_handsfree_phrase_description, word, meaning)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Icon(TandemIcons.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(text = quoted, style = MaterialTheme.typography.titleMedium)
            Text(text = meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal const val COOK_HANDSFREE_BUTTON_TAG = "cook:handsfree"
internal const val COOK_HANDSFREE_BAR_TAG = "cook:handsfree-bar"
internal const val COOK_HANDSFREE_READ_TAG = "cook:handsfree-read"
internal const val COOK_HANDSFREE_SHEET_TAG = "cook:handsfree-sheet"
internal const val COOK_HANDSFREE_SWITCH_TAG = "cook:handsfree-switch"
internal const val COOK_HANDSFREE_VOICE_TAG = "cook:handsfree-voice"
internal const val COOK_HANDSFREE_READ_SWITCH_TAG = "cook:handsfree-read-steps"
