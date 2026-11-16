package casa.moller.tandem.core.sync.engine

import casa.moller.tandem.core.model.error.TandemError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tandoor 2.7.3's refusal of a nested create, told apart from validation errors by shape only. */
class NestedCreateRefusalTest {
    private val entry = """{"food": {"name": "godis"}, "unit": null, "amount": 1.0}"""

    @Test
    fun plainMessagesOnAFieldSentAsAnObjectAreARefusal() {
        // As ErrorClassifier reads `{"food": ["You do not have the required permissions to create food."]}`.
        val error = TandemError.BadRequest(mapOf("food" to listOf("Du har inte behörighet att skapa livsmedel.")))
        assertTrue(NestedCreateRefusal.isRefusal(entry, error))
    }

    @Test
    fun aFieldErrorInsideTheNestedObjectIsNot() {
        // `{"food": {"name": ["This field may not be blank."]}}`: ErrorClassifier keeps the object as JSON text.
        val error = TandemError.BadRequest(mapOf("food" to listOf("""{"name":["This field may not be blank."]}""")))
        assertFalse(NestedCreateRefusal.isRefusal(entry, error))
    }

    @Test
    fun anErrorOnAPlainFieldIsNot() {
        val error = TandemError.BadRequest(mapOf("amount" to listOf("A valid number is required.")))
        assertFalse(NestedCreateRefusal.isRefusal(entry, error))
        val noFood = TandemError.BadRequest(mapOf("food" to listOf("This field may not be null.")))
        assertFalse(NestedCreateRefusal.isRefusal("""{"food": null, "amount": 1.0}""", noFood))
    }

    @Test
    fun otherErrorsAndUnreadablePayloadsAreNot() {
        assertFalse(NestedCreateRefusal.isRefusal(entry, TandemError.NotFound))
        assertFalse(NestedCreateRefusal.isRefusal(entry, TandemError.BadRequest(mapOf("food" to emptyList()))))
        assertFalse(NestedCreateRefusal.isRefusal("not json", TandemError.BadRequest(mapOf("food" to listOf("refused")))))
        assertFalse(NestedCreateRefusal.isRefusal("[1, 2]", TandemError.BadRequest(mapOf("food" to listOf("refused")))))
    }
}
