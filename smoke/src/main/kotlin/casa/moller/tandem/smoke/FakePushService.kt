package casa.moller.tandem.smoke

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * Fas 4 (integration): the optional push service (push-service/, docs/PUSH.md "API") inside
 * [FakeTandoor]'s HTTPS server, so the app's `PushServiceClient` and its DTOs run under R8.
 * Messages are encrypted like `push-service/webpush.go` (RFC 8291, one aes128gcm record) with the
 * keys the app registered, and go to [deliver] (the test wires [FakeDistributor]). A wake-up that
 * cannot be decrypted would still sync, so only the test message, which the app acts on when it
 * reads its own registration id, proves the decryption.
 */
class FakePushService {
    /** One registered phone; [token] is what `Authorization` carried. */
    data class Phone(val id: String, val endpoint: String, val p256dh: String, val auth: String, val token: String?)

    val phones: MutableList<Phone> = CopyOnWriteArrayList()

    /** Hands a message for an endpoint to the distributor; false when it does not know the endpoint. */
    @Volatile
    var deliver: (endpoint: String, message: ByteArray) -> Boolean = { _, _ -> false }

    /** Answers the service's own paths, null for every other one (`/v1/models/…` is Anthropic's). */
    fun answer(request: RecordedRequest): MockResponse? {
        val path = request.url.encodedPath
        if (path !in PATHS) return null
        val body = request.body?.utf8().orEmpty().ifBlank { "{}" }
        val fields = Json.parseToJsonElement(body).jsonObject
        return when (path) {
            "/v1/info" -> json(OK, INFO)
            "/v1/register" -> register(fields, request.headers["Authorization"]?.removePrefix("Bearer ")?.trim())
            "/v1/unregister" -> unregister(fields)
            else -> test(fields)
        }
    }

    /** The service found a change: every phone gets `{"tandem":"sync"}`, encrypted. */
    fun wakeAll(): Boolean = phones.isNotEmpty() && phones.all { send(it, "sync") }

    private fun register(fields: JsonObject, token: String?): MockResponse {
        val endpoint = fields.text("endpoint").orEmpty()
        val phone = phones.firstOrNull { it.endpoint == endpoint }
            ?: Phone(
                id = UUID.randomUUID().toString().replace("-", ""),
                endpoint = endpoint,
                p256dh = fields.text("p256dh").orEmpty(),
                auth = fields.text("auth").orEmpty(),
                token = token,
            ).also { phones += it }
        return json(OK, """{"id":"${phone.id}","poll_seconds":45,"test":true}""")
    }

    private fun unregister(fields: JsonObject): MockResponse {
        phones.removeAll { it.endpoint == fields.text("endpoint") && it.id == fields.text("id") }
        return MockResponse.Builder().code(NO_CONTENT).build()
    }

    private fun test(fields: JsonObject): MockResponse {
        val phone = phones.firstOrNull { it.endpoint == fields.text("endpoint") && it.id == fields.text("id") }
            ?: return json(NOT_FOUND, """{"error":"not_registered"}""")
        if (!send(phone, "test")) return json(BAD_GATEWAY, """{"error":"push_failed"}""")
        return MockResponse.Builder().code(NO_CONTENT).build()
    }

    private fun send(phone: Phone, kind: String): Boolean {
        val message = """{"tandem":"$kind","id":"${phone.id}"}""".encodeToByteArray()
        return deliver(phone.endpoint, encrypt(message, phone.p256dh, phone.auth))
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

    private fun json(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private companion object {
        val PATHS = setOf("/v1/info", "/v1/register", "/v1/unregister", "/v1/test")
        const val INFO = """{"service":"tandem-push","version":"smoke","api":1,"test":true,"encryption":true,"poll_seconds":45}"""
        const val OK = 200
        const val NO_CONTENT = 204
        const val NOT_FOUND = 404
        const val BAD_GATEWAY = 502

        const val CURVE = "secp256r1"
        const val COORDINATE_SIZE = 32
        const val UNCOMPRESSED_POINT: Byte = 0x04
        const val SALT_SIZE = 16
        const val KEY_SIZE = 32
        const val CONTENT_KEY_SIZE = 16
        const val NONCE_SIZE = 12
        const val TAG_BITS = 128
        const val RECORD_SIZE = 4096
        const val LAST_RECORD_DELIMITER: Byte = 0x02
        const val NUL: Byte = 0
        const val COUNTER: Byte = 1
        const val HMAC = "HmacSHA256"

        /** RFC 8291: `salt | record size | key id length | key id | ciphertext`, one record. */
        fun encrypt(plain: ByteArray, p256dh: String, auth: String): ByteArray {
            val receiver = Base64.getUrlDecoder().decode(p256dh)
            val secret = Base64.getUrlDecoder().decode(auth)
            val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }
            val ephemeral = generator.generateKeyPair()
            val public = ephemeral.public as ECPublicKey
            val sender = encodePoint(public.w)
            val shared = KeyAgreement.getInstance("ECDH").run {
                init(ephemeral.private)
                doPhase(decodePoint(receiver, public), true)
                generateSecret()
            }
            val salt = ByteArray(SALT_SIZE).also(SecureRandom()::nextBytes)
            val keyInfo = "WebPush: info".encodeToByteArray() + NUL + receiver + sender
            val inputKey = hkdf(secret, shared, keyInfo, KEY_SIZE)
            val contentKey = hkdf(salt, inputKey, "Content-Encoding: aes128gcm".encodeToByteArray() + NUL, CONTENT_KEY_SIZE)
            val nonce = hkdf(salt, inputKey, "Content-Encoding: nonce".encodeToByteArray() + NUL, NONCE_SIZE)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            }
            val body = cipher.doFinal(plain + LAST_RECORD_DELIMITER)
            return ByteBuffer.allocate(SALT_SIZE + Int.SIZE_BYTES + 1 + sender.size + body.size)
                .put(salt)
                .putInt(RECORD_SIZE)
                .put(sender.size.toByte())
                .put(sender)
                .put(body)
                .array()
        }

        private fun hkdf(salt: ByteArray, input: ByteArray, info: ByteArray, size: Int): ByteArray {
            val pseudoRandomKey = Mac.getInstance(HMAC).run {
                init(SecretKeySpec(salt, HMAC))
                doFinal(input)
            }
            return Mac.getInstance(HMAC).run {
                init(SecretKeySpec(pseudoRandomKey, HMAC))
                doFinal(info + COUNTER)
            }.copyOf(size)
        }

        private fun decodePoint(point: ByteArray, sameCurve: ECPublicKey): ECPublicKey {
            val x = BigInteger(1, point.copyOfRange(1, 1 + COORDINATE_SIZE))
            val y = BigInteger(1, point.copyOfRange(1 + COORDINATE_SIZE, point.size))
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), sameCurve.params)) as ECPublicKey
        }

        private fun encodePoint(point: ECPoint): ByteArray = byteArrayOf(UNCOMPRESSED_POINT) + fixed(point.affineX) + fixed(point.affineY)

        /** A coordinate as exactly [COORDINATE_SIZE] bytes (BigInteger adds a sign byte or drops leading zeros). */
        private fun fixed(value: BigInteger): ByteArray {
            val bytes = value.toByteArray().takeLast(COORDINATE_SIZE).toByteArray()
            return ByteArray(COORDINATE_SIZE - bytes.size) + bytes
        }
    }
}
