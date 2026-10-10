package casa.moller.tandem.contract

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Replaces secret and run-specific values in recorded exchanges so that the recordings can be
 * committed as fixtures (`api-schema/fixtures/<version>/`).
 *
 * - Every distinct real token `tda_<uuid>` becomes a stable fake `tda_00000000_0000_0000_0000_<n>`
 *   (numbered in order of appearance), so fixtures still show which token is which.
 * - Masked tokens (`tda_****…abcd`) keep their shape; the last four characters are mapped to
 *   the last four of the matching fake token.
 * - Literal secrets (the generated password) become `<redacted>`.
 * - ISO-8601 date-times keep their exact format (fraction length, offset) but get a fixed
 *   value, so fixtures do not change between runs.
 * - With [runToday] (Fas 1) dates are instead **shifted**: every `yyyy-MM-dd`, also inside
 *   date-times and request paths, moves by the same number of days so that the run day becomes
 *   [SHIFTED_TODAY] (today, tomorrow and "90 days ago" stay recognisable). Times of day are kept
 *   when they are whole minutes (meal-plan times such as `18:00:00`) and zeroed otherwise.
 */
internal class Sanitizer(secrets: List<String>, private val runToday: LocalDate? = null) {
    private val secrets = secrets.filter { it.length >= MIN_SECRET_LENGTH }
    private val fakeByReal = LinkedHashMap<String, String>()

    fun fakeTokenFor(realToken: String): String {
        if (realToken.startsWith(FAKE_PREFIX)) return realToken
        return fakeByReal.getOrPut(realToken) { fakeToken(fakeByReal.size + 1) }
    }

    fun sanitize(text: String): String {
        var out = text
        for (secret in secrets) out = out.replace(secret, REDACTED)
        out = TOKEN.replace(out) { fakeTokenFor(it.value) }
        out = MASKED_TOKEN.replace(out) { match ->
            val last4 = match.groupValues[1]
            val fake = fakeByReal.entries.firstOrNull { it.key.endsWith(last4) }?.value
            MASK_PREFIX + (fake?.takeLast(4) ?: "0000")
        }
        if (runToday != null) out = DATE.replace(out) { match -> shiftDate(match.value) }
        return out
    }

    private fun shiftDate(value: String): String {
        val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return value
        return SHIFTED_TODAY.plusDays(ChronoUnit.DAYS.between(runToday, date)).toString()
    }

    fun sanitize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> sanitize(value) })

        is JsonArray -> JsonArray(element.map { sanitize(it) })

        is JsonPrimitive ->
            when {
                !element.isString -> element
                runToday != null -> JsonPrimitive(normalizeTimeOfDay(sanitize(element.content)))
                else -> JsonPrimitive(normalizeDateTime(sanitize(element.content)))
            }
    }

    companion object {
        private const val MIN_SECRET_LENGTH = 6
        const val REDACTED = "<redacted>"
        const val FAKE_PREFIX = "tda_00000000_0000_0000_0000_"
        const val MASK_PREFIX = "tda_************_******_***********"

        /** A real Tandoor token: `tda_` + UUID with `_` instead of `-` (serializer.py). */
        val TOKEN = Regex("tda_[0-9a-f]{8}_[0-9a-f]{4}_[0-9a-f]{4}_[0-9a-f]{4}_[0-9a-f]{12}")

        /** The masked form returned after 15 s (AccessTokenSerializer.get_token). */
        val MASKED_TOKEN = Regex("""tda_\*{12}_\*{6}_\*{11}([0-9a-f_]{4})""")

        private val DATE_TIME =
            Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?(\.\d+)?(Z|[+-]\d{2}(:?\d{2})?)?$""")

        /** What the run day becomes in Fas 1 recordings (a Saturday, like any other day). */
        val SHIFTED_TODAY: LocalDate = LocalDate.of(2000, 1, 15)

        private val DATE = Regex("""(?<![0-9])\d{4}-\d{2}-\d{2}(?![0-9])""")

        private val DATE_TIME_PARTS =
            Regex("""^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})(:(\d{2}))?(\.(\d+))?(Z|[+-]\d{2}(:?\d{2})?)?$""")

        /**
         * Fas 1: keeps whole-minute times (`18:00:00`, `12:00:00.000000+01:00`) and zeroes the
         * others (`14:23:11.123456` -> `00:00:00.000000`); the date and the format stay.
         */
        fun normalizeTimeOfDay(value: String): String {
            val match = DATE_TIME_PARTS.matchEntire(value) ?: return value
            val groups = match.groupValues
            val seconds = groups[5]
            val fraction = groups[7]
            val wholeMinute = (seconds.isEmpty() || seconds == "00") && fraction.all { it == '0' }
            if (wholeMinute) return value
            val zeroFraction = if (groups[6].isEmpty()) "" else "." + "0".repeat(fraction.length)
            return "${groups[1]}T00:00${if (groups[4].isEmpty()) "" else ":00"}$zeroFraction${groups[8]}"
        }

        fun fakeToken(n: Int): String = FAKE_PREFIX + n.toString().padStart(12, '0')

        /** `2031-10-08T09:12:33.123456+02:00` -> `2000-01-01T00:00:00.000000+02:00`. */
        fun normalizeDateTime(value: String): String {
            val match = DATE_TIME.matchEntire(value) ?: return value
            val seconds = if (match.groupValues[1].isEmpty()) "" else ":00"
            val fraction = match.groupValues[2].let { if (it.isEmpty()) "" else "." + "0".repeat(it.length - 1) }
            return "2000-01-01T00:00$seconds$fraction${match.groupValues[3]}"
        }
    }
}
