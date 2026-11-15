package casa.moller.tandem.feature.recipes.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.DoneAll
import casa.moller.tandem.core.designsystem.icon.Inbox
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.inbox.InboxEntry
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.layout.MediaRow
import casa.moller.tandem.core.ui.layout.scaledMaxLines
import casa.moller.tandem.core.ui.readonly.ReadOnlyNote
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.feature.recipes.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant

/** Test tag of the list of posts. */
const val INBOX_LIST_TAG = "recipes:inbox:list"

/** Test tag of one post's row: `recipes:inbox:entry:<id>`. */
fun inboxEntryTag(id: Long): String = "recipes:inbox:entry:$id"

/** Test tag of the preview's "Importera". */
const val INBOX_IMPORT_TAG = "recipes:inbox:import"

/**
 * "Inkorg för recept" (Fas 5, rss lane, plan §7 #35), stateless. The posts of the chosen Miniflux
 * categories as rows (image, title, feed and date, "Importerad"), newest first, in a column at the
 * reading width (phones and tablets alike); pull to refresh. A tap opens the post's preview in a
 * bottom sheet ([InboxPreviewContent]) with "Importera", the second and last tap before the import
 * review. A refresh that failed says why above the posts, which stay (they are stored on the phone).
 *
 * Not set up: what the inbox is and "Ställ in" ([onOpenSettings]). Guests read, the preview says that
 * they cannot import. Demo mode shows its built-in inbox with a note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    state: InboxUiState,
    onAction: (InboxAction) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.inbox_title),
                onNavigateBack = onBack,
                actions = {
                    if (!state.isDemo) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(TandemIcons.Settings, contentDescription = stringResource(R.string.inbox_open_settings))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))

            !state.inUse -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = TandemIcons.Inbox,
                    title = stringResource(R.string.inbox_not_set_up_title),
                    body = stringResource(R.string.inbox_not_set_up_body),
                ) {
                    TandemPrimaryButton(text = stringResource(R.string.inbox_set_up), onClick = onOpenSettings)
                }
            }

            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { onAction(InboxAction.Refresh) },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                InboxList(state = state, onAction = onAction)
            }
        }
    }
    val preview = state.preview
    if (preview != null) {
        ModalBottomSheet(
            onDismissRequest = { onAction(InboxAction.ClosePreview) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            InboxPreviewContent(entry = preview, readOnly = state.readOnly, onAction = onAction)
        }
    }
}

@Composable
private fun InboxList(state: InboxUiState, onAction: (InboxAction) -> Unit) {
    val spacing = TandemTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(INBOX_LIST_TAG),
        contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        val notes = state.problem != null || state.readOnly || state.isDemo
        if (notes) {
            item(key = "notes") {
                Column(
                    modifier = Modifier.readingWidth().padding(bottom = spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    state.problem?.let { problem ->
                        val body = stringResource(problem.text)
                        CalloutCard(
                            title = stringResource(R.string.inbox_problem_title),
                            body = state.lastRefresh?.let { body + "\n" + stringResource(R.string.inbox_updated, dateTime(it)) } ?: body,
                            icon = TandemIcons.Warning,
                            tone = TandemTone.Warning,
                        )
                    }
                    if (state.isDemo) {
                        InfoCard(title = stringResource(R.string.inbox_title), body = stringResource(R.string.inbox_demo_note))
                    }
                    if (state.readOnly) ReadOnlyNote(text = stringResource(R.string.inbox_guest_note))
                }
            }
        }
        if (state.entries.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = TandemIcons.DoneAll,
                    title = stringResource(R.string.inbox_empty_title),
                    body = stringResource(R.string.inbox_empty_body),
                    modifier = Modifier.readingWidth(),
                )
            }
        }
        items(state.entries, key = { it.id }) { entry ->
            Column(modifier = Modifier.readingWidth()) {
                InboxRow(entry = entry, onOpen = { onAction(InboxAction.Open(entry.id)) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (!state.isDemo && state.problem == null && state.lastRefresh != null && state.entries.isNotEmpty()) {
            item(key = "updated") {
                Text(
                    text = stringResource(R.string.inbox_updated, dateTime(state.lastRefresh)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.readingWidth().padding(vertical = spacing.md),
                )
            }
        }
    }
}

/** One post: one TalkBack node ("Pannkakor, Exempelköket · 13 nov. 2026, Importerad"), a tap opens its preview. */
@Composable
private fun InboxRow(entry: InboxEntry, onOpen: () -> Unit) {
    val spacing = TandemTheme.spacing
    MediaRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onOpen)
            .padding(vertical = spacing.sm, horizontal = spacing.xs)
            .testTag(inboxEntryTag(entry.id)),
        leading = {
            RecipeImage(
                imageUrl = entry.imageUrl,
                modifier = Modifier.size(THUMBNAIL).clip(MaterialTheme.shapes.small),
            )
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = scaledMaxLines(2),
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = sourceLine(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.imported) ImportedLabel()
            }
        },
        trailing = {},
    )
}

@Composable
private fun ImportedLabel() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xxs)) {
        Icon(
            TandemIcons.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(TandemTheme.sizes.iconSmall),
        )
        Text(
            text = stringResource(R.string.inbox_imported),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The preview of one post (tap 1): image, title, feed and date, the start of its text, and "Importera"
 * (tap 2) fixed under what scrolls, so it stays reachable at the largest text. Guests get a note
 * instead of "Importera". Also shown by the screenshot tests.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InboxPreviewContent(entry: InboxEntry, readOnly: Boolean, onAction: (InboxAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (entry.imageUrl != null) {
                RecipeImage(
                    imageUrl = entry.imageUrl,
                    modifier = Modifier.fillMaxWidth().aspectRatio(IMAGE_RATIO).clip(MaterialTheme.shapes.large),
                )
            }
            Text(text = entry.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(text = sourceLine(entry), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.imported) {
                Text(
                    text = stringResource(R.string.inbox_preview_imported_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (entry.excerpt.isNotBlank()) Text(text = entry.excerpt, style = MaterialTheme.typography.bodyLarge)
        }
        Column(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (readOnly) {
                ReadOnlyNote(text = stringResource(R.string.inbox_preview_guest))
            } else {
                TandemPrimaryButton(
                    text = stringResource(R.string.inbox_preview_import),
                    onClick = { onAction(InboxAction.Import(entry.id)) },
                    modifier = Modifier.fillMaxWidth().testTag(INBOX_IMPORT_TAG),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                TandemTonalButton(
                    text = stringResource(R.string.inbox_preview_mark_read),
                    onClick = { onAction(InboxAction.Dismiss(entry.id)) },
                    leadingIcon = TandemIcons.DoneAll,
                )
                TandemTextButton(
                    text = stringResource(R.string.inbox_preview_open),
                    onClick = { onAction(InboxAction.OpenInBrowser(entry.id)) },
                    leadingIcon = TandemIcons.OpenInNew,
                )
            }
        }
    }
}

private fun Modifier.readingWidth(): Modifier = this.then(Modifier.widthIn(max = 640.dp).fillMaxWidth())

/** "Exempelköket · 13 nov. 2026", or the feed alone for a post without a date. */
@Composable
private fun sourceLine(entry: InboxEntry): String {
    val published = entry.publishedAt ?: return entry.feedTitle
    return stringResource(R.string.inbox_preview_source, entry.feedTitle, date(published))
}

/** "13 nov. 2026" in the app's language and the phone's zone. */
@Composable
private fun date(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.systemDefault()))
}

/** "13 nov. 2026 08:00" in the app's language and the phone's zone. */
@Composable
private fun dateTime(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.systemDefault()))
}

private val THUMBNAIL = 72.dp
private const val IMAGE_RATIO = 16f / 9f
