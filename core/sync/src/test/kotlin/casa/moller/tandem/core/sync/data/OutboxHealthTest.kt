package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.model.diagnostics.OutboxKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fas S (integration): Mer → Diagnostik names the pantry, aliases, drinks and pairings, not "Other". */
class OutboxHealthTest {
    private fun row(op: String, entity: String, payload: String = "{}") =
        OutboxEntity(accountId = "a", op = op, entity = entity, payload = payload, createdAtEpochMillis = 1)

    @Test
    fun phaseSRowsHaveTheirOwnKinds() {
        assertEquals(OutboxKind.PANTRY, OutboxHealth.kindOf(row(OutboxOps.CREATE, OutboxEntities.INVENTORY_LOCATION)))
        assertEquals(OutboxKind.PANTRY, OutboxHealth.kindOf(row(OutboxOps.PATCH, OutboxEntities.INVENTORY_ENTRY)))
        assertEquals(OutboxKind.LEARNED_ALIAS, OutboxHealth.kindOf(row(OutboxOps.CREATE, OutboxEntities.FOOD_ALIAS)))
        assertEquals(OutboxKind.DRINK, OutboxHealth.kindOf(row(OutboxOps.CREATE, OutboxEntities.DRINK)))
        assertEquals(OutboxKind.DRINK, OutboxHealth.kindOf(row(OutboxOps.RECIPE_IMAGE, OutboxEntities.DRINK)))
        assertEquals(OutboxKind.FOOD, OutboxHealth.kindOf(row(OutboxOps.BATCH_ON_HAND, OutboxEntities.FOOD)))
    }

    @Test
    fun recipeKeywordRowsArePairingsOnlyWithoutWantToCook() {
        val pairing = row(OutboxOps.RECIPE_KEYWORDS, OutboxEntities.RECIPE, """{"keyword:12":true,"pairing:Kraftigt rött":true}""")
        val wantToCook = row(OutboxOps.RECIPE_KEYWORDS, OutboxEntities.RECIPE, """{"keywords":{"vill-laga":true}}""")
        val both = row(OutboxOps.RECIPE_KEYWORDS, OutboxEntities.RECIPE, """{"keywords":{"vill-laga":true},"keyword:12":true}""")

        assertEquals(OutboxKind.PAIRING, OutboxHealth.kindOf(pairing))
        assertEquals(OutboxKind.WANT_TO_COOK, OutboxHealth.kindOf(wantToCook))
        assertEquals(OutboxKind.WANT_TO_COOK, OutboxHealth.kindOf(both))
        assertEquals(OutboxKind.WANT_TO_COOK, OutboxHealth.kindOf(row(OutboxOps.RECIPE_KEYWORDS, OutboxEntities.RECIPE)))
    }
}
