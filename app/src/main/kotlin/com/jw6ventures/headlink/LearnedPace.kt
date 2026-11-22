package com.jw6ventures.headlink

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.exp
import kotlin.math.floor

/**
 * The kind of road a stretch of route is, for the learned pace ([PaceBook]): its class from
 * the map's `highway` tag and how built up its surroundings are, packed in one byte as
 * `road * 4 + density`. Only OsmAnd's routes carry these ([OsmAndRouter.toRoute]); a
 * BRouter route has none, and learns and is timed by the overall pace alone.
 */
object PaceRoad {
    const val UNKNOWN = 0
    /** Motorway and trunk: interstates, tollways, US highways built to that standard. */
    const val HIGHWAY = 1
    const val MAJOR = 2
    /** Secondary and tertiary: Texas FM and RM roads, county roads. */
    const val MINOR = 3
    /** Residential, unclassified, service and the like. */
    const val LOCAL = 4
    /** On and off ramps: any `*_link`. */
    const val RAMP = 5

    const val DENSITY_UNKNOWN = 0
    const val URBAN = 1
    const val SUBURBAN = 2
    const val RURAL = 3

    /**
     * Junctions a kilometre at and above which a stretch is [URBAN] and [SUBURBAN]. A
     * junction is a point where another road (not a service road or track) joins. A city
     * grid has one every 100 to 150 m; a suburban arterial one every 200 to 300 m; a rural
     * FM road one every kilometre or more.
     */
    const val URBAN_JUNCTIONS_PER_KM = 7.0
    const val SUBURBAN_JUNCTIONS_PER_KM = 2.5

    /** Each side of a point, metres, over which its junctions are counted. */
    const val DENSITY_HALF_WINDOW_M = 500.0

    /**
     * Grid squares a degree, each way: a quarter degree is 17 miles north to south and
     * about 15 east to west at Austin's latitude.
     */
    const val CELLS_PER_DEGREE = 4

    fun code(road: Int, density: Int): Byte = (road * 4 + density).toByte()
    fun road(code: Byte): Int = code.toInt() / 4
    fun density(code: Byte): Int = code.toInt() % 4
    fun valid(code: Byte): Boolean = code.toInt() in 0..(RAMP * 4 + RURAL)

    /** The class of a road with OsmAnd's `highway` value [highway]. */
    fun roadOf(highway: String?): Int = when (highway) {
        null -> UNKNOWN
        "motorway", "trunk" -> HIGHWAY
        "primary" -> MAJOR
        "secondary", "tertiary" -> MINOR
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link" -> RAMP
        "residential", "unclassified", "service", "living_street", "road", "track" -> LOCAL
        else -> UNKNOWN
    }

    /** Whether a road with [highway] joining the route counts as a junction for [densityOf]. */
    fun countsAsJunction(highway: String?): Boolean =
        highway != null && highway != "service" && highway != "track" && roadOf(highway) != UNKNOWN

    fun densityOf(junctionsPerKm: Double): Int = when {
        junctionsPerKm.isNaN() -> DENSITY_UNKNOWN
        junctionsPerKm >= URBAN_JUNCTIONS_PER_KM -> URBAN
        junctionsPerKm >= SUBURBAN_JUNCTIONS_PER_KM -> SUBURBAN
        else -> RURAL
    }

    /**
     * Each point's [densityOf] from [junction] (whether a road joins there) over
     * [DENSITY_HALF_WINDOW_M] either side, measured along [alongM]. Pure, for the host test.
     */
    fun densities(alongM: DoubleArray, junction: BooleanArray): IntArray {
        val n = alongM.size
        val out = IntArray(n)
        var lo = 0
        var hi = 0
        var count = 0
        for (i in 0 until n) {
            while (hi < n && alongM[hi] <= alongM[i] + DENSITY_HALF_WINDOW_M) {
                if (junction[hi]) count++
                hi++
            }
            while (alongM[lo] < alongM[i] - DENSITY_HALF_WINDOW_M) {
                if (junction[lo]) count--
                lo++
            }
            val span = alongM[hi - 1] - alongM[lo]
            // A route shorter than a few hundred metres says nothing about its surroundings.
            out[i] = if (span < 300.0) DENSITY_UNKNOWN else densityOf(count * 1000.0 / span)
        }
        return out
    }

    /** The grid square holding a point, as a key; stored only on the phone. */
    fun cell(latitude: Double, longitude: Double): String =
        "${floor(latitude * CELLS_PER_DEGREE).toInt()},${floor(longitude * CELLS_PER_DEGREE).toInt()}"
}

/**
 * The driver's pace learned across drives (Routing → Learn my pace), kept apart by road
 * class, by how built up the road's surroundings are, and by 15 mile grid square.
 *
 * **Buckets.** Each counted second of driving ([PaceTracker]) is credited to four buckets:
 * all driving, the road class, the class in its surroundings (urban, suburban or rural),
 * and the class in its grid square. Each holds the router's estimate of the time driven
 * and the time it really took.
 *
 * **Fading.** Older driving fades: each time a bucket is credited, what it held is
 * multiplied by `exp(-seconds / FADE_S)`, so it reflects roughly the last [FADE_S] of
 * driving in it.
 *
 * **Use.** A stretch's factor starts at the Routing screen's time adjustment and is pulled
 * towards each bucket's ratio in turn, all → class → surroundings → grid square, each by a
 * weight that is 0 under [MIN_S] of estimated driving and grows to 1 at [FULL_S]. So a grid
 * square you drive often has the last word, and a road you have never driven in a place you
 * have never been still gets your pace on that class of road. Each ratio is held to
 * [PaceTracker.MIN_FACTOR]..[PaceTracker.MAX_FACTOR].
 *
 * Pure and single-threaded (the main thread); [LearnedPace] keeps it in a file.
 */
class PaceBook {
    private class Sums(var estimatedS: Double, var actualS: Double)

    private val buckets = HashMap<String, Sums>()

    /** Changed since [encode] last ran. */
    var dirty = false
        internal set

    val size: Int get() = buckets.size

    /** Estimated seconds of driving in all buckets, faded: how much has been learned. */
    val learnedS: Double get() = buckets[ALL]?.estimatedS ?: 0.0

    /**
     * [estimatedS] of the router's own time (no time adjustment in it) took [actualS] on
     * a stretch with [code] (null when not known) starting at ([latitude], [longitude]).
     */
    fun add(code: Byte?, latitude: Double, longitude: Double, estimatedS: Double, actualS: Double) {
        if (!(estimatedS.isFinite() && actualS.isFinite()) || actualS <= 0.0) return
        for (key in keys(code, latitude, longitude)) {
            val sums = buckets.getOrPut(key) { Sums(0.0, 0.0) }
            val keep = exp(-actualS / FADE_S)
            sums.estimatedS = sums.estimatedS * keep + estimatedS
            sums.actualS = sums.actualS * keep + actualS
        }
        dirty = true
    }

    /**
     * The factor on the router's own time for a stretch with [code] at ([latitude],
     * [longitude]): [base] (the time adjustment) with nothing learned.
     */
    fun factor(code: Byte?, latitude: Double, longitude: Double, base: Double): Double {
        var f = blend(base, ALL)
        val road = code?.let(PaceRoad::road) ?: PaceRoad.UNKNOWN
        if (road == PaceRoad.UNKNOWN) return f
        f = blend(f, classKey(road))
        val density = PaceRoad.density(code!!)
        if (density != PaceRoad.DENSITY_UNKNOWN) f = blend(f, densityKey(road, density))
        return blend(f, gridKey(road, latitude, longitude))
    }

    /**
     * The seconds from the start to each point of [route] at the learned pace, or null
     * when the route has no point times. Factors are worked out once per kind of stretch.
     */
    fun times(route: Route): FloatArray? {
        val t = route.pointSeconds
        val n = route.points
        if (t.size != n || n < 2) return null
        val scale = route.timeScale.takeIf { it > 0.0 } ?: 1.0
        val cache = HashMap<String, Double>()
        val out = FloatArray(n)
        var clock = 0.0
        for (i in 0 until n - 1) {
            val code = route.paceClassAt(i)
            val lat = route.latitudes[i]
            val lon = route.longitudes[i]
            val key = if (code == null || PaceRoad.road(code) == PaceRoad.UNKNOWN) "-" else "$code|${PaceRoad.cell(lat, lon)}"
            val f = cache.getOrPut(key) { factor(code, lat, lon, scale) }
            clock += (t[i + 1] - t[i]) / scale * f
            out[i + 1] = clock.toFloat()
        }
        return out
    }

    /** Everything learned forgotten. */
    fun clear() {
        if (buckets.isNotEmpty()) dirty = true
        buckets.clear()
    }

    /** The book as text, one bucket a line, the least driven grid squares dropped past [MAX_BUCKETS]. */
    fun encode(): String {
        if (buckets.size > MAX_BUCKETS) {
            val drop = buckets.entries.filter { it.key.startsWith("g") }.sortedBy { it.value.estimatedS }
                .take(buckets.size - MAX_BUCKETS).map { it.key }
            drop.forEach { buckets.remove(it) }
        }
        dirty = false
        return buildString {
            append(HEADER).append('\n')
            for ((key, sums) in buckets.toSortedMap()) {
                append(key).append(' ')
                    .append(String.format(Locale.ROOT, "%.1f %.1f", sums.estimatedS, sums.actualS)).append('\n')
            }
        }
    }

    /** [text] from [encode] read in, replacing what was held; a line that does not parse is skipped. */
    fun decode(text: String) {
        buckets.clear()
        dirty = false
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next().trim() != HEADER) return
        for (line in lines) {
            val parts = line.trim().split(' ')
            if (parts.size != 3 || parts[0].isEmpty() || parts[0].length > 40) continue
            val est = parts[1].toDoubleOrNull() ?: continue
            val act = parts[2].toDoubleOrNull() ?: continue
            if (!(est >= 0.0 && act >= 0.0 && est < 1e9 && act < 1e9)) continue
            buckets[parts[0]] = Sums(est, act)
        }
    }

    /** One line for the report: how much is learned and the overall factor, nothing about any place. */
    fun report(): String {
        val grid = buckets.keys.count { it.startsWith("g") }
        return "pace_learned_s=${learnedS.toInt()} pace_learned_buckets=${buckets.size - grid} " +
            "pace_learned_squares=$grid pace_learned_overall=${String.format(Locale.ROOT, "%.3f", blend(1.0, ALL))}"
    }

    private fun blend(prior: Double, key: String): Double {
        val sums = buckets[key] ?: return prior
        if (sums.estimatedS < MIN_S) return prior
        val ratio = (sums.actualS / sums.estimatedS).coerceIn(PaceTracker.MIN_FACTOR, PaceTracker.MAX_FACTOR)
        val weight = (sums.estimatedS / FULL_S).coerceAtMost(1.0)
        return prior + weight * (ratio - prior)
    }

    private fun keys(code: Byte?, latitude: Double, longitude: Double): List<String> {
        val road = code?.let(PaceRoad::road) ?: PaceRoad.UNKNOWN
        if (road == PaceRoad.UNKNOWN) return listOf(ALL)
        val density = PaceRoad.density(code!!)
        return listOfNotNull(
            ALL,
            classKey(road),
            densityKey(road, density).takeIf { density != PaceRoad.DENSITY_UNKNOWN },
            gridKey(road, latitude, longitude),
        )
    }

    private fun classKey(road: Int) = "c$road"
    private fun densityKey(road: Int, density: Int) = "d$road,$density"
    private fun gridKey(road: Int, latitude: Double, longitude: Double) = "g$road,${PaceRoad.cell(latitude, longitude)}"

    companion object {
        /** Five hours of driving in a bucket. */
        const val FADE_S = 5 * 3600.0
        /** Ten minutes of driving before a bucket counts at all. */
        const val MIN_S = 600.0
        /** An hour of driving before it counts in full. */
        const val FULL_S = 3600.0
        const val MAX_BUCKETS = 4000
        private const val ALL = "all"
        private const val HEADER = "headlink-learned-pace 1"
    }
}

/**
 * [PaceBook] kept in the app's private files (`learned_pace.txt`), which no backup or
 * device transfer takes (data_extraction_rules.xml; `allowBackup` is off). Main thread.
 */
object LearnedPace {
    val book = PaceBook()
    private var file: File? = null

    /** Reads the file once; later calls do nothing. */
    fun load(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        try {
            if (f.isFile && f.length() < MAX_FILE_BYTES) book.decode(f.readText())
        } catch (e: IOException) {
            book.clear()
        }
    }

    /** Writes what changed since the last save, through a temporary file so a crash leaves the old one. */
    fun save() {
        val f = file ?: return
        if (!book.dirty) return
        try {
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(book.encode())
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: IOException) {
            // Kept in memory; the next save tries again.
            book.dirty = true
        }
    }

    /** The Routing screen's Forget learned pace: the file and what is held both go. */
    fun forget(context: Context) {
        load(context)
        book.clear()
        file?.delete()
        book.encode()
    }

    private const val FILE_NAME = "learned_pace.txt"
    private const val MAX_FILE_BYTES = 1_000_000L
}
