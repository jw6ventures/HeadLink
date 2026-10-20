# Review fixes in the lab, 2 October 2026

Lab evidence for the fixes in commit 2ff5611 (the October 2026 review's items 3–6). All runs
used the emulator lab at 1920x1080 with the Massachusetts map and W75_N40.rd5. They prove
nothing about the Pixel or the truck. Each new check was run against the fixed build and
against the same tree with the five app files put back to 73e90df (the pre-fix build), to
show that the check catches the bug and not just that the fix passes it.

| Run | Fixed build | Pre-fix build |
| --- | --- | --- |
| `LAB_REROUTE=fail … guidance` | pass, receiver 23/23 (20261002T153909Z) | FAIL: cluster `1,3,2`, never told it arrived (20261002T153202Z) |
| `focus` (with the new flicker) | pass, receiver 9/9 (20261002T152841Z) | FAIL: drawing on while the head unit had the screen (20261002T153118Z) |
| `guidance` (unchanged drive) | pass, receiver 23/23 (20261002T152016Z) | — |

Results are under `$OPENAUTO_CACHE/emulator-lab/<time>/`.

## Item 3: a re-route that fails

`LAB_REROUTE=fail` (guidance only) moves the routing tile out of the app's files once the first
route is computed. Every request builds a fresh `RoutingEngine`, so each later re-route fails
for real ("datafile W75_N40.rd5 not found"), as one does where BRouter cannot place the
vehicle. The new drive, `tools/lab-receiver/drives/lexington-return.csv`, comes from
`LabDrive … return`. It misses the same turn as `lexington.csv`, drives 275 m the wrong way,
turns round, and follows the planned route from 820 m in. The default drive still regenerates
byte for byte.

Fixed build. Two re-routes are asked for and both fail. "Back on the route after a failed
re-route" is noted at +85.6 s. The cluster hears `1,3,1,2`, every maneuver through
DESTINATION (`6,8,5,7,5,36,39`), and the arrival. The report has
`guidance_reroutes_done=0 guidance_reroute_failures=2 guidance_routes=1`.

Pre-fix build. The cluster hears `1,3,2`: REROUTING for the rest of the drive, then INACTIVE
when the session ends. It is told no maneuver after the fourth and no arrival.

The first fixed-build run failed one receiver check: two cluster updates arrived 1285 ms
apart, against the 1500 ms floor. The gap was at 47 s, before the drive left the route. One
update arrived about 210 ms late on the 1500 ms grid, so the next gap was short. That points
to messages queued behind 1080p video in the emulator, not to the fix. Earlier guidance runs
came close to the same limit (1388 and 1406 ms), and the rerun passed at 1486 ms. On the
truck a late write could bunch two updates the same way; pacing from the write rather than
the send would remove that.

## Item 6: a regain taken straight back

The receiver's focus script now continues after its first loss and regain. It drops focus,
then sends a regain and a loss back to back, and keeps the screen for 12 s before a final
regain. Meanwhile the lab reads the app's live report twice, three seconds apart, through
`lab_report`. Both reads must say `projection_focused=false`, and
`projection_dropped_not_streaming` must not move.

Fixed build. Both reads say false, the drop count holds at 10, and there are three rebuilds.
The flicker's rebuild ran and left drawing off. The final regain draws again with no decode
errors.

Pre-fix build. The first read already says `projection_focused=true`: the rebuild undid the
loss.

## What the lab did not check

- Item 4, the I/O threads after a panic: the Rust test
  `a_panic_while_driving_still_stops_the_io_threads` covers it. It fails on the old code and
  passes on the new. The lab has no way to make the session panic.
- Item 5, `SessionController.note` off the main thread and exceptions on the scene thread:
  nothing in the lab reaches either path. `MediaControl` checks access before asking for
  sessions, so its `SecurityException` only follows a race, and no lab input makes a frame
  throw. The guidance, focus and normal runs pass through the changed code on its normal
  path. A debug-only fault hook would be needed to check the failure paths.
