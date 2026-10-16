package casa.moller.tandem.core.ui.diagnostics

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.Composable

/**
 * Tells the activity that the first screen with content is on screen as soon as [ready] is true
 * (`Activity.reportFullyDrawn` through androidx.activity's `FullyDrawnReporter`). The app measures
 * the cold start up to this moment for Settings → Diagnostics (Phase 1 "Klart när": under 1.5 s)
 * and Android's own startup metrics use it too. Call it on the start screens with content (Today,
 * the recipe list), with "the stored data has been read" as [ready]. Does nothing without an
 * activity (previews) and after the first report.
 */
@Composable
fun ReportContentReady(ready: Boolean) {
    ReportDrawnWhen { ready }
}
