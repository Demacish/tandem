package casa.moller.tandem.core.sync.demo

import android.content.Context
import casa.moller.tandem.core.data.demo.DemoContent
import casa.moller.tandem.core.data.demo.DemoDataRepository
import casa.moller.tandem.core.data.demo.DemoLanguage
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.database.mapper.toDetailWrite
import casa.moller.tandem.core.database.mapper.toEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.sync.SyncSchedule
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.DEMO_ACCOUNT_ID
import casa.moller.tandem.core.sync.book.DemoBooks
import casa.moller.tandem.core.sync.engine.cookStatsRules
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.ImageResolver
import casa.moller.tandem.core.sync.remote.tandoorMillis
import casa.moller.tandem.core.sync.remote.toCookStats
import casa.moller.tandem.core.sync.remote.toEntity
import casa.moller.tandem.core.sync.remote.toIndexEntry
import casa.moller.tandem.core.sync.remote.toModel
import casa.moller.tandem.core.sync.remote.toOverviewWrite
import casa.moller.tandem.core.sync.remote.toRecipe
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Fills the separate demo database (plan 6.2 "Demoläge") from the bundled [DemoContent], through
 * the same mappers and DAO calls as a real sync, so every screen works on it without a server.
 *
 * - **Idempotent:** [ensureSeeded] writes the content when the demo database is empty, when the
 *   content version or the language changed, or on a new day (dates in the demo data move with
 *   today). Rewriting keeps what the user did in demo mode (pinned recipes, chosen servings,
 *   recently viewed), because the DAO keeps those columns.
 * - **Reset** ("Återställ demodata", [resetDemoData]): empties the demo database and seeds it again.
 * - **Leaving demo mode for a real account** ([clearIfSeeded]): the demo database is emptied, so
 *   demo data never mixes with a real account (it lives in its own file anyway).
 */
@Singleton
internal class DemoSeeder(
    private val databases: TandemDatabaseFactory,
    private val content: DemoContent,
    private val clock: SyncClock,
    private val hasDemoFile: () -> Boolean,
    private val locale: () -> Locale,
) : DemoDataRepository {

    @Inject
    constructor(
        databases: TandemDatabaseFactory,
        content: DemoContent,
        clock: SyncClock,
        @ApplicationContext context: Context,
    ) : this(
        databases = databases,
        content = content,
        clock = clock,
        hasDemoFile = { context.getDatabasePath(TandemDatabase.DEMO_FILE_NAME).exists() },
        locale = { Locale.getDefault() },
    )

    private val mutex = Mutex()

    /**
     * Seeds the demo database if it is empty or outdated (see the class comment).
     *
     * @param currentLocale the app language; the default reads it now. `SyncStarter` passes the
     *   new one when the language changes, before the default locale may have caught up.
     */
    suspend fun ensureSeeded(currentLocale: Locale = locale()): Outcome<Unit> = mutex.withLock {
        catchingUnexpected {
            val database = databases.get(DatabaseKind.DEMO)
            val today = clock.today()
            val key = seedKey(currentLocale)
            val state = database.syncStateDao().get(DEMO_ACCOUNT_ID, SEED_KEY)
            if (state == null || state.hash != key || state.lastDate != today.toString()) {
                write(database, currentLocale, today, initial = state == null)
                database.syncStateDao().upsert(
                    SyncStateEntity(
                        accountId = DEMO_ACCOUNT_ID,
                        entity = SEED_KEY,
                        lastDate = today.toString(),
                        lastFullRelistEpochMillis = clock.nowMillis(),
                        hash = key,
                    ),
                )
            }
            // Phase 2 (inköp-data lane): the shopping list, seeded once (it keeps the user's changes).
            DemoShopping.ensureSeeded(database, content, currentLocale, today, clock.nowMillis())
            // Phase 2 (böcker lane): the books, favourites and "Vill laga", seeded once as well.
            DemoBooks.ensureSeeded(database, content, currentLocale, today, clock.nowMillis())
            Outcome.Success(Unit)
        }
    }

    override suspend fun resetDemoData(): Outcome<Unit> {
        val cleared = mutex.withLock { catchingUnexpected { Outcome.Success(databases.clear(DatabaseKind.DEMO)) } }
        return if (cleared is Outcome.Failure) cleared else ensureSeeded()
    }

    /** A real account logged in: the demo data is removed (plan 6.2). Never throws. */
    suspend fun clearIfSeeded(): Outcome<Unit> {
        if (!hasDemoFile()) return Outcome.Success(Unit)
        return mutex.withLock {
            catchingUnexpected {
                val database = databases.get(DatabaseKind.DEMO)
                if (database.syncStateDao().get(DEMO_ACCOUNT_ID, SEED_KEY) != null) databases.clear(DatabaseKind.DEMO)
                Outcome.Success(Unit)
            }
        }
    }

    private fun seedKey(locale: Locale): String = "v" + content.version + "|" + DemoLanguage.of(locale).tag

    private suspend fun write(database: TandemDatabase, locale: Locale, today: LocalDate, initial: Boolean) {
        val accountId = DEMO_ACCOUNT_ID
        val images = ImageResolver.AsIs
        val now = clock.nowMillis()
        val recipes = database.recipeDao()

        // Index first (removes recipes a newer content version dropped), then the list rows
        // (`new` flag, keyword labels), then every detail with its raw JSON.
        recipes.applyIndex(accountId, content.recipeFlat(locale).mapNotNull { it.toIndexEntry(images) })
        val overviews = content.recipeOverviews(locale, today)
        recipes.upsertOverviews(accountId, overviews.mapNotNull { it.toOverviewWrite(accountId, images) })
        for (id in content.recipeIds) {
            val json = content.recipeJson(id, locale, today) ?: continue
            val recipe = content.recipe(id, locale, today)?.toRecipe(images) ?: continue
            recipes.replaceDetail(accountId, recipe.toDetailWrite(accountId, rawJson = json.toString(), fetchedAtEpochMillis = now))
        }
        recipes.replaceKeywords(accountId, content.keywords(locale, today).mapNotNull { it.toEntity(accountId) })

        // The content's cook log is stored like a server list (one transaction); entries logged in
        // demo mode stay and keep counting. The demo details carry their ratings, so only recipes
        // rated in demo mode get the log's rating (ActiveSource.Demo.cookStatsRules).
        val cookLog = content.cookLog(today)
        database.cookLogDao().reconcile(
            accountId = accountId,
            serverEntries = cookLog.mapNotNull { it.toEntity(accountId) },
            listRequestedAtEpochMillis = now,
            nowEpochMillis = now,
            rules = ActiveSource.Demo.cookStatsRules,
        )
        val stats = cookLog.toCookStats()

        val reference = database.referenceDao()
        reference.replaceMealTypes(accountId, content.mealTypes(locale).mapNotNull { it.toEntity(accountId) })
        database.mealPlanDao().replaceWindow(
            accountId,
            today.minus(SyncSchedule.MEAL_PLAN_DAYS_BEFORE, DateTimeUnit.DAY).toString(),
            today.plus(SyncSchedule.MEAL_PLAN_DAYS_AFTER, DateTimeUnit.DAY).toString(),
            content.mealPlan(locale, today).mapNotNull { it.toEntity(accountId) },
        )
        reference.upsertUserPreference(content.userPreferences(locale).toModel().toEntity(accountId, now))

        // Today's carousels, computed like the server's queries (plan 3.3).
        val sections = database.homeSectionDao()
        val oftenCooked = stats.filter { it.timesCooked > 0 }.sortedByDescending { it.timesCooked }.map { it.recipeId }
        val notCookedSince = now - SyncSchedule.NOT_COOKED_LATELY_DAYS.days.inWholeMilliseconds
        val notCooked = stats.filter { (it.lastCookedEpochMillis ?: Long.MAX_VALUE) <= notCookedSince }
            .sortedBy { it.lastCookedEpochMillis }
            .map { it.recipeId }
        val viewedSince = now - SyncSchedule.RECENTLY_VIEWED_DAYS.days.inWholeMilliseconds
        val views = content.viewLog(today)
            .mapNotNull { log -> log.recipe?.let { recipe -> tandoorMillis(log.createdAt)?.let { recipe to it } } }
            .sortedByDescending { it.second }
        val viewed = views.filter { it.second >= viewedSince }.map { it.first }.distinct()
        val newRecipes = overviews.filter { it.isNew }.mapNotNull { it.id }
        val size = SyncSchedule.HOME_SECTION_SIZE
        sections.replaceSection(accountId, HomeSectionType.OFTEN_COOKED, oftenCooked.take(size), now)
        sections.replaceSection(accountId, HomeSectionType.NEW, newRecipes.take(size), now)
        sections.replaceSection(accountId, HomeSectionType.RECENTLY_VIEWED, viewed.take(size), now)
        sections.replaceSection(accountId, HomeSectionType.NOT_COOKED_LATELY, notCooked.take(size), now)

        if (initial) {
            content.pinnedRecipeIds.forEach { recipes.setPinned(accountId, it, true) }
            views.reversed().forEach { (recipe, at) -> recipes.markViewed(accountId, recipe, at) }
        }
    }

    internal companion object {
        /** `sync_state` row of the demo database that records the seeded content. */
        const val SEED_KEY: String = "demo_seed"
    }
}
