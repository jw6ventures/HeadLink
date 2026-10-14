# P11.4 results — typed address search — 3 October 2026

This records the pure-JVM search engine of P11.4 in `docs/p11-plan.md` (§4 typed search, §5
the closest number, §8 privacy). The phone's search screen (`PhoneSearch.kt`) and the reader
(`ObfFile` and friends) were written at the same time by others and are not covered here.
Nothing has run on the Pixel or the truck. Nothing is committed.

## 1. Status

| Part | State |
| --- | --- |
| Parser (`AddressQuery.kt`) | Built and host-tested. |
| Search, closest number, POI names (`AddressSearch.kt`) | Built and host-tested on a synthetic file and on the real Austin file. |
| Speed on the Pixel (release build) | **Not measured.** Host numbers only (§4). |
| Expected positions from OsmAnd's own search on the phone (plan §9) | **Not done.** The real-file checks compare with known places, not with OsmAnd's answers. |

## 2. What was built

App (`app/src/main/kotlin/org/openautoandroid/source/`), pure JVM:

- **`AddressQuery.kt`**
  - `HouseNumber`: a leading number with a letter or fraction (`123`, `123B`, `123 1/2`,
    `123½`). The same parse reads the numbers stored in a file (`111-400` reads as 111).
  - `AddressText`: the folding both sides of every comparison go through. It lower-cases,
    drops accents and apostrophes, and splits at punctuation. It rewrites street type words
    and direction words to one short form each (St/Street, Ave, Rd, Dr, Blvd, Ln, Pkwy, Hwy,
    Fwy, Expy, Ct, Cir, Trl, Pl, Way, Loop, Ter, Cv, Xing and more; N/North, NE/Northeast…).
    It rewrites numbered roads to one prefix: FM ("Farm to Market Road"), RM ("Ranch to
    Market Road", "Ranch Road", RR), SH ("State Highway", TX 71), US ("U.S. Highway",
    US-183), I ("I-35", "IH 35", "Interstate Highway 35") and CR ("County Road"). A route
    prefix is rewritten only before a number, so "Us" alone stays "us".
  - `AddressQuery.parse(text)`: **every plausible reading, best first**, not one guess:
    - a trailing ZIP (5 digits, or ZIP+4), then a trailing state (a full name, or a code);
    - a trailing two-letter code is read both as a state and as a word ("Oak Ct" is not
      Connecticut);
    - a trailing word that a state starts with ("Austin T", "Austin Tex") is also read
      without it, so the suggestions don't vanish while "TX" is typed;
    - a comma decides where the city starts; without one, no city (cities near the phone)
      and each of the last 1–3 words as the city;
    - a short text with no number may be only a city ("Au");
    - three or more digits alone are a ZIP being typed ("787"); one or two are no reading yet.
- **`AddressSearch.kt`**
  - `AddressSearch(sources)`, `suggest(...)`, `warm(...)` and `stats` (§6).
  - **Cities:** prefix or full match on folded names (and `nameEn`). ZIPs match only
    POSTCODE entries, and only digits do. With no city typed, the 6 cities and villages
    nearest the phone are searched. With no position, the file's centre is used.
  - **Streets:** a full match of the folded name scores highest. Next is the typed name with
    the direction or type word left off ("Congress" for "South Congress Avenue"). A prefix
    scores lowest, compared on the folded, the canonical and the as-written forms, so a
    half-typed "Congress Aven" still matches.
  - **Split streets:** OsmAnd gives the stretches of one name in one city a suffix:
    "Congress Avenue (Downtown)", "Congress Avenue South (St. Elmo)". The suffix is dropped
    to group them into one street. A STREET suggestion goes to the stretch nearest the
    phone, and its line two names that stretch ("Downtown").
  - **House number (§5)**, over every stretch of the street:
    1. **Exact:** a plain building with that number. Line two is its ZIP, when the file has one.
    2. **Estimated:** an interpolation range that holds the number, honouring EVEN/ODD/ALL,
       STEP (numbers ≡ the start mod the step) and ALPHABETIC (12A–12F). It is placed
       linearly along the range, and works for ranges numbered downwards too.
    3. **Closest:** the nearest number below and the nearest above, as two suggestions,
       the same side (odd/even) first and the other side only if there is none. Numbers
       inside a range that doesn't hold the query count too ("No 20 on Elm Court — 18 Elm
       Court", line two "Austin · below, estimated"). Line two says "below"/"above", and
       adds "other side" and "estimated" when they apply.
    4. **A street with no numbers:** "Quiet Trail, Austin (no house numbers)", at the street's position.
  - **ZIPs:** "78701" gives a POSTCODE suggestion. "1100 Congress Ave 78701" searches the
    postcode's own streets. A postcode suggestion is placed at the **median of its streets'
    positions**, not at the file's own point (§5.3).
  - **Places by name**, only with no house number and no street found, and only with a
    position. The reader's name search runs first, in a box of about 15 km. When it finds
    fewer than `limit` distinct places, the first typed word is also asked alone. The
    Gas/Food/Shop kinds near the phone (up to 1000 per kind) are then scanned, with each name
    word compared without punctuation. This is why "HEB" finds the "H-E-B" stores (§5.3).
    Places with more typed words in their name rank first, then those of the buttons' kinds,
    then the nearest. Digits alone never search places.
  - **Order** (changed from plan §4 by the lead, §5.2): addresses before places; then the
    street match (as typed, then full, then prefix); within one street match exact, then
    estimated, then closest, then streets, cities and ZIPs; then the city match (typed in
    full, typed prefix, ZIP, then nearby), the gap from the typed number, then distance
    from the phone. One suggestion is kept per label within about 100 m.
  - **Cancellation:** `cancelled()` is checked before and after every reader call. A
    cancelled search returns an empty list and is counted.
  - **Failures:** an `IOException`, or a `RuntimeException` from a reader bug, is caught per
    file and counted. The other files still answer. A `sources()` that throws gives an
    empty answer. `suggest` and `warm` never throw.
  - **Caches:** each file's folded city names are kept, keyed by the identity of the
    reader's city list. Each city's folded street names are kept in a 256-city LRU, keyed
    by the list's identity, so a file the reader replaced or re-read is folded again.
    Buildings are read once per search.

Tools:

- `tools/jni-host-test/AddressSearchTest.java`: 151 checks with the Austin file, 140
  without it. It runs in `tools/jni-host-test.sh`, with a header bullet, after `ObfTest`,
  and takes `OBF_DIR`, which `tools/android-build.sh` already mounts for `ObfTest`.

**Build.** `tools/android-build.sh` (with `OBF_DIR`) compiled the app with these files, and
lint and `verify-apk` passed. `jni-host-test.sh` then stopped early, in
`DashboardTripTest` ("with no card it pans, as before"). That is the dashboard work being
done alongside this, not this engine. `AddressSearchTest` was then run on its own, in the
pinned image, on the Gradle-built classes: **150 checks pass with the Austin file, and 139
without it.** After the lead's ordering change (§5.2) it was rebuilt (`:app:compileReleaseKotlin`)
and run again the same way: **151 and 140 pass.** The numbers in §4 come from these runs and
from host runs.

## 3. Test evidence (synthetic)

The fake `ObfSource` holds Austin, Round Rock, Aubrey (a village) and the ZIP 78701 in one
Texas file. Another Oklahoma file, a New York file and a file whose `cities()` throws
`IOException` are added per test. The tests cover:

- **Parsing:** the parser cases above, and the canonical forms both ways (33 pairs).
- **Every kind:**
  - EXACT, with its ZIP on line two.
  - INTERPOLATED: odd and even ranges laid side by side (the right line is picked), a STEP
    range, letters, an ALL range, and a range numbered downwards.
  - CLOSEST: below on the other side when none is on this side, above on the same side
    skipping a nearer other-side number, only above when nothing is below, `103B` falling
    back to `103`, step-range neighbours marked "estimated", and `12G` past `12A`–`12F`.
  - STREET, with and without "(no house numbers)".
  - CITY ("Au" gives Austin before Aubrey; "Austin" gives the city before Austin Avenue).
  - POSTCODE ("78701", and "787" while it is typed).
  - POI ("Torchy" near the phone only; "HEB" gives "H-E-B" before the bus stop
    "Oltorf (HEB)"; never with a house number, a street hit or no position).
- **Split street:** "107 Main St" finds 105 on the north stretch and 1001 on the south one.
  "Main St Austin" is one suggestion, at the nearer stretch.
- **Order:**
  - exact in Round Rock (nearby) before estimated in Austin;
  - for "1100 Congress Ave": Congress Avenue, then South Congress Avenue, and not
    Congressional Circle;
  - for "1100 Congress": both full names by distance, then the prefix Congressional Circle;
  - for "1101 Congress Ave", where only South Congress has a range holding 1101: the two
    closest numbers on Congress Avenue first, then the estimate on South Congress (the
    street as typed beats the kind);
  - the typed city before nearby ones, then distance;
  - the limit is honoured, and the search works with no position.
- **State:** "Ada OK" answers from the Oklahoma file. "Ada Texas" never reads it (its read
  count is unchanged). `Us_new-york_…` matches "New York".
- **Cancellation:** a keystroke landing during the first `streets()` call leaves no reader
  call after it. The result is empty, and `cancelled` is 1.
- **Failure:** the throwing file is counted once, and the good file still gives the exact
  answer. No sources at all and `warm` on a failing file don't throw.
- **Report:** queries, empties and results by kind are counted. The line holds none of
  "austin", "congress", "oak", "torchy", the typed text, the house number or a coordinate.

## 4. Real file: `Us_texas_austin_northamerica.obf`

This is the updated (July layout) copy in `~/.cache/openauto-android/obf/`, 868 entries:
36 cities, 479 villages, 353 postcodes. Austin has 12 978 streets, 1 480 of them without
numbers. Its buildings are 357 975 plain numbers and 56 245 ranges (8 616 ALL, 23 809 EVEN,
23 820 ODD). **TIGER-style ranges are present**, which answers the plan's §5 question for
this file. Downtown Congress Avenue, for example, has 100–198 EVEN and 101–199 ODD ranges,
with OSM address points at their ends.

All searches were made from the Capitol (30.2747, −97.7404):

| Query | First suggestion | Distance from the Capitol |
| --- | --- | --- |
| 1100 Congress Ave Austin | EXACT "1100 Congress Avenue, Austin", line two "78701", at 30.27649, −97.73978 | **207 m** (the address point is on the grounds' north side; the Capitol is 1100 Congress) |
| Congress Ave Au | STREET "Congress Avenue, Austin", line two "Downtown" | 1 052 m (the middle of the downtown stretch) |
| 78701 | POSTCODE "78701" at 30.27143, −97.74006 (median of its streets) | **365 m**. The file's own point for 78701 is 30.34103, −97.70451, **8 140 m** away (§5.3). |
| 1101 Congress Ave | CLOSEST "No 1101 on Congress Avenue — 1099 Congress Avenue", "Austin · below" | 241 m |
| | then CLOSEST "… — 1301 Congress Avenue", "Austin · above" | 210 m |
| | then INTERPOLATED "1101 Congress Avenue South (estimated)" | 2 473 m |
| 1100 Congress Ave 78701 | EXACT, through the postcode's streets | 207 m |
| 123 Main St Au | Main Street, Austin, among the suggestions | — |
| I-35 Frontage Rd Austin | STREET "I 35 Frontage Road, Austin" and "… Interstate 35 Frontage Road" | — |
| RM 2222 | STREET "Ranch Road 2222, Austin" ("Ranch to Market Road 2222" folds into the same street) | — |
| HEB | POI "H-E-B" supermarkets, nearest first | — |

**Host timings** (Fedora, JDK 17, other builds running at the same time; ms per `suggest`):

- Opening the file takes 6–40 ms, and `cities()` 15–20 ms.
- **The first query, cold, takes 270–380 ms.** It is mostly the first read and fold of the
  ~13 000 Austin streets (and of the other nearby cities), plus JIT warm-up.
- With `warm()` called first (122–372 ms, done when the search screen opens), the first
  keystroke "1100 C" takes **8–36 ms**.
- Typing "1100 Congress Ave Austin TX" a key at a time, warm: **mean 7 ms, worst 41–58 ms**.
  The worst keystroke is "…Austin T", which also searches every city starting with "T".
- Warm address queries take 3–10 ms: "1100 Congress Ave Austin" 6–10, "Congress Ave Au"
  5–8, "1101 Congress Ave" 4–8, "Au" 3–4. "78701" takes under 1 ms.
- **POI name searches take 55–255 ms**: "Torchy" 54–106 ms, "HEB" 76–255 ms. That is the
  fallback scan of up to 1000 places per kind.
- Across one run: p50 5 ms, p95 65 ms, max 272 ms (the cold one).

The plan's target is first suggestions within 300 ms on the Pixel, on a release build. The
host's warm numbers leave a wide margin for addresses. The cold first query and the POI
fallback are the two that could miss it on the phone. `warm()` exists for the first;
P11.7 measures both.

## 5. Deviations and decisions, for James

### 5.1 Additions to the contract

`ObfModel.kt` is unchanged. Three things are public beyond what the brief named:

- `AddressSearch.warm(nearLat, nearLon, cancelled)`, which reads ahead for the first keystroke;
- the `SearchStats.Snapshot` type with `line()` (`PhoneSearch.kt` already uses it);
- the parser types `AddressQuery`, `HouseNumber` and `AddressText`, which the host test
  uses directly.

### 5.2 Order: the street match before the kind (deviation from plan §4, the lead's decision)

Plan §4 orders by kind first (exact, estimated, closest), then by street match. On the real
file that put, for "1101 Congress Ave", an *estimate on Congress Avenue South*, 2.5 km
away, above the *closest real numbers on Congress Avenue itself*, 240 m away. Downtown
Congress Avenue has no range holding 1101 (1001–1099 ODD, then 1301). The lead decided on 3
October that the street match comes first: the street as typed, then a full name, then a
prefix. Within one street match the plan's exact, estimated, closest still holds. Places
stay after every address. This is `ORDER` in `AddressSearch.kt`, and both the synthetic and
the real test check this case.

### 5.3 Where the engine works around the data

- **Postcode positions.** OsmAnd's point for a postcode can be far from it: 78701 is
  downtown, but its point is 8 km north. A postcode suggestion is therefore placed at the
  median of its streets' positions. This is my choice, not the plan's. It should be checked
  against OsmAnd's own answer for 78701 on the phone (plan §9).
- **"HEB" is "H-E-B".** The reader's name filter is a plain case-folded "contains", so
  punctuation defeats it. The engine adds its own scan with punctuation ignored. That scan
  only covers the Gas/Food/Shop kinds, so a punctuated name of any other kind is still
  missed. Extending it to every kind would need a reader change: the reader's `pois` returns
  nothing for no kinds and no name, and compares names only by "contains". Left for the
  reader, as the lead allowed.
- **Junk names come through as streets.** "Congress Avenue, #1039" and "North Interstate
  35, Suite 1805" are in the file as streets, and are offered like any other.

### 5.4 Behaviour the plan left open

- **Wording:**
  - "1100 Congress Avenue, Austin" (exact);
  - "1101 Congress Avenue South (estimated)", with the city on line two;
  - "No 1101 on Congress Avenue — 1099 Congress Avenue", with "Austin · below" on line
    two, plus ", other side" and ", estimated" when they apply;
  - "Quiet Trail, Austin (no house numbers)";
  - "Austin" with "city" or "village", and "78701" with "ZIP code".
- **Distances are not in the text.** The UI has the position and the user's units.
- **Places search** needs a position: there is no box without one.

## 6. Public API

```kotlin
class AddressSearch(sources: () -> List<ObfSource>) {
    val stats: SearchStats
    fun suggest(text: String, nearLat: Double?, nearLon: Double?, limit: Int = 8,
                cancelled: () -> Boolean = { false }): List<Suggestion>
    fun warm(nearLat: Double?, nearLon: Double?, cancelled: () -> Boolean = { false })
}
class SearchStats {
    fun snapshot(): Snapshot   // queries, cancelled, empty, sourcesFailed, results by kind, p50/p95/max ms
    data class Snapshot(...) { fun line(): String }   // counts only
}
class AddressQuery { companion object { fun parse(text: String): List<AddressQuery> } }
class HouseNumber(value: Int, suffix: String) { companion object { fun parse(stored: String): HouseNumber? } }
object AddressText { fun normalize(text: String): String; ... }
```

## 7. Open issues

- **Pixel timing** on a release build (P11.7), with the full 11 Texas files rather than one.
  Nearby cities are searched in every file, so 11 files multiply the cold cost.
- **Expected positions from OsmAnd's own search** (plan §9) are still to be taken on the
  phone: 1100 Congress, 78701, an estimate and a closest number.
- **The reader is shared work in progress.** These numbers are from the reader as it stood
  on 3 October. Its street positions match its buildings (the median gap between a street
  and its nearest building is 0 m in Austin), but they were not checked independently here.
- **Only the July-layout Austin file was searched.** The older-layout Lubbock sample and the
  other regions were not.
- **Not handled:** street-number ranges typed as "123-125", unit or suite numbers, "Saint"
  against "St", ordinal words ("First" against "1st"), and intersections ("Congress & 6th").
