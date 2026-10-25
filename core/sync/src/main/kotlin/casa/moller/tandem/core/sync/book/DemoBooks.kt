package casa.moller.tandem.core.sync.book

import casa.moller.tandem.core.data.demo.DemoContent
import casa.moller.tandem.core.data.demo.DemoLanguage
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.RecipeBookEntity
import casa.moller.tandem.core.database.entity.RecipeBookEntryEntity
import casa.moller.tandem.core.database.entity.RecipeKeywordCrossRef
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.domain.book.RecipeBookRules
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.model.book.WantToCook
import casa.moller.tandem.core.sync.account.DEMO_ACCOUNT_ID
import java.util.Locale
import kotlinx.datetime.LocalDate

/**
 * Phase 2 (böcker lane): the demo's books (plan 6.2 "Demoläge": "två böcker (Favoriter, Vill
 * laga)"), from the bundled content and shaped like a real household's:
 *
 * - the demo user's own "Favoriter" (editable),
 * - "Vill laga" as the shared smart book on the [WantToCook.KEYWORD] keyword: the content's
 *   want-to-cook recipes get the keyword, as on a real server,
 * - a second member's ("Alex", the member of the demo shopping list) favourites book, shared
 *   read-only with the demo user, so the merged Favourites view and the read-only explanation can
 *   be tried.
 *
 * Seeded once per content version and language ([SEED_KEY]), so what the user adds and removes
 * stays. The recipes' daily refresh rewrites keywords, so the keyword and its links are put back
 * from the content when they are missing ("Vill laga" changes made in demo mode last until then).
 * Never the network.
 */
internal object DemoBooks {
    const val SEED_KEY: String = "demo_books_seed"

    /** The demo user (`created_by` of the content's books). */
    const val OWN_USER_ID: Long = 1

    /** The second household member of the demo (as in the demo shopping list). */
    const val OTHER_USER_ID: Long = 2
    private const val OTHER_USER_NAME = "Alex"

    /** Local ids that do not collide with the content's (books 1-2, keywords 1-20). */
    private const val WANT_TO_COOK_KEYWORD_ID = 1_000L
    private const val WANT_TO_COOK_FILTER_ID = 1L
    private const val OTHER_FAVORITES_BOOK_ID = 3L
    private const val OTHER_ENTRY_ID_START = 1_000L

    /** Recipes in Alex's favourites (two of them also in the demo user's). */
    private val OTHER_FAVORITE_RECIPES = listOf(2L, 4L, 6L)

    suspend fun ensureSeeded(database: TandemDatabase, content: DemoContent, locale: Locale, today: LocalDate, now: Long) {
        val accountId = DEMO_ACCOUNT_ID
        val dao = database.recipeBookDao()
        val books = content.recipeBooks(locale)
        val entries = content.recipeBookEntries(locale, today)
        // The content's "Vill laga" / "Want to cook" (a plain book there) is recognised by its name.
        val wantBook = books.firstOrNull { book ->
            val kind = RecipeBookRules.kind(book.name, book.description, hasFilter = true, filterSearch = null, emptySet())
            kind == RecipeBookKind.WANT_TO_COOK
        }
        val wantRecipes = entries.filter { it.book == wantBook?.id }.mapNotNull { it.recipe }
        dao.inTransaction {
            ensureKeyword(database, wantRecipes)
            val states = database.syncStateDao()
            val key = "v" + content.version + "|" + DemoLanguage.of(locale).tag
            if (states.get(accountId, SEED_KEY)?.hash == key) return@inTransaction
            dao.deleteAllEntries(accountId)
            dao.deleteAllBooks(accountId)
            val wantIds = setOf(WANT_TO_COOK_KEYWORD_ID)
            val rows = books.mapNotNull { dto ->
                val row = dto.toEntity(accountId, wantIds) ?: return@mapNotNull null
                if (dto.id == wantBook?.id) {
                    // The content's plain "Vill laga" becomes the smart book on the keyword.
                    row.copy(
                        filterId = WANT_TO_COOK_FILTER_ID,
                        filterSearch = WantToCook.filterSearch(WANT_TO_COOK_KEYWORD_ID),
                        kind = RecipeBookKind.WANT_TO_COOK.toStored(),
                    )
                } else {
                    row
                }
            }
            val favorites = rows.firstOrNull { storedKind(it.kind) == RecipeBookKind.FAVORITES }
            val other = favorites?.let {
                RecipeBookEntity(
                    accountId = accountId,
                    localId = OTHER_FAVORITES_BOOK_ID,
                    serverId = OTHER_FAVORITES_BOOK_ID,
                    name = it.name.substringBefore(" (").trim() + " ($OTHER_USER_NAME)",
                    createdBy = OTHER_USER_ID,
                    createdByName = OTHER_USER_NAME,
                    sharedUserIds = BookIds.encode(listOf(OWN_USER_ID)),
                    bookOrder = it.bookOrder + 1,
                    kind = RecipeBookKind.FAVORITES.toStored(),
                )
            }
            dao.upsertBooks(rows + listOfNotNull(other))
            val ownEntries = entries.filter { it.book != wantBook?.id }.mapNotNull { it.toEntity(accountId) }
            val otherEntries = if (other == null) {
                emptyList()
            } else {
                OTHER_FAVORITE_RECIPES.mapIndexed { index, recipe ->
                    RecipeBookEntryEntity(accountId, OTHER_ENTRY_ID_START + index, OTHER_ENTRY_ID_START + index, other.localId, recipe)
                }
            }
            dao.upsertEntries(ownEntries + otherEntries)
            states.upsert(SyncStateEntity(accountId = accountId, entity = SEED_KEY, hash = key, lastFullRelistEpochMillis = now))
        }
    }

    /** The want-to-cook keyword and the content's links, when the recipes' refresh removed them. */
    private suspend fun ensureKeyword(database: TandemDatabase, recipes: List<Long>) {
        val dao = database.recipeBookDao()
        if (dao.getKeywordIdsNamed(DEMO_ACCOUNT_ID, WantToCook.KEYWORD).isNotEmpty()) return
        dao.insertKeywordIfAbsent(KeywordEntity(DEMO_ACCOUNT_ID, WANT_TO_COOK_KEYWORD_ID, WantToCook.KEYWORD))
        recipes.forEach { dao.insertKeywordRef(RecipeKeywordCrossRef(DEMO_ACCOUNT_ID, it, WANT_TO_COOK_KEYWORD_ID)) }
    }
}
