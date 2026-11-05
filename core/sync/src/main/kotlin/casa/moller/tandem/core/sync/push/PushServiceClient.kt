package casa.moller.tandem.core.sync.push

import casa.moller.tandem.core.model.push.FastPushError
import casa.moller.tandem.core.model.push.FastPushResult
import casa.moller.tandem.core.model.push.FastPushServiceInfo
import casa.moller.tandem.core.network.di.TandoorHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The app's side of the push service's API version 1 (push-service/, docs/PUSH.md "API"):
 * `GET /v1/info`, `POST /v1/register` (with the account's Tandoor token, which the service uses
 * once and forgets), `POST /v1/unregister` and `POST /v1/test` (with the registration's id).
 * Built on the app's shared client: HTTPS only, the app's User-Agent and limits, no redirects.
 */
@Singleton
internal class PushServiceClient @Inject constructor(@TandoorHttpClient private val http: OkHttpClient) {
    /** What `POST /v1/register` answers. */
    data class Registered(val id: String, val pollSeconds: Int?, val testSupported: Boolean)

    suspend fun info(serviceUrl: String): FastPushResult<FastPushServiceInfo> {
        val url = baseUrl(serviceUrl) ?: return FastPushResult.Failure(FastPushError.INVALID_URL)
        return execute(Request.Builder().url(url.resolve("v1/info")!!).get().build()) { response ->
            when {
                response.code == HTTP_OK -> {
                    val info = decode<InfoDto>(response) ?: return@execute FastPushResult.Failure(FastPushError.NOT_A_PUSH_SERVICE)
                    if (info.service != SERVICE_NAME || info.api < 1) {
                        FastPushResult.Failure(FastPushError.NOT_A_PUSH_SERVICE)
                    } else {
                        FastPushResult.Success(FastPushServiceInfo(info.version, info.test, info.pollSeconds))
                    }
                }

                response.code >= HTTP_SERVER_ERROR -> FastPushResult.Failure(FastPushError.SERVICE_ERROR)
                else -> FastPushResult.Failure(FastPushError.NOT_A_PUSH_SERVICE)
            }
        }
    }

    suspend fun register(serviceUrl: String, token: String, endpoint: String, p256dh: String?, auth: String?): FastPushResult<Registered> {
        val url = baseUrl(serviceUrl) ?: return FastPushResult.Failure(FastPushError.INVALID_URL)
        val body = json.encodeToString(RegisterDto.serializer(), RegisterDto(endpoint = endpoint, p256dh = p256dh, auth = auth))
        val request = Request.Builder()
            .url(url.resolve("v1/register")!!)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON))
            .build()
        return execute(request) { response ->
            if (response.code == HTTP_OK) {
                val answer = decode<RegisteredDto>(response)
                if (answer == null || answer.id.isBlank()) {
                    FastPushResult.Failure(FastPushError.NOT_A_PUSH_SERVICE)
                } else {
                    FastPushResult.Success(Registered(answer.id, answer.pollSeconds, answer.test))
                }
            } else {
                FastPushResult.Failure(errorOf(response))
            }
        }
    }

    suspend fun unregister(serviceUrl: String, endpoint: String, id: String): FastPushResult<Unit> =
        postWithId(serviceUrl, "v1/unregister", endpoint, id)

    suspend fun test(serviceUrl: String, endpoint: String, id: String): FastPushResult<Unit> =
        postWithId(serviceUrl, "v1/test", endpoint, id)

    private suspend fun postWithId(serviceUrl: String, path: String, endpoint: String, id: String): FastPushResult<Unit> {
        val url = baseUrl(serviceUrl) ?: return FastPushResult.Failure(FastPushError.INVALID_URL)
        val body = json.encodeToString(RegisterDto.serializer(), RegisterDto(endpoint = endpoint, id = id))
        val request = Request.Builder().url(url.resolve(path)!!).post(body.toRequestBody(JSON)).build()
        return execute(request) { response ->
            if (response.isSuccessful) FastPushResult.Success(Unit) else FastPushResult.Failure(errorOf(response))
        }
    }

    private suspend fun <T> execute(request: Request, read: (Response) -> FastPushResult<T>): FastPushResult<T> =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use(read)
            } catch (e: IOException) {
                FastPushResult.Failure(FastPushError.UNREACHABLE)
            }
        }

    private inline fun <reified T> decode(response: Response): T? = try {
        json.decodeFromString<T>(response.body.string())
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun errorOf(response: Response): FastPushError {
        val code = decode<ErrorDto>(response)?.error
        return when {
            response.code == HTTP_UNAUTHORIZED -> FastPushError.UNAUTHORIZED
            code == "guest" -> FastPushError.GUEST
            code == "other_household" -> FastPushError.OTHER_HOUSEHOLD
            code == "too_many" -> FastPushError.TOO_MANY
            code == "test_disabled" -> FastPushError.TEST_UNSUPPORTED
            code == "invalid_endpoint" || code == "invalid_keys" -> FastPushError.DISTRIBUTOR_FAILED
            response.code == HTTP_TOO_MANY_REQUESTS -> FastPushError.RATE_LIMITED
            response.code == HTTP_NOT_FOUND && code == null -> FastPushError.NOT_A_PUSH_SERVICE
            else -> FastPushError.SERVICE_ERROR
        }
    }

    @Serializable
    private data class InfoDto(
        val service: String = "",
        val version: String = "",
        val api: Int = 0,
        val test: Boolean = false,
        @SerialName("poll_seconds") val pollSeconds: Int = 0,
    )

    @Serializable
    private data class RegisterDto(
        val endpoint: String,
        val p256dh: String? = null,
        val auth: String? = null,
        val id: String? = null,
    )

    @Serializable
    private data class RegisteredDto(
        val id: String = "",
        @SerialName("poll_seconds") val pollSeconds: Int? = null,
        val test: Boolean = false,
    )

    @Serializable
    private data class ErrorDto(val error: String? = null)

    companion object {
        const val SERVICE_NAME: String = "tandem-push"
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
        private val JSON = "application/json".toMediaType()
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /**
         * The service's address as the app keeps it: `https://` only (the app never talks
         * cleartext, plan 5.5), no user info, query or fragment; a trailing slash so `v1/…`
         * resolves below it. Null when [raw] is not such an address.
         */
        fun baseUrl(raw: String): HttpUrl? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("https://", ignoreCase = true)) return null
            val url = trimmed.toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
            val path = url.encodedPath.trimEnd('/') + "/"
            return url.newBuilder().encodedPath(path).build()
        }

        /** [baseUrl] as text without the trailing slash, as shown and saved; null when invalid. */
        fun normalize(raw: String): String? = baseUrl(raw)?.toString()?.trimEnd('/')
    }
}
