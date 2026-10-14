package org.openautoandroid.source

import java.io.IOException

/**
 * The address half of [ObfFile] (plan §3.3), after OsmAnd's `BinaryMapAddressReaderAdapter`.
 *
 * - **Cities** come from each `CitiesIndex` block: type 1 cities and towns ([CityType.CITY]),
 *   3 villages ([CityType.VILLAGE]), 2 postcodes ([CityType.POSTCODE]). A city's position is
 *   absolute at zoom 31, and `shiftToCityBlockIndex` is a big-endian offset **from the start
 *   of the city's own message, before its length** — that start is the city's handle's base.
 *   Entries OsmAnd keeps no streets under (`city_type` 5, boundaries; types it doesn't know)
 *   are skipped and counted.
 * - **Streets** are the city block's `StreetIndex` entries: zoom-24 deltas from the city's
 *   zoom-24 tile (`x31 >> 7`). A street's handle is its message's offset.
 * - **Buildings** are zoom-24 deltas from their street, with an optional second end
 *   (`name2`, `x2`, `y2`) and an `interpolation`: −1 all, −2 even, −3 odd, −4 alphabetic, a
 *   positive step. Under a postcode, only the buildings with that postcode are its own.
 */
internal class ObfAddressReader(
    private val bytes: ObfBytes,
    private val file: String,
    private val sections: List<AddressSection>,
) {
    private val lock = Any()
    private var cityList: List<ObfCity>? = null

    /** Each city's zoom-31 position, packed x shl 32 or y: exact, where the lat/lon round trip may not be. */
    private val cityTiles = HashMap<ObfCity, Long>()

    @Volatile
    var skippedCities = 0
        private set

    /**
     * Streets by city (by identity: a postcode may share a city's block, and its streets
     * belong to it), least recently used first; bounded by [MAX_CACHED_STREETS] streets in all.
     */
    private val streetCache = LinkedHashMap<ObfCity, List<ObfStreet>>(16, 0.75f, true)
    private var cachedStreets = 0

    fun cities(): List<ObfCity> {
        synchronized(lock) { cityList?.let { return it } }
        val tiles = HashMap<ObfCity, Long>()
        var skipped = 0
        val out = ArrayList<ObfCity>()
        val wire = ObfWire(bytes, 65536)
        for (section in sections) {
            for (block in section.cityBlocks) {
                val type = when (block.type) {
                    1 -> CityType.CITY
                    2 -> CityType.POSTCODE
                    3 -> CityType.VILLAGE
                    else -> { continue }
                }
                wire.seek(block.offset)
                val old = wire.pushLimit(block.length)
                while (true) {
                    val tag = wire.readTag()
                    if (tag == 0) break
                    if (tag ushr 3 == 5) {
                        val start = wire.position
                        val length = wire.readLength(tag)
                        val o = wire.pushLimit(length)
                        val city = readCity(wire, start, type, section.attributeTags, tiles)
                        wire.popLimit(o)
                        if (city == null) skipped++ else out += city
                    } else {
                        wire.skipField(tag)
                    }
                }
                wire.popLimit(old)
            }
        }
        synchronized(lock) {
            cityList?.let { return it }
            cityTiles.putAll(tiles)
            skippedCities = skipped
            cityList = out
            return out
        }
    }

    private fun readCity(
        wire: ObfWire,
        start: Long,
        blockType: CityType,
        attributeTags: List<String>,
        tiles: MutableMap<ObfCity, Long>,
    ): ObfCity? {
        var cityType = -1
        var name = ""
        var nameEn: String? = null
        var x = 0L
        var y = 0L
        var handle = -1L
        val pendingTags = ArrayDeque<String>()
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> cityType = wire.readVarint32()
                2 -> name = wire.readString()
                3 -> nameEn = wire.readString()
                5 -> x = wire.readVarint64() and 0xffffffffL
                6 -> y = wire.readVarint64() and 0xffffffffL
                7 -> attributeTags.getOrNull(wire.readVarint32())?.let { pendingTags.addLast(it) }
                8 -> {
                    val value = wire.readString()
                    if (pendingTags.removeFirstOrNull() == "name:en" && nameEn.isNullOrEmpty()) nameEn = value
                }
                10 -> handle = start + wire.readBigLength()
                else -> wire.skipField(tag)
            }
        }
        val type = when {
            blockType == CityType.POSTCODE -> CityType.POSTCODE
            cityType == CITY_TYPE_POSTCODE -> CityType.POSTCODE
            cityType == CITY_TYPE_BOUNDARY || cityType > CITY_TYPE_POSTCODE -> return null
            else -> blockType
        }
        if (name.isEmpty()) return null
        return ObfCity(
            name = name,
            nameEn = nameEn?.takeIf { it.isNotEmpty() && it != name },
            type = type,
            latitude = ObfTiles.latitude31(y),
            longitude = ObfTiles.longitude31(x),
            file = file,
            handle = handle,
        ).also { tiles[it] = (x shl 32) or y }
    }

    fun streets(city: ObfCity): List<ObfStreet> {
        if (city.handle < 0) return emptyList()
        synchronized(lock) { streetCache[city]?.let { return it } }
        val packed = synchronized(lock) { cityTiles[city] }
        val x24 = if (packed != null) (packed ushr 32) shr 7 else ObfTiles.x31(city.longitude) shr 7
        val y24 = if (packed != null) (packed and 0xffffffffL) shr 7 else ObfTiles.y31(city.latitude) shr 7
        checkInAddress(city.handle)
        val wire = ObfWire(bytes, 16384)
        wire.seek(city.handle)
        val old = wire.pushLimit(wire.readVarint64())
        val out = ArrayList<ObfStreet>()
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            if (tag ushr 3 == 12) {
                val start = wire.position
                val o = wire.pushLimit(wire.readLength(tag))
                val street = readStreetHeader(wire, start, city, x24, y24)
                wire.seek(wire.limit)
                wire.popLimit(o)
                if (street != null) out += street
            } else {
                wire.skipField(tag)
            }
        }
        wire.popLimit(old)
        synchronized(lock) {
            streetCache.put(city, out)?.let { cachedStreets -= it.size }
            cachedStreets += out.size
            val it = streetCache.entries.iterator()
            while (cachedStreets > MAX_CACHED_STREETS && streetCache.size > 1) {
                val eldest = it.next()
                cachedStreets -= eldest.value.size
                it.remove()
            }
        }
        return out
    }

    /** A street's own fields, buildings and intersections skipped. */
    private fun readStreetHeader(wire: ObfWire, start: Long, city: ObfCity, cityX24: Long, cityY24: Long): ObfStreet? {
        var name = ""
        var nameEn: String? = null
        var x = cityX24
        var y = cityY24
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> name = wire.readString()
                2 -> nameEn = wire.readString()
                3 -> x = cityX24 + wire.readSInt32()
                4 -> y = cityY24 + wire.readSInt32()
                else -> wire.skipField(tag)
            }
        }
        if (name.isEmpty()) return null
        return ObfStreet(
            name = name,
            nameEn = nameEn?.takeIf { it.isNotEmpty() && it != name },
            latitude = ObfTiles.latitude(24, y.toDouble()),
            longitude = ObfTiles.longitude(24, x.toDouble()),
            city = city,
            handle = start,
        )
    }

    fun buildings(street: ObfStreet): List<ObfBuilding> {
        checkInAddress(street.handle)
        val x24 = ObfTiles.x24Of(street.longitude)
        val y24 = ObfTiles.y24Of(street.latitude)
        val postcode = if (street.city.type == CityType.POSTCODE) street.city.name else null
        val wire = ObfWire(bytes, 16384)
        wire.seek(street.handle)
        val old = wire.pushLimit(wire.readVarint64())
        val out = ArrayList<ObfBuilding>()
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            if (tag ushr 3 == 12) {
                val o = wire.pushLimit(wire.readLength(tag))
                val building = readBuilding(wire, x24, y24)
                wire.popLimit(o)
                if (building != null && (postcode == null || postcode.equals(building.postcode, ignoreCase = true))) {
                    out += building
                }
            } else {
                wire.skipField(tag)
            }
        }
        wire.popLimit(old)
        return out
    }

    private fun readBuilding(wire: ObfWire, streetX24: Long, streetY24: Long): ObfBuilding? {
        var number = ""
        var number2: String? = null
        var interpolation = 0
        var x = streetX24
        var y = streetY24
        var x2: Long? = null
        var y2: Long? = null
        var postcode: String? = null
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> number = wire.readString()
                3 -> number2 = wire.readString()
                5 -> interpolation = wire.readSInt32()
                7 -> x = streetX24 + wire.readSInt32()
                8 -> y = streetY24 + wire.readSInt32()
                9 -> x2 = streetX24 + wire.readSInt32()
                10 -> y2 = streetY24 + wire.readSInt32()
                14 -> postcode = wire.readString()
                else -> wire.skipField(tag)
            }
        }
        if (number.isEmpty()) return null
        val kind = when {
            interpolation > 0 -> Interpolation.STEP
            interpolation == -1 -> Interpolation.ALL
            interpolation == -2 -> Interpolation.EVEN
            interpolation == -3 -> Interpolation.ODD
            interpolation == -4 -> Interpolation.ALPHABETIC
            else -> Interpolation.NONE
        }
        val hasEnd = x2 != null && y2 != null
        return ObfBuilding(
            number = number,
            latitude = ObfTiles.latitude(24, y.toDouble()),
            longitude = ObfTiles.longitude(24, x.toDouble()),
            number2 = number2?.takeIf { it.isNotEmpty() },
            latitude2 = if (hasEnd) ObfTiles.latitude(24, y2!!.toDouble()) else null,
            longitude2 = if (hasEnd) ObfTiles.longitude(24, x2!!.toDouble()) else null,
            interpolation = kind,
            step = if (kind == Interpolation.STEP) interpolation else 0,
            postcode = postcode?.takeIf { it.isNotEmpty() },
        )
    }

    private fun checkInAddress(offset: Long) {
        if (sections.none { offset >= it.offset && offset < it.offset + it.length }) {
            throw IOException("$file: offset $offset is outside every address section")
        }
    }

    companion object {
        /** OsmAnd's `City.CityType` ordinals that matter here. */
        private const val CITY_TYPE_BOUNDARY = 5
        private const val CITY_TYPE_POSTCODE = 6

        /** About 3 MB of street objects at most. */
        const val MAX_CACHED_STREETS = 40_000
    }
}
