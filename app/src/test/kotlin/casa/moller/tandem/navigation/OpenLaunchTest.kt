package casa.moller.tandem.navigation

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.ui.navigation.AppOpenIntents
import casa.moller.tandem.feature.shell.navigation.ShellOpenRequest
import casa.moller.tandem.feature.shell.navigation.ShellTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** core:ui's "open the app at …" intent (shortcuts, widgets, the tile) as MainActivity reads it (Fas 3, widgets lane). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class OpenLaunchTest {
    private fun open(destination: String) = Intent(AppOpenIntents.ACTION).putExtra(AppOpenIntents.EXTRA_DESTINATION, destination)

    @Test
    fun everyDestinationOpensItsRequest() {
        assertEquals(ShellOpenRequest.Tab(ShellTab.SHOPPING), OpenLaunch.requestFrom(open(AppOpenIntents.SHOPPING)))
        assertEquals(ShellOpenRequest.Tab(ShellTab.WEEK), OpenLaunch.requestFrom(open(AppOpenIntents.WEEK)))
        assertEquals(ShellOpenRequest.Tab(ShellTab.RECIPES), OpenLaunch.requestFrom(open(AppOpenIntents.RECIPES)))
        assertEquals(ShellOpenRequest.Import, OpenLaunch.requestFrom(open(AppOpenIntents.IMPORT)))
        assertEquals(ShellOpenRequest.BarcodeScan, OpenLaunch.requestFrom(open(AppOpenIntents.SCAN)))
        assertEquals(ShellOpenRequest.Recipe(42), OpenLaunch.requestFrom(open(AppOpenIntents.recipe(42))))
        // The data alone (a PendingIntent whose extras were dropped) is enough.
        val dataOnly = Intent(AppOpenIntents.ACTION).setData("tandem-open://week".toUri())
        assertEquals(ShellOpenRequest.Tab(ShellTab.WEEK), OpenLaunch.requestFrom(dataOnly))
    }

    @Test
    fun unknownDestinationsAndOtherIntentsJustOpenTheApp() {
        assertNull(OpenLaunch.requestFrom(null))
        assertNull(OpenLaunch.requestFrom(Intent(Intent.ACTION_MAIN)))
        assertNull(OpenLaunch.requestFrom(open("settings")))
        assertNull(OpenLaunch.requestFrom(open("recipe:abc")))
        assertNull(OpenLaunch.requestFrom(open("recipe:0")))
        assertNull(OpenLaunch.requestFrom(Intent(AppOpenIntents.ACTION)))
    }

    @Test
    fun aRequestRoundTripsThroughItsName() {
        val requests = listOf(
            ShellOpenRequest.Tab(ShellTab.SHOPPING),
            ShellOpenRequest.Tab(ShellTab.MORE),
            ShellOpenRequest.Recipe(-3),
            ShellOpenRequest.Import,
            ShellOpenRequest.BarcodeScan,
            ShellOpenRequest.UseUp,
        )
        for (request in requests) assertEquals(request, ShellOpenRequest.of(ShellOpenRequest.nameOf(request)))
    }
}
