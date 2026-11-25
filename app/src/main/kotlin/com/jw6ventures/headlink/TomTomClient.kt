package com.jw6ventures.headlink

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Live traffic (P9, docs/navigation.md, "Live traffic (HERE)"): TomTom's Orbis traffic API
 * beside HERE's. Flow comes as vector tiles (TomTomTiles), incidents as JSON (TomTomJson);
 * both are read into the same TrafficModel types HERE's are, so everything after the
 * fetch is the same for either provider.
 */

/** TomTom's incident and error bodies. */
object TomTomJson {
    /**
     * An Incident Details response's `incidents[]`. Geometry is a GeoJSON Point
     * (`[lon, lat]`) or LineString (`[[lon, lat], …]`); an incident without a point is
     * dropped, as is an entry that is not an object. A body with no `incidents` array at
     * its root is not TomTom's answer, and throws, so Failover asks the other provider.
     */
    @Throws(IOException::class)
    fun incidents(input: Reader): List<TrafficIncident> {
        val json = JsonStream(input, "TomTom's JSON")
        val incidents = ArrayList<TrafficIncident>()
        var found = false
        json.forEachField { name ->
            if (name == "incidents" && json.peek() == JsonStream.Token.BEGIN_ARRAY) {
                found = true
                json.forEachItem { incident(json)?.let(incidents::add) }
            } else {
                json.skipValue()
            }
        }
        if (!found) throw TrafficParseException("not TomTom's JSON: no incidents array")
        return incidents
    }

    /**
     * An error body in words for the card's status line, or null when it is not TomTom's:
     * `detailedError.message` with its code, else a top-level `error`, `errorText` or
     * `message`. Names are read trimmed: TomTom's own example spells `"detailedError "`.
     */
    fun error(body: String): String? {
        var detailed: String? = null
        var code: String? = null
        val top = HashMap<String, String>()
        try {
            val json = JsonStream(body.reader(), "TomTom's JSON")
            json.forEachField { raw ->
                when (val name = raw.trim()) {
                    "detailedError" -> json.forEachField { field ->
                        when (field.trim()) {
                            "message" -> detailed = json.optString()
                            "code" -> code = json.optString()
                            else -> json.skipValue()
                        }
                    }
                    "error", "errorText", "message" -> json.optString()?.let { top[name] = it }
                    else -> json.skipValue()
                }
            }
        } catch (e: IOException) {
            return null
        }
        detailed?.takeIf { it.isNotBlank() }?.let { message ->
            return message + (code?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        }
        return top["error"] ?: top["errorText"] ?: top["message"]
    }

    private fun incident(json: JsonStream): TrafficIncident? {
        val lats = Points()
        val lons = Points()
        var id: String? = null
        var iconCategory: String? = null
        var magnitude: String? = null
        var summary: String? = null
        json.forEachField { name ->
            when (name) {
                "geometry" -> json.forEachField { field ->
                    if (field == "coordinates") coordinates(json, lats, lons) else json.skipValue()
                }
                "properties" -> json.forEachField { field ->
                    when (field) {
                        "id" -> id = json.optString()
                        // optString: a legacy numeric category reads as its digits.
                        "iconCategory" -> iconCategory = json.optString()
                        "magnitudeOfDelay" -> magnitude = json.optString()
                        "events" -> json.forEachItem {
                            json.forEachField { event ->
                                if (event == "description") {
                                    val text = json.optString()
                                    if (summary == null && !text.isNullOrBlank()) summary = text
                                } else {
                                    json.skipValue()
                                }
                            }
                        }
                        else -> json.skipValue()
                    }
                }
                else -> json.skipValue()
            }
        }
        if (lats.size == 0) return null
        return TrafficIncident(
            id = id ?: "",
            type = iconCategory ?: "unknown",
            criticality = magnitude,
            roadClosed = iconCategory == "roadClosed",
            summary = summary,
            latitudes = lats.toArray(),
            longitudes = lons.toArray(),
        )
    }

    /** `coordinates`: a Point's one position, or a LineString's positions in order. */
    private fun coordinates(json: JsonStream, lats: Points, lons: Points) {
        if (json.peek() != JsonStream.Token.BEGIN_ARRAY) { json.skipValue(); return }
        json.beginArray()
        if (json.hasNext() && json.peek() == JsonStream.Token.BEGIN_ARRAY) {
            while (json.hasNext()) {
                if (json.peek() != JsonStream.Token.BEGIN_ARRAY) { json.skipValue(); continue }
                json.beginArray()
                position(json, lats, lons)
                json.endArray()
            }
        } else {
            position(json, lats, lons)
        }
        json.endArray()
    }

    /** The rest of an open position array, `[lon, lat, …]`; anything past the two is skipped. */
    private fun position(json: JsonStream, lats: Points, lons: Points) {
        var lon: Double? = null
        var lat: Double? = null
        var index = 0
        while (json.hasNext()) {
            when (index++) {
                0 -> lon = json.optDouble()
                1 -> lat = json.optDouble()
                else -> json.skipValue()
            }
        }
        val a = lat ?: return
        val b = lon ?: return
        // A point off the globe is dropped, not the whole incident, as in TrafficJson.
        if (a !in -90.0..90.0 || b !in -180.0..180.0) return
        if (lats.size > 0 && lats.last() == a && lons.last() == b) return
        lats.add(a)
        lons.add(b)
    }

    /** A growable array of doubles, so a long incident is not boxed point by point. */
    private class Points {
        private var values = DoubleArray(16)
        var size = 0
            private set

        fun add(value: Double) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun last(): Double = values[size - 1]

        fun toArray(): DoubleArray = values.copyOf(size)
    }
}

/**
 * TomTom's side of live traffic: the gate, the counts, the flow tiles and incident requests,
 * and the parsing, as [TrafficClient] is HERE's.
 *
 * A fetch is every z[FLOW_ZOOM] flow tile the box touches, then one incidents request per
 * area of at most [MAX_INCIDENT_KM2] the box splits into. Tiles and incident requests are
 * counted apart, in [tileLedger] against the fixed [TILE_LIMIT] and in [incidentLedger] against
 * the card's limit, since TomTom's free allowances for them differ; errors, the last fetch
 * and the card's Status are [incidentLedger]'s. Nothing is sent unless the whole fetch fits
 * under both.
 *
 * **Threading:** as [TrafficClient]: every call blocks, off the main thread only; the counts'
 * check and increment are each one atomic [TrafficLedger.update].
 *
 * The key goes only in the `TomTom-Api-Key` header, never in a URL, to TomTom's host (or,
 * in a debuggable build only, the lab's stand-in). It is never in a message this returns.
 */
class TomTomClient(
    private val settings: () -> TrafficSettings,
    private val incidentLedger: TrafficLedger,
    private val tileLedger: TrafficLedger,
    http: TrafficHttp,
    /** `SystemClock.elapsedRealtime` in the app; stamped on each snapshot. */
    private val elapsed: () -> Long,
    /** Wall clock, for the month counted and the last fetch time. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Whether [TrafficSettings.baseUrl] is honoured: a debuggable build only. */
    private val debuggable: Boolean = false,
    zone: ZoneId = ZoneOffset.UTC,
) : TrafficProviderClient {
    override val provider: TrafficProvider = TrafficProvider.TOMTOM

    private val counted = CountedGet(TrafficProvider.TOMTOM, http, now, zone, TomTomJson::error)

    /**
     * Flow and incidents for [box]: the tiles first, then the incident areas, each request
     * counted before it is sent. When one fails, nothing after it is asked for.
     */
    override fun fetch(box: TrafficBox): TrafficFetch {
        val raw = settings()
        val tiles = TomTomTiles.covering(box, FLOW_ZOOM)
        val areas = incidentBoxes(box)
        gate(raw, areas.size, tiles.size)?.let { return counted.skipped(it) }
        val s = raw.repaired()
        val base = base(s)
        val key = s.tomTomKey
        val tileHeaders = headers(key, TILE_TYPE, FLOW_ATTRIBUTES)
        val flow = ArrayList<FlowStretch>()
        for ((i, tile) in tiles.withIndex()) {
            val got = counted.get(
                url = "$base$TILE_PATH/${tile.zoom}/${tile.x}/${tile.y}",
                headers = tileHeaders,
                ledger = tileLedger,
                limit = TILE_LIMIT,
                need = tiles.size - i,
                key = key,
                // Settings as they are now: traffic turned off, the key removed or the
                // limit lowered while a tile was out sends nothing after it.
                regate = { gate(settings(), areas.size, tiles.size - i) },
                record = incidentLedger,
            ) { TomTomTiles.flow(readAll(it, MAX_TILE_BYTES), tile, box) }
            when (got) {
                is Outcome.Ok -> flow.addAll(got.value)
                is Outcome.Stopped -> return got.result
            }
        }
        val incidentHeaders = headers(key, JSON_TYPE, INCIDENT_ATTRIBUTES)
        val incidents = ArrayList<TrafficIncident>()
        for ((j, area) in areas.withIndex()) {
            val got = counted.get(
                url = incidentsUrl(base, area),
                headers = incidentHeaders,
                ledger = incidentLedger,
                limit = s.tomTomLimit,
                need = areas.size - j,
                key = key,
                regate = { gate(settings(), areas.size - j, 0) },
            ) { TomTomJson.incidents(InputStreamReader(it, Charsets.UTF_8)) }
            when (got) {
                is Outcome.Ok -> incidents.addAll(got.value)
                is Outcome.Stopped -> return got.result
            }
        }
        incidentLedger.update { it.copy(lastFetchMs = now(), lastError = null) }
        return TrafficFetch.Fetched(TrafficSnapshot(box, elapsed(), flow, incidents, setOf(TrafficProvider.TOMTOM)))
    }

    /**
     * The card's **Test**: one incidents request for [TrafficClient.TEST_BOX], read whole.
     * Counted and gated like any other, so it too sends nothing while the switch is off.
     */
    override fun test(): TrafficCheck {
        val raw = settings()
        gate(raw, 1, 0)?.let { return TrafficCheck(false, counted.skipped(it).message) }
        val s = raw.repaired()
        val got = counted.get(
            url = incidentsUrl(base(s), TrafficClient.TEST_BOX),
            headers = headers(s.tomTomKey, JSON_TYPE, INCIDENT_ATTRIBUTES),
            ledger = incidentLedger,
            limit = s.tomTomLimit,
            need = 1,
            key = s.tomTomKey,
            regate = { gate(settings(), 1, 0) },
        ) { TomTomJson.incidents(InputStreamReader(it, Charsets.UTF_8)) }
        return when (got) {
            is Outcome.Ok -> TrafficCheck(true, "works")
            is Outcome.Stopped -> TrafficCheck(false, (got.result as? TrafficFetch.Failed)?.message
                ?: (got.result as TrafficFetch.Skipped).message)
        }
    }

    /**
     * Why a call needing [incidentNeed] incident requests and [tileNeed] tiles would send
     * nothing now, or null when it may go ahead.
     */
    private fun gate(s: TrafficSettings, incidentNeed: Int, tileNeed: Int): TrafficSkip? = when {
        !s.enabled -> TrafficSkip.OFF
        // Switched away from TomTom, or its terms not accepted: as good as off for TomTom.
        TrafficProvider.TOMTOM !in s.selection.order || !s.tomTomDisclosed -> TrafficSkip.OFF
        TrafficSettings.cleanKey(s.tomTomKey) == null -> TrafficSkip.NO_KEY
        incidentLedger.status().reserve(counted.month(), s.repaired().tomTomLimit, 0, incidentNeed) == null -> TrafficSkip.LIMIT
        tileNeed > 0 && tileLedger.status().reserve(counted.month(), TILE_LIMIT, 0, tileNeed) == null -> TrafficSkip.LIMIT
        else -> null
    }

    /** TomTom's host, or in a debuggable build the lab's stand-in, as [TrafficClient.labBase] allows. */
    private fun base(s: TrafficSettings): String = TrafficClient.labBase(s, debuggable) ?: TOMTOM_BASE

    companion object {
        const val TOMTOM_BASE = "https://api.tomtom.com"
        const val TILE_PATH = "/maps/orbis/traffic/flow/vector/tile"
        const val INCIDENTS_PATH = "/maps/orbis/traffic/incidents/details"
        const val API_VERSION = "2"
        /** About 2 m to a tile unit: enough for arterials, and 15 to 40 tiles for a 30 km box. */
        const val FLOW_ZOOM = 12
        /** Tiles a month: a fixed cap under TomTom's free 200,000, not on the card. */
        const val TILE_LIMIT = 180_000
        /** The largest incident area asked for, km², under TomTom's 10,000. */
        const val MAX_INCIDENT_KM2 = 9_000.0
        const val MAX_TILE_BYTES = 8 * 1024 * 1024

        /** The flow tags read, and the roads asked for: the route's classes 1 to 4. */
        const val FLOW_ATTRIBUTES =
            "tags(road_category,relative_speed,absolute_speed,road_closure)," +
                "roadCategories(motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary,secondary_link)"

        /** The incident fields read; TomTom leaves the rest out. */
        const val INCIDENT_ATTRIBUTES =
            "incidents(type,geometry(type,coordinates),properties(id,iconCategory,magnitudeOfDelay,events(description)))"

        private const val TILE_TYPE = "application/vnd.mapbox-vector-tile"
        private const val JSON_TYPE = "application/json"
        private const val KM_PER_DEGREE = 111.195

        /**
         * [box] as incident areas of at most [MAX_INCIDENT_KM2]: itself when small enough,
         * else a k×k grid of equal cells, rows south to north, each row west to east.
         */
        fun incidentBoxes(box: TrafficBox): List<TrafficBox> {
            val area = areaKm2(box)
            if (!(area > MAX_INCIDENT_KM2)) return listOf(box)
            val k = ceil(sqrt(area / MAX_INCIDENT_KM2)).toInt()
            val dLat = (box.north - box.south) / k
            val dLon = (box.east - box.west) / k
            val cells = ArrayList<TrafficBox>(k * k)
            for (row in 0 until k) {
                val south = box.south + row * dLat
                val north = if (row == k - 1) box.north else box.south + (row + 1) * dLat
                for (column in 0 until k) {
                    val west = box.west + column * dLon
                    val east = if (column == k - 1) box.east else box.west + (column + 1) * dLon
                    cells.add(TrafficBox(south = south, west = west, north = north, east = east))
                }
            }
            return cells
        }

        /** TomTom's `bbox` value: west, south, east, north. */
        fun query(box: TrafficBox): String =
            "%.5f,%.5f,%.5f,%.5f".format(Locale.ROOT, box.west, box.south, box.east, box.north)

        /**
         * [box]'s area, km², taking the width at its latitude nearest the equator, so the
         * figure is never under TomTom's own.
         */
        private fun areaKm2(box: TrafficBox): Double {
            val equatorward = if (box.south <= 0.0 && box.north >= 0.0) 0.0 else min(abs(box.south), abs(box.north))
            return (box.north - box.south) * KM_PER_DEGREE *
                (box.east - box.west) * KM_PER_DEGREE * cos(Math.toRadians(equatorward))
        }

        private fun incidentsUrl(base: String, area: TrafficBox): String =
            "$base$INCIDENTS_PATH?bbox=${query(area)}&timeValidity=present"

        /** The key only here, in a header: never in a URL. */
        private fun headers(key: String, accept: String, attributes: String): Map<String, String> = mapOf(
            "Accept-Encoding" to "gzip",
            "Accept" to accept,
            "TomTom-Api-Key" to key,
            "TomTom-Api-Version" to API_VERSION,
            "Attributes" to attributes,
        )

        /** [input] whole, or a [TrafficParseException] past [limit] bytes. */
        private fun readAll(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (out.size() + n > limit) throw TrafficParseException("tile larger than 8 MB")
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        }
    }
}
