package casa.moller.tandem.feature.recipes.recipeimport

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Check
import casa.moller.tandem.core.designsystem.icon.Edit
import casa.moller.tandem.core.designsystem.icon.Image
import casa.moller.tandem.core.designsystem.icon.PhotoCamera
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.core.ui.toErrorText
import casa.moller.tandem.feature.recipes.R

/**
 * "Importera recept" (Fas 3, import lane; plan §8.9): a field for a link or a text and
 * "Hämta receptet"; "Ta foto" and "Välj bild eller PDF" when the space has an AI provider; the
 * answer as a card (picture, name, steps, ingredients, the keywords, a warning for a duplicate,
 * the other pictures to choose from) and "Öppna i redigeraren" fixed below. A photographed page
 * waits for the next ones ("Ta ett foto till", "Ta bort sista", "Skicka": one recipe, plan §8.4).
 * A photo or PDF that was read by the AI is shown with the result (plan §8.4), beside it from
 * [ORIGINAL_BESIDE_MIN_WIDTH] (plan 6.5), else behind "Visa originalet". A link or a text has
 * nothing to put there, and the form makes way for the result: one column at every width.
 */
@Composable
fun RecipeImportScreen(
    state: RecipeImportUiState,
    onAction: (RecipeImportAction) -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onScanText: (() -> Unit)? = null,
    pageView: @Composable (reading: PageReading, modifier: Modifier) -> Unit = { _, viewModifier -> PagePlaceholder(viewModifier) },
    onSendPages: () -> Unit = {},
    originalView: OriginalView = { path, viewModifier -> OriginalPages(path, viewModifier) },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TandemTopAppBar(title = stringResource(R.string.recipe_import_title), onNavigateBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val original = state.sourcePath?.takeIf { state.result != null }
                val beside = original != null && maxWidth >= ORIGINAL_BESIDE_MIN_WIDTH
                val content: @Composable (Modifier) -> Unit = { contentModifier ->
                    Content(
                        state = state,
                        onAction = onAction,
                        onTakePhoto = onTakePhoto,
                        onPickFile = onPickFile,
                        onSendPages = onSendPages,
                        onScanText = onScanText,
                        pageView = pageView,
                        originalView = originalView.takeUnless { beside },
                        modifier = contentModifier.verticalScroll(rememberScrollState()),
                    )
                }
                if (beside && original != null) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        OriginalPane(
                            path = original,
                            view = originalView,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = TandemTheme.spacing.lg, vertical = TandemTheme.spacing.md),
                        )
                        content(Modifier.weight(1f))
                    }
                } else {
                    content(Modifier.widthIn(max = FORM_MAX_WIDTH))
                }
            }
            if (state.result != null) {
                HorizontalDivider()
                Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                    TandemPrimaryButton(
                        text = stringResource(R.string.recipe_import_open_editor),
                        onClick = { onAction(RecipeImportAction.OpenEditor) },
                        leadingIcon = TandemIcons.Edit,
                        modifier = Modifier
                            .widthIn(max = FORM_MAX_WIDTH)
                            .fillMaxWidth()
                            .padding(horizontal = TandemTheme.spacing.lg, vertical = TandemTheme.spacing.md),
                    )
                }
            }
        }
    }
}

@Composable
private fun Content(
    state: RecipeImportUiState,
    onAction: (RecipeImportAction) -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onSendPages: () -> Unit,
    onScanText: (() -> Unit)?,
    pageView: @Composable (reading: PageReading, modifier: Modifier) -> Unit,
    originalView: OriginalView?,
    modifier: Modifier,
) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (state.readOnly) ReadOnlyBanner(compact = true)
        if (state.isDemo) {
            CalloutCard(
                title = stringResource(R.string.recipe_import_demo_title),
                body = stringResource(R.string.recipe_import_demo),
                icon = TandemIcons.Info,
            )
        }
        when {
            state.working -> LoadingState(message = stringResource(workingText(state)))
            state.pageReading != null -> PageReader(state, state.pageReading, onAction, pageView)
            state.result != null -> Result(state, state.result, onAction, originalView)
            else -> Form(state, onAction, onTakePhoto, onPickFile, onSendPages, onScanText)
        }
    }
}

/** A text is read on the phone: the server's name does not belong on that message (plan §8.4 #39). */
@StringRes
internal fun workingText(state: RecipeImportUiState): Int =
    if (state.readingText) R.string.recipe_import_working_text else R.string.recipe_import_working

@Composable
private fun Form(
    state: RecipeImportUiState,
    onAction: (RecipeImportAction) -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onSendPages: () -> Unit,
    onScanText: (() -> Unit)?,
) {
    val spacing = TandemTheme.spacing
    Text(
        stringResource(if (state.scanned) R.string.recipe_import_intro_scanned else R.string.recipe_import_intro),
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = state.input,
        onValueChange = { onAction(RecipeImportAction.SetInput(it)) },
        label = { Text(stringResource(R.string.recipe_import_input)) },
        placeholder = { Text(stringResource(R.string.recipe_import_input_hint)) },
        minLines = 3,
        maxLines = 8,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    TandemPrimaryButton(
        text = stringResource(R.string.recipe_import_fetch),
        onClick = { onAction(RecipeImportAction.Import) },
        enabled = state.canImport,
        leadingIcon = TandemIcons.Check,
        modifier = Modifier.fillMaxWidth(),
    )
    state.problem?.let { problem ->
        val detail = state.failure?.toErrorText()?.title
        CalloutCard(
            title = stringResource(problem),
            body = detail ?: state.problemHint?.let { stringResource(it) }.orEmpty(),
            icon = TandemIcons.Warning,
            tone = TandemTone.Warning,
            onDismiss = { onAction(RecipeImportAction.Clear) },
        )
    }
    // Fas 4 (textigenkänning av recept): a photographed page read on the phone, without any server.
    if (onScanText != null) {
        SectionHeading(stringResource(R.string.recipe_import_scan_heading))
        Text(stringResource(R.string.recipe_import_scan_intro), style = MaterialTheme.typography.bodyMedium)
        TandemSecondaryButton(
            text = stringResource(R.string.recipe_import_scan_button),
            onClick = onScanText,
            enabled = !state.working && !state.readOnly,
            leadingIcon = TandemIcons.PhotoCamera,
        )
    }
    SectionHeading(stringResource(R.string.recipe_import_file_heading))
    if (state.aiAvailable && state.pages.isNotEmpty()) {
        PagesCard(state = state, onTakePhoto = onTakePhoto, onSendPages = onSendPages, onAction = onAction)
    } else if (state.aiAvailable) {
        Text(stringResource(R.string.recipe_import_file_intro), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            TandemSecondaryButton(
                text = stringResource(R.string.recipe_import_take_photo),
                onClick = onTakePhoto,
                enabled = state.canImportFile,
                leadingIcon = TandemIcons.PhotoCamera,
            )
            TandemSecondaryButton(
                text = stringResource(R.string.recipe_import_pick_file),
                onClick = onPickFile,
                enabled = state.canImportFile,
                leadingIcon = TandemIcons.Image,
            )
        }
    } else {
        Text(
            stringResource(if (state.isDemo) R.string.recipe_import_file_demo else R.string.recipe_import_file_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A section's title: a heading for TalkBack too. */
@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
}

/** The photographed pages so far: another one, the last one away, or send them as one recipe. */
@Composable
private fun PagesCard(
    state: RecipeImportUiState,
    onTakePhoto: () -> Unit,
    onSendPages: () -> Unit,
    onAction: (RecipeImportAction) -> Unit,
) {
    val count = state.pages.size
    CalloutCard(
        title = pluralStringResource(R.plurals.recipe_import_pages_title, count, count),
        body = stringResource(R.string.recipe_import_pages_body),
        icon = TandemIcons.PhotoCamera,
        modifier = Modifier.testTag(IMPORT_PAGES_TAG),
        actions = {
            TandemPrimaryButton(
                text = stringResource(R.string.recipe_import_pages_send),
                onClick = onSendPages,
                enabled = state.canImportFile,
                modifier = Modifier.testTag(IMPORT_PAGES_SEND_TAG),
            )
            TandemSecondaryButton(
                text = stringResource(R.string.recipe_import_pages_more),
                onClick = onTakePhoto,
                enabled = state.canImportFile,
                leadingIcon = TandemIcons.PhotoCamera,
            )
            // Tonal: a text button's colour is too faint on the card.
            TandemTonalButton(
                text = stringResource(R.string.recipe_import_pages_remove_last),
                onClick = { onAction(RecipeImportAction.RemoveLastPage) },
            )
        },
    )
}

@Composable
private fun Result(
    state: RecipeImportUiState,
    result: RecipeImportResult,
    onAction: (RecipeImportAction) -> Unit,
    originalView: OriginalView?,
) {
    val spacing = TandemTheme.spacing
    val draft = result.draft
    val ingredients = draft.ingredients.count { !it.isHeader }
    // The form with the focused button is gone: focus goes to what replaced it.
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    SectionHeading(stringResource(R.string.recipe_import_found), modifier = Modifier.focusRequester(focus).focusable())
    if (originalView != null) state.sourcePath?.let { OriginalFold(path = it, view = originalView) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(spacing.md), horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            RecipeImage(
                imageUrl = draft.imageUrl,
                contentDescription = null,
                modifier = Modifier.size(PREVIEW).clip(MaterialTheme.shapes.medium),
            )
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(draft.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOfNotNull(
                        pluralStringResource(R.plurals.recipe_import_steps, draft.steps.size, draft.steps.size),
                        pluralStringResource(R.plurals.recipe_import_ingredients, ingredients, ingredients),
                        (draft.workingMinutes + draft.waitingMinutes).takeIf { it > 0 }
                            ?.let { stringResource(R.string.recipe_import_minutes, it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (draft.keywords.isNotEmpty()) {
                    Text(
                        draft.keywords.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (draft.description.isNotBlank()) {
                    Text(draft.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
            }
        }
    }
    if (result.duplicates.isNotEmpty()) {
        CalloutCard(
            title = stringResource(R.string.recipe_import_duplicate_title),
            body = stringResource(R.string.recipe_import_duplicate_body, result.duplicates.joinToString(", ") { it.name }),
            icon = TandemIcons.Warning,
            tone = TandemTone.Warning,
        )
    }
    if (result.imageUrls.size > 1) {
        Text(
            stringResource(R.string.recipe_import_pictures),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            result.imageUrls.forEachIndexed { index, url ->
                val selected = url == draft.imageUrl
                val label = stringResource(R.string.recipe_import_picture_n, index + 1, result.imageUrls.size)
                RecipeImage(
                    imageUrl = url,
                    contentDescription = label,
                    modifier = Modifier
                        .size(THUMBNAIL)
                        .clip(MaterialTheme.shapes.medium)
                        .border(
                            BorderStroke(
                                width = if (selected) SELECTED_BORDER else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            ),
                            MaterialTheme.shapes.medium,
                        )
                        .semantics { this.selected = selected }
                        .clickable { onAction(RecipeImportAction.ChooseImage(url)) },
                )
            }
        }
    }
    StepSort(state, onAction)
    Text(
        stringResource(R.string.recipe_import_check_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TandemTextButton(text = stringResource(R.string.recipe_import_again), onClick = { onAction(RecipeImportAction.Clear) })
}

/**
 * #38: the page read in the app. The page itself is shown (the user may have to confirm
 * something in it), with "Läser sidan…", progress while the HTML is with the server, "Läs sidan
 * igen" after a page without a recipe, and "Avbryt". Fas 4 review (fix-data): after a page without
 * a recipe also "Tolka texten med AI", which says that it is an AI call, or without a provider
 * "Läs den delade texten".
 */
@Composable
private fun PageReader(
    state: RecipeImportUiState,
    reading: PageReading,
    onAction: (RecipeImportAction) -> Unit,
    pageView: @Composable (reading: PageReading, modifier: Modifier) -> Unit,
) {
    val spacing = TandemTheme.spacing
    val title = stringResource(if (reading.nothingFound) R.string.recipe_import_page_nothing_title else R.string.recipe_import_page_title)
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Text(
        stringResource(if (reading.nothingFound) R.string.recipe_import_page_nothing_body else R.string.recipe_import_page_body),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (reading.sending) {
        val sending = stringResource(R.string.recipe_import_page_sending)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().semantics { contentDescription = sending })
    }
    val pageLabel = stringResource(R.string.recipe_import_page_view, reading.url)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().height(PAGE_HEIGHT).semantics { paneTitle = pageLabel },
    ) {
        pageView(reading, Modifier.fillMaxSize().clip(MaterialTheme.shapes.large))
    }
    if (state.canInterpretText && state.aiAvailable) {
        Text(stringResource(R.string.recipe_import_page_ai_note), style = MaterialTheme.typography.bodySmall)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        if (state.canInterpretText) {
            val label = if (state.aiAvailable) R.string.recipe_import_page_interpret_ai else R.string.recipe_import_page_read_text
            TandemSecondaryButton(text = stringResource(label), onClick = { onAction(RecipeImportAction.InterpretText) })
        }
        if (reading.nothingFound) {
            TandemSecondaryButton(
                text = stringResource(R.string.recipe_import_page_again),
                onClick = { onAction(RecipeImportAction.ReadPageAgain) },
                enabled = !reading.sending,
                leadingIcon = TandemIcons.Check,
            )
        }
        TandemTextButton(text = stringResource(R.string.recipe_import_page_cancel), onClick = { onAction(RecipeImportAction.CancelPage) })
    }
}

/** What the screenshots show in the WebView's place (a WebView cannot be drawn there). */
@Composable
private fun PagePlaceholder(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** #40: "Fördela ingredienserna på stegen" (one more AI call, said so), progress, and Ångra after it. */
@Composable
private fun StepSort(state: RecipeImportUiState, onAction: (RecipeImportAction) -> Unit) {
    when {
        state.sortingSteps -> LoadingState(message = stringResource(R.string.recipe_import_sorting))

        state.stepsSorted -> TandemTextButton(
            text = stringResource(R.string.recipe_import_sort_undo),
            onClick = { onAction(RecipeImportAction.UndoSortSteps) },
        )

        state.canSortSteps -> {
            TandemSecondaryButton(
                text = stringResource(R.string.recipe_import_sort),
                onClick = { onAction(RecipeImportAction.SortSteps) },
                leadingIcon = TandemIcons.Edit,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.recipe_import_sort_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The photographed pages' card and its "Skicka". */
const val IMPORT_PAGES_TAG = "recipe-import:pages"
const val IMPORT_PAGES_SEND_TAG = "recipe-import:pages:send"

private val FORM_MAX_WIDTH = 640.dp
private val PREVIEW = 96.dp
private val THUMBNAIL = 72.dp
private val SELECTED_BORDER = 3.dp
private val PAGE_HEIGHT = 360.dp
