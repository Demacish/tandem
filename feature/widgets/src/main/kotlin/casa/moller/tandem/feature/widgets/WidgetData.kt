package casa.moller.tandem.feature.widgets

import android.content.Context
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.home.HomeRepository
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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

    @ApplicationScope
    fun applicationScope(): CoroutineScope

    fun updater(): WidgetUpdater
}

/** The widgets' data as flows (the widgets read one value, [WidgetUpdater] follows them). */
internal class WidgetData(val entryPoint: WidgetEntryPoint) {
    constructor(context: Context) : this(EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java))

    private val signedIn: Flow<Boolean> = entryPoint.sessions().session
        .map { it is Session.LoggedIn || it is Session.Demo }
        .distinctUntilChanged()

    val shopping: Flow<ShoppingWidgetState> = combine(entryPoint.shopping().observeEntries(), signedIn) { entries, signedIn ->
        ShoppingWidgetState.of(entries, Clock.System.now(), signedIn)
    }.catch { emit(ShoppingWidgetState()) }.distinctUntilChanged()

    val tonight: Flow<TonightWidgetState> = combine(entryPoint.home().observeUpcomingMeals(), signedIn) { upcoming, signedIn ->
        TonightWidgetState.of(upcoming, signedIn)
    }.catch { emit(TonightWidgetState()) }.distinctUntilChanged()
}
