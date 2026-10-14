# P11.2–P11.3 results — the OBF reader — 3 October 2026

This records the build of P11.2 (container, table of contents, address) and P11.3 (POI)
of `docs/p11-plan.md` §3. It is a pure-JVM reader, checked on the host against three real
Texas files and against OpenStreetMap's own positions. Nothing here has run on the Pixel.
Nothing is committed.

## 1. What was built

App (`app/src/main/kotlin/org/openautoandroid/source/`), all pure JVM:

- **`ObfWire.kt`**
  - `ObfBytes`, with `ChannelBytes` (positional `FileChannel.read`, no mapping) and
    `ArrayBytes` (tests).
  - `ObfWire`, the walker. It reads varints, zigzag, and wire types 0, 1, 2 and 5, plus
    OsmAnd's **wire type 6**: a 4-byte big-endian length, or 8 bytes when the top bit is
    set (`readBigLength`). It also has limits, and skips unknown fields of every wire type.
    Groups (3, 4) and 7 are refused with an `IOException`, not misread.
  - `ObfTiles`, OsmAnd's `MapUtils` tile arithmetic at zoom 31 and 24.
- **`ObfFile.kt`**: `ObfFile(FileChannel, name) : ObfSource, Closeable`, `ObfFile.open(File)`,
  and the table of contents (`ObfToc`, `ObfSection`, `ObfSectionType`).
- **`ObfAddressReader.kt`** (internal): cities, streets and buildings.
- **`ObfPoiReader.kt`** (internal): the tables, the box tree and the atoms. Its public
  `ObfPoiKinds` holds the button rules and `unzip`.

Other files:

- **`protocol/upstream/osmand/OBF.proto`**: vendored unmodified from OsmAnd-resources
  `3e2f8ef`, with a `README.md` (URL, SHA-256, GPLv3, "no code generated"). Two rows were
  added to `docs/licenses.md`.
- **`tools/jni-host-test/ObfTest.java`**: 65 checks always, and 34 more with `OBF_DIR`.
  `jni-host-test.sh` gained one run line and one header bullet. `android-build.sh` mounts
  `OBF_DIR` read-only, as it does `ROUTING_SEGMENTS`.

`ObfModel.kt` is unchanged.

### Build

The Kotlin compiled cleanly in the full `tools/android-build.sh` run, with no lint finding
in the OBF files. **The full build did not pass.** Lint stopped it on two unused strings
from the parallel P11 work (`osmand_maps_reading`, `search_suggestions_label`), and
`jni-host-test.sh` then stopped at `DashboardTripTest` ("with no card it pans, as
before"). Neither is in this reader's files.

`ObfTest` was therefore also run on its own, in the same pinned image, against Gradle's
release classes, with `OBF_DIR` mounted read-only. It passed 65 checks without the files,
and 99 (65 synthetic, 34 real) with them. The full build needs re-running once the other
parts settle.

### Public API

```kotlin
class ObfFile(channel: FileChannel, name: String) : ObfSource, Closeable  // owns and closes the channel
ObfFile.open(file: File): ObfFile
val toc: ObfToc          // version, dateCreated, complete, sections, skippedSections
val bounds: GeoBox?      // POI section bounds (the address sections carry none)
cities(); streets(city); buildings(street); pois(box, kinds, limit, lat, lon, nameQuery)
poiCategories(): Map<String, List<String>>; poiSubtypes(): List<String>; skippedCities: Int
```

Every call may throw `IOException`, and they are annotated `@Throws`. One `ObfFile` may be
queried from many threads at once. Each query walks with its own `ObfWire`, and the
caches are synchronized.

## 2. How the format reads

Each point below is taken from OsmAnd's Java reader (`b75433b`) and confirmed on the real
files. The proto alone was not enough.

1. **Top level.** The fields are version (1) and dateCreated (18). Sections 4, 6, 7, 8, 9
   and 10 are wire type 6. VersionConfirm (32) closes the file. Only 7 (address) and 8
   (POI) are opened. The other five (map, routing, transport, two HH routing) are passed
   over by their length.
2. **Address.** The sections have **no bounds field**, so `bounds` comes from the POI
   section. There are four `CitiesIndex` blocks (wire 6), with types 1 (cities), 3
   (villages), 2 (postcodes) and **0 (boundaries)**. The boundaries block holds 523 entries
   in Austin, all `city_type` 5, and is not read. `shiftToCityBlockIndex` is a big-endian
   4-byte offset from the position of the city message's **length varint**, after its tag.
   It points at the block's own length varint.
3. **Streets** are zoom-24 deltas from `cityX31 >> 7`. **Buildings** are deltas from the
   street's zoom-24 tile. The second end (`x2`, `y2`) and `name2` are deltas from that same
   tile. Under a postcode, OsmAnd keeps only the buildings carrying that postcode.
4. **POI.** The box tree is: zoom as a delta, `left`/`top` as `dx + (px << (zoom − pzoom))`,
   sub-boxes in wire 6, and `shiftToData` big-endian. That offset is measured from the
   section's first byte to the 4-byte length of the `poiData` field. Atoms are at
   `x31 = (dx + (tileX << (24 − zoom))) << 7`. `precisionXY` adds two bits per axis
   (zoom 26), y first, as `MapUtils.calculateFinalXYFromBaseAndPrecisionXY` does.
5. **Names are not in fields 6 and 7.** In every atom of all three files, the name is a
   *text tag*: `textCategories` (14) names the subtype `name`, and `textValues` (15)
   carries it. The same holds for `name:en`, `brand` and `opening_hours`. This is the
   proto-names trap again: the first run decoded every POI with an empty name. The reader
   still takes fields 6 and 7 when present.
6. **One place, several categories, several atoms.** The airport appears three times under
   one id (aerodrome, wiki place, internet access). Results are de-duplicated by id. The
   copy kept is the best kind, in the order gas, food, shop, none.
7. **POI ids** are OsmAnd's: the OSM id `<< 1`, plus 1 for a way. This is how the checks
   below match objects to Nominatim.
8. **Values beginning `" gz "`** are gzip, one char per byte plus 160
   (`MapObject.unzipContent`). The reader unzips them.

## 3. Verification against reality

Positions come from Nominatim (OSM, 3 October 2026), by OSM id where the object is a POI.
Every row below is a check in `ObfTest` (`OBF_DIR=~/.cache/openauto-android/obf`).

| What | Decoded | OSM | Error |
| --- | --- | --- | --- |
| Austin (city, admin centre) | 30.271129, −97.743700 | 30.2711286, −97.7436995 | 0.0 m |
| Round Rock | 30.508592, −97.678806 | 30.5085915, −97.6788056 | 0.0 m |
| Pflugerville | 30.439370, −97.620004 | 30.4393696, −97.6200043 | 0.0 m |
| Georgetown | 30.637015, −97.677563 | 30.6370152, −97.6775634 | 0.0 m |
| 1100 Congress Avenue (Capitol) | 30.276488, −97.739782 | 30.2764850, −97.7397680 | 1.4 m |
| 1800 Congress Avenue (Bob Bullock IMAX) | 30.280583, −97.738945 | 30.2805847, −97.7389369 | 0.8 m |
| 3050 Duval Street, interpolated in the 3000–3098 even range | 30.292336, −97.733953 | 30.2923327, −97.7339343 | 1.8 m |
| Whataburger, Barton Springs (way 262312068) | 30.258057, −97.750688 | 30.2580592, −97.7506844 | 0.4 m |
| UT Tower (way 516187631) | 30.286276, −97.739369 | 30.2862732, −97.7393655 | 0.5 m |
| H-E-B, South Congress (way 1235247866) | 30.238701, −97.754567 | 30.2387456, −97.7546883 | 12.7 m |
| Buc-ee's fuel, Temple (node 10879244160) | 31.136382, −97.329340 | 31.1363799, −97.3293382 | 0.3 m |
| Amarillo | 35.207290, −101.837119 | 35.2072900, −101.8371192 | 0.0 m |
| Lubbock (older-layout file) | 33.585568, −101.847022 | 33.5855677, −101.8470215 | 0.0 m |
| Whataburger, Lubbock (way 264959568) | 33.587538, −101.869322 | 33.5875354, −101.8693635 | 3.9 m |

For an area, OsmAnd stores its own representative point, and Nominatim uses a different
one. For a building that costs metres (H-E-B, 12.7 m). For a large area it can be far more:
Austin-Bergstrom's aerodrome way decodes 488 m from Nominatim's centroid
(30.189104, −97.665362 against 30.1934893, −97.6650096). Points and small ways agree to
within a metre, so the decoding is exact; the gap is OsmAnd's choice of point. The
airport is not a test row for that reason.

**POI counts, downtown Austin** (30.262–30.282, −97.752 to −97.735):

| Kind | Reader | Brute-force decode | OSM today (Overpass) |
| --- | --- | --- | --- |
| Food | 177 | 177 | 181 |
| Shop | 87 | 87 | 94 |
| Gas | 5 | 5 | 6 |

- The brute-force decode is an independent Python script in the scratchpad. It decodes
  every atom of every data box, with no box-tree pruning. That it agrees exactly shows the
  pruning by area and by category drops nothing.
- The OSM counts are live data, and the file is from 1 September.
- No EV charger is returned as gas (also a check).

**Other names found:**

- Three Buc-ee's: Temple, Giddings and New Braunfels. Each is fuel, convenience shop and
  "services".
- Two H-E-Bs and six Whataburgers within the Austin box.

**Concurrency.** 32 mixed queries on 8 threads matched the single-thread results. The
queries were downtown POIs, and streets plus buildings of 16 cities.

### TIGER interpolation: yes

The plan asked whether the US files carry TIGER ranges. They do.

| File | NONE | ALL | EVEN | ODD | Buildings in all |
| --- | --- | --- | --- | --- | --- |
| Austin, city of Austin only | 357,975 | 8,616 | 23,809 | 23,820 | 414,220 |
| Lubbock | 75,984 | 4,836 | 13,901 | 13,897 | — |

- A range carries `name2` and a second end. Duval Street, for example, has 3000–3098 even
  and 3001–3099 odd. Interpolating 3050 along its range lands 1.8 m from OSM's own 3050.
- **A range's first number often also appears as a plain building at the same point**, for
  example "1100 NONE" next to "1100 EVEN". Search should not count it twice.

### Real category keys

These come from the file's own category tables; the Austin table is the one checked:

- **Gas:** `transportation` / `fuel`. `charging_station` is in the same category and is
  excluded.
- **Food:** `sustenance` has 10 subcategories: `bar`, `barbecue`, `biergarten`, `cafe`,
  `fast_food`, `food_court`, `hookah_lounge`, `outdoor_seating_poi`, `pub` and
  `restaurant`. Food takes `restaurant`, `fast_food`, `cafe` and `food_court`, as planned.
  **`barbecue` exists and is left out.** In OSM that tag is a grill, not a restaurant, but
  James may want to look.
- **Shop:** `shop` has 133 subcategories. They include `gas`, which is bottled gas, not
  fuel, and `vending_machine`, which decision 3 keeps.
- **Extra tags:** the subtype table has `name`, `name:en`, `brand` and `opening_hours`,
  all as text tags. Austin's table holds 1,505 subtypes, and the Lubbock file holds 709.

## 4. Both layouts

- **Amarillo** (1 October) and **Austin** (1 September) are the updated files. **Lubbock**
  is the 30 June file. *Correction to the brief:* the Austin file's `dateCreated` is
  1 September 2026, not 30 September. Amarillo's is 1 October.
- All three read the same way, with the same code. Every check above passes on Lubbock
  too: the city to 0.0 m, a Whataburger to 3.9 m, and the interpolation ranges on Avenue Q.
- The name indexes (address field 7, POI field 4) have the same top-level shape in both
  layouts:
  - address: `table` (wire 6), `commonStats`, then atoms;
  - POI: `table` (wire 6), `commonStats`, then data.
- Whatever changed in July 2026 is inside those atoms, which this reader never opens. So
  the plan's "newer format" refusal (§2, §3.3) is **not needed for this reader**. No file
  has to be refused. P11.1's card line "N maps in a newer OBF format" can be dropped, or
  kept only for a future `version` other than 2.

## 5. Address findings the search work needs

1. **Street names can carry the suburb.** Downtown Congress Avenue is
   **"Congress Avenue (Downtown)"**, listed under Austin. A separate "Congress Avenue"
   entry under Austin holds two stray addresses, one of them 8 km away. Match the typed
   street as a prefix, or strip a trailing `" (…)"`.
2. **A postcode's own point is not its centre.** It is the position of its first address.
   78701's point is 30.34103, −97.70451, about 8.5 km from downtown, the same stray
   address. The median of its 92 streets is 30.27143, −97.74006, which is downtown. Place
   a ZIP suggestion at the median of its streets.
3. **Villages include suburbs.** Seven entries with `city_type` 4 (SUBURB) sit in the
   villages block, for example Avery Ranch, Brushy Creek, Jollyville and Steiner Ranch.
   They come back as `CityType.VILLAGE`.
4. **One postcode has an empty name** in each file. It is skipped and counted in
   `skippedCities` (1 in every file).
5. **One place name may appear more than once.** Plainview is a CITY in Lubbock's file and
   also two VILLAGEs elsewhere in it. A search should rank by distance.

## 6. Speed (host JVM, files in page cache)

| Step | Austin (249 MB) |
| --- | --- |
| Open and table of contents | 0.3 ms warm JVM; 28 ms first open in a fresh JVM, class loading included |
| `cities()` (868 entries) | 6.6–9 ms |
| `streets(Austin)` (12,978) | 16–19 ms first, then cached (0.01 ms) |
| Every street and building of Austin (124k streets in all cities, 414k buildings in Austin) | 210–320 ms |
| Downtown POIs, all three kinds | 11–90 ms first in a JVM, 3–15 ms after |
| A name query over the whole file's bounds ("buc-ee") | 190–420 ms |

These are desktop numbers. P11.7 measures a release build on the Pixel.

Memory:

- The reader keeps the cities of each file, about 900 objects.
- It keeps at most 40,000 cached streets (about 3 MB), evicting the least recently used
  city first.
- Buildings and POIs are not kept.

## 7. Deviations from the plan

- **The address name index is never read** (as briefed). Suggestions as you type must
  walk `cities()` and `streets()`. At the speeds in §6 that fits the 300 ms target
  comfortably for one file. Over 11 files it needs a check on the phone.
- **The newer-format refusal is unnecessary** (§4).
- **`bounds` is the POI section's only.** The address sections have none.
- **Results are de-duplicated by id** (§2.6). The plan did not foresee multi-category
  copies.
- **Additions, not in the contract:** `ObfFile.toc`, `poiCategories()`, `poiSubtypes()`,
  `skippedCities`, `ObfFile.open(File)`, the public `ObfWire`, `ObfBytes`, `ObfTiles` and
  `ObfPoiKinds`. All are additive.

## 8. Open issues

- **Nothing has run on the phone.** The Pixel timing on a release build is P11.7's.
- **Not checked against OsmAnd itself.** §9 of the plan asks for expected positions from
  OsmAnd's own search on the phone (P11.0). Here they come from OSM via Nominatim, which
  is independent of our reading and of OsmAnd's. P11.0 remains the check that our
  results match what OsmAnd shows.
- **Tag groups** (atom field 17, box field 8) are skipped. None of the fields this reader
  returns was found in them.
- **Area POIs** sit at OsmAnd's representative point, up to hundreds of metres from a big
  area's centroid (the airport). For markers this is as OsmAnd draws them.
- **A FileChannel from a `ParcelFileDescriptor`** has not been exercised. `ObfFile` needs
  only `size()` and positional `read`, which `FileInputStream.getChannel()` provides on
  Android.
