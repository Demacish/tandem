package casa.moller.tandem.navigation

import android.content.Intent
import casa.moller.tandem.core.ui.navigation.AppOpenIntents
import casa.moller.tandem.feature.shell.navigation.ShellOpenRequest

/**
 * Fas 3 (widgets lane): reads core:ui's "open the app at …" intent ([AppOpenIntents]: the launcher
 * shortcuts in `res/xml/shortcuts.xml`, the widgets, the quick settings tile) into the shell's
 * [ShellOpenRequest]. An unknown destination, or one another app made up, just opens the app.
 */
internal object OpenLaunch {
    /** The request in [intent], or null when it is not an open intent or names nothing the shell knows. */
    fun requestFrom(intent: Intent?): ShellOpenRequest? = ShellOpenRequest.of(AppOpenIntents.destinationOf(intent))
}
