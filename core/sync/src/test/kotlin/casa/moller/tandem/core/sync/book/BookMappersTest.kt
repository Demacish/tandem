package casa.moller.tandem.core.sync.book

import casa.moller.tandem.core.database.entity.RecipeBookKinds
import casa.moller.tandem.core.network.dto.RecipeBookDto
import casa.moller.tandem.core.network.json.TandoorJson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A server book as a row, also in the shape of Tandoor 2.7.3, which sends the filter's id as
 * `filter_id` next to the `filter` object (api-schema/fixtures/2.7.3/FACTS.md).
 */
class BookMappersTest {
    @Test
    fun theFilterObjectGivesTheFilterAsBefore() {
        val dto = book("""{"id": 7, "name": "Snabbt", "filter": {"id": 3, "search": "{\"keywords_or\":[8]}"}}""")
        val row = dto.toEntity(ACCOUNT, emptySet())
        assertEquals(3L, row?.filterId)
        assertEquals(RecipeBookKinds.SMART, row?.kind)
    }

    @Test
    fun theObjectWinsOverFilterId() {
        val row = book("""{"id": 7, "name": "Snabbt", "filter": {"id": 3, "search": ""}, "filter_id": 3}""").toEntity(ACCOUNT, emptySet())
        assertEquals(3L, row?.filterId)
        assertEquals(RecipeBookKinds.SMART, row?.kind)
    }

    @Test
    fun filterIdAloneStillMakesASmartBook() {
        val row = book("""{"id": 7, "name": "Snabbt", "filter": null, "filter_id": 4}""").toEntity(ACCOUNT, emptySet())
        assertEquals(4L, row?.filterId)
        assertEquals(RecipeBookKinds.SMART, row?.kind)
    }

    @Test
    fun aPlainBookHasNoFilter() {
        val row = book("""{"id": 7, "name": "Vardag", "filter": null, "filter_id": null}""").toEntity(ACCOUNT, emptySet())
        assertEquals(null, row?.filterId)
        assertEquals(RecipeBookKinds.BOOK, row?.kind)
    }

    private fun book(json: String): RecipeBookDto = TandoorJson.decodeFromString(RecipeBookDto.serializer(), json)

    private companion object {
        const val ACCOUNT = "account"
    }
}
