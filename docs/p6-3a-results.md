# P6.3a — drop folder and seamless regions: implementation record — 21 September 2026

**An addition to the P6 plan, not one of its slices.** Asked for by James on 21 September
2026, after P6.3: *"have maps and routing data monitor a directory and pick the appropriate
file for the region we're in … if I drive from Austin to Colorado I want maps and nav to be
seamless on the trip."* Of three ways to reach a folder (§1), James chose the drop folder.
§6 proposes the plan row.

Status: **implemented, host-tested with real data, lab-verified on the emulator.** The drop
folder's Android half — listing, copying and deleting through a folder grant — **has not
run on a device**; the emulator lab installs data with `adb`, as it always has.

---

## 1. The design, and why nothing switches

The request reads as "pick the right file for where we are". It turned out that nothing has
to pick:

- **Routing already worked this way.** BRouter opens whichever 5° tiles a search reaches,
  by name, from one directory. A trip across four tiles is one route (§3.1).
- **Maps now work the same way.** VTM's `MultiMapFileTileSource` draws several Mapsforge
  files as one map, each only where it has data, with de-duplication along a border
  where two extracts overlap. `MapEngine` now opens **every** installed map instead of the
  newest one.

What was left was getting files onto the phone without eight trips through a file picker.
Three ways were offered; James chose the first:

1. **Drop folder** — the operator grants one folder once; files put there are checked and
   *moved* into app-private storage. No new permission, nothing stored twice. **Chosen.**
2. The same folder, copied and kept in sync — every file stored twice.
3. Read in place — the "all files access" permission, or unofficial file-descriptor paths.

## 2. What was built

- **`DropRules`** (pure JVM): which files are taken (`.map`, `.rd5`, any case), which wait,
  which are already done. A file modified in the last **30 s** waits, because a browser
  download, a sync app or a USB copy may still be writing it; a sweep that finds one looks
  again 31 s later. A file already installed at the same size, after the drop last changed,
  is not copied again — only its delete is retried — so a provider that refuses deletes
  does not cost a full copy on every sweep.
- **`DropFolder`** (Android): the folder is a persisted `ACTION_OPEN_DOCUMENT_TREE` grant,
  and **the grant is the only record of it** — no setting to fall out of step. Choosing a
  new folder releases the old. Sweeps run on a background-priority `drop-folder` thread when
  the connection screen comes to the front, when the folder is chosen, on "Check folder
  now", and after the settle delay. Each file goes through the **same** import as the
  picker: `MapData.installInto` or `RoutingData.install`, then `DocumentsContract.deleteDocument`.
  A file that fails its check **stays in the folder**, and the screen and event log say why.
  Only files directly in the folder are read.
- **`MapData.checkHeader`**, new: the Mapsforge header declares the file's own length, so a
  map cut short is refused at import — "map file is incomplete: 5000 of 8192 bytes" —
  instead of by VTM at the start of a drive, where it shows as an empty pane. Tiles already
  had the equivalent (P6.3). This matters more for a folder than for a picker, because a
  sweep cannot know whether something is still copying in.
- **`MapEngine`** opens all installed maps (`MapData.resolveAll`); one that fails to open
  costs only its own region, and the report's `data=` names every file drawn. The lab's
  named-file override still selects one.
- **`RoutePlanner.MAX_RUNNING_MS`** raised from 60 s to **300 s** — §3.1.
- Connection screen: the drop-folder line (folder, last check), "Choose drop folder",
  "Check folder now", and the map line lists every installed map with their total size.

## 3. The runs — 21 September 2026

### 3.1 A long trip, on the host

Four tiles from `brouter.de/brouter/segments4/`: `W100_N30` (63,927,253 bytes), `W100_N35`
(33,013,163), `W105_N30` (19,248,225), `W105_N35` (21,790,524), beside `W75_N40`, with
SHA-256s in `~/.cache/openauto-android/rd5/tiles.sha256`.

```
routing-test: austin-denver compute_ms=14834 distance_m=1459572 seconds=66688 points=8677 maneuvers=71
jni-host-test: ok 103 routing checks
```

**Austin to Denver is one route, 1,460 km, computed in 15 s** on the desktop JVM with the
app's 64 MiB node cache. It also ran in a 128 MiB heap (15.5 s) and held to one core
(20.6 s). That last number is why the ceiling moved: a phone core is slower than a desktop
one, and 60 s would have failed exactly the trips worth planning. It is a ceiling, not a
wait — a newer request still stops the search.

BRouter's 66,688 s (18.5 h) estimate is well above what the drive takes; its car speed
model is conservative, which P6.5's time-to-arrival will inherit unless corrected.

A Houston–Dallas route failed in 2 ms with **"datafile W100_N25.rd5 not found"** — that
tile was not downloaded. The message names the missing tile, which is the right failure
for a trip that leaves the data.

### 3.2 Real maps pass the new header check

`massachusetts.map` (201,049,315 bytes) and `monaco.map` (354,495) both pass
`MapData.checkHeader`. Checked before relying on it, because a check that refused real
maps would have broken every import.

### 3.3 Build container — pass

`tools/android-build.sh`: lint as errors, `verify-apk.sh`, and host checks 18 + 17 + 52 +
196 + 57 routing + **23 drop folder** + 90.

### 3.4 Emulator: two maps, moving, with a route mid-projection — pass

`LAB_ROUTING=… LAB_MAP=…/massachusetts.map LAB_MAP_EXTRA=…/monaco.map LAB_VIDEO_CONFIG=1920x1080 tools/emulator-lab.sh track`,
results in `emulator-lab/20260921T175413Z`:

```
emulator-lab: track: vehicle events=134 camera moves=124 rasterised=288
emulator-lab: routing_result=ok routing_thread=aap-route routing_compute_ms=14482
emulator-lab: maps: both files drawn as one
receiver_track_moved=1 metres=123 map_changed=0.483782 tiles_changed=0
receiver_track_moved=2 metres=122 map_changed=0.49644 tiles_changed=0
receiver_video_decoded_frames=794 receiver_video_decode_errors=0 receiver_video_size=1920x1080
receiver_checks=12 receiver_check_failures=0
emulator-lab: pass
```

The moving-map intervals match the single-map run to three figures (0.4838/0.4964 against
0.4839/0.4971 in P6.3's run), so a second file adds nothing where it has no data, and the
frame work is unchanged.

### 3.5 The first phone number, and the priority it exposed

James ran "Compute route" on the Pixel, 21 September 2026, over `W100_N30.rd5` from the drop
folder: southeast Austin to near Rockdale (30.22177,−97.66843 → 30.69355,−97.09260). The
phone showed the same route the desktop computes — 94,044 m, 79 min, 9 maneuvers:

| | cold | warm |
| --- | --- | --- |
| desktop JVM, all cores | 1.1 s | 0.66 s |
| desktop JVM, one core | 2.6 s | 2.3 s |
| **Pixel, `THREAD_PRIORITY_BACKGROUND`** | **24.8 s** | **23.9 s** (twice) |

The first attempt failed with "datafile E95_N30.rd5 not found": the longitudes had been
typed without their minus signs, which is a real point in western China. The error was
correct and unhelpful; §4 lists the hint that would fix it.

Ten times one desktop core is more than the hardware explains. `Process.setThreadPriority`
at `THREAD_PRIORITY_BACKGROUND` (nice 10) also moves the thread into Android's background
scheduling group, which is held to the little cores. The routing thread now runs at
**nice 4** — still behind the scene and encoder threads at nice 0, short of the group
change — and the report says so (`routing_thread_priority=4`). The drop-folder copy thread
stays at background: it is I/O, and has no one waiting on it.

The emulator re-run (`emulator-lab/20260921T194959Z`, same `track` configuration as §3.4
without the second map) passed, and the search took **8.0 s against 12.9–14.5 s** before:

| Window | fps before (nice 10) | fps now (nice 4) | Longest gap now |
| --- | --- | --- | --- |
| before the request | 29.8 | 29.8 | 100 ms |
| during the search | 26.7 | 26.3 | 115 ms |
| after it | 31.2 | 30.9 | 100 ms |

The same dip, for a search that ends 38% sooner; nothing dropped (806 rendered, 805
accepted), and the longest gap grew from 96 to 115 ms. The emulator has no little cores,
so it cannot show the speed-up the phone should — **the Pixel re-run is the measurement.**

**It showed no speed-up**: 23.9 s and 23.5 s at nice 4. The priority was not the cause.
**The build was.** Every build the phone had run was the debug APK, and ART runs a
debuggable app's code without most of its optimisation. The same code — the release APK,
signed with the debug key so it installs over the debug build
(`app/build/outputs/apk/release/app-release-debugsigned.apk`) — computed the same route in:

| Build on the Pixel | Time |
| --- | --- |
| debug, nice 10 | 24.8 s cold, 23.9 s warm |
| debug, nice 4 | 23.9 s, 23.5 s |
| **release, nice 4** | **0.8 s** |

About **thirty times faster**, and in line with the desktop (1.1 s cold, 0.66 s warm).
Scaling the desktop's 15 s puts Austin to Denver in the region of 15–20 s on the phone —
unmeasured, but far inside the 300 s ceiling. Nice 4 stays: it is still the right level for
work someone is waiting on, and it did no harm in the lab.

Two consequences outlive this slice:

- **Every emulator timing of CPU-bound work is a debug-build timing.** The lab needs a
  debuggable APK (lab intents, `run-as` for reports), so its 8–14 s routes say nothing about
  speed — only about thread placement and frame pacing, which is what they were used for.
- **Vehicle runs have been on debug builds.** For projection that has not mattered visibly;
  for routing and guidance it will. Drives from P6.4 on should use the release build.

## 4. Not verified — the honest list

- **The drop folder has not run on a device.** Folder grant, listing, copying through the
  document provider and deleting are all Android API calls no host test reaches, and the
  lab does not use them. First thing to try on the Pixel: grant `Download/OpenAuto`, drop a
  tile in, open the app.
- **A border has never been drawn.** The lab's two maps are Massachusetts and Monaco, which
  do not touch. Two adjacent extracts — Texas and New Mexico, say — are what the
  de-duplication is for, and nothing has shown it working. Texas and Colorado do not
  touch at all: Austin to Denver crosses the Oklahoma panhandle or New Mexico, so a
  seamless *map* on that trip needs those states' extracts too. The routing tiles already
  cover them, because tiles are squares, not states.
- **Swapped signs get no hint.** A point outside every installed tile whose mirror-image
  longitude *is* installed is almost certainly a missing minus sign; the error could say so.
- **A long route on the phone.** 15 s on the desktop; the phone is unmeasured and the 300 s
  ceiling is a guess with margin, not a measurement.
- **Replacement is by name.** `texas.map` over `texas.map` replaces it; a differently named
  newer extract sits beside the old one and both are drawn. There is no way to delete an
  installed map or tile from the app yet.
- **The folder is not watched while the app is in the background.** Sweeps happen when the
  connection screen is opened, which is how a drive begins; a file dropped mid-drive waits
  for the next.

## 5. Verification actually performed

`tools/android-build.sh` (green); `RoutingTest` on the host with five tiles, 103 checks;
`MapData.checkHeader` against both real maps; one emulator lab run (§3.4).

## 6. Amendments this needs — proposed, not applied

1. **P6 plan §7** — a **P6.3a** row: drop folder and seamless regions, after P6.3, no gate.
   Added at the operator's request; the plan did not foresee multi-region data.
2. **spec §5 / APP10** — data now also arrives through a persisted folder grant. No new
   permission, still no `INTERNET`, and nothing reads outside the granted folder.
3. **P6 plan §10** — add: *does a border between two Mapsforge extracts draw cleanly?*
   (Added to the plan's open questions already; the plan is not a governed file.)
