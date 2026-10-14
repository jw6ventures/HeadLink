# Video congestion in the lab, 11 October 2026

On 11 October the truck's picture smeared, smudged, and froze for long stretches on the
drive out, on country roads as well as in Georgetown. A screen switch cleared it for a while.
The drive home on the same route was clean. Report d11eec1e (the drive out) shows the head
unit falling behind the video almost constantly. Compared with the drive home (0e927af8) and
47 other sessions, all with the same build and the same settings:

| | Drive out (d11eec1e) | Drive home (0e927af8) | 47 other sessions |
| --- | --- | --- | --- |
| Frames dropped | 33.3% | 2.3% | at most 3.2% |
| Congestion episodes | 5,200 | 43 | at most 139 |
| Keyframes a second | about 2 | about 0.9 | about 1 |
| Frames sent a second | 32 | 51 | — |

At 12:36–12:40 the screen held one damaged picture for four minutes. During that time the
truck acknowledged every frame and a dashboard tap went through. What starts these episodes,
and why Ford shows smear rather than stutter, is not known: everything the phone sent was a
complete, valid stream.

## The change (spec §8.2, amended)

1. **Keyframes after congestion.** The link no longer asks for a keyframe the moment the queue
   overflows. The IDR that ends an episode is taken only after the queue drains. It is asked
   for only once drained and at least a second after the last keyframe was sent or asked for
   (`ProjectionConfig::keyframe_spacing`), so the encoder's own 1 s interval usually supplies
   it. New counters: `video_dropped_draining` and `video_keyframe_requests_drained`.
2. **Stream restart.** Five episodes within ten seconds restart the stream exactly as a screen
   switch does: stop indication, start under a new session id, rebuilt encoder. This happens
   at most once a minute; restarts held back by that limit are counted. It appears as
   `video_congestion_restarts` / `video_congestion_restarts_held`, as
   `projection_congestion_restarts` in the app, and as a "video restarted: …" note in the
   events. It is on by default, with "Restart video when the car falls behind" on the
   Diagnostics tab and `congestion_restart=false` in the native config to turn it off.
3. **Timeline.** `video_timeline=minute:frames/keyframes/episodes/dropped/requests/restarts,…`
   gives one row per minute of projection, up to 600 rows.

## Lab runs

`tools/emulator-lab.sh congestion` makes the receiver a head unit that falls behind for 45 s,
between 5 s and 20 s of normal running. While it is slow, each unit is acknowledged when a
serial decoder of `LAB_CONGESTION_KBPS` plus `LAB_CONGESTION_FRAME_MS` a frame would have
finished it. A keyframe, the largest frame, holds back everything behind it.

All runs below used 500 kbps and 8 ms a frame, at 800x480 and 30 fps, against Prodigy's
window of 10. That decoder keeps up with one keyframe a second but not with two. The "old"
run is the 8 October debug APK (`LAB_APK`) with the native library built from 19db2ed
(`LAB_NATIVE_TREE`), whose `core/` is the code that was in the truck.

| While the head unit was slow (45 s) | Old (20261011T232748Z) | New (20261011T232548Z) | New, restart off (20261011T233017Z) |
| --- | --- | --- | --- |
| Congestion episodes | 67 | 42 | 41 |
| Keyframes asked for | 67 | 33 | 35 |
| Frames dropped | 1,075 | 768 | 762 |
| Pictures the receiver showed | 286 (6.4 fps) | 590 (13.1 fps) | 598 (13.3 fps) |
| Longest an acknowledgement waited | 4,678 ms | 1,262 ms | 1,255 ms |
| Stream restarts | 0 | 1 (7 held) | 0 |
| Decode errors | 0 | 0 | 0 |
| After it caught up | 29.4 fps | 29.4 fps | 29.6 fps |

The old policy reproduces the spiral: twice the keyframes, half the pictures, and a head unit
up to 4.7 s behind. The new policy halves it. Turning the restart off changes almost nothing
here, which is expected: the receiver has no stuck state for a restart to clear. The restart
itself went through cleanly: stop, start under a new session, configuration, IDR, and no
decode error. The new run's timeline was `0:936/64/42/768/32/1,1:373/13/0/0/0/0`.

A first run at 1,500 kbps and 4 ms (20261011T232315Z) never filled the window, so it caused
no congestion and failed that check, as it should.

Regressions: the `stall` (20261011T233221Z) and `focus` (20261011T233305Z) scripts pass, and
so does `tools/check.sh`. `tools/android-build.sh` passes too, with lint and every host test.

## Not shown

- None of this is phone or truck evidence. Whether Ford SYNC 4.0 accepts a stop and start
  indication the phone sends without a focus change is unknown. If the screen drops back to
  Ford's own UI after a "video restarted" note, clear the Diagnostics box.
- The lab's head unit is slow by construction. Why the truck fell behind on 11 October, and
  not on the way home, is still open. The next bad drive's `video_timeline` should say when
  it starts, and whether restarts end it.
