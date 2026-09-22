# P6 plan — navigation — 20 September 2026

Draft. Scope for the navigation work package, which delivers spec APP07 ("offline-capable
OSM navigation using Android's normal location APIs") on top of the owned map P5.5 built.
It picks up the line the [P5 plan](p5-plan.md) §5 ended on and deferred:

> Turn-by-turn to Ford's cluster over the `navigation` channel (ch11) is a separate slice
> after a route exists.

That deferral turns out to be the wrong order, and §7 below inverts it. The reason is in
§2: the navigation descriptor Ford has been sending since the first vehicle run says
something unexpected, and reading it cost nothing.

**Status.** §11's descriptor slice (P6.0) is implemented and covered by tests. **The §4
routing-engine decision is taken: BRouter is vendored as source** (20 September 2026,
James), and the vendoring has landed and builds reproducibly — §4.1 records it. **P6.1 is
implemented and lab-verified** (20 September 2026): the lab now drives, the projected map
follows it, and the run caught two defects — see [P6.1 results](p6-1-results.md). **P6.2 is
implemented and lab-verified** (21 September 2026): the `navigation` channel is opened, the
cluster is waited for, navigation focus is taken on the control channel, and a scripted
maneuver sequence goes out at the advertised rate — see [P6.2 results](p6-2-results.md).
**§2's contradiction is answered and G9 is passed** (21 September 2026): Ford SYNC 4.0
renders a purely semantic maneuver — icon, road name, distance countdown — on its own
instrument cluster, with no image bytes anywhere, and it **never sends
`InstrumentClusterStart`**, which makes §3's step 2 wrong for this vehicle (p6-2-results
§7.6). **P6.3 is implemented and lab-verified** (21 September 2026): `.rd5` tiles import
by streaming and are checked against the packaged `lookups.dat`, and a cross-town route is
computed on its own background thread while a 1080p dashboard projects — see
[P6.3 results](p6-3-results.md). Not yet measured on the Pixel. **P6.3a, added at James's
request**: a drop folder the app moves map and routing files in from, every installed map
drawn as one, and Austin to Denver routed across four tiles in 15 s on the desktop — see
[P6.3a results](p6-3a-results.md). **P6.4 is implemented and lab-verified** (21 September
2026): destinations arrive as OsmAnd-style `geo:` and share-sheet text, typed, or by a long
press on the projected map, route from the vehicle, and draw as a line the receiver's
decoded frames show — see [P6.4 results](p6-4-results.md). No real OsmAnd share has been
read yet. **P6.5 is implemented and lab-verified** (21 September 2026): a guidance loop over
the computed route drives the cluster — countdown, `REROUTING`, a new route, arrival — through
a lab drive along real streets that misses a turn; the run caught the source breaking the
head unit's interval after a re-route, which is fixed. See [P6.5 results](p6-5-results.md).
G10 needs the vehicle.

---

## 0. Scope, and what is deliberately outside it

In scope: computing a route offline on the phone, drawing it on the owned map, following
the vehicle along it, and publishing turn-by-turn to the head unit's own cluster display.

Out of scope for P6, stated so it is not assumed:

- **Offline address search.** Mapsforge `.map` carries place and street labels for
  *rendering*; it is not a geocoding index, and BRouter takes coordinates only. Full
  offline search needs a third dataset and an index build. §5 says what replaces it.
- **Voice guidance.** Spoken instructions need either a speech channel or the phone's
  own audio path, and P5.3's slot is a single PCM `MEDIA` stream carrying app-owned
  audio. Worth doing; not here.
- **Lane guidance and junction views.** The schema carries `NavigationLane` (§3), but
  BRouter's model has no lane data to fill it with.
- **Traffic, tolls-as-routing-cost, and anything needing a network.** APP10 stands: there
  is still no `INTERNET` permission.

## 1. The two halves are independent, and that is the plan's main lever

Navigation splits into two pieces that share only a route object:

1. **The owned half** — routing, the route line on our map, and guidance. All of it is
   phone-side, none of it touches the wire, and it is the part spec APP07 actually
   requires.
2. **The head-unit half** — the `navigation` channel, which puts maneuvers on the truck's
   own cluster display rather than into the picture we project. It is protocol work with
   no routing in it at all.

Neither needs the other to be demonstrable. The head-unit half can be driven from a
**scripted maneuver sequence** — the same move P5.3 made when it sent a generated tone
rather than capturing another app's playback, and for the same reason: it makes the gate
checkable without first solving the harder problem behind it.

## 2. What Ford actually advertises — decoded, not assumed

`discovery_channel_011=navigation` has been in every vehicle report since 17 September with
nothing after it, because the descriptor was never parsed. It is not empty. From the
sanitized capture in `protocol/fixtures/ford-sync4-discovery-response.hex` (report
c2407cff, Ford SYNC 4.0, software 25221_PRODUCT.2025), channel 11 carries five bytes:

```
08 90 03 10 02      field 1 (varint) = 400      field 2 (varint) = 2      field 3 absent
```

Against the pinned `NavigationChannelData.proto`:

| Field | Value | The schema's reading |
| --- | --- | --- |
| 1 `minimum_interval_ms` | **400** | at most 2.5 updates per second |
| 2 `type` | **2** | `NEXT_TURN_IMAGE` |
| 3 `image_options` | **absent** | no width, height or colour depth |

**Ford asks for turn images and does not say how big they must be.** That is the finding,
and it is a contradiction this source cannot settle from a descriptor. Three candidate
explanations, none of them chosen here:

- The enum reading is wrong. `NavigationTypeEnum.proto` is marked `confidence: unverified`
  upstream — the weakest confidence class in the vendored set — and this project has now
  had **four** vendored field or enum names turn out wrong on the wire (see
  [references.md](references.md), and spec §8.1's rule that came out of the audio focus
  `unsolicited` flag). A value of 2 may not mean images at all.
- Ford means it, and expects the phone to choose a size. The upstream notes on
  `InstrumentClusterMessages.proto` record that current 16.2 source work found **no
  reachable native `NEXT_TURN_IMAGE` / `NavigationImageOptions` sender path**, and that
  legacy image delivery survives only on the deprecated `0x8004` message. If real Android
  Auto never sends this truck an image either, the advertisement is aspirational.
- `image_options` is genuinely optional because the cluster's icon size is fixed in
  firmware.

**What settles it is sending on the channel and watching the cluster**, which is why §7
puts that slice first. More reading will not do it — and it did not have to. **P6.2 sent,
and someone watched** (21 September 2026): Ford's cluster drew a maneuver icon, a road
name and a distance countdown from a stream carrying **no image bytes at all**, so the
`NEXT_TURN_IMAGE` advertisement does not require images and the missing dimensions never
mattered. Of the three candidate explanations above, the third is closest: the cluster's
iconography is its own. The enum reading may still be wrong — Ford drew *an* icon, and
nobody checked it against the maneuver sent.

Two consequences taken now:

- **Start semantic, not pictorial.** The first maneuvers sent should be `0x8003` +
  `0x8006` + `0x8007` — state, notification and distance — with no image bytes anywhere.
  That is the path modern Android Auto uses, and it is the path that does not require
  guessing a size Ford declined to give.
- **Keep the raw value.** The implemented descriptor parse (§11) stores `nav_type` as the
  integer 2 and prints the schema's name beside it in brackets. Nothing branches on the
  name.

## 3. The navigation channel, from the pin

All of this is read from `oaa/navigation/*.proto` at the spec pin (`61eab61`), which
carries a fuller navigation set than the three files vendored into
`protocol/upstream/`. It is **APK-derived schema reading, not wire observation**, except
where marked.

**Service type 10, `NAVIGATION_STATUS`** — the head unit's instrument cluster, a display
separate from the projected surface. Message IDs, from the `ian.java` handler trace:

| ID | Direction | Message | Note |
| --- | --- | --- | --- |
| `0x8001` | HU → phone | `InstrumentClusterStart` | empty; the cluster is available |
| `0x8002` | HU → phone | `InstrumentClusterStop` | empty; it is not |
| `0x8003` | phone → HU | `NavigationState` | one enum: unavailable/active/inactive/rerouting |
| `0x8004` | phone → HU | `NavigationTurnEvent` | **deprecated**; the only path carrying image bytes |
| `0x8005` | phone → HU | `LegacyNavigationTurnEvent` | **deprecated**; flat maneuver + distance |
| `0x8006` | phone → HU | `NavigationNotification` | modern, semantic: steps, maneuver, lanes, road names, destinations |
| `0x8007` | phone → HU | `NavigationNextTurnDistanceEvent` | step distance, ETA, current road — **wire verified upstream 2026-03-04** |
| `0x8008` | phone → HU | `VehicleEnergyForecast` | EV; not ours |

**Navigation focus is not on this channel.** `NavigationFocusRequest` is *control* message
**13** and `NavigationFocusResponse` is **14**, both already in our generated
`ids` enum. This is the session's third focus type after video and audio, and like audio
focus it lives on the control channel rather than the channel it governs.

The split between modern and legacy is gated head-unit-side on `CarInfo` PDK version
(`>= 1.6` gets `0x8006`), and **we have no way to read a PDK version from what Ford
sends** — the P5 plan already recorded that as an open question for audio. So the
modern/legacy choice cannot be made by inspection; it has to be made by trying one and
reading what comes back, with both outcomes recorded.

Working hypothesis for the sequence, to be confirmed or destroyed by a run:

1. Open channel 11 after input binding, opt-in and non-fatal, exactly as `OpenMediaInfo`
   and `OpenAudio` are.
2. Wait for `0x8001 InstrumentClusterStart` rather than assuming the cluster is there.
3. Request navigation focus on the control channel (message 13, `NAV_FOCUS_PROJECTED`).
4. Send `NavigationState(ACTIVE)` and then stream `0x8006`/`0x8007`, never faster than
   the advertised 400 ms.
5. On stop: `NavigationState(INACTIVE)` and release focus.

**Spec §8.1's rule applies to every step of that**: act on what the head unit answers,
not on what a vendored field is named. The audio focus slice lost two vehicle attempts to
exactly this, and the lab could not catch it because both ends of `hu-sim` were written
from the same reading of the same schema.

### One hazard found while reading

`NavigationChannel` declares `minimum_interval_ms` and `type` as **`required`**, so a head
unit that advertises the navigation service without either field makes the *entire*
`ServiceDiscoveryResponse` fail to decode — not just that descriptor. Ford sends both, so
this is latent rather than live, and it predates P6. It is worth deciding whether
descriptor decoding should be per-descriptor tolerant before a second vehicle is ever
tried, because the failure mode is "this head unit has no usable video" rather than
anything mentioning navigation.

## 4. Routing: BRouter, and a distribution problem worse than VTM's

The P5 plan recommended **BRouter** (MIT) on architecture, and that recommendation still
holds — it is pure Java, needs no NDK build, has no transitive dependency graph, and its
`.rd5` tiles are the smallest data footprint of the candidates. GraphHopper (runtime
bytecode generation), Valhalla (Boost/Protobuf/libcurl/Lua) and OSRM (no maintained
Android port) were rejected there and are not re-litigated.

What the P5 plan did **not** check is how BRouter is distributed. Measured 20 September
2026:

- **BRouter is not on Maven Central at all.** A Solr search for `brouter` returns
  `numFound: 0`; `btools/`, `com/github/abrensch/` and `org/btools/` are all 404 under
  `repo1.maven.org`. Distribution is a GitHub release zip (`brouter-1.7.10.zip`,
  6,724,983 bytes, 17 July 2026) plus JitPack (`jitpack.yml` sits in the repo root).
- The project is **MIT**, not archived, and actively maintained (last push 9 September
  2026).

So **P5.5's answer is unavailable here.** VTM had a Maven Central 0.25.0 to pin and
`--write-verification-metadata sha256` handled it with no special cases. BRouter has no
centrally hosted, permanently checksummed artifact to point `verification-metadata.xml`
at. MIT into a GPL-3.0-only work is allowed, so this is a supply-chain question, not a
licence one.

The runtime subset is small enough that vendoring is genuinely on the table. Measured from
the repository tree, excluding tests:

| Module | Files | Bytes | Runtime? |
| --- | --- | --- | --- |
| `brouter-core` | 33 | 325,855 | yes |
| `brouter-mapaccess` | 20 | 94,737 | yes |
| `brouter-util` | 27 | 75,365 | yes |
| `brouter-expressions` | 10 | 60,624 | yes |
| `brouter-codec` | 11 | 46,602 | yes |
| **runtime subset** | **101** | **603,183** | |
| `brouter-routing-app` | 20 | 194,508 | no — it is the Android app |
| `brouter-map-creator` | 35 | 173,106 | no — offline data production |
| `brouter-server` | 12 | 68,174 | no |

**The decision — taken 20 September 2026: (a), vendor the sources.**

- **(a) Vendor the five runtime modules' sources**, pinned and audited the way the
  open-android-auto schema subset is. Keeps `verification-metadata.xml` untouched, keeps
  the offline reproducible build trivially, and the code is reviewable at 600 KB. Cost: a
  third-party source tree in the repo and a manual update path. **Chosen.**
- **(b) Vendor a checksummed jar** built once from the pinned release. Less code in the
  repo; more opaque, and the checksum is ours rather than a registry's.
- **(c) JitPack coordinate.** Cheapest to write and the weakest guarantee — it is exactly
  what P5.5 rejected for VTM 0.30.0.

(a) was the recommendation because it is the only one of the three where "reproducible
offline build" needs no argument, and §4.1 shows that held.

Writing our own router over the Mapsforge `.map` data is not an option and should not be
re-raised: `.map` is a rendering format and carries no routing topology.

### 4.1 The vendoring, as landed — 20 September 2026

`vendor/brouter/`, a plain `java-library` Gradle module, with provenance in
[`vendor/brouter/README.md`](../vendor/brouter/README.md). Pin: commit
`4d2639af77ea5ed9c30d3e400764eb6f9e8522da`, tag `v1.7.10`.

**Held unmodified.** All five packages were diffed against the upstream tree after copying
and are byte-identical; so are the two profile files, which are relocated into the
module's resources and otherwise untouched. A re-sync is a delete and a re-copy.

Measured against the same criteria P5.5 used for VTM:

| Question | Measured |
| --- | --- |
| Offline **reproducible** build survives? | **Yes.** Two clean builds (`rm -rf app/build vendor/brouter/build`, full `tools/android-build.sh`) produced byte-identical release APKs, SHA-256 `17f329a3d81c8acefbe0898dfccbddbe4f694432dd4e957a895c797503095018`. |
| Transitive **AndroidX** pull-in? | **Zero.** No `androidx/` or `com/google/` string in `classes.dex`. |
| **ABI** count? | **One**, `arm64-v8a`, unchanged. BRouter is pure Java and adds no native library; `verify-apk.sh` passed unaltered on both APKs. |
| **APK size**? | 9,092,535 → **9,365,811 bytes, +273,276**. Of that, 43,495 is the two profile files, so roughly **230 KB is dex**. |
| Network in the read path? | None. No `INTERNET` permission, and `verify-apk.sh` still asserts its absence. |
| Android APIs in the vendored tree? | **None.** The whole tree imports only `btools.*` and `java.*` — no `javax`, no `java.nio.file`, no AWT — and compiling it as a plain `java-library` rather than an Android library is what keeps a future re-sync honest about that. |

Three things worth carrying forward, found while reading the tree rather than by building
it:

- **`Class.forName` is load-bearing.** `RoutingContext.setModel` instantiates the path
  model named as a string inside the `.brf` profile. `isMinifyEnabled = false` today, so
  it works; **turning minification on without keep rules for `btools.router.*Model` breaks
  routing at the first route**, with a `RuntimeException` rather than a build error.
- **Two `main()` methods ship.** `Rd5DiffTool` (which calls `System.exit(1)`) and
  `StackSampler` are command-line tools living in runtime modules. Unreachable from the
  routing path, and deleting them would be a modification every re-sync would repeat.
- **Upstream compiles at release 11**; this module is set to 17 to match the app, which
  AGP requires.

Not done, and not part of vendoring: nothing called BRouter yet. `lookups.dat` and
`car-vario.brf` were packaged but unread — BRouter loads profiles from a `File`, so P6.3
had to copy them out to app-private storage. **Done in P6.3** (`RoutingData.installProfile`,
on the routing thread, rewritten only when the packaged copy differs).

### Data, and the answer to a P5 open question

The P5 plan asks: *"How much storage does a usable region cost with two datasets?"*
Measured:

- `.rd5` segments are a **5°×5° grid, 1142 tiles worldwide** on `brouter.de/brouter/segments4/`.
- `W75_N40.rd5` — 70–75 W, 40–45 N, which is New England plus New York in one tile — is
  **119,388,352 bytes (119 MB)**.
- Against the **201 MB** Massachusetts Mapsforge extract the P5.5 lab already uses, a
  usable region pair is roughly **320 MB**, with the routing tile covering far more ground
  than the rendering extract.

BRouter also needs `lookups.dat` (31,604 bytes) and a profile: `car-vario.brf` (11,891
bytes) or `car-vario-nocost.brf`. Both are small, and both ship in the repository's
`misc/profiles2`. The P5 plan's caveat that BRouter is "historically bicycle- and
foot-first, so its car profile needs validating" stands — the car profiles are two out of
twenty-one.

Installation follows the path `MapData` already implements for `.map` files: operator
supplies the file, the app imports it into app-private storage, no `INTERNET` permission.
At 119 MB the import must stream rather than buffer.

### One correction to the P5 plan, in BRouter's favour

The P5 plan says BRouter's `VoiceHint` model is "thinner than GraphHopper's
`Instruction`/`Sign`" and that "more instruction assembly falls to us." That undersells
it: `brouter-core` carries `VoiceHintProcessor.java` (20,514 bytes) alongside
`VoiceHint.java`, so BRouter does derive turn instructions itself rather than handing back
bare geometry. The parts that genuinely remain ours are the ones the P5 plan named
correctly — **GPS snapping and re-route-on-deviation** — because BRouter is a
point-to-point calculator with no notion of a vehicle moving along its output.

## 5. Destination entry

With offline search out of scope (§0), a destination is set by one of:

- **Long-press on the owned map**, which the P5.5 gesture layer can already locate in
  world coordinates.
- **Coordinates**, typed on the phone.
- **A saved-places list** maintained on the phone, which is where spec §5's "settings and
  extensive text entry live on the phone" points anyway.

That is enough to demonstrate navigation end to end and to pass the gates in §8. It is not
enough to be pleasant, and the gap should be stated plainly in any summary rather than
implied away.

### 5.1 Address search, borrowed from OsmAnd — decided 21 September 2026

The gap above has a cheap answer. **James chose (21 September 2026) to take destinations
shared from OsmAnd**, which already does offline address search over maps he keeps
synced, rather than add a geocoding dataset of our own. Nothing new is downloaded, the app
still has no `INTERNET` permission, and address search stays offline. Rejected with it:
a Mapsforge `.poi` file (named places only, no house numbers; Texas is 106 MB), an address
index built from OSM ourselves (hundreds of MB per state plus a pipeline), and Android's
`Geocoder` (online, and usually absent on GrapheneOS).

**What OsmAnd sends**, read from its source (`ShareMenu.java`, `GeoParsedPoint.java`,
master, 21 September 2026) — to be confirmed against a real share on the Pixel, because
source is not wire (§8.1's rule applies to intents too):

| OsmAnd action | Intent | Payload |
| --- | --- | --- |
| Share → **Geo** | `ACTION_VIEW`, data URI | `geo:30.26720,-97.74310?z=16&q=30.26720,-97.74310(Label)` — five decimals, RFC 5870 |
| Share → **Message** / system sheet | `ACTION_SEND`, `text/plain`, `EXTRA_TEXT` | `Location: geo:…` then `https://osmand.net/map?pin=lat,lon#zoom/lat/lon` on the next line |

**P6.4's destination sources**, in order of value:

1. **`ACTION_SEND` text** — the ordinary share sheet, where OpenAutoAndroid appears
   beside every other app. Taken from the `geo:` in the text, else the `osmand.net` pin,
   else a bare `lat,lon`. This is the primary path: it does not compete with OsmAnd.
2. **`ACTION_VIEW geo:`** — OsmAnd's "Geo" item, and any app that opens a location
   (Contacts, Calendar, Organic Maps). Weaker as a primary path because OsmAnd itself
   handles `geo:` and may be the chooser's default.
3. **Typed coordinates** — exists since P6.3.
4. **Long-press on the projected map** — as §5 above.
5. Saved places — deferred; OsmAnd's own favourites, shared, cover it.

**Refused, with a useful message**: a `geo:0,0?q=<address>` link that carries only
text. It needs geocoding we do not have, so it says: search it in OsmAnd and share the
point. Refusing beats guessing — a route to a misread address is worse than none (the
rule `RouteRequest.parse` already follows).

**The rest of P6.4**:

- **Origin** — the vehicle's position while projecting (the dashboard already follows it,
  `dashboard_position_source=vehicle`), otherwise the phone's last fix. Which one was used
  goes in the report.
- **Route line** — a VTM `PathLayer` in `MapEngine`, fed from the main thread's route result
  and applied on the scene thread. Austin–Denver is 8,677 points, so whether VTM needs the
  geometry thinned for a 1080p frame is a measurement, not an assumption.
- **A destination arriving mid-projection** replaces the route: `RoutePlanner` already
  stops a running search for a newer one.
- **Privacy (spec §10, the §9.6 proposal)** — the label OsmAnd sends is often a street
  address. It may be shown on the phone and must never reach the report or event log. The
  report records only *which source* set the destination (`send`, `view`, `typed`,
  `map`) and the route totals P6.3 already reports.
- **The swapped-sign hint** from P6.3a: a destination outside every installed tile whose
  mirrored longitude *is* installed gets "did you mean −97.66843?".

**Verification planned**:

- Host: a pure-JVM `DestinationParser` tested against OsmAnd's exact strings above, the
  query-only refusal, and malformed input.
- Emulator lab: `am start -a android.intent.action.VIEW -d "geo:…"` and an `ACTION_SEND`
  text during projection, checking a route came back and the route line is in the picture
  (a pixel check in the map pane, as P6.1 does for movement).
- Pixel, no drive: share from OsmAnd both ways, and record what the intents actually carry.
  **This is the check that matters**; everything above it trusts OsmAnd's source.

**Open questions for P6.4**: whether OpenAutoAndroid shows up in OsmAnd's "Geo" chooser at
all; what Organic Maps sends (unread); and — carried from P6.3a — **answered**: routing on the
Pixel was slow because it was a debuggable build. The release build computes the 94 km
Austin route in 0.8 s against 23.9 s debug. Drives from here on should use the release
build ([P6.3a](p6-3a-results.md) §3.5).

## 6. Guidance, and the lab problem in front of it

Guidance is: snap the fix to the route, measure progress along it, produce distance and
time to the next maneuver, detect leaving the route, and re-route. Plus the route line on
the owned map, which is a VTM `PathLayer` over the existing `MapEngine`.

**None of it could be verified in the lab as the lab stood when this was written.** That
is what P6.1 changed — [p6-1-results.md](p6-1-results.md) — and the two paragraphs below
record the state it fixed. The lab receiver reported a single fixed GPS fix, so:

- the map stops rasterising once its tiles load — P5.5 measured `drawn=48 reused=830` and
  read it as a parked vehicle costing nothing, which it is, but it also means **steady-state
  redraw has never been exercised**;
- nothing has ever moved along a route, so snapping, progress, off-route detection and
  re-route have no failing case available to them.

So the first thing P6 needs is not navigation code at all: **a moving position source in
the lab.** A synthetic track played into the receiver's sensor handler at a realistic rate,
with position, speed and bearing consistent with each other. That unblocks the whole of §6,
and it independently unblocks **G8**, which asks for a map that "pans and follows vehicle
position" — something the lab had never been able to show. **Built and run on 20 September
2026**: a parked map changes 0% of its pane, a driving one 49% per 120 m, and the two
defects that came out of the first runs are in [p6-1-results.md](p6-1-results.md) §4.

It is also the cheapest defect-finding tool available here. Every one of the four silent
defects P5.5's first run caught was invisible to the build, the lint and 60 host checks,
and three of them were about *change over time* — a redraw request checked in the wrong
order, a tile source closed after validation, blend state set once at start-up.

## 7. Slice decomposition

Ordered so that each slice produces evidence, and so that the expensive decision in §4
happens after the cheap vehicle evidence rather than before it.

| Slice | What it delivers | Depends on | Gate |
| --- | --- | --- | --- |
| **P6.0** | Navigation descriptor parsed and reported (§11) | — | none; evidence |
| **P6.1** | ~~Moving position in the lab: synthetic track player (§6)~~ **done, lab-verified — [results](p6-1-results.md)** | — | unblocks **G8** |
| **P6.2** | ~~Navigation channel driven by a **scripted** maneuver sequence (§3)~~ **done; lab- and vehicle-verified — [results](p6-2-results.md)** | P6.0 | **G9 passed** |
| **P6.3** | ~~Routing engine: the §4 decision, `.rd5` + profile import, route computation off the render thread~~ **done; host- and lab-verified — [results](p6-3-results.md)** | — | none; evidence |
| **P6.3a** | **Added 21 September 2026 at the operator's request.** Drop folder, every installed map drawn as one, multi-tile routing — **done; host- and lab-verified — [results](p6-3a-results.md)** | P6.3 | none |
| **P6.4** | ~~Destination entry (§5; **addresses via OsmAnd shares, §5.1**) and the route line on the owned map~~ **done; host- and lab-verified — [results](p6-4-results.md)** | P6.3 | none |
| **P6.5** | ~~Guidance loop, feeding real maneuvers into P6.2's channel~~ **done; host- and lab-verified — [results](p6-5-results.md)** | P6.1–P6.4 | **G10** open (vehicle) |

**P6.2 before P6.3 is deliberate.** It is protocol work with no dependency decision, no
new dataset and no routing in it, and it answers §2's contradiction — the one question
here that only a vehicle can answer. If Ford's cluster shows nothing for a semantic
maneuver stream, that changes what P6.5 has to build, and it is much better to learn it
before committing to an engine than after.

P6.1 is first because it is a lab investment that pays into P6.2's verification, P6.5's
verification, and G8 — which is currently unmet for want of a moving map, not for want of
a map.

## 8. Proposed gates

Numbered on the assumption that P5.5's proposed **G8** is accepted; if it is not, these
shift down by one. Each needs an operator observation on the factory head unit, not
phone-side counters (spec §10).

| Gate | Required evidence | Interpretation |
| --- | --- | --- |
| **G9** navigation channel | A scripted maneuver sequence from this source appears on the head unit's own cluster or display, with navigation focus acquired and released cleanly. | Turn-by-turn to the vehicle's own instruments works. Degraded pass, the same shape as G6 and G7: a head unit that opens the channel, accepts the messages and displays nothing is a **recorded outcome**, provided the refusal or silence is in the report and focus was not left dangling. A refusal leaves the gate unmet rather than passed. |
| **G10** navigation | A route computed offline on the phone is followed on the factory display for a drive, with the map following position, correct maneuvers on the cluster, and at least one successful re-route after a deliberate deviation. | Offline navigation works on this setup — APP07 demonstrated rather than argued. |

## 9. Spec and plan amendments this needs — proposed, not applied

`spec.md` and `plan.md` are governed; these are proposals, and they sit **behind** the six
P5.5 already proposed in [p5-5-results.md](p5-5-results.md) §8, which are still unapplied.

1. **spec §11.1** — add **G9** and **G10** as above, after P5.5's proposed G8.
2. **spec §12 / plan.md** — a `P6` section and rows, with every implementation task
   unchecked until evidence exists, per the standing rule.
3. **spec §2.2 / §13** — **now live, not conditional.** §4.1 puts third-party *source* in
   the repository for the first time; `protocol/upstream/` vendors schemas, which compile
   to nothing, and this compiles into the APK. The property that replaced P5.5's
   zero-runtime-dependency one holds unchanged and should be restated to cover it:
   reproducible offline builds still hold (measured), the ABI count is still one, AndroidX
   is still zero, and `verify-apk.sh` still pins the native set and asserts no `INTERNET`.
   Worth adding one invariant of its own — **vendored third-party source is held
   unmodified**, so provenance stays a diff against a pin rather than a merge.
4. ~~**docs/licenses.md**~~ — **applied.** `licenses.md` carries the vendored tree, its
   profile data and the not-packaged `.rd5` segments; `licenses.md` is not a governed file,
   so this needed no amendment proposal. The pin lives in `vendor/brouter/README.md` rather
   than `protocol/upstream/references.toml`, which is a register of *protocol reference*
   pins and holds nothing that ships.
5. **docs/p5-plan.md §8** — the storage open question is answered (§4 above: ~320 MB per
   region pair) and can be closed. The BRouter car-profile question stands.
6. **spec §10** — a destination is at least as much the operator's business as a track
   title or a position is. Whatever the report says about navigation, it should carry no
   destination, no route geometry and no road names by default, on the same footing as
   `sensor_location`.

## 10. Open questions

- ~~**Does Ford's cluster render a semantic maneuver stream at all, and what does it do
  with `NEXT_TURN_IMAGE` when no image arrives?**~~ **Answered 21 September 2026: yes, and
  it needs no image.** Icon, road name and distance countdown, all drawn from a stream with
  no image bytes in it.
- ~~**Does Ford send `InstrumentClusterStart` at all?**~~ **Answered: no, never** — in
  either of two runs, on a head unit that opens the channel in 5 ms and grants navigation
  focus 15 ms after being asked. The announcement and the grant are independent here.
- **Does the icon Ford draws match the maneuver sent?** New, and the obvious next question:
  `ManeuverType` is `confidence: unverified` upstream, an icon appeared, and nobody checked
  it against what the source had sent. One leg change settles it.
- **Why does Ford answer one focus request with two responses?** New. Both `PROJECTED`,
  both inside 79 ms, neither an echo of our hand-back. `NavigationFocusResponse` has no
  `unsolicited` flag, so nothing on the wire tells a volunteered state from a reply.
- **Is `_P1` a tenths value?** `DISTANCE_UNIT_MILES_P1` is documented only as a
  "one-decimal precision variant" and nothing says what the value counts; P6.2 sends tenths
  and puts the same distance in `display_text` beside it. The vehicle run did **not**
  settle it — the operator's "sensible, but hard to tell with simulated data" is exactly
  what an unverifiable countdown looks like. p6-2-results §6 has a one-drive protocol:
  watch the first leg cross 305 m, where the rendering changes from `0.2 mi` to `1000 ft`.
- **Imperial or metric on a US truck?** New, and a possible confounder for G9: a cluster
  that renders one and not the other looks from a report exactly like one that renders
  nothing. `navigation_units` makes it a second run.
- **Modern or legacy?** `0x8006` needs PDK ≥ 1.6 head-unit-side and we cannot read a PDK
  version. Try modern, record what happens, keep `0x8005` as a fallback to try rather than
  a fallback to assume. P6.2 sends modern only and tries nothing else.
- **Is `NavigationType` value 2 really `NEXT_TURN_IMAGE`?** The enum is `confidence: unverified`
  upstream and four vendored names have already been wrong.
- **What does Ford do if we exceed `minimum_interval_ms=400`?** Drop, queue, or complain.
  Worth one deliberate test rather than discovering it mid-drive.
- **Is BRouter's `car-vario` profile good enough for turn-by-turn?** Carried over from the
  P5 plan, still unanswered, and now narrowed: two car profiles out of twenty-one.
  `car-vario.brf` is the one packaged (§4.1), chosen because it is the maintained car
  profile, not because it was evaluated.
- **Does minification ever get turned on?** If it does, `Class.forName` in
  `RoutingContext.setModel` breaks routing silently at the first route (§4.1). Worth a
  keep-rule file written *before* it is needed rather than after.
- **How long does a cross-town route take to compute on the phone, and on which thread?**
  **Half answered** (P6.3): on `aap-route`, a background-priority thread of its own. A
  19 km Greater Boston route takes 1.0 s on the desktop JVM and 12.9 s on the emulator
  while it projects a moving 1080p map, costing about a tenth of the frame rate and
  dropping nothing. **The phone is still unmeasured**, and that needs no drive — the
  connection screen's "Compute route" works with no head unit attached.
- ~~**Does a 119 MB `.rd5` import fit the `MapData` path?**~~ **Yes** (P6.3): the same
  staged-and-renamed stream, plus BRouter's own header check against `lookups.dat` so a
  tile from another lookup table is refused at import rather than failing the first route.
- **Does a border between two Mapsforge extracts draw cleanly?** New (P6.3a). Every
  installed map is drawn as one with VTM's de-duplication on, so a drive from one state's
  extract into the next should cross without a seam; the lab's two maps do not touch, so nothing has shown it.
- **Should a re-route be allowed to start by turning round?** New (P6.5). BRouter's start
  direction is passed, but `car-vario` prices a U-turn at ~180 m, so the lab's first re-route
  after a missed turn still went back the way it came and a second followed six seconds
  later ([P6.5 results](p6-5-results.md) §4.2).
- ~~**How does P6.5 read what a maneuver *is*?**~~ **Answered (P6.5): a read-only class of
  our own in package `btools.router`, in the app tree** — compile-time rather than run-time
  failure on a re-sync; the vendored tree stays byte-identical. Original question: New (P6.3). `VoiceHint`'s command is
  package-private in the vendored tree, which is held unmodified; only the maneuver's
  position and roundabout exit are public. `FormatJson` output or a class of our own in
  package `btools.router` are the two ways in, and both are a decision about what
  "unmodified" permits.
- **Should discovery tolerate a descriptor that fails to decode?** §3's `required`-field
  hazard; not a navigation question, found while reading one.

## 11. What is implemented as of this document

**P6.0 — the navigation descriptor is parsed and reported.** Implemented 20 September 2026,
covered by tests, no new dependency and no new vendored file: `NavigationChannel` was
already inside the discovery import closure, so nothing was added to the generated set.

- `NavigationChoice` and `NavigationImage` in `core/aap-session/src/discovery.rs`, filled
  from the descriptor and exposed as `Capabilities::navigation` — the same shape as
  `MediaInfoChoice`, which is the accepted precedent for reading a descriptor whether or
  not the channel is ever used.
- A new report line, and the channel dump line now carries the descriptor rather than
  just the service name:

  ```
  discovery_channel_011=navigation min_interval_ms=400 type=2 (NEXT_TURN_IMAGE) image=-
  discovery_navigation=channel=11 min_interval_ms=400 type=2 (NEXT_TURN_IMAGE) image=-
  ```

- `nav_type` keeps the integer the head unit sent; the schema's name is printed beside it
  in brackets and nothing branches on it (§2).
- Image dimensions are reported **by field name**, `height=… width=…`, never as `WxH`. The
  pinned `NavigationImageOptionsData.proto` puts `height` at tag 1 and `width` at tag 2 —
  the opposite of every other geometry in this protocol — and the reference tree's
  `NavigationChannelConfigData.proto` transcribes the same APK class the other way round,
  with `width` at tag 1. One of those is wrong, Ford sends neither, and there is nothing to
  settle it against; when the two values differ the line says `(field order unverified)` in
  as many words.
- `hu-sim` now advertises a navigation channel on 11 with values chosen to *not* match
  Ford's: a 300 ms floor, `type=1`, and image options present at 64×96×16. Ford advertises
  no image options, so the simulator covers the branch the one vehicle fixture cannot; the
  dimensions are unequal so a square pair cannot hide the tag-order question; and the floor
  differs so a source that hardcoded 400 would fail.
- Tests: the Ford fixture assertion in `core/aap-session/tests/projection.rs` pins
  `(11, 400, 2)` and `image == None`, and `core/aap-session/tests/protocol.rs` pins the
  `hu-sim` path and both report lines. `cargo clippy --workspace --all-targets -D warnings`
  and `cargo test --workspace` are green.

P6.0 opened nothing and sent nothing; **P6.2 does both** — see
[p6-2-results.md](p6-2-results.md) for the channel, the focus exchange, the scripted
sequence and the fifteen `navigation_*` report lines it added.

## 12. Research status

§2 is **decoded from a vehicle capture** — the one sanitized fixture in the repository —
and is the only measurement in this document that came from Ford. The numbers 400 and 2
are what the truck sent; `NEXT_TURN_IMAGE` is a reading of an upstream enum marked
`confidence: unverified`, and §2 treats those two facts differently on purpose.

§3 is **APK-derived schema reading at the spec pin**, taken from `oaa/navigation/*.proto`
directly rather than from a summary. The message-ID table is upstream's `ian.java` handler
trace; `0x8007` is marked wire-verified upstream (4 March 2026) and the rest are not. No
navigation message has ever been on this project's wire, in the lab or in a vehicle.

§4's distribution and size figures were **measured on 20 September 2026** against
`repo1.maven.org`, the GitHub API and `brouter.de`: the Maven Central absence is a search
returning zero plus three 404s, the module sizes are a recursive tree listing with tests
excluded, and the 119 MB is a `Content-Length`. The architectural recommendation is
inherited from the P5 plan's spike and not re-derived; the `VoiceHintProcessor` correction
is a file listing, not a reading of what it produces.

§4.1 is **measured on this machine, 20 September 2026**: the byte-identical hashes are two
clean `tools/android-build.sh` runs, the APK delta is `stat` on both artifacts, the
AndroidX absence is a string scan of `classes.dex`, and the "unmodified" claim is a
per-package `diff -r` against the extracted upstream tarball. The `Class.forName` and
`main()` findings are source reading, not observed failures — nothing has called BRouter
yet.

§6's claim that the lab cannot show a moving map is from P5.5's own run: one fixed fix,
`drawn=48 reused=830`.

Not researched: what Ford's cluster physically looks like or whether it has a
turn-by-turn area at all. Nobody has looked while a navigation session was active, and
that observation costs one drive.
