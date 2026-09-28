package org.openautoandroid.source

import java.net.URLDecoder

/**
 * Where a destination came from. The label is the only part of a destination the
 * report and the event log ever carry (P6 plan §5.1, §9.6).
 */
enum class DestinationSource(val label: String) {
    /** `ACTION_SEND` text: the ordinary share sheet, OsmAnd's "Message" item. */
    SEND("send"),

    /** `ACTION_VIEW geo:`: OsmAnd's "Geo" item, Contacts, Calendar. */
    VIEW("view"),

    /** Typed on the connection screen. */
    TYPED("typed"),

    /** A long press on the projected map. */
    MAP("map"),

    /** The emulator lab's debug intent. */
    LAB("lab"),

    /** A tap on the dashboard's favorites tile: a place saved on the connection screen. */
    FAVORITE("favorite"),
}

/**
 * A point to route to, in degrees, and what the sender called it.
 *
 * The [label] is often a street address — OsmAnd puts the searched address there — so it
 * may be shown on the phone and nowhere else: never the report, never the event log.
 */
class Destination(val latitude: Double, val longitude: Double, val label: String? = null)

/** What reading a shared location came to. */
sealed class ParsedDestination {
    class Found(val destination: Destination) : ParsedDestination()

    class Refused(val reason: Refusal) : ParsedDestination()
}

/** Why a share was not taken. [label] goes in the report; nothing else about it does. */
enum class Refusal(val label: String) {
    /**
     * A place named by text only — `geo:0,0?q=1600 Main St` from Contacts or Calendar, or
     * a search around a point. Turning it into a coordinate needs geocoding this app does
     * not have, and a route to a misread address is worse than none.
     */
    ADDRESS_ONLY("address-only"),

    /** Nothing in it reads as a coordinate. */
    NO_LOCATION("no-location"),

    /**
     * A coordinate no installed routing tile covers. BRouter would only fail on it after
     * spinning up, and the phone can say which tile to import instead.
     */
    NO_ROUTING_DATA("no-routing-data"),
}

/**
 * Reads a destination out of what another app shares: a `geo:` URI (RFC 5870, plus
 * Android's `q=lat,lon(label)` form), an `osmand.net` link, or a bare `lat,lon`.
 *
 * Pure JVM, so tools/jni-host-test holds it to OsmAnd's exact strings. Written against
 * OsmAnd's source (`ShareMenu.java`, `GeoParsedPoint.java`, 21 September 2026), not yet
 * against a share seen on the Pixel.
 *
 * Refuses rather than guesses, the rule [RouteRequest.parse] already follows: anything
 * that is not plainly one coordinate pair is [Refusal.NO_LOCATION] or
 * [Refusal.ADDRESS_ONLY], never a nearby reading of it.
 */
object DestinationParser {
    private const val NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)"

    /** `lat,lon` with an optional `(label)`, the whole of a `q=` value. */
    private val QUERY_POINT = Regex("^\\s*($NUMBER)\\s*,\\s*($NUMBER)\\s*(?:\\((.*)\\))?\\s*$", RegexOption.DOT_MATCHES_ALL)

    /** A `geo:` URI running to the end of its line; OsmAnd puts its link on the next one. */
    private val GEO_IN_TEXT = Regex("geo:[^\\r\\n]*", RegexOption.IGNORE_CASE)

    private val OSMAND_LINK = Regex("https?://(?:www\\.)?osmand\\.net/(?:map|go)[^\\s]*", RegexOption.IGNORE_CASE)

    /**
     * A bare pair, both halves with a decimal point: "30.26720, -97.74310". The decimal
     * point is required so that "1,2" in the middle of a sentence, or a phone number
     * broken by a comma, is not a place.
     */
    private val BARE_PAIR = Regex(
        "(?<![\\w.])([+-]?\\d{1,2}\\.\\d+)\\s*,\\s*([+-]?\\d{1,3}\\.\\d+)(?![\\w.])",
    )

    /** Longest label kept for the phone's own screen. */
    private const val LABEL_MAX = 200

    /**
     * An `ACTION_VIEW` data URI. Anything that is not a `geo:` URI is
     * [Refusal.NO_LOCATION]: the manifest only asks for that scheme.
     */
    fun parseUri(uri: String): ParsedDestination =
        parseGeo(uri.trim()) ?: ParsedDestination.Refused(Refusal.NO_LOCATION)

    /**
     * `ACTION_SEND` text, or what was typed: a `geo:` URI anywhere in it, else an OsmAnd
     * link's pin, else exactly one bare `lat,lon`.
     *
     * A `geo:` that names only an address does not end the search — OsmAnd may put a
     * usable pin on the next line — but if nothing else is found it is refused as an
     * address rather than as nothing.
     */
    fun parseText(text: String): ParsedDestination {
        var refusal = Refusal.NO_LOCATION
        GEO_IN_TEXT.find(text)?.let { match ->
            when (val geo = parseGeo(match.value.trim())) {
                is ParsedDestination.Found -> return geo
                is ParsedDestination.Refused -> if (geo.reason == Refusal.ADDRESS_ONLY) refusal = geo.reason
                null -> Unit
            }
        }
        OSMAND_LINK.find(text)?.let { match -> parseOsmAnd(match.value)?.let { return ParsedDestination.Found(it) } }
        val pairs = BARE_PAIR.findAll(text).toList()
        if (pairs.size == 1) {
            val (lat, lon) = pairs[0].destructured
            point(lat.toDouble(), lon.toDouble(), null)?.let { return ParsedDestination.Found(it) }
        }
        return ParsedDestination.Refused(refusal)
    }

    /**
     * A `geo:` URI, or null when [uri] is not one.
     *
     * `q=lat,lon(label)` wins over the path, which is how Android documents a labelled
     * pin and what OsmAnd sends (the two are the same point there). A `q=` that is text
     * is a search — for an address with `geo:0,0`, around the path's point otherwise — and
     * either way the destination is not a coordinate this URI carries.
     */
    private fun parseGeo(uri: String): ParsedDestination? {
        if (!uri.startsWith("geo:", ignoreCase = true)) return null
        val body = uri.substring(4)
        val path = body.substringBefore('?')
        val query = if ('?' in body) body.substringAfter('?') else ""

        val q = query.split('&')
            .firstOrNull { it.startsWith("q=", ignoreCase = true) }
            ?.substring(2)
            ?.let(::decode)
        if (q != null && q.isNotBlank()) {
            val match = QUERY_POINT.matchEntire(q) ?: return ParsedDestination.Refused(Refusal.ADDRESS_ONLY)
            val (lat, lon, label) = match.destructured
            return point(lat.toDouble(), lon.toDouble(), label)
                ?.let { ParsedDestination.Found(it) }
                ?: ParsedDestination.Refused(Refusal.NO_LOCATION)
        }

        // RFC 5870: `lat,lon[,alt][;crs=…][;u=…]`. A label in brackets after the path is
        // not in the RFC, but costs nothing to read.
        val decoded = decode(path.substringBefore(';'))
        val label = decoded.substringAfter('(', "").substringBeforeLast(')', "").ifEmpty { null }
        val parts = decoded.substringBefore('(').split(',').map { it.trim() }
        if (parts.size !in 2..3) return ParsedDestination.Refused(Refusal.NO_LOCATION)
        val lat = parts[0].toDoubleStrict() ?: return ParsedDestination.Refused(Refusal.NO_LOCATION)
        val lon = parts[1].toDoubleStrict() ?: return ParsedDestination.Refused(Refusal.NO_LOCATION)
        return point(lat, lon, label)?.let { ParsedDestination.Found(it) }
            ?: ParsedDestination.Refused(Refusal.NO_LOCATION)
    }

    /**
     * An OsmAnd link's point: `?pin=lat,lon`, or the older `/go?lat=…&lon=…`.
     *
     * The `#zoom/lat/lon` fragment is deliberately not read. It is where the map was
     * looking, which is the pin only when the sender happened to centre on it.
     */
    private fun parseOsmAnd(link: String): Destination? {
        val query = link.substringAfter('?', "").substringBefore('#')
        val params = query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=', "")
            if (key.isEmpty()) null else key.lowercase() to decode(pair.substringAfter('='))
        }.toMap()
        params["pin"]?.let { pin ->
            val parts = pin.split(',')
            if (parts.size == 2) {
                val lat = parts[0].trim().toDoubleStrict() ?: return null
                val lon = parts[1].trim().toDoubleStrict() ?: return null
                return point(lat, lon, null)
            }
            return null
        }
        val lat = params["lat"]?.toDoubleStrict() ?: return null
        val lon = params["lon"]?.toDoubleStrict() ?: return null
        return point(lat, lon, null)
    }

    /**
     * A destination, or null when the numbers cannot be one. `0,0` is refused too: it is
     * what an app sends when it has no coordinate, and a route to the Gulf of Guinea is a
     * misreading, not a destination.
     */
    private fun point(lat: Double, lon: Double, label: String?): Destination? {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return Destination(lat, lon, cleanLabel(label))
    }

    /** Printable, trimmed and bounded, or null when nothing is left. */
    private fun cleanLabel(label: String?): String? {
        val cleaned = label?.filter { !it.isISOControl() }?.trim()?.take(LABEL_MAX)
        return cleaned?.ifEmpty { null }
    }

    /**
     * Percent-decoding, with `+` as a space the way `URLEncoder` writes one. A malformed
     * escape leaves the text as it was rather than refusing the whole share.
     */
    private fun decode(text: String): String =
        try {
            URLDecoder.decode(text, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            text
        }

    /** A plain decimal — not `NaN`, `Infinity` or a hex float, which `toDouble` takes. */
    private fun String.toDoubleStrict(): Double? =
        if (Regex(NUMBER).matches(this)) toDoubleOrNull() else null

    /**
     * The longitude to suggest when [lon] lands in no installed routing tile but its
     * mirror does, or null.
     *
     * A dropped minus sign is the commonest typing mistake with western coordinates, and
     * it puts Austin in a Chinese desert where there is no routing data; the P6.3a lab run
     * made exactly this mistake. Only a suggestion: the operator decides.
     */
    fun mirroredLongitude(lat: Double, lon: Double, installedTiles: Set<String>): Double? {
        if (lon == 0.0 || RoutingData.tileFor(lat, lon) in installedTiles) return null
        return if (RoutingData.tileFor(lat, -lon) in installedTiles) -lon else null
    }
}
