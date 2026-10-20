package com.jw6ventures.headlink

/**
 * P11's contract types (docs/p11-plan.md): what the OBF reader hands to search, the map's
 * POI markers and search ahead. Pure JVM. The reader ([ObfFile], [ObfLibrary]) produces
 * these; nothing here knows the file format.
 *
 * Names and labels in these types are places James typed or drove near: phone only, never
 * the report or the event log (plan §8).
 */

/** One of the three map buttons (plan §6, decisions 3). */
enum class PoiKind(val label: String) {
    /** `transportation` / `fuel` only; EV charging is left out. */
    GAS("gas"),

    /** `sustenance`: restaurant, fast_food, cafe, food_court, barbecue. */
    FOOD("food"),

    /** Every `shop` subcategory. */
    SHOP("shop"),
}

/** A point of interest read from a file's POI section. */
class ObfPoi(
    /** OSM-derived id as the file stores it; stable within one file version. */
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    /** The local name, or "" when the place has none. */
    val name: String,
    val nameEn: String?,
    /** The file's own category and subcategory keys, e.g. `sustenance` / `fast_food`. */
    val category: String,
    val subcategory: String,
    val kind: PoiKind?,
    val brand: String?,
    val openingHours: String?,
    /** The file it came from, by display name (`Us_texas_austin_northamerica.obf`). */
    val file: String,
)

enum class CityType { CITY, VILLAGE, POSTCODE }

/** A city, town, village or postcode from a file's address section. [handle] is the reader's own. */
class ObfCity(
    val name: String,
    val nameEn: String?,
    val type: CityType,
    val latitude: Double,
    val longitude: Double,
    val file: String,
    val handle: Long,
)

/** A street in one city. Long streets may appear more than once under one name. */
class ObfStreet(
    val name: String,
    val nameEn: String?,
    val latitude: Double,
    val longitude: Double,
    val city: ObfCity,
    val handle: Long,
)

/**
 * A house number on a street. With [interpolation] other than [Interpolation.NONE] this is
 * a range from [number] at ([latitude], [longitude]) to [number2] at ([latitude2], [longitude2]).
 */
class ObfBuilding(
    val number: String,
    val latitude: Double,
    val longitude: Double,
    val number2: String?,
    val latitude2: Double?,
    val longitude2: Double?,
    val interpolation: Interpolation,
    /** Positive step for [Interpolation.STEP], else 0. */
    val step: Int,
    val postcode: String?,
)

enum class Interpolation { NONE, ALL, EVEN, ODD, ALPHABETIC, STEP }

/** A box in degrees, south < north, west < east (no antimeridian here: Texas). */
data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in south..north && longitude in west..east

    fun intersects(other: GeoBox): Boolean =
        south <= other.north && other.south <= north && west <= other.east && other.west <= east
}

/** What one search suggestion is (plan §4, §5). */
enum class SuggestionKind { EXACT, INTERPOLATED, CLOSEST, STREET, CITY, POSTCODE, POI }

class Suggestion(
    /** One line, as shown: "123 Main St, Austin", "No 123 on Main St — 121 Main St". */
    val label: String,
    /** A second line, may be empty: the city, the distance, "estimated". */
    val detail: String,
    val latitude: Double,
    val longitude: Double,
    val kind: SuggestionKind,
    val file: String,
)

/**
 * The reader's surface, one per open file. Implemented by [ObfFile]; tests and the search
 * engine may use a fake. Every call may throw [java.io.IOException] (a short read, a file
 * replaced mid-read); callers count and drop rather than crash.
 */
interface ObfSource {
    /** Display name of the file. */
    val name: String

    /** The union of the file's address and POI sections' bounds, or null if it has neither. */
    val bounds: GeoBox?

    /** Every city, village and postcode in the address section. Cached after the first call. */
    fun cities(): List<ObfCity>

    /** The streets of one city or postcode. Cached per city. */
    fun streets(city: ObfCity): List<ObfStreet>

    /** The house numbers of one street. */
    fun buildings(street: ObfStreet): List<ObfBuilding>

    /**
     * POIs of [kinds] in [box], at most [limit] per kind, nearest ([centreLat], [centreLon])
     * first. With [nameQuery] set, only POIs whose name or brand contains it (case-folded),
     * of any kind when [kinds] is empty.
     */
    fun pois(
        box: GeoBox,
        kinds: Set<PoiKind>,
        limit: Int,
        centreLat: Double,
        centreLon: Double,
        nameQuery: String? = null,
    ): List<ObfPoi>
}
