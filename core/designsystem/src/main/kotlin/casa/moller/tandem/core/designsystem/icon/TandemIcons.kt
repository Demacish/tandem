package casa.moller.tandem.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import casa.moller.tandem.core.designsystem.R

/**
 * Material Symbols Rounded (weight 400, grade 0, 24 px) used by Tandem. The vector drawables in
 * res/drawable/tandem_ic_*.xml are converted 1:1 from the official SVGs in
 * google/material-design-icons (Apache-2.0, see assets/licenses/NOTICE.txt).
 *
 * Only the icons the app uses are bundled (no material-icons-extended). To add one: download
 * symbols/web/<name>/materialsymbolsrounded/<name>_24px.svg at a pinned commit, convert it the
 * same way and add a property here. Directional icons set android:autoMirrored for RTL.
 */
object TandemIcons {
    /** Navigate back (auto-mirrored for RTL). */
    val ArrowBack: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_arrow_back)

    /** Forward / continue (auto-mirrored). */
    val ArrowForward: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_arrow_forward)

    /** Week / meal plan tab ("Veckan"). */
    val CalendarMonth: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_calendar_month)

    /** Week / meal plan tab ("Veckan"). Filled variant (selected navigation item, status). */
    val CalendarMonthFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_calendar_month_filled)

    /** Done / success. */
    val CheckCircle: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_check_circle)

    /** Done / success. Filled variant (selected navigation item, status). */
    val CheckCircleFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_check_circle_filled)

    /** Close / dismiss. */
    val Close: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_close)

    /** Offline (server unreachable). */
    val CloudOff: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_cloud_off)

    /** Paste. */
    val ContentPaste: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_content_paste)

    /** Theme / dark mode. */
    val DarkMode: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_dark_mode)

    /** Server address. */
    val Dns: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_dns)

    /** Error. */
    val Error: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_error)

    /** Error. Filled variant (selected navigation item, status). */
    val ErrorFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_error_filled)

    /** Home. */
    val Home: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_home)

    /** Home. Filled variant (selected navigation item, status). */
    val HomeFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_home_filled)

    /** Information. */
    val Info: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_info)

    /** Information. Filled variant (selected navigation item, status). */
    val InfoFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_info_filled)

    /** API token. */
    val Key: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_key)

    /** Language. */
    val Language: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_language)

    /** Secure / locked. */
    val Lock: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_lock)

    /** Log out. */
    val Logout: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_logout)

    /** Recipes tab ("Recept"). */
    val MenuBook: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_menu_book)

    /** Recipes tab ("Recept"). Filled variant (selected navigation item, status). */
    val MenuBookFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_menu_book_filled)

    /** More tab ("Mer"). */
    val MoreHoriz: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_more_horiz)

    /** Opens an external link (auto-mirrored). */
    val OpenInNew: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_open_in_new)

    /** Retry / refresh. */
    val Refresh: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_refresh)

    /** Time / timers. */
    val Schedule: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_schedule)

    /** Demo mode. */
    val Science: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_science)

    /** Settings. */
    val Settings: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_settings)

    /** Safe mode. */
    val Shield: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_shield)

    /** Shopping tab ("Handla"). */
    val ShoppingCart: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_shopping_cart)

    /** Shopping tab ("Handla"). Filled variant (selected navigation item, status). */
    val ShoppingCartFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_shopping_cart_filled)

    /** Today tab ("Idag"). */
    val Today: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_today)

    /** Today tab ("Idag"). Filled variant (selected navigation item, status). */
    val TodayFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_today_filled)

    /** Show password. */
    val Visibility: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_visibility)

    /** Hide password. */
    val VisibilityOff: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_visibility_off)

    /** Warning / safe mode. */
    val Warning: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_warning)

    /** Warning / safe mode. Filled variant (selected navigation item, status). */
    val WarningFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_warning_filled)

    /** No network. */
    val WifiOff: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_wifi_off)
}
