# P11.1 and P11.4 (phone side) results — maps folder, Settings card, typed search — 3 October 2026

This records the Android side of P11.1 (the maps folder and Settings → OsmAnd maps) and
the phone UI of P11.4 (typed search with live suggestions) from `docs/p11-plan.md`. The
OBF reader (`docs/p11-2-3-results.md`) and the search engine
(`docs/p11-4-search-results.md`) were built alongside, by others. Nothing here has run on
the Pixel or the truck. Nothing is committed.

## 1. Status

| Part | State |
| --- | --- |
| Read-only maps folder grant, `DropFolder.choose()` fix | Built. Grant rules host-tested; the grants themselves need the phone. |
| File listing, open, cache, close | Built. File-name filter, cache key and status lines host-tested; SAF listing and `ParcelFileDescriptor` reads need the phone. |
| Settings → OsmAnd maps card | Built. Not seen on a screen. |
| `PoiSettings` | Built and host-tested (defaults, repair, stored keys, clamping). |
| Typed search UI on Home | Built against `AddressSearch`. Not seen on a screen; no query typed. |
| Report counts | Built (`[routing]` lines). |

## 2. What was built

App (`app/src/main/kotlin/org/openautoandroid/source/`):

- **`PoiSettings.kt`**: `PoiValues` (pure; stored in a `poi` preferences file as
  `poi_markers_per_kind`, `poi_ahead_miles`, `poi_corridor_tenths`,
  `poi_ahead_candidates`) and `object PoiSettings` with the getters the dashboard calls and
  clamping setters. The corridor is stored in tenths of a mile, since preferences hold no
  double. A stored value out of range reads as its default, like `TrafficSettings`.
- **`ObfLibrary.kt`**:
  - `ObfRules` (pure): which names are read (`*.obf` only; not `*.srtmf.obf`,
    `*.srtm.obf`, `*.wiki.obf`, `World_basemap*`, hidden files, or `.obf.download`), the
    `FileKey` (name, size, modified time), the region from OsmAnd's file name, which
    persisted grant is which (`isDropGrant` read+write, `isMapsGrant` read-only), and
    the card's status line in the plan's §2 wording.
  - `ObfLibrary`: the read-only grant, a listing of the folder's direct children, each
    file opened through `openFileDescriptor(…, "r")` → `FileInputStream(fd).channel` →
    `ObfFile`. Open files are cached by key; a listing is trusted for 15 s, then repeated;
    a changed key closes the old source and opens the new file. A file whose OBF version
    is above 2 is counted as **newer format**; one whose closing version does not match
    its opening one (cut short) or that throws is counted as **could not be read**.
    Neither throws out. Debug builds also read the `.obf` files in `filesDir/lab-obf`.
    Files stay open while a holder holds them (`hold("projection")` from
    `ProjectionService`, `hold("screen")` from `ConnectionActivity.onStart`/`onStop`); when
    the last lets go, everything is closed on the worker thread.
  - `ObfReaders` (private): the one place `ObfFile` is named.
- **`PhoneSearch.kt`**: the debounce (250 ms), one background thread, a generation number
  that a newer keystroke bumps (passed to `AddressSearch.suggest` as `cancelled`), 8 rows,
  3 characters minimum, and counts. Text the old box already routes (coordinates, a
  `geo:` URI, an OsmAnd link, two points) is not searched.
- **`OsmAndMapsViews.kt`**: `SearchSuggestions` (the rows under the destination box; a
  tap routes) and `OsmAndMapsCard` (the Settings card).
- **`ConnectionActivity.kt`**: the suggestions under `routeInput` on Home; the card on
  Settings, after Weather and before Speech; `REQUEST_MAPS_FOLDER`; `chooseMapsFolder`,
  `searchPoint` (the vehicle while projecting, else the phone's newest cached fix) and
  `routeToSuggestion` (`Navigation.set(…, DestinationSource.SEARCH)` and the same toast
  a favorite gets); `onStart`/`onStop` holds.
- **`DestinationParser.kt`**: `DestinationSource.SEARCH("search")`.
- **`DropFolder.kt`**: `choose()` gives up only earlier read+write grants; `folder()`
  uses `ObfRules.isDropGrant` (unchanged meaning).
- **`ProjectionService.kt`**: the projection's hold, released in `stopProjection` and in
  `onDestroy`'s path.
- **`Routing.kt`**: `ObfLibrary.report()` after `Navigation.report()`.
- **`strings.xml`**: the card's and search's strings, added in one block after the
  weather strings; `route_input_hint` now reads "Address or place, lat,lon, or two points
  lat,lon lat,lon".

Tools: `tools/jni-host-test/ObfLibraryTest.java`, run from `jni-host-test.sh` after
`DropFolderTest`.

## 3. Report lines

Counts only (plan §8):

```
obf_files_open= obf_newer_format= obf_unreadable= obf_open_failures= obf_listings= obf_opens= obf_newer_seen= obf_closes=
search_queries= search_cancelled= search_failures= search_suggestions_shown= search_empty= search_picks=
search queries= cancelled= empty= failed_sources= exact= … p50_ms= p95_ms= max_ms=   (AddressSearch's own, once a search has run)
```

The event log gets "maps folder chosen", "a file could not be opened: <exception class>"
and "search suggestion picked"; never a file name, a typed text, a label or a place.

## 4. Deviations and choices

- **Distances are stored in miles**, as the plan states them, and **shown in the
  dashboard's speed unit** (km when it is km/h). The card's choices are fixed lists
  (markers 5–100, look ahead 10–150 mi, corridor 0.1–2.0 mi, candidates 1–10); a stored
  value not in the list is shown as an extra choice.
- **The card is on the Settings tab**, as the plan names it, not on Maps & routing beside
  the drop folder.
- **"Newer format" means OBF version above 2.** The reader never reads the address name
  index whose layout changed in July 2026, so a July file is not "newer" to it; only a
  version bump is.
- **Picking the drop folder as the maps folder is refused** with a toast: its
  read+write grant would hide the read-only one. Picking the maps folder as the drop
  folder upgrades the grant and the maps folder then reads as "No folder chosen"; not
  guarded.
- **The newest date** is the file's own `dateCreated`; the file's modified time only when
  the file gives none.
- **Status before the first listing** says "Reading maps…" and lists off the main thread.
  Closing the files keeps the last status, so a render never reopens them.
- **`ObfLibrary.hold(holder, on)`, `refresh`, `hasSources`, `report` and `listener`** are
  additions beyond the five agreed functions.

## 5. Build and tests

`tools/android-build.sh` (see §7). `ObfLibraryTest`: file filter, keys, regions, grants,
every status line, `PoiValues` defaults, repair, round trip and clamping. No manifest
change, so `tools/verify-apk.sh`'s permission and `<queries>` pins are untouched.

## 6. What needs the phone

1. Settings → OsmAnd maps → Choose folder → pick `Documents/osmand`. The status should
   read like "11 maps · Texas · newest 30 Jun 2026" (or a July date for updated files).
2. Settings → Maps & routing → choose the drop folder again: the maps status must not
   change to "No folder chosen" (the `choose()` fix). And the reverse.
3. Home: type "123 Main St Au" slowly and quickly; rows should appear about 250 ms after
   the last keystroke, never for an older text. Tap one: the share toast, and a route.
4. Diagnostics report: the `obf_*` and `search_*` lines, with no names in them.
5. Time (release build): the first suggestions after the last keystroke, against the
   plan's 300 ms target.
6. Unplug, leave the screen: `obf_files_open=0` in the next report.

## 7. Build outcome

- **Run 1:** failed to compile: a KDoc held `lab-obf/*.obf`, and `/*` opens a nested
  comment in Kotlin. Fixed.
- **Run 2:** compiled clean (no warning from these files), then `lintAnalyzeDebug` sat in
  `BidirectionalTextDetector` for over 50 minutes while other agents' files were being
  written; it was stopped. Not seen again.
- **A parallel build** caught two `UnusedResources` errors from this work
  (`osmand_maps_reading`, `search_suggestions_label`): the first is now used by
  `ObfLibrary.status`, the second removed.
- **Run 3:** Gradle `BUILD SUCCESSFUL` (assemble, lint), `verify-apk` ok on all three
  APKs. `jni-host-test.sh` then stopped at **`DashboardTripTest`**: "with no card it
  pans, as before", in the dashboard's map-pan code, which is not part of this work. The
  tests after it did not run in that pass, so these were run by hand on the host JDK
  against run 3's classes: `ObfLibraryTest` 41 checks ok, `DropFolderTest` 23 ok,
  `DestinationTest` 50 ok, `FavoritesTest` 21 ok.
- Nothing was installed on the Pixel.
