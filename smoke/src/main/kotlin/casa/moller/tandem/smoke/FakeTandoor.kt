package casa.moller.tandem.smoke

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.rules.ExternalResource

/**
 * A Tandoor 2.6.13 stand-in for the smoke test: an HTTPS MockWebServer on localhost that answers
 * the endpoints of the intro, the shell, logout and the Phase 1 sync with the sanitized
 * recordings in `api-schema/fixtures/2.6.13` (packaged as assets of this test APK).
 *
 * Its certificate is issued at runtime by the fixed, test-only CA in
 * `assets/tandem-smoke-test-ca.pem`, which only the app's `minified` build type trusts
 * (`app/src/minified/res/xml/network_security_config.xml`). The app therefore runs its normal
 * HTTPS path, with no "trust everything" switch anywhere.
 *
 * Tokens work like on the real server: the password login hands out [LOGIN_TOKEN], which may
 * create the device token [DEVICE_TOKEN]; every other API call needs one of the two as a Bearer
 * token, otherwise it gets Tandoor's 403. A deleted token stops working.
 *
 * Phase 1 (integration lane): the recorded server address `tandoor.example.com` in every body is
 * replaced by this server's own address, so recipe photos (`/media/...`) are loaded from here (a
 * generated PNG); `api/recipe/` answers the sync's and Today's queries with the matching
 * recordings and filters the recipe list for `query` like Tandoor's search (case and diacritics
 * folded). While [offline] is set every request is answered by closing the connection: the
 * emulator's airplane mode does not cut localhost, so this is what takes the server away.
 *
 * Phase 1b (integration lane): Shakshuka's last step gets two short text timers
 * ([TIMER_STEP_TEXT]), and `POST /api/cook-log/` answers 201 with the sent entry and records its
 * body ([cookLogPosts]).
 *
 * Phase 2 (integration lane): the shopping list ([shopping]) and the meal plan ([mealPlan]) are
 * stateful, so the test can play "the partner" and "the web" and compare the server's state with
 * what the app did; both start empty. `addshopping` (and, in a test, the web with
 * `mealplan_autoadd_shopping`) puts a recipe's ingredients on the list. Every answer with status
 * 404 is kept in [notFound].
 */
class FakeTandoor(private val assets: AssetManager) : ExternalResource() {
    private val server = MockWebServer()
    private val revoked = CopyOnWriteArrayList<String>()

    /** `METHOD path` of every request, in order, e.g. `POST /api-token-auth/`. */
    val requests: MutableList<String> = CopyOnWriteArrayList()

    /** The `query` of every recipe search (`GET /api/recipe/?query=...`), decoded. */
    val searches: MutableList<String> = CopyOnWriteArrayList()

    /** Phase 1b: the JSON body of every `POST /api/cook-log/` ("Klart!"), in order. */
    val cookLogPosts: MutableList<String> = CopyOnWriteArrayList()

    /** `METHOD path` of the requests that arrived while [offline] was set (all were refused). */
    val requestsWhileOffline: MutableList<String> = CopyOnWriteArrayList()

    /** True: every request is answered by closing the connection (see the class comment). */
    @Volatile
    var offline: Boolean = false

    /** Phase 2: `METHOD path` of every request answered with 404 ("a 404 in the Caddy log"). */
    val notFound: MutableList<String> = CopyOnWriteArrayList()

    /** Phase 2: the stateful shopping list (empty until a test seeds it). */
    val shopping = FakeShoppingList(::template)

    /** Phase 2: the stateful meal plan (empty until a test plans on "the web"). */
    val mealPlan = FakeMealPlan(::template).also { plan ->
        plan.onList = { mealId -> shopping.hasListRecipeFor(mealId) }
        plan.onAddShopping = { meal -> shopping.serverAddMeal(meal.id, meal.recipe ?: 0, meal.servings, foodsOf(meal.recipe ?: 0)) }
    }

    /** `https://localhost:<port>`: replaces the recorded server address in every body. */
    private val origin: String get() = "https://localhost:${server.port}"

    private val image: ByteArray by lazy { generatedPng() }

    /** What the user types as the server address, e.g. `localhost:41234`. */
    val address: String get() = "localhost:${server.port}"

    override fun before() {
        val packaged = assets.list("").orEmpty().toSet()
        check(FIXTURE_CHECK in packaged && CA_ASSET in packaged) {
            "Test APK assets incomplete (api-schema/fixtures/2.6.13 not packaged?): $packaged"
        }
        val ca = HeldCertificate.decode(assets.open(CA_ASSET).bufferedReader().use { it.readText() })
        val serverCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .signedBy(ca)
            .build()
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate, ca.certificate)
            .build()
        server.useHttps(certificates.sslSocketFactory())
        server.protocolNegotiationEnabled = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = try {
                respond(request).also {
                    Log.i(TAG, "${request.method} ${request.url.encodedPath} -> ${it.code}")
                    if (it.code == NOT_FOUND) notFound += "${request.method} ${request.url.encodedPath}"
                }
            } catch (e: Exception) {
                // Visible in logcat; the app then sees a 500 instead of a dropped connection.
                Log.e(TAG, "${request.method} ${request.url.encodedPath} failed in the fake server", e)
                MockResponse.Builder().code(500).body("fake Tandoor: ${e.javaClass.simpleName}").build()
            }
        }
        server.start()
    }

    override fun after() {
        server.close()
    }

    private fun respond(request: RecordedRequest): MockResponse {
        val method = request.method
        val path = request.url.encodedPath
        if (offline) {
            requestsWhileOffline += "$method $path"
            return MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        }
        requests += "$method $path"
        val token = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val loggedIn = token != null && token in VALID_TOKENS && token !in revoked
        return when {
            method == "GET" && path == "/api/server-settings/current/" -> fixture("01-server-settings-no-auth")

            // Recipe photos: served without auth like the official image (FACTS "Fas 1 e").
            method == "GET" && path.startsWith("/media/") -> MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/png")
                .body(Buffer().write(image))
                .build()

            method == "POST" && path == "/api-token-auth/" -> {
                val body = request.body?.utf8().orEmpty()
                if ("\"username\"" in body && "\"password\"" in body) {
                    fixture("03-login-json")
                } else {
                    fixture("07-login-missing-fields")
                }
            }

            !loggedIn -> fixture(if (method == "GET") "15-invalid-token-missing-get" else "16-invalid-token-missing-post")

            method == "POST" && path == "/api/access-token/" ->
                if (token == LOGIN_TOKEN) fixture("08-access-token-create") else fixture("16-invalid-token-missing-post")

            method == "GET" && path == "/api/access-token/" -> fixture("09-access-token-list-fresh")

            method == "DELETE" && ACCESS_TOKEN_PATH.matches(path) -> {
                val id = ACCESS_TOKEN_PATH.find(path)!!.groupValues[1]
                TOKENS_BY_ID[id]?.let { revoked += it }
                fixture("34-revoke-device-token")
            }

            method == "GET" && path == "/api/space/current/" -> fixture("13-space-current")

            method == "GET" && path == "/api/user-preference/" -> fixture("12-user-preference-list")

            method == "GET" && USER_PATH.matches(path) -> fixture("11-user-by-id")

            // --- Phase 2 (integration lane): the stateful shopping list and meal plan. ---
            else -> shopping.answer(request) ?: mealPlan.answer(request) ?: respondRecorded(request, method, path)
        }
    }

    /** The recorded (stateless) answers after login. */
    private fun respondRecorded(request: RecordedRequest, method: String, path: String): MockResponse = when {
        // --- Phase 1 (sync lane): the first sync and the background sync after a login read
        // the recipe index, overviews, small tables, meal plan and cook log, and send view logs.
        method == "GET" && path == "/api/recipe/" -> recipeList(request.url)

        method == "GET" && path in PHASE1_LISTS -> fixture(PHASE1_LISTS.getValue(path))

        // --- Phase 2 (inköp-data lane): every sync reads the shopping list and its small tables. ---
        method == "GET" && path in PHASE2_LISTS -> fixture(PHASE2_LISTS.getValue(path))

        method == "GET" && RECIPE_PATH.matches(path) -> {
            val id = RECIPE_PATH.find(path)!!.groupValues[1]
            val recording = RECIPES_BY_ID[id] ?: "fas1/recipe-deleted-get"
            // Phase 1b: the cook-mode recipe gets two short timers in its last step.
            if (id == TIMER_RECIPE_ID.toString()) fixture(recording, ::withShortTimers) else fixture(recording)
        }

        // --- Phase 1b (integration lane): "Klart!" from cook mode, sent by the outbox. ---
        method == "POST" && path == "/api/cook-log/" -> {
            val body = request.body?.utf8().orEmpty()
            cookLogPosts += body
            fixture("fas1/cook-log-create") { recorded -> echoCookLog(recorded, body) }
        }

        method == "POST" && path == "/api/view-log/" -> fixture("fas1/view-log-create")

        // Tandoor sends unknown paths to its login page (FACTS g).
        else -> fixture("29-not-found-unknown-path")
    }

    /**
     * `GET /api/recipe/` like Tandoor 2.6.13 answers the app's queries (plan 3.3): a search
     * (`query`) filters the recorded list by name, Today's carousels get their recordings, and
     * every other list (the sync's overview pages) gets the default list.
     */
    private fun recipeList(url: HttpUrl): MockResponse {
        val query = url.queryParameter("query")
        if (query != null) {
            searches += query
            val words = fold(query).split(' ').filter { it.isNotBlank() }
            return fixture("fas1/recipe-list-default") { body ->
                val results = body.jsonObject.getValue("results").jsonArray.filter { recipe ->
                    val name = fold(recipe.jsonObject["name"]?.jsonPrimitive?.content.orEmpty())
                    words.all { it in name }
                }
                JsonObject(
                    body.jsonObject + mapOf(
                        "count" to JsonPrimitive(results.size),
                        "next" to JsonNull,
                        "results" to JsonArray(results),
                    ),
                )
            }
        }
        val recording = when {
            url.queryParameter("timescooked_gte") != null -> "fas1/recipe-list-timescooked-gte"
            url.queryParameter("viewedon_gte") != null -> "fas1/recipe-list-viewedon-gte"
            url.queryParameter("cookedon_lte") != null -> "fas1/recipe-list-cookedon-lte"
            url.queryParameter("new") != null -> "fas1/recipe-list-new"
            else -> "fas1/recipe-list-default"
        }
        return fixture(recording)
    }

    /**
     * Shakshuka ([TIMER_RECIPE_ID]) with [TIMER_STEP_TEXT] added to its last step ("Eggs") and no
     * step time, so cook mode offers exactly two short text timers there (core:domain's
     * TimerDetector) and the smoke test can let both ring within a minute.
     */
    private fun withShortTimers(body: JsonElement): JsonElement {
        val recipe = body.jsonObject
        val steps = recipe.getValue("steps").jsonArray
        val last = steps.last().jsonObject
        val instruction = last.getValue("instruction").jsonPrimitive.content
        val changed = JsonObject(last + mapOf("instruction" to JsonPrimitive("$instruction $TIMER_STEP_TEXT"), "time" to JsonPrimitive(0)))
        return JsonObject(recipe + mapOf("steps" to JsonArray(steps.dropLast(1) + changed)))
    }

    /** Tandoor's 201 for `POST /api/cook-log/`: the recorded entry with what the app sent. */
    private fun echoCookLog(recorded: JsonElement, sent: String): JsonElement {
        val request = runCatching { json.parseToJsonElement(sent).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val echoed = listOf("recipe", "servings", "rating", "comment", "created_at").mapNotNull { key -> request[key]?.let { key to it } }
        return JsonObject(recorded.jsonObject + echoed.toMap() + mapOf("id" to JsonPrimitive(COOK_LOG_ID + cookLogPosts.size)))
    }

    /**
     * The recorded response of [name], without hop-by-hop and length headers, with this server's
     * address in place of the recorded one; [transform] may change a JSON body first.
     */
    private fun fixture(name: String, transform: ((JsonElement) -> JsonElement)? = null): MockResponse {
        val root = json.parseToJsonElement(assets.open("$name.json").bufferedReader().use { it.readText() }).jsonObject
        val response = root.getValue("response").jsonObject
        val status = response.getValue("status").jsonPrimitive.content.toInt()
        val headers = response["headers"]?.jsonObject.orEmpty()
            .mapValues { it.value.jsonPrimitive.content }
            .filterKeys { it !in SKIPPED_HEADERS }
        val body = when (val recorded = response["body"]) {
            null, is JsonPrimitive -> (response["bodyText"] as? JsonPrimitive)?.content.orEmpty()
            else -> (transform?.invoke(recorded) ?: recorded).toString()
        }.replace(RECORDED_ORIGIN, origin).replace(RECORDED_ORIGIN_HTTP, origin)
        return MockResponse.Builder()
            .code(status)
            .apply { headers.forEach { (header, value) -> addHeader(header, value) } }
            .body(body)
            .build()
    }

    /** Phase 2: the recorded body of [name] (`fas2/...`), a template for the stateful answers. */
    private fun template(name: String): JsonObject =
        json.parseToJsonElement(assets.open("$name.json").bufferedReader().use { it.readText() })
            .jsonObject.getValue("response").jsonObject.getValue("body").jsonObject

    /** Phase 2: the foods of a recorded recipe's ingredients, in order (what `addshopping` puts on the list). */
    fun foodsOf(recipeId: Long): List<String> {
        val recording = RECIPES_BY_ID[recipeId.toString()] ?: return emptyList()
        return template(recording).getValue("steps").jsonArray.flatMap { step ->
            step.jsonObject.getValue("ingredients").jsonArray.mapNotNull { ingredient ->
                (ingredient.jsonObject["food"] as? JsonObject)?.get("name")?.jsonPrimitive?.content
            }
        }.distinct()
    }

    /** A small two-coloured PNG: something Coil can decode and show as a recipe photo. */
    private fun generatedPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0xE0, 0x7A, 0x2F))
        canvas.drawCircle(IMAGE_WIDTH / 2f, IMAGE_HEIGHT / 2f, IMAGE_HEIGHT / 3f, Paint().apply { color = Color.rgb(0x2F, 0x6B, 0x3A) })
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    companion object {
        /** From `03-login-json`: the token `api-token-auth` returns. */
        const val LOGIN_TOKEN = "tda_00000000_0000_0000_0000_000000000001"

        /** From `08-access-token-create`: this device's own token (id 2). */
        const val DEVICE_TOKEN = "tda_00000000_0000_0000_0000_000000000002"

        /** fas1/recipe-shakshuka: 2 servings, steps "Sauce" and "Eggs" (the timers go in "Eggs"). */
        const val TIMER_RECIPE_ID = 6
        const val TIMER_RECIPE_NAME = "Shakshuka"

        /** The two timers (core:domain TimerDetector, English rules): 50 s and 55 s. */
        const val TIMER_STEP_TEXT = "Let the eggs set for 50 seconds, then warm the bread for 55 seconds."
        private const val COOK_LOG_ID = 4200

        private const val CA_ASSET = "tandem-smoke-test-ca.pem"
        private const val FIXTURE_CHECK = "01-server-settings-no-auth.json"
        private const val TAG = "FakeTandoor"
        private const val NOT_FOUND = 404
        private val VALID_TOKENS = setOf(LOGIN_TOKEN, DEVICE_TOKEN)
        private val TOKENS_BY_ID = mapOf("1" to LOGIN_TOKEN, "2" to DEVICE_TOKEN)
        private val ACCESS_TOKEN_PATH = Regex("^/api/access-token/(\\d+)/$")
        private val USER_PATH = Regex("^/api/user/\\d+/$")
        private val RECIPE_PATH = Regex("^/api/recipe/(\\d+)/$")
        private val PHASE1_LISTS = mapOf(
            "/api/recipe/flat/" to "fas1/recipe-flat",
            "/api/keyword/" to "fas1/keyword-list",
            "/api/food/" to "fas1/food-list",
            "/api/unit/" to "fas1/unit-list",
            "/api/cook-log/" to "fas1/cook-log-list-recipe",
        )
        private val PHASE2_LISTS = mapOf(
            "/api/shopping-list/" to "fas2/shopping-list-list",
            "/api/supermarket/" to "fas2/supermarket-list",
            "/api/supermarket-category/" to "fas2/supermarket-category-list",
            // Phase 2 (böcker lane): the role (guest mode) and the books are read with the small tables.
            "/api/user-space/all_personal/" to "14-user-space-all-personal",
            "/api/recipe-book/" to "fas1/recipe-book-list",
            "/api/recipe-book-entry/" to "fas1/recipe-book-entry-list",
        )
        private val RECIPES_BY_ID = mapOf(
            "1" to "fas1/recipe-pyttipanna",
            "2" to "fas1/recipe-svamppasta",
            "3" to "fas1/recipe-pannkakor",
            "4" to "fas1/recipe-kanelbullar",
            "5" to "fas1/recipe-kottbullar",
            "6" to "fas1/recipe-shakshuka",
            "7" to "fas1/recipe-artsoppa",
            "8" to "fas1/recipe-gronsakssoppa",
            "9" to "fas1/recipe-smorgastarta",
            "10" to "fas1/recipe-appelkaka",
            "11" to "fas1/recipe-rarakor",
        )
        private val SKIPPED_HEADERS = setOf("content-length", "transfer-encoding", "connection")
        private val json = Json { ignoreUnknownKeys = true }

        /** The server address in the recordings (FACTS: sanitized to tandoor.example.com). */
        private const val RECORDED_ORIGIN = "https://tandoor.example.com"
        private const val RECORDED_ORIGIN_HTTP = "http://tandoor.example.com"
        private const val IMAGE_WIDTH = 160
        private const val IMAGE_HEIGHT = 120
        private val combiningMarks = Regex("\\p{Mn}+")

        /** Lower case without diacritics, like Tandoor's unaccent search: "Äppel" -> "appel". */
        fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase(Locale.ROOT)
    }
}
