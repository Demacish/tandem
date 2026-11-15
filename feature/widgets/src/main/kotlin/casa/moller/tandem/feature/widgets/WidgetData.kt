package casa.moller.tandem.feature.widgets

import android.content.Context
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.home.HomeRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.preferences.PreferencesRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.security.AppLock
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.domain.security.AppLockPolicy
import casa.moller.tandem.core.domain.security.AppSurface
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.RecipeSort
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What the widgets and the tile read (Hilt entry point: widgets and services are created by the system). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun shopping(): ShoppingRepository

    fun home(): HomeRepository

    fun sessions(): SessionRepository

    fun permissions(): SpacePermissionsRepository

    fun preferences(): PreferencesRepository

    fun recipes(): RecipeRepository

    /** Fas 5 (las lane): Mer → Lås appen (a locked placeholder while it is on). */
    fun appLock(): AppLock

    @ApplicationScope
    fun applicationScope(): CoroutineScope

    fun updater(): WidgetUpdater
}

/**
 * The widgets' data as flows: a widget reads the first value and follows the rest inside its
 * composition, [WidgetUpdater] follows them to start a session when none runs.
 */
internal class WidgetData(val entryPoint: WidgetEntryPoint, private val locale: Locale) {
    constructor(context: Context) : this(
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java),
        context.resources.configuration.locales[0],
    )

    private val signedIn: Flow<Boolean> = entryPoint.sessions().session
        .map { it is Session.LoggedIn || it is Session.Demo }
        .distinctUntilChanged()

    /**
     * Fas 5 (las lane, plan §5.5): the lock is on, so the widgets, the tile and the launcher's
     * recent recipes show nothing of the user's (the home screen is seen without unlocking).
     */
    private val locked: Flow<Boolean> = entryPoint.appLock().settings
        .map { !AppLockPolicy.showsContent(AppSurface.WIDGET, it) }
        .distinctUntilChanged()

    val shopping: Flow<ShoppingWidgetState> = combine(
        entryPoint.shopping().observeEntries(),
        signedIn,
        entryPoint.permissions().permissions.map { it.readOnly }.distinctUntilChanged(),
        entryPoint.preferences().observeUserPreferences(),
        locked,
    ) { entries, signedIn, readOnly, preferences, locked ->
        ShoppingWidgetState.of(entries, Clock.System.now(), signedIn, readOnly, locale, preferences, locked)
    }.catch { emit(ShoppingWidgetState()) }.distinctUntilChanged()

    val tonight: Flow<TonightWidgetState> = combine(
        entryPoint.home().observeUpcomingMeals(),
        signedIn,
        locked,
    ) { upcoming, signedIn, locked ->
        TonightWidgetState.of(upcoming, signedIn, locked)
    }.catch { emit(TonightWidgetState()) }.distinctUntilChanged()

    /** The launcher's "senast lagade" (empty signed out, so the shortcuts go with the account; empty while the lock is on). */
    val recentRecipes: Flow<List<RecentRecipe>> = combine(
        entryPoint.recipes().observeRecipes(RecipeQuery(sort = RecipeSort.LAST_COOKED)),
        signedIn,
        locked,
    ) { recipes, signedIn, locked ->
        if (signedIn && !locked) RecentRecipe.of(recipes) else emptyList()
    }.catch { emit(emptyList()) }.distinctUntilChanged()
}
