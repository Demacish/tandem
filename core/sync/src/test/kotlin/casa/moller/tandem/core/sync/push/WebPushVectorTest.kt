package casa.moller.tandem.core.sync.push

import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The link between the push service and the phone: `push-service/service_test.go`
 * (`TestSealMatchesRfc8291AppendixA`) proves that webpush.go produces exactly this RFC 8291 body,
 * and this test decrypts it with the same class the UnifiedPush connector uses on the phone.
 */
class WebPushVectorTest {
    @Test
    fun `the connector's decryption reads the body the push service produces`() {
        val decrypt = WebPushHybridDecrypt.Builder()
            .withAuthSecret(bytes("BTBZMqHH6r4Tts7J_aSIgg"))
            .withRecipientPublicKey(bytes(RECIPIENT_PUBLIC_KEY))
            .withRecipientPrivateKey(bytes("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"))
            .build()

        val plain = decrypt.decrypt(bytes(BODY), null)

        assertEquals("When I grow up, I want to be a watermelon", plain.decodeToString())
    }

    private fun bytes(base64Url: String): ByteArray = Base64.getUrlDecoder().decode(base64Url)

    private companion object {
        const val RECIPIENT_PUBLIC_KEY = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
        const val BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
            "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
            "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
    }
}
