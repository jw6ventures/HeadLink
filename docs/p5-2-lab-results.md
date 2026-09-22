# P5.2 lab results — sensor harvest, read-only — 18 September 2026

> **The vehicle evidence arrived on 19 September 2026** (reports `a24db7e0` and
> `7f869585`, collected alongside the G7 audio runs). All nine subscriptions accepted,
> 296 events, no errors:
> `sensor_populated=1,3,7,8,10,13,21`, `sensor_silent=6,24` — FUEL_LEVEL and TRAILER are
> the accepted-and-silent pair. **LOCATION, CAR_SPEED and GPS all carry values**, which is
> what P5.5 was waiting for. Units are a separate and still-open question: three values do
> not read sensibly. Full finding in
> [references.md](references.md#g6-and-g7-pass-and-p52s-vehicle-answer-19-september-2026).

Lab evidence only below this line. Nothing below is vehicle evidence: the question this
slice exists to answer — **which of the nine sensors Ford SYNC 4.0 advertises carry real
values** — was answerable only on the truck. What the lab
shows is that the harvest works, that it separates advertised from populated, and that
position stays out of a report unless the operator asked for it.

Scope is [P5 plan §2](p5-plan.md). No schema changed: all 26 `SensorEventIndication`
payload messages were already generated at the pin, so there is no `proto-gen` run, no
new vendored file, and no licence change.

## What was added

| Where | Change |
| --- | --- |
| `core/aap-session/src/projection.rs` | `SensorSet` (`none`, `restriction`, `all`, or an explicit list) replaces the fixed `RESTRICTION_SENSORS` pair; `ProjectionConfig::sensor_location`; a `harvest()` that reads all 26 payload fields; `SensorHarvest` per-type counts in the report |
| `core/aap-session/src/discovery.rs` | `discovery_sensor=channel=N types=…` on its own line, so a discovery-only run records the advertisement |
| `core/aap-jni/src/manager.rs` | `sensors=` takes a set rather than a boolean; new `sensor_location=` key |
| `tools/source-harness` | `--sensors SET`, `--sensor-location` |
| `tools/hu-sim` | advertises the nine types Ford does and answers seven of them; `volunteer_sensor` pushes an unsubscribed type |
| `tools/lab-receiver` | `LabSensorHandler` (Prodigy's protobuf classes, our own handler) advertises and drives all nine; `--sensors restriction\|all` |
| `app/` | the sensors checkbox becomes a three-way set, with a separate position opt-in |

`RESTRICTION_SENSORS` is kept, as the definition of `SensorSet::Restriction`, so the P4
behaviour is a named case rather than a deleted one. `sensors=true` still parses and still
means that pair.

## Verification

`tools/check.sh` and `tools/android-build.sh` both pass. Rust tests: 154 passed, of which
**projection 35** (five new). The new ones are

- `the_whole_advertised_sensor_set_can_be_harvested_per_type`
- `head_unit_position_is_redacted_unless_it_was_asked_for`
- `an_unrequested_sensor_value_is_counted_and_kept`
- `a_sensor_set_never_exceeds_the_advertisement`
- `a_refused_sensor_subscription_is_recorded_and_projection_continues`

plus `projection_config_keys_are_strict` extended in the JNI parser tests and the JVM
host contract test (89 checks) carrying `sensors=all` and `sensor_location=true`.

## Controlled receiver run

`LAB_STOP_AFTER=projection LAB_SENSORS=all tools/lab-receiver.sh demo`, against the
pinned OpenAuto Prodigy protocol library. Prodigy's own `SensorChannelHandler` models
only night mode, driving status and parking brake, so the wider set needs a handler
written for the lab — but it encodes with Prodigy's generated protobuf classes, so the
decode on our side is still independent of our generated Rust types.

The receiver advertises the nine types Ford advertises and deliberately answers only
seven of them; FUEL\_LEVEL and GEAR are accepted and then silent, because "advertised,
accepted, and wordless" is the case the report has to tell apart from populated.

Receiver side:

```
receiver_sensors_requested=1,3,6,7,8,10,13,21,24 receiver_sensors_populated=1,3,7,10,13,21,24
receiver_checks=12 receiver_check_failures=0
```

Source side, same run:

```
discovery_sensor=channel=2 types=1,3,6,7,8,10,13,21,24
projection_open_002=sensor sent_ms=6 response_ms=6 status=0 (OK)
sensor_set=all sensor_advertised=1,3,6,7,8,10,13,21,24 sensor_location=redacted
sensor_requests=1:0,3:0,6:0,7:0,8:0,10:0,13:0,21:0,24:0 sensor_errors=0 sensor_events=7 …
sensor_value_001=LOCATION requested=true count=1 last=latitude=redacted longitude=redacted accuracy=8 altitude=213 speed=0 bearing=1790
sensor_value_003=CAR_SPEED requested=true count=1 last=speed=0 cruise_engaged=false unknown_4=-
sensor_value_006=FUEL_LEVEL requested=true count=0 last=-
sensor_value_021=GPS requested=true count=1 last=satellite_count=11 satellites_used=7 entries=0 in_use=0
sensor_value_024=TRAILER requested=true count=1 last=present=true
sensor_populated=1,3,7,10,13,21,24 sensor_silent=6,8
```

The two lists agree: what the receiver says it sent is what the source says it received,
type for type, decoded independently at both ends.

`LAB_SENSORS=restriction` opens the same channel with two subscriptions and Prodigy's own
handler, which is the P4 path unchanged (11 checks, 0 failures).

## Position redaction

Spec §10 treats position like the raw-capture options: opt-in, bounded, app-private, and
out of an ordinary shared report. The lab receiver sends a fixed inland fix
(`424242424, -712121212` in the schema's 1e7-degree units).

- Default: `sensor_location=redacted`, and the coordinates appear nowhere in the report
  text. The fix is still **counted**, and accuracy, altitude, speed and bearing are still
  reported, so a run still answers "does Ford populate LOCATION at all" without saying
  where the truck was. Satellite counts are reported for the same reason and need no
  redaction — they say whether the fix is real, not where it is.
- `LAB_SENSOR_LOCATION=1`: `sensor_location=reported` and
  `last=latitude=424242424 longitude=-712121212 …`.

The app carries the same split: the set is a spinner, position is a separate checkbox
that only appears once a set is chosen, and `sensor_location=` in the report header names
which case the run was — so evidence from a drive is unambiguous about whether
coordinates could be in the file at all.

## Decisions taken in this slice

- **Sensor type number equals payload field number.** `SensorEventIndication` field *n*
  is `SensorType` *n* for all 26, so the mapping in `harvest()` is the schema's own rather
  than a guess. The one that reads oddly is field 14, `steering_wheel`, which is
  `DEAD_RECONING`.
- **Refused is not silent.** `sensor_silent` lists only types whose subscription was
  answered OK and which then sent nothing. A type the head unit refused, errored on, or
  never answered is left out, because a refusal says nothing about whether the vehicle
  has that sensor.
- **Unrequested values are kept.** A head unit volunteering a type nobody subscribed to
  is harvested with `requested=false` and counted in `sensor_unrequested`, rather than
  dropped. Ford has not been seen doing this; it costs three lines to be sure.
- **Opaque payloads are counted, never printed.** `Diagnostics` and the two raw
  energy-model messages are unbounded manufacturer `bytes` with no schema here to read
  them by, so they are reported by length only — the same rule `capture_unhandled`
  applies to unexamined bodies.
- **Every sensor failure stays non-fatal**, unchanged from P4: a refused channel, a
  refused type, a decode failure and a missing response are each recorded and skipped.

## Limitations and open items

- ~~**No vehicle evidence.**~~ *Obtained 19 September; see the banner above.* P5.5's
  position source is now a measurement rather than an assumption — the vehicle populates
  LOCATION, CAR_SPEED and GPS — but **their units are not established**, and three fields
  (`bearing`, `satellites_used` against `satellite_count`, and `gear`) carry values that
  do not read sensibly. "The type carries a value" and "we know what the value means" are
  different claims, and only the first is evidenced.
- The lab receiver's values are invented. That they decode correctly says nothing about
  what Ford's units, scaling or update rates are — in particular `CAR_SPEED`'s unit and
  `GpsLocation`'s 1e7-degree assumption are untested on the vehicle. The 19 September run
  did not settle this and in places contradicts the schema's names outright; a stationary
  vehicle also cannot exercise a speed or bearing.
- No refresh interval is negotiated: `SensorRequest::refresh_interval` is still 0, as in
  P4. Whether Ford honours a nonzero one is unasked.
- Nothing consumes the harvest yet. Values reach the report and, for the two restriction
  types, the scene; nothing else reads them. P5.5 is what would.
