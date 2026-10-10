package casa.moller.tandem.core.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons

/**
 * "Tandem kraschade förra gången" (plan 4.2 p7, "Exportera fellogg"): a card shown on the
 * welcome step and in Mer while a crash log from an earlier run is stored on the device. It
 * never blocks anything. Nothing is sent automatically: "Visa" opens the log, "Kopiera" puts it
 * on the clipboard and "Dela" opens the system share sheet, each only when the user taps it.
 * "Ta bort" deletes the logs.
 *
 * @param report every stored crash log as one text (already redacted when it was written).
 */
@Composable
fun CrashReportNotice(report: String, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showLog by rememberSaveable { mutableStateOf(false) }
    val copy = { copyCrashReport(context, report) }
    val share = { shareCrashReport(context, report) }
    CrashReportCard(
        onShow = { showLog = true },
        onCopy = copy,
        onShare = share,
        onDelete = onDelete,
        modifier = modifier,
    )
    if (showLog) {
        CrashReportDialog(
            report = report,
            onCopy = copy,
            onShare = share,
            onDismiss = { showLog = false },
        )
    }
}

/** Stateless card of [CrashReportNotice], for screenshots. */
@Composable
fun CrashReportCard(onShow: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    CalloutCard(
        title = stringResource(R.string.ui_crash_title),
        body = stringResource(R.string.ui_crash_body),
        modifier = modifier,
        icon = TandemIcons.Warning,
        tone = TandemTone.Warning,
        actions = {
            TandemPrimaryButton(text = stringResource(R.string.ui_crash_show), onClick = onShow, size = TandemButtonSize.Small)
            TandemTextButton(text = stringResource(R.string.ui_crash_copy), onClick = onCopy)
            TandemTextButton(text = stringResource(R.string.ui_crash_share), onClick = onShare)
            TandemTextButton(text = stringResource(R.string.ui_crash_delete), onClick = onDelete)
        },
    )
}

/** The whole log, selectable, with "Kopiera" and "Dela". */
@Composable
private fun CrashReportDialog(report: String, onCopy: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_crash_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.ui_crash_dialog_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = report,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_crash_close)) }
        },
        dismissButton = {
            TextButton(onClick = onCopy) { Text(stringResource(R.string.ui_crash_copy)) }
            TextButton(onClick = onShare) { Text(stringResource(R.string.ui_crash_share)) }
        },
    )
}

private fun copyCrashReport(context: Context, report: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    // Android 13+ confirms a copy itself, so there is no toast of our own.
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.ui_crash_dialog_title), report)) }
}

/** ACTION_SEND text/plain through the system share sheet; the user picks where it goes. */
private fun shareCrashReport(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.ui_crash_share_subject))
        .putExtra(Intent.EXTRA_TEXT, report)
    val chooser = Intent.createChooser(send, context.getString(R.string.ui_crash_share_chooser))
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        // No app can receive text; "Kopiera" still works.
    }
}
