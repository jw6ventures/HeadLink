# OsmAnd-java, vendored

OsmAnd's router and `.obf` reader, used by `OsmAndRouter` behind Settings -> Route
preferences -> Route with OsmAnd maps. See [docs/navigation.md](../../docs/navigation.md),
"Routing on OsmAnd maps", for how it is used. **Vendored source.** Upstream files are held
unmodified; the few HeadLink replaces live in a separate source directory (below).

## Pin

| | |
| --- | --- |
| Upstream | https://github.com/osmandapp/OsmAnd, `OsmAnd-java/src/main/java` |
| Commit | `91d9bd647032397884a218be308270b565372ed5`, 30 October 2026 |
| Resources | https://github.com/osmandapp/OsmAnd-resources, commit `c280c2960cccba93a122a62f8d7e00bb0c4ea4d9` |
| Licence | GPL-3.0-or-later (`LICENSE` in this directory, copied verbatim), used under GPL-3.0 with this GPL-3.0-only project |
| Fetched | Anonymous `git clone` of both repositories at those commits, 1 November 2026 |

The commit SHAs are the pin. The authoritative copy of what is vendored is this directory.

## Why vendored rather than resolved

OsmAnd publishes OsmAnd-java to its own builder server, not to Maven Central, and with no
checksummed coordinate for `gradle/verification-metadata.xml` to pin. Vendoring the
source keeps the build offline and free of prebuilt jars, the same reasoning as
`vendor/brouter`.

## What was taken

The import closure of `net.osmand.router.RoutePlannerFrontEnd` and
`net.osmand.binary.BinaryMapIndexReader`, unmodified, less the files below: **212 files**
under `src/main/java`.

| Package | Files | What for |
| --- | --- | --- |
| `net.osmand.router` | 32 | The router: A*, highway hierarchy (HH), turn types, `routing.xml` profiles |
| `net.osmand.binary` | 24 | The `.obf` reader and its protobuf messages (`OsmandOdb`) |
| `net.osmand.data`, `net.osmand.osm`, `net.osmand.util`, `net.osmand` | 58 | Shared types the two above use |
| `net.osmand.search` and below | 33 | Reached through the reader's address and POI types; never run |
| `net.osmand.gpx`, `net.osmand.render`, `net.osmand.map` | 17 | Reached through types; never run |
| `com.google.protobuf` | 41 | OsmAnd's in-tree protobuf runtime, which `OsmandOdb` is generated against |

**Not taken:** the rest of OsmAnd-java (rendering, map creation, tests), the Android app,
OsmAnd-shared (Kotlin multiplatform; the few names used are replaced below), the native
library, junidecode, and BBN OpenMap (`com.jwetherell.openmap.common`, 8 files), whose "All rights reserved" OpenMap licence is not GPL-compatible.

### Replaced files

`src/replaced/java` holds HeadLink's own files for the places where the closure reaches
outside OsmAnd-java. None shares a path with an upstream file in `src/main/java`, so a
re-sync can never silently overwrite one.

| File | Why |
| --- | --- |
| `net/osmand/PlatformUtil.java` | Upstream's uses commons-logging and loads `regions.ocbf`. This one logs through `java.util.logging` (INFO lines at FINE, because they name roads), uses the platform's XmlPullParser, and gives an `OsmandRegions` that knows no regions, so the router never looks for maps to download. HH routing works without regions |
| `org/apache/commons/logging/Log.java` | The logging interface the upstream sources are written against, declared here because Android lint rejects the commons-logging artifact (DuplicatePlatformClasses) |
| `net/osmand/router/ExcludeTLongObjectMap.java` | Upstream's, with five method signatures changed for trove4j 3.0.3's wildcard procedures. Bodies unchanged; the file says so at the top |
| `net/osmand/util/LocationParser.java` | Upstream's, with the UTM and MGRS readings in `parseLocation` removed, because they need OpenMap. Only search uses it, and HeadLink does not run OsmAnd's search. The file says so at the top |
| `com/jwetherell/openmap/common/MoreMath.java` | HeadLink's own, not OpenMap's: just the quarter-pi constant upstream's `MapUtils` imports, so `MapUtils` stays unmodified |
| `net/osmand/util/TransliterationHelper.java` | Same methods, returns names unchanged, so junidecode is not needed |
| `net/osmand/shared/routing/GeneralRouterProfile.java` | OsmAnd-shared's Kotlin enum written out in Java |
| `net/osmand/shared/gpx/GpxFile.java`, `net/osmand/shared/wiki/WikiHelper.java`, `WikiImage.java`, `net/osmand/shared/util/PlatformUtil.java` | Stand-ins for the one constant or method each the closure names from OsmAnd-shared. No network |

### Resources

- `src/main/resources/net/osmand/router/routing.xml`: OsmAnd-resources `routing/routing.xml`
  at the pinned commit, byte-identical (150,860 bytes, SHA-256
  `df05dc28f4c80a66eb1eeb90da81794e4a57fbd07bd390f91bb68b60c3d1bf65`). HeadLink uses its
  `car` profile.
- `src/main/resources/net/osmand/osm/poi_types.xml`: written here, one category
  (`user_defined_other`), the only one the router's start-up needs. OsmAnd's full file is
  not taken because nothing HeadLink runs reads POI types from it.

## Dependencies

| Artifact | Scope | Licence |
| --- | --- | --- |
| `net.sf.trove4j:trove4j:3.0.3` | implementation | LGPL-2.1 |
| `com.vividsolutions:jts-core:1.14.0` | implementation | LGPL-2.1 |
| `com.google.openlocationcode:openlocationcode:1.0.4` | implementation | Apache-2.0 |
| `com.google.code.gson:gson:2.8.9` | implementation | Apache-2.0 |
| `org.json:json:20171018` | compileOnly; Android supplies it | JSON License |
| `net.sf.kxml:kxml2:2.1.8` | compileOnly; Android supplies XmlPullParser | BSD-style |

All from Maven Central, pinned by SHA-256 in `gradle/verification-metadata.xml`.
`tools/jni-host-test.sh` puts the two compile-only jars on the desktop JVM's classpath.

## Re-syncing

1. Clone OsmAnd at the new commit and copy the same 212 paths from
   `OsmAnd-java/src/main/java` over `src/main/java`. If the closure grew, compile errors
   name the missing classes; take them from the same tree.
2. Check the closure still needs nothing from `com.jwetherell` beyond `MoreMath.QUAD_PI_D`. Compare each replaced file's upstream original for changes the replacement should follow.
3. Copy `routing/routing.xml` from OsmAnd-resources at a matching commit.
4. Update the pins above, `docs/licenses.md` and `app/src/main/assets/NOTICES.txt`, and run
   `tools/jni-host-test.sh` (`OsmAndRoutingTest`).
