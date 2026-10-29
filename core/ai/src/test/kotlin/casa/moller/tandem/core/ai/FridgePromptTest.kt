package casa.moller.tandem.core.ai

import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ONE result shape, the prompt and the lenient parsing (plan §8.8). */
class FridgePromptTest {
    @Test
    fun thePromptAsksForVisibleItemsInTheUiLanguageWithoutGuessing() {
        val prompt = FridgePrompt.system("sv-SE")
        assertTrue(prompt.contains("\"sv\""))
        assertTrue(prompt.contains("VISIBLE"))
        assertTrue(prompt.contains("Do not guess items that are hidden"))
        assertTrue(FridgePrompt.system("").contains("\"en\""))
    }

    @Test
    fun everySchemaAsksForTheSameFields() {
        val fields = listOf("name", "amount", "unit", "confidence", "note")
        for (schema in listOf(FridgePrompt.claudeSchema(), FridgePrompt.openAiSchema(), FridgePrompt.geminiSchema())) {
            val items = (schema["properties"] as Map<*, *>)["items"] as Map<*, *>
            val item = items["items"] as Map<*, *>
            assertEquals(fields.toSet(), (item["properties"] as Map<*, *>).keys)
            assertTrue((item["required"] as List<*>).containsAll(listOf("name", "confidence")))
        }
        // Strict dialects: every field required, no extra properties.
        for (schema in listOf(FridgePrompt.claudeSchema(), FridgePrompt.openAiSchema())) {
            val item = ((schema["properties"] as Map<*, *>)["items"] as Map<*, *>)["items"] as Map<*, *>
            assertEquals(fields, item["required"])
            assertEquals(false, item["additionalProperties"])
        }
    }

    @Test
    fun theStructuredObjectAndABareArrayAreRead() {
        val structured = """{"items":[{"name":"mjölk","amount":1,"unit":"l","confidence":0.9,"note":null},""" +
            """{"name":"ägg","amount":null,"unit":null,"confidence":0.4,"note":"delvis skymd"}]}"""
        val items = (FridgePrompt.parse(structured) as FridgeVisionResult.Items).items
        assertEquals(listOf("mjölk", "ägg"), items.map { it.name })
        assertEquals(1.0, items[0].amount ?: 0.0, 0.0)
        assertEquals(null, items[1].amount)
        assertEquals("delvis skymd", items[1].note)

        val fenced = "```json\n[{\"name\":\" ägg \",\"amount\":6,\"confidence\":1.7},{\"name\":\"\"},{\"amount\":2}]\n```"
        val bare = (FridgePrompt.parse(fenced) as FridgeVisionResult.Items).items
        assertEquals(listOf("ägg"), bare.map { it.name })
        assertEquals(1.0, bare.single().confidence ?: 0.0, 0.0)
        assertEquals(FridgeVisionResult.Items(emptyList()), FridgePrompt.parse("""{"items":[]}"""))
    }

    @Test
    fun proseIsUnparseable() {
        val result = FridgePrompt.parse("Jag ser mjölk.")
        assertTrue((result as FridgeVisionResult.Failed).error is FridgeVisionError.Unparseable)
    }

    @Test
    fun aCutOffAnswerKeepsTheCompleteItems() {
        val cut = """{"items":[{"name":"mjölk","confidence":0.9},{"name":"ost {gul}","note":"a \"quote\"","confidence":0.8},{"name":"smö"""
        val result = FridgePrompt.parse(cut, truncated = true) as FridgeVisionResult.Items
        assertEquals(listOf("mjölk", "ost {gul}"), result.items.map { it.name })
        assertTrue(result.truncated)
        assertTrue((FridgePrompt.parse(cut) as FridgeVisionResult.Failed).error is FridgeVisionError.Unparseable)
    }
}
