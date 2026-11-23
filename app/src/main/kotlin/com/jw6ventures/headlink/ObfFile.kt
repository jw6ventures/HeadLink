package com.jw6ventures.headlink

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Which kind of section a table-of-contents entry is. Only these two are read. */
enum class ObfSectionType { ADDRESS, POI }

/** One address or POI section: where it is in the file, its name and, if the file says, its bounds. */
class ObfSection(
    val type: ObfSectionType,
    /** First byte of the section's body, after its length. */
    val offset: Long,
    val length: Long,
    val name: String,
    val bounds: GeoBox?,
)

/**
 * A file's table of contents. [skippedSections] counts the map, routing,
 * transport and other sections passed over without being read.
 */
class ObfToc(
    val version: Int,
    /** Milliseconds since the epoch, as the file says. */
    val dateCreated: Long,
    /** True when the closing `versionConfirm` matched [version], as OsmAnd requires of a whole file. */
    val complete: Boolean,
    val sections: List<ObfSection>,
    val skippedSections: Int,
)

/**
 * OsmAnd's `.obf` map file, read in place: the address and POI sections, and the routing
 * sections' roads for exit signs ([ObfRouteReader]).
 *
 * Written fresh from OsmAnd's own reader (`BinaryMapIndexReader`,
 * `BinaryMapAddressReaderAdapter`, `BinaryMapPoiReaderAdapter` at OsmAnd `b75433b`) and
 * the vendored `protocol/upstream/osmand/OBF.proto`, then checked against real places
 * (docs/verification.md). Pure JVM: the host tests read real Texas files with it.
 *
 * - **Reads are positional** ([ChannelBytes]); nothing is memory-mapped, so files of any
 *   size open, and any number of threads may query at once: each query walks with its own
 *   [ObfWire], and the caches are guarded.
 * - **The table of contents is built here, in the constructor.** It reads the top-level
 *   fields, each address section's header and city-list offsets, and each POI section's
 *   name and bounds, and where each routing section is; every other section is passed
 *   over by its length, unread.
 * - **Cities are read once and kept** ([cities]); **streets are read per city and kept in a
 *   bounded LRU** ([streets]); buildings are read per street and not kept.
 * - **The address name index is never read.** OsmAnd changed its layout in July 2026, so
 *   nothing here depends on it; search walks cities and streets instead.
 *
 * Owns [channel] and closes it in [close].
 */
class ObfFile @Throws(IOException::class) constructor(private val channel: FileChannel, override val name: String) : ObfSource, Closeable {
    private val bytes = ChannelBytes(channel)
    private val lock = Any()

    /** What the constructor found. */
    val toc: ObfToc

    private val addressSections: List<AddressSection>
    private val poiSections: List<PoiSectionHeader>
    private val routingSections: List<RoutingSectionHeader>

    init {
        val found = readToc()
        toc = found.toc
        addressSections = found.address
        poiSections = found.poi
        routingSections = found.routing
    }

    override val bounds: GeoBox? = toc.sections.mapNotNull { it.bounds }.reduceOrNull { a, b ->
        GeoBox(minOf(a.south, b.south), minOf(a.west, b.west), maxOf(a.north, b.north), maxOf(a.east, b.east))
    }

    private val address = ObfAddressReader(bytes, name, addressSections)
    private val poi = ObfPoiReader(bytes, name, poiSections)
    private val route = ObfRouteReader(bytes, routingSections)

    @Throws(IOException::class)
    override fun cities(): List<ObfCity> = address.cities()

    @Throws(IOException::class)
    override fun streets(city: ObfCity): List<ObfStreet> = address.streets(city)

    @Throws(IOException::class)
    override fun buildings(street: ObfStreet): List<ObfBuilding> = address.buildings(street)

    @Throws(IOException::class)
    override fun pois(
        box: GeoBox,
        kinds: Set<PoiKind>,
        limit: Int,
        centreLat: Double,
        centreLon: Double,
        nameQuery: String?,
    ): List<ObfPoi> = poi.pois(box, kinds, limit, centreLat, centreLon, nameQuery)

    /** Whether the file has routing data: what [roadWays] reads. */
    val hasRouting: Boolean get() = route.hasRouting

    /**
     * The roads in OsmAnd's routing data near [box], with their tags and their points'
     * tags: where exit numbers and sign text are ([ExitSigns]). Empty with no routing data.
     */
    @Throws(IOException::class)
    fun roadWays(box: GeoBox): List<ObfRoadWay> = route.ways(box)

    /** Each POI section's category keys and their subcategory keys, as the file's own table has them. */
    @Throws(IOException::class)
    fun poiCategories(): Map<String, List<String>> = poi.categoryKeys()

    /** Each POI section's additional-tag (subtype) names. */
    @Throws(IOException::class)
    fun poiSubtypes(): List<String> = poi.subtypeNames()

    /**
     * Entries of the city, village and postcode lists passed over: no name, a boundary
     * (`city_type` 5) or a type OsmAnd does not know. The boundaries list itself
     * (`CitiesIndex` type 0) is never read.
     */
    val skippedCities: Int get() = address.skippedCities

    @Throws(IOException::class)
    override fun close() {
        synchronized(lock) { channel.close() }
    }

    private class Found(
        val toc: ObfToc,
        val address: List<AddressSection>,
        val poi: List<PoiSectionHeader>,
        val routing: List<RoutingSectionHeader>,
    )

    private fun readToc(): Found {
        val wire = ObfWire(bytes, 4096)
        var version = -1
        var date = 0L
        var confirm = -1
        var skipped = 0
        val sections = ArrayList<ObfSection>()
        val address = ArrayList<AddressSection>()
        val poi = ArrayList<PoiSectionHeader>()
        val routing = ArrayList<RoutingSectionHeader>()
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                F_VERSION -> version = wire.readVarint32()
                F_DATE_CREATED -> date = wire.readVarint64()
                F_VERSION_CONFIRM -> confirm = wire.readVarint32()
                F_ADDRESS_INDEX -> {
                    val length = wire.readLength(tag)
                    val start = wire.position
                    val section = readAddressHeader(wire, start, length)
                    address += section
                    sections += ObfSection(ObfSectionType.ADDRESS, start, length, section.name, section.bounds)
                    wire.seek(start + length)
                }
                F_POI_INDEX -> {
                    val length = wire.readLength(tag)
                    val start = wire.position
                    val section = readPoiHeader(wire, start, length)
                    poi += section
                    sections += ObfSection(ObfSectionType.POI, start, length, section.name, section.bounds)
                    wire.seek(start + length)
                }
                F_ROUTING_INDEX -> {
                    // Only where it is: read when exits are first asked for, and still a
                    // section passed over here.
                    val length = wire.readLength(tag)
                    routing += RoutingSectionHeader(wire.position, length)
                    skipped++
                    wire.skip(length)
                }
                else -> {
                    if (tag and 7 == ObfWire.WIRE_BIG_LENGTH) skipped++
                    wire.skipField(tag)
                }
            }
        }
        if (version < 0) throw IOException("$name: no OBF version field")
        return Found(ObfToc(version, date, confirm == version, sections, skipped), address, poi, routing)
    }

    private fun readAddressHeader(wire: ObfWire, start: Long, length: Long): AddressSection {
        val old = wire.pushLimit(length)
        var sectionName = ""
        var nameEn: String? = null
        var box: GeoBox? = null
        val tags = ArrayList<String>()
        val blocks = ArrayList<CityBlock>()
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> sectionName = wire.readString()
                2 -> nameEn = wire.readString()
                3 -> box = readTileBox(wire, wire.readLength(tag))
                4 -> {
                    val o = wire.pushLimit(wire.readLength(tag))
                    while (true) {
                        val t = wire.readTag()
                        if (t == 0) break
                        if (t ushr 3 == 1) tags += wire.readString() else wire.skipField(t)
                    }
                    wire.popLimit(o)
                }
                6 -> {
                    val blockLength = wire.readLength(tag)
                    val blockStart = wire.position
                    val o = wire.pushLimit(blockLength)
                    var type = 1
                    while (true) {
                        val t = wire.readTag()
                        if (t == 0) break
                        if (t ushr 3 == 2) { type = wire.readVarint32(); break }
                        wire.skipField(t)
                    }
                    wire.popLimit(o)
                    blocks += CityBlock(type, blockStart, blockLength)
                    wire.seek(blockStart + blockLength)
                }
                else -> wire.skipField(tag) // 7: the name index, never read (its layout changed July 2026)
            }
        }
        wire.popLimit(old)
        return AddressSection(sectionName, nameEn, start, length, box, tags, blocks)
    }

    private fun readPoiHeader(wire: ObfWire, start: Long, length: Long): PoiSectionHeader {
        val old = wire.pushLimit(length)
        var sectionName = ""
        var box: GeoBox? = null
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> sectionName = wire.readString()
                2 -> box = readTileBox(wire, wire.readLength(tag))
                else -> break // the tables and boxes follow; read when first asked
            }
        }
        wire.popLimit(old)
        return PoiSectionHeader(sectionName, start, length, box)
    }

    private fun readTileBox(wire: ObfWire, length: Long): GeoBox {
        val old = wire.pushLimit(length)
        var left = 0L; var right = 0L; var top = 0L; var bottom = 0L
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> left = wire.readVarint64() and 0xffffffffL
                2 -> right = wire.readVarint64() and 0xffffffffL
                3 -> top = wire.readVarint64() and 0xffffffffL
                4 -> bottom = wire.readVarint64() and 0xffffffffL
                else -> wire.skipField(tag)
            }
        }
        wire.popLimit(old)
        return GeoBox(ObfTiles.latitude31(bottom), ObfTiles.longitude31(left), ObfTiles.latitude31(top), ObfTiles.longitude31(right))
    }

    companion object {
        private const val F_VERSION = 1
        private const val F_DATE_CREATED = 18
        private const val F_ADDRESS_INDEX = 7
        private const val F_POI_INDEX = 8
        private const val F_ROUTING_INDEX = 9
        private const val F_VERSION_CONFIRM = 32

        /** Opens [file] read-only, named after it: for the host tests and the lab. */
        @Throws(IOException::class)
        fun open(file: File): ObfFile {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
            try {
                return ObfFile(channel, file.name)
            } catch (e: Throwable) {
                channel.close()
                throw e
            }
        }
    }
}

internal class CityBlock(val type: Int, val offset: Long, val length: Long)

internal class AddressSection(
    val name: String,
    val nameEn: String?,
    val offset: Long,
    val length: Long,
    val bounds: GeoBox?,
    val attributeTags: List<String>,
    val cityBlocks: List<CityBlock>,
)

internal class PoiSectionHeader(val name: String, val offset: Long, val length: Long, val bounds: GeoBox?)
