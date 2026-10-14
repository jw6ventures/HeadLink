# P11 plan — address search and map POIs from OsmAnd's maps — 3 October 2026

**Status 3 October 2026:** built on James's "Implement P11". P11.1–P11.5a are host-tested,
and P11.5 and P11.5a are lab-verified in Austin. Nothing has run on the Pixel or the truck.
P11.0's OsmAnd API probe and P11.6 (the API as a second source) were not built. The
records:

- `docs/p11-2-3-results.md`: the reader
- `docs/p11-4-search-results.md`: search
- `docs/p11-1-results.md`: the maps folder, the settings card and the phone search box
- `docs/p11-5-results.md`: the buttons and markers
- `docs/p11-5a-results.md`: search ahead

**Deviations from this plan:**

- **Street match ranks before kind** (§4). The real case: for "1101 Congress Ave", the
  closest real numbers on Congress Avenue came after an estimate on South Congress.
- **Food also takes `barbecue`** (§6).
- **No "newer format" refusal.** The July 2026 change is in the name index, which the
  reader never opens.
- **ZIP suggestions are placed at the middle of the ZIP's streets.** The file's own
  point for a ZIP can be kilometres off.
- **Best-stop rule** (§6a): a nearer stop must be at least 1 km nearer to beat a smaller
  detour.
- **A stop is let go when it is reached,** as well as once it has been passed.

The amendments in §11 are proposed and not applied.

James asked for three things on 3 October 2026:

1. read OsmAnd's own map files in place, from a folder he grants;
2. type "123 Main St Austin tx" and get suggestions as he types, and, when the street
   is found but the number isn't, be offered the closest number that is;
3. quick buttons along the top of the map for **gas**, **restaurants** and **shopping**,
   with POI markers on the map.

He also said OsmAnd's own API is acceptable **as long as it runs on the phone**.

P6.4 rejected building our own index, and chose OsmAnd shares instead (`docs/p6-plan.md`
§5.1, 21 September). That choice still stands for routing: BRouter stays, and nothing
here routes on OBF data. What changes is that P11 *reads* OsmAnd's maps. It does not
build or import a dataset of its own.

---

## 0. Scope, and what is deliberately outside it

In scope:

- **A read-only maps folder** (§2): a second SAF grant, separate from the P6.3a drop
  folder, that holds OsmAnd's `.obf` files.
- **An OBF reader** (§3): our own Kotlin code that reads OBF files in place, written
  against OsmAnd's proto file. It reads only the POI and address sections.
- **Typed address search with suggestions** on the phone, including the
  closest-number fallback (§4, §5).
- **POI quick buttons and markers** on the map pane, where tapping a marker can start a
  route (§6).
- **Optionally, OsmAnd's on-device search API** (§7): as a second source of
  suggestions, and as the reference our own results are checked against.

Out of scope:

- **Typing on the car's screen.** The head unit has no keyboard of ours, and building
  one, plus deciding when it may be used, is its own package. Search is typed on the
  phone; the car shows the result as a route and as markers.
- **Routing on OBF data**, OBF map rendering, and OBF transport, wiki or terrain
  sections. The Mapsforge map and BRouter are untouched.
- **Reverse geocoding** (coordinates → an address). The address index isn't built for
  that.
- **Downloading or updating maps.** OsmAnd does that, and P11 reads whatever OsmAnd has.
- **Any network access.** APP10 is not touched.

## 1. What the phone looks like today (checked 3 October 2026)

- The phone runs Android 17 with OsmAnd+ **5.3.10** (`net.osmand.plus`, targetSdk 35).
- There are 15 maps. The 11 Texas regions (amarillo, austin, dallas, del-rio, houston,
  lubbock, san-angelo, san-antonio, tyler, victoria, waco) are dated 30 June 2026 and
  add up to about **2.0 GB**. The other four are Stockholm, Noord-Holland, the Copenhagen
  region and the world basemap. On the phone the files are named without the `_2`
  that the download names carry, for example `Us_texas_austin_northamerica.obf`.
- **They are in `Android/data/net.osmand.plus/files/`.** Android 11 and later keep
  that folder from every other app, through the folder picker and with all-files access
  alike. OsmAnd's "Shared memory" choice (`/storage/emulated/0/osmand/`) is greyed out in
  current OsmAnd, which says Android's storage rules no longer allow it. That is why
  switching to it moved nothing.
- **Update, later on 3 October:** James switched OsmAnd to *Multi-user storage*, and the
  maps are now in **`Android/obb/net.osmand.plus/`** (all 15 moved, nothing left in
  `Android/data`). Android also keeps another app's `Android/obb` folder from the folder
  picker. My recollection is that since Android 11 an app holding
  `REQUEST_INSTALL_PACKAGES` may read other apps' `obb` folders, but this is
  **unconfirmed**. P11.0 tests it with a throwaway probe app before anything is built
  on it. It would also be a new manifest permission, which needs a spec amendment (§11).
- **Settled, later still on 3 October:** James set OsmAnd's *Manually specified* folder to
  **`Documents/osmand`**, and OsmAnd accepted it without all-files access being granted.
  The maps are now there: the 11 Texas regions and the world basemap. Stockholm,
  Noord-Holland and Copenhagen did not come along. The files are ordinary shared
  storage, owned by the media provider, so a folder-picker grant on `Documents/osmand`
  reaches them with **no new permission**, and the `obb` question is moot. OsmAnd
  downloads into the same folder as `<name>.obf.download` and renames it when done; P11
  reads only names ending in `.obf`, so a half-downloaded file is never opened. James
  began updating the maps the same day. Updated files will use the July 2026 layout
  (§3.3), so that layout is now the one P11 must read first.
- **OsmAnd's API service is installed:** `net.osmand.aidl.OsmandAidlServiceV2`,
  exported, with no permission.

## 2. The maps folder (P11.1)

- **Settings → OsmAnd maps → Choose folder.** This opens `ACTION_OPEN_DOCUMENT_TREE`,
  and P11 takes a persisted **read-only** grant on the folder.
- **It is kept apart from the drop folder.** `DropFolder.folder()` picks the first
  grant that has *both* read and write, so a read-only grant is never mistaken for it.
  But `DropFolder.choose()` currently gives up **every** other grant, so choosing a new
  drop folder would silently drop the maps folder. P11.1 narrows `choose()` to give up
  only earlier read-write grants. The maps folder is recognised as the read-only grant,
  so neither folder needs a separate setting.
- **Only `*.obf` files directly in the folder are read.** `*.srtmf.obf`, `*.wiki.obf`
  and the subfolders are skipped, and so is `World_basemap*`, which holds no addresses.
- **Files are opened through `ParcelFileDescriptor`**, and read with positional
  `FileChannel` reads. Nothing is memory-mapped: a single mapping is capped at 2 GB and
  Dallas alone is 475 MB. Each file stays open while a projection is attached or the
  search screen is open, and is closed otherwise.
- **An index of each file's sections** (§3.1) is built on first open. It is kept in
  memory and keyed by name, size and modified time, so the next drive doesn't re-scan
  the file. When OsmAnd updates a map, the file is replaced, its key changes, and the
  index is rebuilt.
- **Status line on the card:** one of
  - "No folder chosen"
  - "Folder unreadable"
  - "No OsmAnd maps in this folder"
  - "11 maps · Texas · newest 30 Jun 2026"
  - "3 maps in a newer OBF format than this app reads"

  The card's help line names OsmAnd's setting: *Settings → OsmAnd settings → Data
  storage folder → Manually specified*.

## 3. The OBF reader (P11.2–P11.3)

### 3.1 The container

- **We vendor `OBF.proto` unmodified** from OsmAnd-resources, at a recorded commit,
  next to the AA protos. **No code is generated from it.** OsmAnd frames its large
  sections with a 4-byte big-endian length, its own wire type 6, which no generator
  understands. If the top bit of that length is set, it is an 8-byte length instead.
- **Field offsets** (`shiftToData`, `shiftToCityBlockIndex`, `shiftToCityIndex`) are
  read the same big-endian way, not as protobuf `fixed32` (which is little-endian).
- **The reader is a hand-written walker:** varints, zigzag, and skipping any field it
  doesn't know, including wire type 6. It is pure JVM, so it can be tested on the host.
- **The table of contents** holds the version, the creation date, and each POI or
  address section's offset, length, name and bounding box.

### 3.2 POI (P11.3)

- **Read once per file:** the category table and subtype table, which are small.
- **To answer "these categories in this box":**
  - walk the box tree (`OsmAndPoiBox`: zoom, then left/top as offsets from the parent),
    pruning by area and by the box's category list;
  - follow `shiftToData` to each data box;
  - decode each `OsmAndPoiBoxDataAtom`: position, categories, name, `nameEn`, id, and
    from the text tags only brand and opening hours.
- **Position:** `x31 = (dx + (tileX << (24 − zoom))) << 7`, refined with `precisionXY`
  when it is present. This is the classic place for a silent off-by-shift error. Every
  decoded position is checked against OsmAnd itself (§9).
- **"Shopping":** the category id is `(subcategory << 7) | category`, so a filter like
  "all of `shop` except vending" is a set of ids. Those ids are resolved from each
  file's own table, never hard-coded.

### 3.3 Address (P11.2)

- **Cities** (`CitiesIndex` type 1 = cities and towns, 3 = villages, 2 = postcodes)
  give a name, an absolute position, and an offset to the city's block.
- **The city block** gives streets: name, position as an offset from the city at
  zoom 24, and intersections. Each street has **buildings**: number, position, and
  `name2`/`x2`/`y2` with an `interpolation` value (−1 all, −2 even, −3 odd,
  −4 alphabetic, >0 a step).
- **The address name index** maps name prefixes to cities and streets. It is read for
  suggestions as you type, so a search doesn't scan every street.
- **Format versions:** OsmAnd changed the name-index layout in July 2026. The phone's
  Texas files are from 30 June and use the older layout. P11 reads the older layout
  first. A file in the newer layout is reported as such on the card (§2), and counted.
  It is not misread. Supporting the newer layout is a follow-up, done once OsmAnd's next
  monthly update lands on the phone and gives us a real file to test against.

## 4. Typed search (P11.4)

**The parser** splits US-style input into parts, without guessing more than it must:

- **House number:** a leading number, possibly with a letter or fraction (`123`,
  `123B`, `123½`).
- **Street:** the name, with type words matched both ways (St/Street, Ave, Rd, Dr, Blvd,
  Ln, Pkwy, Hwy, Fwy, Ct, Cir, Trl) and direction words both ways (N/North and so on).
  A street can be a number plus a type ("Main St", "FM 1431", "I-35 Frontage Rd").
- **City:** whatever matches a city or town in the address index.
- **State:** a trailing two-letter code or full state name. It is used only to drop
  files for other states, since all of James's are Texas.
- **ZIP:** a trailing 5-digit number, matched against the postcode index.
- Commas are optional, case doesn't matter, and punctuation is ignored.

**How the search runs:**

1. Find candidate cities from the city part, using the prefix index. With no city
   typed, use the cities nearest the phone's position (which P5.5 already has).
2. In each candidate city, look up the street from its prefix.
3. With a house number, look the number up on each matching street (§5).
4. Suggestions are ordered by:
   - an exact number before an interpolated number before the closest number;
   - a full street-name match before a prefix match;
   - a matching city before a nearby city;
   - then distance from the phone.

   The first **8** are shown.

**While typing:** results update 250 ms after the last keystroke. They are computed off
the main thread, and a newer keystroke cancels a search that is still running. Typing
"123 Main St Au" should already suggest "123 Main St, Austin".

**What a suggestion carries:** a label, the position, the kind (exact, interpolated,
closest, street only, POI), and the source file. Picking one hands a destination to
`Navigation.set`, as a share does today. The address stays on the phone, as in P6.4
(§8).

**Plain names find POIs too.** A query with no house number, such as "HEB Lamar",
also searches POI names in the visible area, so places that have no address still come
up.

## 5. The closest number (P11.4)

When the street is found but the exact number isn't, the search works it out from the
street's own buildings:

1. **An interpolation range that holds the number** (`123` between `101` and `199`,
   odd) gives a position worked out along the range. It is labelled
   "123 Main St (estimated)". This is the usual case in Texas, where many addresses come
   from TIGER ranges.
2. **Otherwise, the nearest real numbers.** The suggestion offers the nearest number
   below and the nearest above, same side of the street first (same odd or even):
   "No 123 on Main St — 121 Main St, 131 Main St".
3. **Streets split into several entries** under one name in one city are treated as one
   street for this, so the closest number may be on another stretch of it.
4. **A street with no numbers at all** gives "Main St, Austin (no house numbers)", which
   routes to the middle of the street.

Whether Texas's OBF files really carry TIGER ranges is unconfirmed. P11.0 checks with
one street on the Austin file.

## 6. POI quick buttons and markers on the map (P11.5)

**The buttons:** three toggles, **Gas**, **Food** and **Shop**, sit in a row along the
**top edge of the map pane**. That spot is free: the left bubbles and the zoom bubbles
are both at the bottom, and the header has about 5 px spare on Ford's 726×480. They
are drawn like the existing bubbles, and a lit button means its markers are on. More
than one can be on at once.

**What each button shows** (from OsmAnd's `poi_types.xml`, to be confirmed against the
real category table in P11.0):

| Button | OBF categories |
| --- | --- |
| Gas | `transportation` / `fuel` only; EV charging is left out (§12 decision 3) |
| Food | `sustenance`: restaurant, fast_food, cafe, food_court, barbecue |
| Shop | every `shop` subcategory (§12 decision 3) |

**The markers:**

- They use VTM's `ItemizedLayer` (in VTM 0.25, so no new dependency), drawn above the
  route line and below labels.
- They cover what is **visible in the map pane**. They are re-queried after the map
  stops moving (500 ms), and while following a route at most every 10 s.
- There are at most **25 per category** by default, nearest the centre first, so a Dallas
  downtown doesn't bury the map. The number is a setting (§12 decision 5).
- Each category has its own glyph and colour, in both the day and the night theme.

**Tapping a marker** shows the place in the flex tile, the same way a notification
does: name, kind, distance and the open-now line when opening hours are known. The tile
has **Go** and **Close** buttons. **Go** goes through `Navigation.set`, as a favourite
does.

**What is remembered:** toggles last for the drive only, are off when a projection
starts, and are not saved.

**Counts:** P11 counts queries, markers drawn, taps and Go presses in the report. Place
names never go in the report.

## 6a. Search ahead on the route (P11.5a)

James asked for this on 3 October 2026: press a button and be told something like
"There's a gas station along your route in 12 miles. It's a 3-minute detour."

**When it applies.** Only while a route is being followed. With no route, the buttons
behave as in §6.

**The button.** While following a route, tapping **Gas**, **Food** or **Shop** does two
things:

- it lights that category's markers as usual, plus markers along the route ahead;
- it **searches ahead** and announces the best stop.

Tapping a lit button turns it off, as in §6. Pressing it again later searches again from
wherever the vehicle is by then.

**What "along the route" means.** POIs within a **corridor** of the remaining route:

- **Width:** 0.5 mile either side by default.
- **Length:** 50 miles ahead by default, or to the destination if that is nearer.
- **Query:** the route's line is split into short pieces, POI boxes are queried for each
  piece's bounding box, and each hit is projected onto the line. That gives two numbers:
  its **distance ahead** along the route, and its **distance off** the route.
- Anything behind the vehicle, or more than the corridor width off the route, is
  dropped.

**The detour.** For the nearest **5** candidates ahead, BRouter works out the extra time
to visit the POI:

- Take a point **A** on the route about 2 km before the POI's projection and a point
  **B** about 2 km after it.
- Route A → POI → B, and compare it with the route's own time from A to B.
- Detour = (time A → POI + time POI → B) − time A → B.

This is two **short** routes per candidate rather than two whole-trip routes, so it
stays cheap: 10 short routes per press. A candidate whose detour can't be routed is
dropped, and counted.

**Choosing the best stop:** the smallest detour among the candidates, with a tie broken
by the nearer one. A stop under **2 minutes** of detour that is nearer comes before a
slightly better stop further on. The exact rule is a setting default, open to change
after a drive.

**What is said and shown:**

- **Spoken**, when the dashboard voice toggle is on (P8's `Speaker`):
  > "Gas along your route in 12 miles: Buc-ee's, about a 3-minute detour."

  With no name it says "a gas station". With no candidate: "No gas along your route in
  the next 50 miles."
- **The flex tile** lists up to 3 stops, each with name, miles ahead, detour minutes and
  side of the road, and buttons **Add stop** and **Close**. The best stop's marker is
  drawn larger.
- **Add stop** sets the POI as the route's **via point** (`Navigation.via`, the one via
  point `Router` supports today) and re-routes to the same destination. If the route
  already has a via point (from a preview alternative), the stop replaces it, and the
  tile says so.
- **Passing the stop:** whether the via point is let go once the vehicle has passed it is
  unverified today, and P11.5a must check it. If it is not let go, a re-route after the
  stop would send the driver back to it.

**Units:** miles and minutes, following the existing distance and speed settings.

**Settings,** all under Settings → OsmAnd maps:

- *Look ahead*: 50 miles, from 10 to 150
- *Corridor width*: 0.5 miles, from 0.1 to 2
- *Candidates to check*: 5, from 1 to 10

**Speed check (P11.7):** time from the tap to the announcement, on a release build on
the Pixel. The target is under 3 seconds. If 10 short routes take longer, the announcement
says the nearest stop first and the detour follows.

**Reports:** counts only — presses, candidates found, detours routed and failed, and the
time to the announcement. Never names or places.

## 7. OsmAnd's on-device search API (P11.6, optional)

`OsmandAidlServiceV2.search(SearchParams, callback)` runs OsmAnd's own offline search
engine (`SearchUICore`) on the phone. It can return cities, streets, **house numbers**
and intersections, with lat/lon, name, type name and alternative names. James has
accepted it because it is local.

**Limits:**

- **OsmAnd's Connected apps list must include us, switched on.** An app OsmAnd hasn't
  seen is added switched off, and its searches are refused.
- **There is no category search**, so it cannot fill the Gas/Food/Shop markers.
- **Results carry no OSM id and no house-number detail**, so it can't do the
  closest-number fallback.
- **It shares one search engine with OsmAnd's own search screen.**
- **No Maven artifact:** the `.aidl` files would be vendored unmodified, under GPLv3.
  Binding needs a `<queries>` entry for the service's action, which is a spec amendment
  (§11).

**Recommended use:**

- **A second source of suggestions**, shown under ours and switched off by default.
- **The reference for the lab:** the same query run through both, compared in P11.0 and
  in §9.

The OBF reader stays the main engine, because POI markers and the closest-number
fallback both need it whatever the API does. If James would rather have the API as the
main engine, see §12 decision 2.

## 8. Privacy and reports

- **Typed text, suggestions and chosen places stay on the phone.** They never go in the
  event log or the report, as in P6.4. The report gets counts only: queries, results by
  kind, files read, newer-format files refused, and query times (p50 and p95).
- **No search history is kept.** A chosen place can be saved as a favourite through the
  existing flow.
- **OBF data is OpenStreetMap, under ODbL.** P11 reads James's own copies and doesn't
  pass the data on, so the licence has no notice duty here.

## 9. Lab and checks

- **Host tests, against real data:** a copy of `Us_texas_austin_northamerica.obf` is
  pulled to `~/.cache/openauto-android/obf/` with `adb` (the shell can read OsmAnd's
  folder) and is **never committed**. A small set of real-world checks — a known address
  with an exact number, one inside a range, one that must fall back to the closest
  number, a ZIP, a fuel station by name — has its expected positions taken from
  **OsmAnd's own search on the phone**, not from our reading of the proto. Every expected
  position comes from the phone, not from the proto's field names.
- **Speed:** query time on the Pixel, measured with a **release** build only (debug
  builds were about 30× slower for routing). The target is the first suggestions within
  300 ms of the last keystroke, and a marker refresh within 500 ms.
- **The emulator lab runs in Austin** (§12 decision 6). The lab receiver's fixed position
  is 42.4242,−71.2121 (Massachusetts), so P11 runs override it to downtown Austin.
  The lab data, pulled from the Pixel on 3 October 2026 and never committed:
  - `obf/Us_texas_austin_northamerica.obf` and `obf/Us_texas_amarillo_northamerica.obf`:
    updated, in the newer layout;
  - `obf/Us_texas_lubbock_northamerica.old-2026-06.obf`: an older-layout sample;
  - `maps/texas.map`: Mapsforge, from James's GadgetBridge folder, 575 MB;
  - `rd5/W100_N30.rd5`: already there from P6.3. Either way `LAB_POI=gas|food|shop` turns buttons on at start-up, and
  `tools/` gains a pixel check that markers are present.
- **The Pixel, then the truck:** typing on the phone and routing to the result; markers
  on SYNC 4, with a tap and Go, parked.

## 10. Slices

| Slice | What | Needs |
| --- | --- | --- |
| **P11.0** | On the Pixel: James moves OsmAnd's storage to a manual folder (§1). A debug-only probe asks OsmAnd's API for the §9 check addresses and "fuel", and writes the answers to `files/` for `run-as`. Records whether TIGER ranges are present and the real category keys. | James, phone |
| **P11.1** | Maps folder grant, `DropFolder.choose()` fix, file listing, Settings card | P11.0 folder |
| **P11.2** | OBF container walker and table of contents; address reader; host tests on Austin | — |
| **P11.3** | POI reader; host tests | P11.2 |
| **P11.4** | Parser, search, closest number; phone search UI with live suggestions; Go | P11.2 |
| **P11.5** | Map buttons, markers, tap card, `LAB_POI` | P11.3 |
| **P11.5a** | Search ahead: route corridor, detour times via BRouter, spoken and tile result, Add stop as via point; `LAB_POI_AHEAD` with a route | P11.5 |
| **P11.6** | Optional: AIDL as a second source (vendored `.aidl`, `<queries>`) | decision 2 |
| **P11.7** | Pixel release timing; truck: markers on, tap, Go; a search ahead on a real drive, with the stop added | all |

P11.2–P11.5 can be built before P11.0 finishes, against the pulled Austin file. Only
P11.1's use on the phone waits for the folder.

## 11. Spec and plan amendments this needs (proposed, not applied)

1. **spec §5.2 / APP09 integration list:** add *OsmAnd maps*: "reads the OsmAnd map
   files in a folder you choose, on the phone, for search and map places. No network.
   Nothing written." No new permission: a folder grant is not a manifest permission.
2. **Only if P11.6 is built:** spec §5.2 and `tools/verify-apk.sh` gain a second
   `<queries>` entry, the intent action `net.osmand.aidl.OsmandAidlServiceV2`, and the
   integration list gains *OsmAnd search*.
3. **spec, third-party runtime code:** record `OBF.proto` (and, if built, the `.aidl`
   files) as vendored and unmodified, GPLv3; add both to `docs/licenses.md`.
4. **plan.md:** a P11 entry like P10's, pointing here.
5. **`docs/p6-plan.md` §5.1:** a note that P6.4's "no index of our own" still holds
   (we build none), but that P11 now reads OsmAnd's.

## 12. Decisions — 3 October 2026

1. **Where the maps are read from:** settled by the move to `Documents/osmand` (§1). No
   fallback is needed.
2. **Main engine: our own reader.** OsmAnd's API stays optional (P11.6), as a second
   source and as the lab reference.
3. **Shop means all of `shop`.** Every `shop` subcategory the file's table holds, with
   none left out. **Gas does not include EV charging:** `fuel` only.
4. **Typing on the phone only.** A car-screen keyboard is not planned.
5. **Marker count is a setting.** Settings → OsmAnd maps → *Markers per category*,
   default 25, range 5–100, so James can find the right number on a drive. **Search
   ahead: yes** (§6a). While a route is followed, a button searches the route ahead and
   announces the best stop with its detour.
6. **Lab data: Austin.** P11's lab runs are moved to Austin, not Massachusetts:
   - the lab position is overridden to downtown Austin;
   - the map is the Texas Mapsforge file;
   - routing uses `W100_N30.rd5`;
   - the OsmAnd maps are the updated Austin file.

   All of them live in `~/.cache/openauto-android/` and none is committed (§9).

## 13. Risks

- **The format, read from the proto alone.** Field names and comments have been wrong
  before (see "Schema names are not evidence"). Every decoded value is checked against
  OsmAnd's own answer on the phone, never against our reading of the proto.
- **Format drift:** OsmAnd's maps update monthly, and the July 2026 name-index change
  will reach this phone at the next update. The table of contents refuses what it
  doesn't know, so P11 degrades to "newer format" rather than misreading.
- **Storage:** OsmAnd may refuse a manual folder, or later move its data back. The card
  says "No OsmAnd maps in this folder", and nothing breaks.
- **Speed on 2 GB of files:** the table of contents is held in memory, and cities and
  streets are read lazily. P11.7 measures on a release build before a promise is made.
- **Marker clutter and touch targets** on the 726×480 pane are untested. The cap and the
  button size are settings defaults, open to change after a drive.
