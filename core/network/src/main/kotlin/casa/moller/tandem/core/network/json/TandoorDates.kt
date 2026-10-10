package casa.moller.tandem.core.network.json

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Tandoor sends date-times in several ISO-8601 shapes (FACTS c): `Z` or `±hh:mm`, with or without
 * fractional seconds, e.g. `2031-10-08T05:46:12.345678Z` and `2027-10-09T07:46:13+02:00`.
 */
object TandoorDates {

    /** Parses any of the shapes above; returns null for blank or unparseable input. */
    fun parseInstantOrNull(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        val text = value.trim()
        return try {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (notOffset: DateTimeParseException) {
            try {
                Instant.parse(text)
            } catch (notInstant: DateTimeParseException) {
                null
            }
        }
    }

    /** Formats for request bodies: UTC, whole seconds, e.g. `2027-10-09T05:46:13Z`. */
    fun format(instant: Instant): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        instant.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC),
    )
}
