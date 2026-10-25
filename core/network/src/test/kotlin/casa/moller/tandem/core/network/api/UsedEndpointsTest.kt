package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.testing.ApiSchema
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * `api-schema/used-endpoints.txt` is the allow-list for the schema diff in `tandoor-watch.yml`
 * (plan 3.6). It only protects the app if it is complete: every Retrofit call of [TandoorApi]
 * must be listed, and every listed endpoint must exist in the committed OpenAPI schema.
 */
class UsedEndpointsTest {
    private val schemaDir = ApiSchema.dir()

    private val listed: Set<String> = File(schemaDir, "used-endpoints.txt").readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toSet()

    private val called: Set<String> = TandoorApi::class.java.declaredConstructors
        .flatMap { it.parameterTypes.toList() }
        .filter { it.isInterface }
        .flatMap { service -> service.declaredMethods.mapNotNull { endpointOf(it) } }
        .toSet()

    private fun endpointOf(method: java.lang.reflect.Method): String? {
        val (verb, path) = method.annotations.firstNotNullOfOrNull { annotation ->
            when (annotation) {
                is GET -> "GET" to annotation.value
                is POST -> "POST" to annotation.value
                is PUT -> "PUT" to annotation.value
                is PATCH -> "PATCH" to annotation.value
                is DELETE -> "DELETE" to annotation.value
                else -> null
            }
        } ?: return null
        return "$verb /${path.substringBefore('?')}"
    }

    @Test
    fun everyCallIsListed() {
        assertTrue("No Retrofit calls found", called.size >= 20)
        val missing = called - listed
        assertTrue("Add these to api-schema/used-endpoints.txt: $missing", missing.isEmpty())
    }

    @Test
    fun everyListedEndpointExistsInTheSchema() {
        val operations = schemaOperations(File(schemaDir, "tandoor-2.6.13.yaml"))
        // allauth headless (Tandoor 2.7+) is not part of Tandoor's schema; the contract suite checks it.
        val unknown = listed.filterNot { it.substringAfter(' ').startsWith(ALLAUTH_PREFIX) }.toSet() - operations
        assertTrue("Not in tandoor-2.6.13.yaml: $unknown", unknown.isEmpty())
    }

    /** `METHOD /path` for every operation under `paths:` (the schema is indented with 2 spaces). */
    private fun schemaOperations(yaml: File): Set<String> {
        val operations = mutableSetOf<String>()
        var path: String? = null
        for (line in yaml.readLines()) {
            when {
                PATH_LINE.matches(line) -> path = line.trim().removeSuffix(":")
                path != null && METHOD_LINE.matches(line) -> operations += "${line.trim().removeSuffix(":").uppercase()} $path"
                line.isNotEmpty() && !line.startsWith(" ") -> path = null
            }
        }
        return operations
    }

    @Test
    fun allauthEndpointsAreOnlyTheAppLogin() {
        assertEquals(
            setOf("POST /_allauth/app/v1/auth/login", "DELETE /_allauth/app/v1/auth/session"),
            listed.filter { it.substringAfter(' ').startsWith(ALLAUTH_PREFIX) }.toSet(),
        )
    }

    private companion object {
        const val ALLAUTH_PREFIX = "/_allauth/"
        val PATH_LINE = Regex("^ {2}/\\S*:$")
        val METHOD_LINE = Regex("^ {4}(get|post|put|patch|delete):$")
    }
}
