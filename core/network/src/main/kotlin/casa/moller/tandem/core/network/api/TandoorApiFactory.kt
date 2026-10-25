package casa.moller.tandem.core.network.api

import casa.moller.tandem.core.network.di.TandoorHttpClient
import casa.moller.tandem.core.network.http.AuthInterceptor
import casa.moller.tandem.core.network.http.HtmlResponseGuardInterceptor
import casa.moller.tandem.core.network.http.TokenProvider
import casa.moller.tandem.core.network.json.TandoorJson
import javax.inject.Inject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** The Retrofit services for one server and one token source. */
class TandoorApi internal constructor(
    /** Normalized base URL with trailing slash. */
    val baseUrl: String,
    val auth: AuthService,
    /** Tandoor 2.7+: password login through allauth headless. */
    val allauth: AllauthService,
    val users: UserService,
    val space: SpaceService,
    val server: ServerService,
    /** Phase 1: recipe index, search and details. */
    val recipes: RecipeService,
    /** Phase 1: keywords, foods, units, meal types and recipe books. */
    val taxonomy: TaxonomyService,
    val mealPlans: MealPlanService,
    /** Phase 2 (matsedel lane): planning, moving and removing meals, new meal types. */
    val mealPlanEdits: MealPlanEditService,
    val preferences: PreferenceService,
    /** Phase 1: cook log and view log. */
    val logs: LogService,
    // --- Phase 2 (inköp-data lane): the shopping list, stores, aisles and the ingredient parser. ---
    val shopping: ShoppingService,
    val supermarkets: SupermarketService,
    // --- Phase 2 (böcker lane): recipe books, favourites, "Vill laga" and the user's role. ---
    val books: BookService,
    val userSpaces: UserSpaceService,
    /** Capability probes; see `CapabilityResolver`. */
    internal val probes: CapabilityProbeService,
    /** The client with this server's [AuthInterceptor]; for raw calls in tests and later image loading. */
    internal val httpClient: OkHttpClient,
)

/**
 * Creates [TandoorApi] instances at runtime, because the server URL is chosen by the user.
 * All instances share the injected base client (dispatcher, connection pool, rate limiter).
 */
class TandoorApiFactory @Inject constructor(@TandoorHttpClient private val baseClient: OkHttpClient) {
    /**
     * @param baseUrl the normalized `https://` base URL from the probe (trailing slash).
     * @param tokenProvider sends its token only to [baseUrl] (see [AuthInterceptor]).
     * @param retryOnConnectionFailure false for the password login, which must never be sent twice.
     * @throws IllegalArgumentException if [baseUrl] is not an absolute https URL.
     */
    fun create(baseUrl: String, tokenProvider: TokenProvider, retryOnConnectionFailure: Boolean = true): TandoorApi {
        val url = requireHttpsBase(baseUrl)
        val client = baseClient.newBuilder()
            .addInterceptor(AuthInterceptor(url, tokenProvider))
            .addInterceptor(HtmlResponseGuardInterceptor())
            .retryOnConnectionFailure(retryOnConnectionFailure)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(TandoorJson.asConverterFactory(JSON))
            .build()
        return TandoorApi(
            baseUrl = url.toString(),
            auth = retrofit.create(AuthService::class.java),
            allauth = retrofit.create(AllauthService::class.java),
            users = retrofit.create(UserService::class.java),
            space = retrofit.create(SpaceService::class.java),
            server = retrofit.create(ServerService::class.java),
            recipes = retrofit.create(RecipeService::class.java),
            taxonomy = retrofit.create(TaxonomyService::class.java),
            mealPlans = retrofit.create(MealPlanService::class.java),
            mealPlanEdits = retrofit.create(MealPlanEditService::class.java),
            preferences = retrofit.create(PreferenceService::class.java),
            logs = retrofit.create(LogService::class.java),
            shopping = retrofit.create(ShoppingService::class.java),
            supermarkets = retrofit.create(SupermarketService::class.java),
            books = retrofit.create(BookService::class.java),
            userSpaces = retrofit.create(UserSpaceService::class.java),
            probes = retrofit.create(CapabilityProbeService::class.java),
            httpClient = client,
        )
    }

    private companion object {
        val JSON = "application/json".toMediaType()

        fun requireHttpsBase(baseUrl: String): HttpUrl {
            val url = requireNotNull(baseUrl.toHttpUrlOrNull()) { "Not a URL: $baseUrl" }
            require(url.isHttps) { "Tandem only talks HTTPS (plan 5.5): $baseUrl" }
            return if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
        }
    }
}
