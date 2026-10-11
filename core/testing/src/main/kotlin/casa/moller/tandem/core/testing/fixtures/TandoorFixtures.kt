package casa.moller.tandem.core.testing.fixtures

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the sanitized recordings of a real Tandoor (`api-schema/fixtures/<version>/`, made by
 * `Phase0ContractTest` and, in the `fas1/` subfolder, `Phase1ContractTest` in contract.yml; see
 * FACTS.md there). core:testing puts that folder on the test classpath, so any module's unit test
 * can use the same files as the contract tests.
 *
 * ```
 * val user = TandoorFixtures.exchange("11-user-by-id")
 * val recipes = TandoorFixtures.exchange("fas1/recipe-list-default")
 * server.enqueue(MockResponse(code = user.status, body = user.body!!))
 * ```
 */
object TandoorFixtures {
    /** The Tandoor version the app is tested against (the committed schema's version). */
    const val BASELINE: String = "2.6.13"

    /** The raw text of [file] (e.g. `FACTS.md`, `observed-facts.json`, `11-user-by-id.json`). */
    fun text(file: String, version: String = BASELINE): String {
        val path = "$version/$file"
        val stream = TandoorFixtures::class.java.classLoader?.getResourceAsStream(path)
            ?: error("Fixture $path not found: is api-schema/fixtures/$path committed?")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** One recorded request/response, by file name with or without `.json` (e.g. `11-user-by-id`). */
    fun exchange(name: String, version: String = BASELINE): RecordedExchange {
        val file = if (name.endsWith(".json")) name else "$name.json"
        val root = Json.parseToJsonElement(text(file, version)).jsonObject
        val request = root.getValue("request").jsonObject
        val response = root.getValue("response").jsonObject
        return RecordedExchange(
            name = root["name"]?.jsonPrimitive?.content ?: file.removeSuffix(".json"),
            method = request.getValue("method").jsonPrimitive.content,
            path = request.getValue("path").jsonPrimitive.content,
            status = response.getValue("status").jsonPrimitive.int,
            body = response.bodyText(),
        )
    }

    private fun JsonObject.bodyText(): String? = when {
        this["body"] != null -> this["body"].toString()
        this["bodyText"] != null -> this["bodyText"]?.jsonPrimitive?.content
        else -> null
    }
}

/**
 * A recorded call.
 *
 * @property name the recording's name, e.g. `user-by-id`.
 * @property method HTTP method of the request.
 * @property path request path (with query), e.g. `/api/user/1/`.
 * @property status response status code.
 * @property body the response body exactly as recorded: JSON text for JSON responses, the plain
 *   text for others, null when there was none.
 */
data class RecordedExchange(val name: String, val method: String, val path: String, val status: Int, val body: String?)
