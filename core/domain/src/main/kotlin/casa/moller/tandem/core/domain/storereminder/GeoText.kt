package casa.moller.tandem.core.domain.storereminder

import casa.moller.tandem.core.model.shopping.GeoPoint

/**
 * Fas 5 (butik lane, plan §7 #34): a store's place from pasted text, without a map SDK or any
 * service: nothing is looked up, only read from the text.
 *
 * Understood:
 * - a `geo:` link (RFC 5870 and Android's form): `geo:12.3456,45.6789`, with an altitude,
 *   `;u=` or `?z=`, and `geo:0,0?q=12.3456,45.6789(Butiken)`; `geo:0,0` alone is no place;
 * - a map link with the place in it: `…!3d12.3456!4d45.6789…`, `…/@12.3456,45.6789,17z`, `?q=` / `query=` / `ll=` /
 *   `center=` / `destination=` / `daddr=` with `lat,lon`, `mlat=…&mlon=…` and `#map=17/12.3456/45.6789`;
 * - two numbers with decimals: `12.3456, 45.6789`, `12.3456 45.6789`, `12.3456;45.6789`, Swedish
 *   decimal commas between a space or a semicolon (`12,3456 45,6789`, `12,3456; 45,6789`), with
 *   compass letters (`12.3456 N, 45.6789 E`, `Ö`/`V` too; south and west turn negative) and degree signs;
 * - degrees, minutes and seconds: `12°20'44.2"N 45°40'44.0"W`.
 *
 * Plain numbers need decimals ("59, 18" is more likely one Swedish number than a place). Out of
 * range gives null.
 */
object GeoText {
    private const val NUMBER = """[-+]?\d{1,3}(?:\.\d+)?"""
    private val GEO_URI = Regex("""^geo:\s*($NUMBER)\s*,\s*($NUMBER)(?:\s*,\s*[-+]?\d+(?:\.\d+)?)?([;?].*)?$""", RegexOption.IGNORE_CASE)
    private val QUERY_PAIR = Regex("""(?:^|[?&;])(?:q|query|ll|center|destination|daddr|sll)=([^&#]*)""", RegexOption.IGNORE_CASE)
    private val AT_PAIR = Regex("""@($NUMBER),($NUMBER)""")

    /** Google Maps' place itself (`!3d<lat>!4d<lon>` in the link's data), before the map's centre (`@`). */
    private val PLACE_DATA = Regex("""!3d($NUMBER)!4d($NUMBER)""")
    private val OSM_MARKER_LAT = Regex("""[?&]mlat=($NUMBER)""", RegexOption.IGNORE_CASE)
    private val OSM_MARKER_LON = Regex("""[?&]mlon=($NUMBER)""", RegexOption.IGNORE_CASE)
    private val OSM_MAP = Regex("""#map=\d{1,2}/($NUMBER)/($NUMBER)""", RegexOption.IGNORE_CASE)

    /** Dot decimals (required), any separator. Compass letters after each number. */
    private val DOT_PAIR = Regex(
        """^([-+]?\d{1,3}\.\d+)\s*([NSns])?\s*[,;\s]\s*([-+]?\d{1,3}\.\d+)\s*([EWOÖVewoöv])?$""",
    )

    /** Comma decimals (required); the numbers are apart by a space or a semicolon, never a comma. */
    private val COMMA_PAIR = Regex(
        """^([-+]?\d{1,3},\d+)\s*([NSns])?\s*(?:;\s*|\s+)([-+]?\d{1,3},\d+)\s*([EWOÖVewoöv])?$""",
    )

    /** One coordinate in degrees, minutes and (optionally) seconds with its compass letter. */
    private const val DMS = """(\d{1,3})\s*°\s*(\d{1,2})\s*['′]\s*(?:(\d{1,2}(?:[.,]\d+)?)\s*(?:["″]|'')\s*)?([NSEWOÖVnsewoöv])"""
    private val DMS_PAIR = Regex("""^$DMS\s*[,;]?\s*$DMS$""")

    /** The place in [text], or null when there is none (or it is out of range). */
    fun parse(text: String): GeoPoint? {
        val clean = text.trim()
            .replace('−', '-')
            .replace(' ', ' ')
            .trim('<', '>', '"', '\'', '(', ')')
            .trim()
        if (clean.isEmpty()) return null
        if (clean.startsWith("geo:", ignoreCase = true)) return geoUri(clean)
        if (clean.contains("://") || clean.startsWith("www.", ignoreCase = true)) return mapLink(clean)
        return dms(clean) ?: plain(clean)
    }

    private fun geoUri(text: String): GeoPoint? {
        val match = GEO_URI.find(text) ?: return null
        val rest = match.groupValues[3]
        // Android's form: geo:0,0?q=lat,lng(label) puts the place in the query.
        QUERY_PAIR.find(rest)?.let { query -> pairIn(decode(query.groupValues[1]))?.let { return it } }
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        return point(lat, lon)
    }

    private fun mapLink(text: String): GeoPoint? {
        PLACE_DATA.find(text)?.let { point(it.groupValues[1], it.groupValues[2])?.let { found -> return found } }
        AT_PAIR.find(text)?.let { point(it.groupValues[1], it.groupValues[2])?.let { found -> return found } }
        val markerLat = OSM_MARKER_LAT.find(text)
        val markerLon = OSM_MARKER_LON.find(text)
        if (markerLat != null && markerLon != null) point(markerLat.groupValues[1], markerLon.groupValues[1])?.let { return it }
        for (query in QUERY_PAIR.findAll(text)) pairIn(decode(query.groupValues[1]))?.let { return it }
        OSM_MAP.find(text)?.let { point(it.groupValues[1], it.groupValues[2])?.let { found -> return found } }
        return null
    }

    /** `lat,lon` at the start of a query value (a label may follow: `12.3,45.6(Butiken)`). */
    private fun pairIn(value: String): GeoPoint? {
        val match = Regex("""^\s*($NUMBER)\s*,\s*($NUMBER)""").find(value) ?: return null
        return point(match.groupValues[1], match.groupValues[2])
    }

    private fun plain(text: String): GeoPoint? {
        val noDegrees = text.replace("°", " ").replace(Regex("""\s+"""), " ").trim()
        DOT_PAIR.matchEntire(noDegrees)?.let { return withCompass(it.groupValues, decimalComma = false) }
        COMMA_PAIR.matchEntire(noDegrees)?.let { return withCompass(it.groupValues, decimalComma = true) }
        return null
    }

    private fun withCompass(groups: List<String>, decimalComma: Boolean): GeoPoint? {
        fun number(raw: String) = (if (decimalComma) raw.replace(',', '.') else raw).toDoubleOrNull()
        val lat = number(groups[1]) ?: return null
        val lon = number(groups[3]) ?: return null
        val south = groups[2].equals("S", ignoreCase = true)
        val west = groups[4].uppercase() in WEST
        // "-59 S" says south twice: ambiguous, not a place.
        val doubleSouth = south && lat < 0
        val doubleWest = west && lon < 0
        if (doubleSouth || doubleWest) return null
        return point(if (south) -lat else lat, if (west) -lon else lon)
    }

    private fun dms(text: String): GeoPoint? {
        val match = DMS_PAIR.matchEntire(text) ?: return null
        val g = match.groupValues
        val first = degrees(g[1], g[2], g[3], g[4]) ?: return null
        val second = degrees(g[5], g[6], g[7], g[8]) ?: return null
        val firstIsLat = g[4].uppercase() in setOf("N", "S")
        val secondIsLat = g[8].uppercase() in setOf("N", "S")
        if (firstIsLat == secondIsLat) return null
        return if (firstIsLat) point(first, second) else point(second, first)
    }

    private fun degrees(deg: String, min: String, sec: String, compass: String): Double? {
        val d = deg.toDoubleOrNull() ?: return null
        val m = min.toDoubleOrNull()?.takeIf { it < MINUTES } ?: return null
        val s = if (sec.isEmpty()) 0.0 else sec.replace(',', '.').toDoubleOrNull()?.takeIf { it < SECONDS } ?: return null
        val value = d + m / MINUTES + s / (MINUTES * SECONDS)
        return if (compass.uppercase() in setOf("S", "W", "V")) -value else value
    }

    private fun point(lat: String, lon: String): GeoPoint? {
        val a = lat.toDoubleOrNull() ?: return null
        val b = lon.toDoubleOrNull() ?: return null
        return point(a, b)
    }

    private fun point(lat: Double, lon: Double): GeoPoint? {
        val inRange = lat in -90.0..90.0 && lon in -180.0..180.0
        // 0,0 is what a map app writes when it has no place (Android's geo:0,0?q=…).
        val nullIsland = lat == 0.0 && lon == 0.0
        return if (inRange && !nullIsland) GeoPoint(lat, lon) else null
    }

    /** Percent-decoding of the few characters a map link escapes around coordinates. */
    private fun decode(value: String): String = value
        .replace("%2C", ",", ignoreCase = true)
        .replace("%20", " ")
        .replace("+", " ")
        .replace("%28", "(")
        .replace("%29", ")")

    private val WEST = setOf("W", "V")
    private const val MINUTES = 60.0
    private const val SECONDS = 60.0
}
