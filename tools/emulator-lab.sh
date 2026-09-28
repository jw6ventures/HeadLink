#!/bin/sh
# Optional lab projection run of the app on ART: the debug APK in a headless x86_64
# emulator streams its scene to the controlled lab receiver, which decodes the H.264 with
# libavcodec, taps each button, and checks that the decoded picture changes. Offline: the
# receiver and the emulator share one Podman pod whose only interface is loopback, and the
# app reaches the receiver through an abstract local socket forwarded by adbd
# (`adb reverse`), so the APK still has no INTERNET permission and no network transport.
#
# This is lab evidence for the app's encoder, EGL renderer, media submission, and input
# handling. It is not device or vehicle evidence: the phone is arm64 GrapheneOS, the
# emulator has a software encoder, and no head unit is involved. It cannot pass G4 or G5.
#
# Needs /dev/kvm, localhost/openauto-emulator:smoke (tools/emulator-image.sh),
# localhost/openauto-lab-receiver:p4 with tools/lab-receiver.sh build, and the debug APK
# from tools/android-build.sh. Results go to $OPENAUTO_CACHE/emulator-lab/<UTC time>/.
#
#   tools/emulator-lab.sh [taps|focus|stall|audio|track|navigation|guidance|daynight|controls|endroute|calls|speech|voice|none]
#
# The audio script (P5.3) makes the receiver advertise one PCM MEDIA channel, grant audio
# focus, and measure the PCM the app's own AudioSource produces against the tone it says
# it is sending; LAB_AUDIO=1 adds the audio channel to any other script.
#
# The navigation script (P6.2) makes the receiver advertise the instrument cluster
# channel, announce a cluster and grant navigation focus, then decodes the scripted
# maneuver sequence the app sends and times it against the advertised floor. It is the
# only script whose evidence is not in the picture: the cluster is a display of the head
# unit's own. LAB_NAVIGATION=1 adds the channel to any other script.
#
# LAB_NAVIGATION_CLUSTER=none leaves the receiver as Prodigy actually behaves — a head
# unit that advertises the service and never says a cluster is there — so the app must
# send nothing at all. LAB_NAVIGATION_FORCE=1 ticks the app's own "send regardless" box
# through the lab intent, which is the pair a vehicle attempt has to choose between.
#
# The track script (P6.1) plays a moving synthetic position into the sensor channel and
# checks that the projected map pane follows it while the static half of the dashboard
# does not. It needs LAB_MAP: with no map data the pane draws a message instead of a
# map, and a message does not move. LAB_TRACK=1 drives the same track under any other
# script and checks nothing, which is how to watch a long drive in the captured frames.
#
# LAB_ROUTING=<dir holding W75_N40.rd5> (P6.3) installs that BRouter tile the way LAB_MAP
# installs a map, and once the app is projecting asks it for a cross-town route through a
# debug intent. It works under any script. The checks are the app's own: the route came
# back, on the routing thread, and the projection it ran beside still passed its own.
# LAB_ROUTE="lat,lon lat,lon" changes the route; both points must be inside the tile.
#
# LAB_DESTINATION=1 (P6.4, needs LAB_ROUTING) replaces that debug intent with the two ways
# a destination really arrives: an ACTION_VIEW geo: URI, as OsmAnd's "Geo" share item
# sends, and then an ACTION_SEND text in OsmAnd's "Message" format, which supersedes it.
# An address-only geo: goes first and must be refused. The checks: both routes started
# from the vehicle, the second replaced the first, the map engine holds a route line, no
# label or coordinate reached the report, and the decoded picture shows the line — route
# colour absent from the first frames and present in the last.
#
# The guidance script (P6.5, needs LAB_ROUTING and LAB_MAP) is the whole loop: the app is
# sent a destination as an OsmAnd text share while the vehicle is parked, routes to it,
# and puts it on the receiver cluster; the receiver then drives LAB_DRIVE (default
# tools/lab-receiver/drives/lexington.csv, written by tools/jni-host-test/LabDrive.java) -
# real streets that follow the route, miss a turn, and reach the destination another way -
# and checks what the cluster was told: a countdown, REROUTING, a new route, arrival. The
# app side checks its own guidance counters and that no destination reached the report.
# LAB_DRIVE_SPEED sets the cruise in m/s (default 18). LAB_GUIDE_DEST="lat,lon" sends a
# different destination, for a LAB_DRIVE of its own (the live alternatives need a trip long
# enough to have some); the arrival and re-route checks then fail by design.
#
# LAB_FAVORITES="name@lat,lon;name@lat,lon" seeds the dashboard's favorites tile through
# the lab intent before the attempt starts, so the captured frames show the list drawn
# with rows in it (and, past three, its scrollbar). Nothing taps it: the tile's tap and
# scroll rules are host-tested in DashboardTest, and this is the picture of them.
#
# LAB_MAP_EXTRA=<file.map> installs a second map beside LAB_MAP. The app draws every
# installed map as one, so this checks that the pane still renders and follows the
# vehicle with more than one file behind it, and that the report names both.
#
# The daynight script (P7.6, needs LAB_MAP) parks the vehicle, starts the head unit in
# day (NIGHT_DATA is_night=false), and once the map has settled flips it to night, back
# to day, and to night again. The receiver watches the map pane in every decoded picture
# across each flip: it must change theme (most of the pane, darker for night, lighter for
# day), settle, and show no blank or half-drawn picture on the way; the second day and
# night must match the first, so no tile was left in the wrong theme. The app side checks
# its own switch counters: three switches, none of which gave up waiting for its tiles.
# Frames of each theme are in frames/; the receiver prints the video second of each flip.
#
# The controls script (P7.2, P7.3, needs LAB_MAP) presses the dashboard's own controls
# on the projected picture: +, +, -, the notification pause and day/night, each on a
# settled picture. The receiver checks each press changed what it should (the map pane,
# the pause icon, a header gone light); the app side checks its report says zoom 17, three
# zoom presses, the pause on, Day over a head unit still saying night, and that both
# choices were saved to the settings file.
#
# LAB_MARKER=<colour> and LAB_DIAGNOSTICS=1 (P7.2, P7.3) seed the app's saved settings
# before its first launch, the way LAB_MAP installs a map: the marker colour and the
# header's debug line are display choices the session reads from the settings, never
# from the lab intent, so this is how a lab frame shows a non-default one.
#
# LAB_NIGHT=day|night|flip:<seconds> sets what NIGHT_DATA says under any other script:
# night is what every run before P7.6 sent and stays the default; flip starts in day and
# sends night that long after the app subscribes. It needs LAB_SENSORS=all (the default).
#
# The endroute script (P7.4, P7.5, needs LAB_ROUTING and LAB_MAP) shares the guidance
# destination while parked, and once the cluster has the route presses End route on the
# trip panel. The receiver checks the cluster went INACTIVE and heard no route after it,
# and that the trip panel and the turn card left the picture; the app side checks it was
# End route that ended it, and that no destination reached the report. It then presses
# the first Resume row of the idle media pane, which in the emulator lists whatever
# browser services its image has, and checks only the app own counters for that press.
# Since P7.4a the destination is then shared again: the receiver checks the cluster was
# handed back (NAV_FOCUS_NATIVE) when End route was pressed, that the new route asked for
# it again before reaching it, and presses End route once more for a second hand-back.
#
# The calls script (P7.8, P7.9) needs the lab player (tools/lab-player.sh build): it turns
# on dashboard notifications and Call controls in the seeded settings, grants the app
# notification access, and while projecting posts three alerting notifications from the
# lab player. The receiver taps the first row (the read-aloud stub), the app side then
# removes one on the phone, and the receiver presses the pause twice (paused, then the
# shade re-read). Then a call rings, and the receiver waits for the green Answer, presses
# it, and with LAB_CALL_ROUTE=telecom presses Mute, Keypad, the keys 1, 2 and #, Close and
# Hang up; with LAB_CALL_ROUTE=notification (the default) only Hang up, which is all that
# route offers. Each press is checked in the picture; the app side checks its report.
#   * telecom: MANAGE_ONGOING_CALLS is allowed with appops, as the operator would over adb,
#     and the call is a real modem call (adb emu gsm call). The emulator modem simulator
#     dials the host at ::1, which glibc refuses to resolve on a host with no IPv6 address
#     but loopback, so this run alone puts the pod on an internal IPv6 network
#     (oaa-lab-v6: no route out, created on first use) instead of none.
#   * notification: the app-op is left at its default, so calls go through the call
#     notification. The emulator dialer does not post CallStyle, so the lab player posts
#     one, as Google Phone does, and logs which of its intents the dashboard sent.
# Every run also checks that no caller, notification text or keypad digit reached the
# report. LAB_NIGHT works with it, for day frames.
#
# The speech script (P8.1) also needs the lab player. With dashboard notifications on and
# notification access granted, the lab player posts two notifications, the newer with a
# text of about 600 characters; the receiver taps the first row three times, 2.5 s apart
# (read, a second tap that stops it, read again), then waits 30 s. The emulator has no
# A2DP output, so the lab intent turns on Speaker's debug-only Bluetooth override
# (speech_gate=lab). The AOSP image has no text-to-speech engine, so LAB_SPEECH_ENGINE
# picks one:
#   * espeak (the default when $OPENAUTO_CACHE/tts/espeak-ng.apk is there): eSpeak NG for
#     Android, placed there by the operator; this script never downloads one. Installed
#     and made the default engine.
#   * lab (the default otherwise): the lab player's silent stand-in engine, enabled with
#     pm. It speaks 25 ms of silence a character and logs only how many characters.
#   * none: no engine, which checks the degraded path: every tap counted as
#     skipped_no_engine and nothing asked of the audio system.
# The app side checks the report's read-aloud counts for whichever it was, and that no
# sender, text or app reached it. Nothing in the lab listens to the audio.
#
# P8.3 adds the phone's Maps & routing -> Voice guidance card to the speech script,
# through uiautomator. Before the notifications, its Speak turn prompts switch is turned
# off and on: each flip must be saved and noted by the projection ("phone voice guidance").
# Once the last read is over, Play a sample is pressed during the projection: it must be
# counted as voice_samples_*, never as a prompt, and the phone must say it was spoken.
# After the session, Play a sample again with nothing bound: the engine is bound for it,
# handed the 40-character sample, and released (the stand-in service is gone from
# dumpsys); then the lab Bluetooth override is withdrawn and the phone must say there is
# no Bluetooth audio. screen-maps-voice-session.png, screen-voice-sample-*.png.
#
# The voice script (P8.2, needs LAB_ROUTING, LAB_MAP and the lab player) is the guidance
# drive with turn prompts: the same destination, drive and checks as the guidance script,
# with the speech script's engine (LAB_SPEECH_ENGINE, the lab player's silent stand-in by
# default) and Bluetooth override. On the way the receiver presses the trip panel's voice
# toggle off 15 s after pulling away and on again 20 s later, before the drive leaves the
# route, and checks each press changed the toggle's picture and the second put it back.
# The app side checks the saved voice_guidance after each press, and the report: two
# presses, the toggle on at the end, prompts spoken at prepare and at the turn, the
# re-route and the arrival announced, none refused for want of an engine or Bluetooth,
# and no instruction, street or destination in it. P8.3: the phone shows its Voice
# guidance card through the drive, and its switch must follow each press
# (screen-voice-phone-off.png, screen-voice-phone-on.png).
#
# The lab player is also what the endroute script's Resume press plays: the emulator image
# has no launchable player, and since part 2b only launchable apps are listed.
#
# LAB_SCREENS=1 (P7.7) also photographs the phone screen, after any route or destination
# step so it delays none of them: each tab of the connection screen in turn
# (screen-home.png, screen-setup.png, ...), found by its label through uiautomator and
# tapped, the Voice guidance card on Maps (screen-maps-voice.png and -lower, P8.3), the
# Diagnostics tab after turning its switch on in Settings, then Home and
# Settings again with the system in dark mode (screen-home-dark.png, ...). It works under
# any script, checks nothing, and is the only way the lab sees the phone's own screen:
# the frames are the head unit's. The session may have ended by the time it runs.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
EMULATOR_IMAGE=localhost/openauto-emulator:smoke
RECEIVER_IMAGE=localhost/openauto-lab-receiver:p4
SCRIPT=${1:-taps}
# The track script drives for about half a minute after the map has settled, so it gets
# a longer window than the scripts that finish as soon as their taps land.
if [ "$SCRIPT" = track ] || [ "$SCRIPT" = daynight ] || [ "$SCRIPT" = controls ] || [ "$SCRIPT" = endroute ]; then DEFAULT_MS=90000; else DEFAULT_MS=40000; fi
# P7.8, P7.9: notifications, a pause and back, then a call rung, answered and hung up.
if [ "$SCRIPT" = calls ]; then DEFAULT_MS=180000; fi
# P8.1: notifications, three taps and half a minute for the last read to finish.
if [ "$SCRIPT" = speech ]; then DEFAULT_MS=150000; fi
# The guidance drive is several kilometres long and ends only when the app says it arrived.
if [ "$SCRIPT" = guidance ] || [ "$SCRIPT" = voice ]; then DEFAULT_MS=600000; fi
PROJECTION_MS=${LAB_PROJECTION_MS:-$DEFAULT_MS}
# The app-owned audio path: on for --script audio, and available alongside the others so
# a run also covers audio interleaving with fragmenting video keyframes.
if [ -n "${LAB_AUDIO:-}" ] || [ "$SCRIPT" = audio ]; then AUDIO=1; else AUDIO=0; fi
# The app-driven cluster path (P6.2), on the same footing.
if [ -n "${LAB_NAVIGATION:-}" ] || [ "$SCRIPT" = navigation ] || [ "$SCRIPT" = guidance ] || [ "$SCRIPT" = endroute ] \
    || [ "$SCRIPT" = voice ]; then NAVIGATION=1; else NAVIGATION=0; fi
# The navigation script plays the session own demonstration; the app now defaults to the
# followed route, so that script asks for the demonstration by name.
if [ "$SCRIPT" = navigation ]; then NAVIGATION_SCRIPT=1; else NAVIGATION_SCRIPT=0; fi
if [ -n "${LAB_NAVIGATION_FORCE:-}" ]; then NAVIGATION_FORCE=1; else NAVIGATION_FORCE=0; fi
NAVIGATION_CLUSTER=${LAB_NAVIGATION_CLUSTER:-start}
case $NAVIGATION_CLUSTER in start|none) ;; *) echo "emulator-lab: LAB_NAVIGATION_CLUSTER is start or none" >&2; exit 1 ;; esac
# A forced source sends whether or not a cluster was announced, so the receiver expects a
# sequence either way; an unforced one against a silent cluster must stay quiet.
if [ "$NAVIGATION_FORCE" = 1 ]; then NAVIGATION_EXPECT=sequence; else NAVIGATION_EXPECT=auto; fi
work=$CACHE_ROOT/receiver
[ -f app/build/outputs/apk/debug/app-debug.apk ] || { echo "run tools/android-build.sh first" >&2; exit 1; }
[ -x "$work/build/lab-receiver" ] || { echo "run tools/lab-receiver.sh build first" >&2; exit 1; }
[ -e /dev/kvm ] || { echo "emulator-lab: /dev/kvm is required" >&2; exit 1; }
out=$CACHE_ROOT/emulator-lab/$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$out" "$CACHE_ROOT/emulator-avd"
# LAB_VIDEO_CONFIG=1920x1080 advertises the resolution the vehicle actually projects,
# which is what the owned map's label density and frame cost should be judged at.
# LAB_MAP=<file.map> renders the P5.5 owned map from real data. The region has to cover
# the position LabSensorHandler reports (42.4242, -71.2121) or the pane draws an empty
# map, which looks identical to a broken one.
if [ -n "${LAB_MAP:-}" ]; then
    [ -f "$LAB_MAP" ] || { echo "emulator-lab: no map file at $LAB_MAP" >&2; exit 1; }
    cp "$LAB_MAP" "$out/region.map"
elif [ "$SCRIPT" = track ] || [ "$SCRIPT" = guidance ] || [ "$SCRIPT" = daynight ] || [ "$SCRIPT" = controls ] \
    || [ "$SCRIPT" = endroute ] || [ "$SCRIPT" = voice ]; then
    echo "emulator-lab: the $SCRIPT script needs LAB_MAP=<region>.map covering 42.42,-71.21" >&2
    exit 1
fi
DRIVE_ARGS=
if [ "$SCRIPT" = endroute ] && [ -z "${LAB_ROUTING:-}" ]; then
    echo "emulator-lab: the endroute script needs LAB_ROUTING" >&2
    exit 1
fi
if [ "$SCRIPT" = guidance ] || [ "$SCRIPT" = voice ]; then
    [ -n "${LAB_ROUTING:-}" ] || { echo "emulator-lab: the $SCRIPT script needs LAB_ROUTING" >&2; exit 1; }
    drive=${LAB_DRIVE:-tools/lab-receiver/drives/lexington.csv}
    [ -f "$drive" ] || { echo "emulator-lab: no drive at $drive" >&2; exit 1; }
    cp "$drive" "$out/drive.csv"
    DRIVE_ARGS="--track-file /out/drive.csv --track-speed ${LAB_DRIVE_SPEED:-18}"
fi
if [ -n "${LAB_MAP_EXTRA:-}" ]; then
    [ -f "$LAB_MAP_EXTRA" ] || { echo "emulator-lab: no map file at $LAB_MAP_EXTRA" >&2; exit 1; }
    [ -n "${LAB_MAP:-}" ] || { echo "emulator-lab: LAB_MAP_EXTRA needs LAB_MAP" >&2; exit 1; }
    cp "$LAB_MAP_EXTRA" "$out/extra.map"
fi
# P7.6: what NIGHT_DATA says. Unset leaves the receiver at its default, night.
NIGHT_ARGS=
if [ -n "${LAB_NIGHT:-}" ]; then
    case $LAB_NIGHT in day|night|flip:*) ;; *) echo "emulator-lab: LAB_NIGHT is day, night or flip:<seconds>" >&2; exit 1 ;; esac
    [ "$SCRIPT" != daynight ] || { echo "emulator-lab: the daynight script sets NIGHT_DATA itself" >&2; exit 1; }
    [ "$SCRIPT" != controls ] || { echo "emulator-lab: the controls script needs the default night" >&2; exit 1; }
    NIGHT_ARGS="--night $LAB_NIGHT"
fi
# P7.8, P7.9: which way calls reach the calls script's dashboard, and whether the
# emulator needs its modem (and so the IPv6 network the modem simulator dials over).
CALL_ROUTE=${LAB_CALL_ROUTE:-notification}
case $CALL_ROUTE in telecom|notification) ;; *) echo "emulator-lab: LAB_CALL_ROUTE is telecom or notification" >&2; exit 1 ;; esac
MODEM=no
POD_NETWORK=none
if [ "$SCRIPT" = calls ] && [ "$CALL_ROUTE" = telecom ]; then
    MODEM=yes
    POD_NETWORK=oaa-lab-v6
    podman network exists "$POD_NETWORK" \
        || podman network create --internal --ipv6 --subnet fd0a:a0a0:1::/64 "$POD_NETWORK" > /dev/null
fi
CALL_ARGS=
if [ "$SCRIPT" = calls ]; then
    CALL_ARGS="--call-controls $([ "$CALL_ROUTE" = telecom ] && echo full || echo basic)"
fi
# The lab player (tools/lab-player.sh): a launchable player for Resume, and the stand-in
# dialer and messenger for the calls script. Small; copied in beside the app.
if [ -f "$CACHE_ROOT/lab-player/lab-player.apk" ]; then
    cp "$CACHE_ROOT/lab-player/lab-player.apk" "$out/lab-player.apk"
elif [ "$SCRIPT" = calls ] || [ "$SCRIPT" = endroute ] || [ "$SCRIPT" = speech ] || [ "$SCRIPT" = voice ]; then
    echo "emulator-lab: the $SCRIPT script needs the lab player; run tools/lab-player.sh first" >&2
    exit 1
fi
# P8.1: the speech script's engine; an operator-supplied one comes from the cache only.
if [ -f "$CACHE_ROOT/tts/espeak-ng.apk" ]; then SPEECH_ENGINE=${LAB_SPEECH_ENGINE:-espeak}; else SPEECH_ENGINE=${LAB_SPEECH_ENGINE:-lab}; fi
case $SPEECH_ENGINE in espeak|lab|none) ;; *) echo "emulator-lab: LAB_SPEECH_ENGINE is espeak, lab or none" >&2; exit 1 ;; esac
if { [ "$SCRIPT" = speech ] || [ "$SCRIPT" = voice ]; } && [ "$SPEECH_ENGINE" = espeak ]; then
    [ -f "$CACHE_ROOT/tts/espeak-ng.apk" ] || { echo "emulator-lab: no $CACHE_ROOT/tts/espeak-ng.apk" >&2; exit 1; }
    cp "$CACHE_ROOT/tts/espeak-ng.apk" "$out/tts-engine.apk"
fi
# P7.2, P7.3: display choices in the app's own settings file, installed before launch.
if [ -n "${LAB_MARKER:-}" ] || [ -n "${LAB_DIAGNOSTICS:-}" ] || [ "$SCRIPT" = calls ] || [ "$SCRIPT" = speech ] \
    || [ "$SCRIPT" = voice ]; then
    case ${LAB_MARKER:-blue} in blue|white|yellow|orange|green|red|magenta) ;;
        *) echo "emulator-lab: LAB_MARKER is one of blue white yellow orange green red magenta" >&2; exit 1 ;; esac
    {
        echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
        echo "<map>"
        [ -z "${LAB_MARKER:-}" ] || echo "    <string name=\"marker_colour\">$LAB_MARKER</string>"
        [ -z "${LAB_DIAGNOSTICS:-}" ] || echo "    <boolean name=\"diagnostics\" value=\"true\" />"
        # P7.8, P7.9: both are off by default and are the operator's to turn on.
        if [ "$SCRIPT" = calls ]; then
            echo "    <boolean name=\"notifications_on_dashboard\" value=\"true\" />"
            echo "    <boolean name=\"call_controls\" value=\"true\" />"
        fi
        # P8.1: read aloud is on by default once the tile is; seeded anyway, and Pause media.
        if [ "$SCRIPT" = speech ]; then
            echo "    <boolean name=\"notifications_on_dashboard\" value=\"true\" />"
            echo "    <boolean name=\"read_aloud\" value=\"true\" />"
            echo "    <string name=\"speech_media\">pause</string>"
        fi
        # P8.2: voice guidance is on by default; seeded on so the first press turns it off.
        if [ "$SCRIPT" = voice ]; then
            echo "    <boolean name=\"voice_guidance\" value=\"true\" />"
            echo "    <string name=\"speech_media\">pause</string>"
        fi
        echo "</map>"
    } > "$out/settings.xml"
fi
ROUTE=
if [ -n "${LAB_ROUTING:-}" ]; then
    [ -f "$LAB_ROUTING/W75_N40.rd5" ] || { echo "emulator-lab: no W75_N40.rd5 in $LAB_ROUTING" >&2; exit 1; }
    cp "$LAB_ROUTING/W75_N40.rd5" "$out/W75_N40.rd5"
    # The lab position to Boston Common: about 14 km of town streets and one motorway.
    ROUTE=${LAB_ROUTE:-42.4242,-71.2121 42.3551,-71.0656}
fi
DESTINATION=0
if [ -n "${LAB_DESTINATION:-}" ]; then
    [ -n "${LAB_ROUTING:-}" ] || { echo "emulator-lab: LAB_DESTINATION needs LAB_ROUTING" >&2; exit 1; }
    DESTINATION=1
fi
pod=openauto-lab-$$
trap 'podman pod rm -f "$pod" > /dev/null 2>&1 || true' EXIT
# Only loopback, shared by the receiver and the emulator; the telecom calls run adds the
# internal IPv6 network above, which has no route out either.
podman pod create --name "$pod" --network "$POD_NETWORK" --userns keep-id > /dev/null

# The receiver, with freshly generated credentials, waiting for the app.
podman run -d --pod "$pod" --name "$pod-receiver" --security-opt label=disable \
  -v "$work:/receiver" -v "$out:/out" "$RECEIVER_IMAGE" sh -ec '
    cd /out
    openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 7 \
        -subj "/CN=OpenAutoAndroid lab receiver" \
        -keyout receiver-key.pem -out receiver-cert.pem 2>/dev/null
    export OAA_LAB_CERT_PEM=/out/receiver-cert.pem OAA_LAB_KEY_PEM=/out/receiver-key.pem
    # Caught rather than left to -e: a failed check is when the frames matter most.
    status=0
    openssl x509 -in receiver-cert.pem -noout -fingerprint -sha256 \
        | sed "s/.*=//; s/://g" | tr "A-F" "a-f" > receiver-fingerprint.txt
    /receiver/build/lab-receiver --listen 127.0.0.1:5288 --request-version 1.1 --projection \
        --script '"$SCRIPT"' --timeout-ms '"$((PROJECTION_MS + 120000))"' --hold-ms 2000 --tolerance 24 \
        --video-config '"${LAB_VIDEO_CONFIG:-800x480}"' \
        '"$([ "$AUDIO" = 1 ] && echo '--audio --audio-out /out/audio.pcm')"' \
        '"$([ "$NAVIGATION" = 1 ] && echo "--navigation --navigation-cluster $NAVIGATION_CLUSTER --navigation-expect $NAVIGATION_EXPECT")"' \
        '"$([ -n "${LAB_SENSORS:-all}" ] && echo "--sensors ${LAB_SENSORS:-all}")"' \
        '"$([ -n "${LAB_TRACK:-}" ] && echo '--track')"' '"$DRIVE_ARGS"' '"$NIGHT_ARGS"' '"$CALL_ARGS"' \
        --trace /out/receiver-trace.jsonl --video-out /out/video.h264 > /out/receiver.txt 2> /out/receiver-err.txt \
        || status=$?
    echo "receiver_exit=$status" >> /out/receiver.txt
    if [ -s /out/video.h264 ]; then
        ffmpeg -v error -xerror -i /out/video.h264 -f null - > /out/ffmpeg.txt 2>&1
        echo "ffmpeg_exit=$? video_bytes=$(wc -c < /out/video.h264)" >> /out/ffmpeg.txt
        mkdir -p /out/frames
        # A picture a second from the app-encoded stream, as projection evidence.
        ffmpeg -v error -i /out/video.h264 -vf fps=1 /out/frames/%03d.png >> /out/ffmpeg.txt 2>&1 || true
    fi
    exit $status
  ' > /dev/null

name=openauto-emulator-lab-$$
status=0
podman run --rm --pod "$pod" --name "$name" --security-opt label=disable \
  --passwd-entry '$USERNAME:*:$UID:$GID:$NAME:/tmp:/bin/sh' \
  -v "$PWD:/workspace" -w /workspace -v "$CACHE_ROOT/cargo:/cache/cargo" -e CARGO_HOME=/cache/cargo \
  -v "$CACHE_ROOT/sources:/cache/sources:ro" --device /dev/kvm \
  -v "$CACHE_ROOT/android-user:/cache/android-user" -e ANDROID_USER_HOME=/cache/android-user \
  -v "$CACHE_ROOT/emulator-avd:/cache/avd" -e ANDROID_AVD_HOME=/cache/avd \
  -e ANDROID_EMULATOR_HOME=/cache/avd -e ANDROID_SDK_ROOT=/opt/android-sdk \
  -e PROJECTION_MS="$PROJECTION_MS" -e SOFTWARE_ENCODER="${LAB_SOFTWARE_ENCODER:-false}" \
  -e AUDIO="$AUDIO" -e NAVIGATION="$NAVIGATION" -e NAVIGATION_FORCE="$NAVIGATION_FORCE" \
  -e NAVIGATION_CLUSTER="$NAVIGATION_CLUSTER" \
  -e LAB_SENSORS="${LAB_SENSORS:-all}" -e SCRIPT="$SCRIPT" -e ROUTE="$ROUTE" \
  -e DESTINATION="$DESTINATION" -e NAVIGATION_SCRIPT="$NAVIGATION_SCRIPT" \
  -e GUIDE_DEST="${LAB_GUIDE_DEST:-42.44950,-71.23100}" \
  -e LAB_FAVORITES="${LAB_FAVORITES:-}" -e LAB_SCREENS="${LAB_SCREENS:-}" \
  -e MAPS_EXPECTED="$([ -n "${LAB_MAP_EXTRA:-}" ] && echo 2 || echo 1)" \
  -e CALL_ROUTE="$CALL_ROUTE" -e MODEM="$MODEM" -e SPEECH_ENGINE="$SPEECH_ENGINE" \
  -v "$out:/out" "$EMULATOR_IMAGE" sh -eu -c '
    package=org.openautoandroid.source
    log() { echo "emulator-lab: $*"; }
    fail() {
        echo "emulator-lab: FAIL $*" >&2
        timeout 20 adb logcat -d > /out/logcat.txt 2>&1 || true
        # The saved report says why the app gave up, which logcat does not: this source
        # records its reasons in the report rather than in the system log.
        last=$(adb shell run-as $package ls files/reports 2>/dev/null | tr -d "\r" | sort | tail -n 1)
        [ -n "$last" ] && adb exec-out run-as $package cat "files/reports/$last" > /out/app-report.txt 2>/dev/null
        exit 1
    }

    tools/openssl-build.sh x86_64-linux-android
    . tools/openssl-env.sh
    cargo build -p aap-jni --release --target x86_64-linux-android --offline --locked 2>&1 | tail -n 1

    work=$(mktemp -d)
    cp app/build/outputs/apk/debug/app-debug.apk "$work/unsigned.apk"
    # The APK ships arm64-v8a only, so the emulator copy gets x86_64 builds of both
    # native libraries injected: the one from this project, and the VTM tessellator.
    # Without the second the dashboard still runs and reports that the map did not
    # load, which is the degradation path rather than the map itself.
    # NOTE: no apostrophes anywhere in this container script. It is one single-quoted
    # sh -c argument, so a stray quote ends it and everything after is silently lost.
    vtm_natives=/cache/sources/vtm-android-0.25.0-natives-x86_64.jar
    python3 -c "import sys, zipfile
with zipfile.ZipFile(sys.argv[1], \"a\") as apk:
    apk.write(sys.argv[2], \"lib/x86_64/libaap_jni.so\", compress_type=zipfile.ZIP_STORED)
    if len(sys.argv) > 3:
        with zipfile.ZipFile(sys.argv[3]) as natives:
            apk.writestr(
                zipfile.ZipInfo(\"lib/x86_64/libvtm-jni.so\"),
                natives.read(\"lib/x86_64/libvtm-jni.so\"),
                compress_type=zipfile.ZIP_STORED,
            )
" "$work/unsigned.apk" target/x86_64-linux-android/release/libaap_jni.so \
        $([ -f "$vtm_natives" ] && echo "$vtm_natives")
    zipalign -f -P 16 4 "$work/unsigned.apk" "$work/aligned.apk"
    apksigner sign --ks /cache/android-user/debug.keystore --ks-pass pass:android \
        --key-pass pass:android --out /out/lab.apk "$work/aligned.apk"

    rm -rf /cache/avd/smoke.avd /cache/avd/smoke.ini
    mkdir -p /cache/avd/smoke.avd
    printf "avd.ini.encoding=UTF-8\npath=/cache/avd/smoke.avd\ntarget=android-36\n" > /cache/avd/smoke.ini
    printf "%s\n" "AvdId=smoke" "abi.type=x86_64" "hw.cpu.arch=x86_64" \
        "image.sysdir.1=system-images/android-36/default/x86_64/" "tag.id=default" \
        "hw.ramSize=3072" "disk.dataPartition.size=2G" "hw.lcd.width=720" "hw.lcd.height=1280" \
        "hw.lcd.density=320" "hw.keyboard=yes" "hw.gpu.enabled=yes" "hw.gpu.mode=swangle_indirect" \
        "hw.gsmModem=$MODEM" "hw.gps=no" "hw.sensors.proximity=no" \
        > /cache/avd/smoke.avd/config.ini

    emulator -avd smoke -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
        -gpu swangle_indirect -feature -Vulkan $([ "$MODEM" = yes ] || echo -feature -ModemSimulator) -accel on -no-metrics \
        > /out/emulator.txt 2>&1 &
    emulator=$!
    trap "kill $emulator 2>/dev/null || true" EXIT
    adb start-server > /dev/null 2>&1
    booted=
    for _ in $(seq 240); do
        if [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" = 1 ]; then booted=1; break; fi
        kill -0 $emulator 2>/dev/null || { tail -n 30 /out/emulator.txt >&2; fail "emulator exited"; }
        sleep 2
    done
    [ -n "$booted" ] || fail "emulator did not boot"
    log "booted: $(adb shell getprop ro.build.fingerprint | tr -d "\r")"
    timeout 30 adb root > /out/adb-root.txt 2>&1 || fail "adb root: $(cat /out/adb-root.txt)"
    sleep 3
    timeout 60 adb wait-for-device || fail "device not back after adb root"
    adb shell setprop debug.checkjni 1 || fail "setprop debug.checkjni"
    for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
        adb shell settings put global $setting 0
    done

    adb install /out/lab.apk > /out/install.txt 2>&1 || { cat /out/install.txt >&2; fail install; }
    adb shell pm grant $package android.permission.POST_NOTIFICATIONS
    labplayer=org.openautoandroid.labplayer
    if [ -f /out/lab-player.apk ]; then
        adb install /out/lab-player.apk > /out/install-lab-player.txt 2>&1 || { cat /out/install-lab-player.txt >&2; fail "lab player install"; }
        adb shell pm grant $labplayer android.permission.POST_NOTIFICATIONS
        log "lab player installed"
    fi
    if [ "$SCRIPT" = calls ] || [ "$SCRIPT" = speech ]; then
        # P7.8: what the operator does once in system Settings.
        adb shell cmd notification allow_listener $package/$package.MediaAccessService || fail "notification access"
        # P7.9: what the operator runs once from a computer (CallControl.GRANT_COMMAND).
        if [ "$CALL_ROUTE" = telecom ]; then
            adb shell appops set --user 0 $package MANAGE_ONGOING_CALLS allow || fail "appops"
            log "MANAGE_ONGOING_CALLS: $(adb shell appops get --user 0 $package MANAGE_ONGOING_CALLS | tr -d "\r")"
        fi
    fi
    # P8.1: which engines the image has, and the operator-supplied one if the cache had it.
    if [ "$SCRIPT" = speech ] || [ "$SCRIPT" = voice ]; then
        if [ "$SPEECH_ENGINE" = espeak ]; then
            adb install /out/tts-engine.apk > /out/install-tts.txt 2>&1 || { cat /out/install-tts.txt >&2; fail "speech engine install"; }
            engine=$(aapt2 dump packagename /out/tts-engine.apk 2>/dev/null | tr -d "\r")
            [ -n "$engine" ] && adb shell settings put secure tts_default_synth "$engine"
            log "speech engine installed from the cache: $engine"
        elif [ "$SPEECH_ENGINE" = lab ]; then
            adb shell pm enable $labplayer/.LabSpeechEngine > /dev/null || fail "enable the lab speech engine"
            adb shell settings put secure tts_default_synth $labplayer
            log "speech engine: the lab player stand-in (silent)"
        else
            log "no speech engine; the degraded path is checked"
        fi
        adb shell cmd package query-services --user 0 -a android.intent.action.TTS_SERVICE > /out/tts-services.txt 2>&1 || true
        log "speech engines on the image: $(grep -c "packageName=" /out/tts-services.txt || true)"
    fi
    # P7.2, P7.3: the seeded display choices, in place before the app first starts, so the
    # file it loads is this one. Owned and labelled as the app would have written it.
    if [ -f /out/settings.xml ]; then
        adb push /out/settings.xml /data/local/tmp/settings.xml > /dev/null 2>&1 || fail "settings push"
        uid=$(adb shell stat -c %u /data/data/$package | tr -d "\r")
        adb shell "mkdir -p /data/data/$package/shared_prefs \
            && chown $uid:$uid /data/data/$package/shared_prefs && chmod 771 /data/data/$package/shared_prefs \
            && cp /data/local/tmp/settings.xml /data/data/$package/shared_prefs/settings.xml \
            && chown $uid:$uid /data/data/$package/shared_prefs/settings.xml \
            && chmod 660 /data/data/$package/shared_prefs/settings.xml \
            && restorecon -R /data/data/$package/shared_prefs \
            && rm -f /data/local/tmp/settings.xml" || fail "settings install"
        log "settings seeded: $(tr -d "\n" < /out/settings.xml)"
    fi
    # The owned map reads operator-supplied data out of app-private storage; there is no
    # download path (APP10), so the lab supplies it the same way an operator would.
    if [ -f /out/region.map ]; then
        adb push /out/region.map /data/local/tmp/region.map > /dev/null 2>&1 || fail "map push"
        uid=$(adb shell stat -c %u /data/data/$package | tr -d "\r")
        adb shell "mkdir -p /data/data/$package/files/maps \
            && cp /data/local/tmp/region.map /data/data/$package/files/maps/region.map \
            && chown $uid:$uid /data/data/$package/files/maps/region.map \
            && restorecon -R /data/data/$package/files \
            && rm -f /data/local/tmp/region.map" || fail "map install"
        log "map installed: $(adb shell stat -c %s /data/data/$package/files/maps/region.map | tr -d "\r") bytes"
    fi
    if [ -f /out/extra.map ]; then
        adb push /out/extra.map /data/local/tmp/extra.map > /dev/null 2>&1 || fail "extra map push"
        uid=$(adb shell stat -c %u /data/data/$package | tr -d "\r")
        adb shell "cp /data/local/tmp/extra.map /data/data/$package/files/maps/extra.map \
            && chown $uid:$uid /data/data/$package/files/maps/extra.map \
            && restorecon -R /data/data/$package/files \
            && rm -f /data/local/tmp/extra.map" || fail "extra map install"
        log "extra map installed: $(adb shell stat -c %s /data/data/$package/files/maps/extra.map | tr -d "\r") bytes"
    fi
    # P6.3: the routing tile goes where the connection screen import would put it. The
    # staged copy is deleted as soon as it is on the device; it is 119 MB per run.
    if [ -f /out/W75_N40.rd5 ]; then
        adb push /out/W75_N40.rd5 /data/local/tmp/W75_N40.rd5 > /dev/null 2>&1 || fail "routing tile push"
        rm -f /out/W75_N40.rd5
        uid=$(adb shell stat -c %u /data/data/$package | tr -d "\r")
        adb shell "mkdir -p /data/data/$package/files/routing/segments \
            && cp /data/local/tmp/W75_N40.rd5 /data/data/$package/files/routing/segments/W75_N40.rd5 \
            && chown -R $uid:$uid /data/data/$package/files/routing \
            && restorecon -R /data/data/$package/files \
            && rm -f /data/local/tmp/W75_N40.rd5" || fail "routing tile install"
        log "routing tile installed: $(adb shell stat -c %s /data/data/$package/files/routing/segments/W75_N40.rd5 | tr -d "\r") bytes"
    fi
    adb logcat -c

    # The app reaches the receiver through adbd, so the APK needs no network permission.
    adb reverse localabstract:openauto-lab tcp:5288 || fail "adb reverse"
    log "adb reverse localabstract:openauto-lab -> tcp:5288"

    # First launch prepares the source identity (RSA key generation).
    adb shell am start -W -n $package/.ConnectionActivity > /out/launch.txt
    grep -q "Status: ok" /out/launch.txt || { cat /out/launch.txt >&2; fail launch; }
    ready=
    for _ in $(seq 180); do
        [ "$(adb shell run-as $package ls files/identity 2>/dev/null | tr -d "\r")" = source-identity.pem ] && { ready=1; break; }
        sleep 1
    done
    [ -n "$ready" ] || fail "source identity was not prepared"

    # The lab transport is a debug-build intent extra; a vehicle attempt never uses it.
    adb shell am start -n $package/.ConnectionActivity \
        --es lab_socket openauto-lab --es lab_stop_after projection \
        --ez lab_software_encoder "$SOFTWARE_ENCODER" \
        --ez lab_audio "$([ "$AUDIO" = 1 ] && echo true || echo false)" \
        --ez lab_navigation "$([ "$NAVIGATION" = 1 ] && echo true || echo false)" \
        --ez lab_navigation_force "$([ "$NAVIGATION_FORCE" = 1 ] && echo true || echo false)" \
        --ez lab_navigation_script "$([ "$NAVIGATION_SCRIPT" = 1 ] && echo true || echo false)" \
        --es lab_sensors "${LAB_SENSORS:-all}" \
        $({ [ "$SCRIPT" = speech ] || [ "$SCRIPT" = voice ]; } && echo "--ez lab_speech_bluetooth true") \
        ${LAB_FAVORITES:+--es lab_favorites "\"$LAB_FAVORITES\""} \
        --el lab_projection_ms "$PROJECTION_MS" > /out/lab-intent.txt 2>&1 || fail "lab intent"
    log "lab projection attempt started (${PROJECTION_MS} ms)"

    # P7.7: the stage is read from the saved report, not the screen. The report is
    # rewritten on every session event; the phone screen now says the status in words on
    # Home, and the old Stage line lives on the Diagnostics tab, which a lab run never
    # opens. Its event log gets one "stage=X" line per change and keeps it, so a stage is
    # seen even if it has already passed by the next poll; the [attempt] section holds
    # the current stage as a line of its own, "stage=X" and nothing else.
    report() {
        latest=$(adb shell run-as $package ls files/reports 2>/dev/null | tr -d "\r" | sort | tail -n 1)
        [ -n "$latest" ] || return 0
        adb exec-out run-as $package cat "files/reports/$latest" 2>/dev/null || true
    }
    # The phone screen through uiautomator (P7.7, P8.3). tap_text taps the node whose
    # text starts with $1: the first in the view tree, or with $2=last the last one, which
    # for a tab label is the tab bar below the page. A button may be drawn in capitals, so
    # the P8.3 helpers match any case.
    dump() {
        adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || return 1
        adb exec-out cat /sdcard/ui.xml 2>/dev/null | tr ">" "\n"
    }
    tap_text() {
        nodes=$(dump | grep "text=\"$1" || true)
        if [ "${2:-first}" = last ]; then node=$(echo "$nodes" | tail -n 1); else node=$(echo "$nodes" | head -n 1); fi
        box=$(echo "$node" | sed -n "s/.*bounds=\"\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]\".*/\1 \2 \3 \4/p")
        [ -n "$box" ] || return 1
        set -- $box
        adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
        sleep 1
    }
    tap_texti() {
        node=$(dump | grep -i "text=\"$1" | head -n 1)
        box=$(echo "$node" | sed -n "s/.*bounds=\"\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]\".*/\1 \2 \3 \4/p")
        [ -n "$box" ] || return 1
        set -- $box
        adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
        sleep 1
    }
    shot() { adb exec-out screencap -p > "/out/screen-$1.png"; }
    swipe_up() { adb shell input swipe 360 1000 360 250 300; sleep 1; }
    # Scrolls until the node whose text starts with $1 (any case) sits in the middle of the
    # screen, clear of the app bar and the tab bar. The dump lists nodes scrolled out of
    # sight too, so being in it is not enough; short swipes, so none is jumped over.
    scroll_to() {
        for _ in $(seq 14); do
            box=$(dump | grep -i "text=\"$1" | head -n 1 \
                | sed -n "s/.*bounds=\"\[[0-9]*,\([0-9]*\)\]\[[0-9]*,\([0-9]*\)\]\".*/\1 \2/p")
            if [ -n "$box" ]; then
                top=${box% *}
                bottom=${box#* }
                y=$(( (top + bottom) / 2 ))
                if [ "$top" -lt "$bottom" ] && [ "$y" -ge 300 ] && [ "$y" -le 950 ]; then return 0; fi
                if [ "$y" -lt 300 ]; then adb shell input swipe 360 500 360 850 400; sleep 1; continue; fi
            fi
            adb shell input swipe 360 850 360 500 400
            sleep 1
        done
        return 1
    }
    # Whether the switch labelled $1 is on: true or false.
    checked() {
        dump | grep "text=\"$1\"" | head -n 1 | sed -n "s/.* checked=\"\([a-z]*\)\".*/\1/p"
    }
    # Whether any node text on screen contains $1.
    on_screen() {
        dump | grep -q "text=\"[^\"]*$1"
    }
    # P8.3: Maps and routing, scrolled to its Voice guidance card.
    voice_card() {
        tap_text Maps last || return 1
        scroll_to "Speak turn prompts"
    }
    projecting=
    for _ in $(seq 120); do
        if report | grep -q " stage=PROJECTING "; then projecting=1; break; fi
        sleep 1
    done
    [ -n "$projecting" ] || { adb exec-out screencap -p > /out/not-projecting.png; fail "the app never reached PROJECTING"; }
    log "app reached PROJECTING"
    adb exec-out screencap -p > /out/projecting.png
    computed() {
        report | grep -c "route computed in" || true
    }
    # Asked for only now, so the search runs while frames are being rendered and encoded.
    if [ "$SCRIPT" = guidance ] || [ "$SCRIPT" = endroute ] || [ "$SCRIPT" = voice ]; then
        # P6.5: one destination, shared as OsmAnd shares it, while the vehicle is parked
        # at the start of the drive. The receiver pulls away when the cluster goes active
        # (guidance), or presses End route on the trip panel (endroute, P7.4).
        # LAB_GUIDE_DEST="lat,lon" sends another destination with a LAB_DRIVE of its own;
        # the arrival and re-route checks are written for the default and will not pass.
        glat=${GUIDE_DEST%,*}
        glon=${GUIDE_DEST#*,}
        text="Location: geo:$glat,$glon?z=16&q=$glat,$glon(Guidemarker Green)
https://osmand.net/map?pin=$glat,$glon#16/$glat/$glon"
        adb shell am start -W -a android.intent.action.SEND -t text/plain -n $package/.DestinationActivity \
            --es android.intent.extra.TEXT "\"$text\"" > /out/lab-destination.txt 2>&1 || fail "SEND intent"
        routed=
        for _ in $(seq 180); do
            if [ "$(computed)" -ge 1 ]; then routed=1; break; fi
            sleep 1
        done
        [ -n "$routed" ] || fail "no route came back for the shared destination"
        log "route computed; the receiver drives once the cluster is active"
        if [ "$SCRIPT" = endroute ]; then
            # P7.4a: the next route after End route. Once the app has noted the press, and
            # after the receiver has watched the cluster stay clear and pressed Resume, the
            # same destination is shared again. The receiver checks that route asked for
            # the cluster again before using it, then presses End route a second time.
            pressed=
            for _ in $(seq 120); do
                if report | grep -q "dashboard end route pressed"; then pressed=1; break; fi
                sleep 1
            done
            [ -n "$pressed" ] || fail "End route was never pressed"
            sleep 10
            adb shell am start -W -a android.intent.action.SEND -t text/plain -n $package/.DestinationActivity \
                --es android.intent.extra.TEXT "\"$text\"" > /out/lab-destination-again.txt 2>&1 || fail "second SEND intent"
            routed=
            for _ in $(seq 180); do
                if [ "$(computed)" -ge 2 ]; then routed=1; break; fi
                sleep 1
            done
            [ -n "$routed" ] || fail "no route came back for the destination shared after End route"
            log "second route computed after End route"
        fi
        if [ "$SCRIPT" = voice ]; then
            # P8.3: the phone Voice guidance card, on screen through the drive, so its
            # switch can be seen to follow the trip panel toggle.
            voice_card || fail "no Voice guidance card on the phone"
            # P8.2: each press of the toggle is saved at once, like day/night, so the saved
            # file is read after each: off after the first, on after the second.
            for state in off on; do
                noted=
                for _ in $(seq 150); do
                    if report | grep -q "dashboard voice guidance $state"; then noted=1; break; fi
                    sleep 1
                done
                [ -n "$noted" ] || fail "the voice toggle was never pressed $state"
                sleep 1
                adb exec-out run-as $package cat shared_prefs/settings.xml > /out/app-settings-voice-$state.xml 2>/dev/null \
                    || fail "no saved settings file after the voice toggle went $state"
                if [ "$state" = off ]; then value=false; else value=true; fi
                grep -q "name=\"voice_guidance\" value=\"$value\"" /out/app-settings-voice-$state.xml \
                    || fail "voice_guidance was not saved as $value"
                log "voice toggle $state, saved as voice_guidance=$value"
                # P8.3: the phone switch shows it, redrawn by the note, not only on return.
                shown=
                for _ in $(seq 10); do
                    if [ "$(checked "Speak turn prompts")" = "$value" ]; then shown=1; break; fi
                    sleep 1
                done
                [ -n "$shown" ] || fail "the phone switch did not follow the trip panel toggle $state"
                shot voice-phone-$state
                log "phone switch shows $state"
            done
        fi
    elif [ "$DESTINATION" = 1 ]; then
        # P6.4: the intents a share really produces, sent to the share target itself.
        # The labels are markers the privacy check below looks for in the report.
        adb shell am start -W -a android.intent.action.VIEW -n $package/.DestinationActivity \
            -d "\"geo:0,0?q=1100+Labmarker+Street\"" > /out/lab-destination-0.txt 2>&1 \
            || fail "address-only VIEW intent"
        adb shell am start -W -a android.intent.action.VIEW -n $package/.DestinationActivity \
            -d "\"geo:42.35510,-71.06560?z=16&q=42.35510,-71.06560(Viewmarker Common)\"" \
            > /out/lab-destination-1.txt 2>&1 || fail "geo VIEW intent"
        log "destination shared as geo: while projecting"
        routed=
        for _ in $(seq 120); do
            if [ "$(computed)" -ge 1 ]; then routed=1; break; fi
            sleep 1
        done
        [ -n "$routed" ] || fail "no route came back for the geo: destination"
        log "first route computed; sharing a second destination as text"
        # OsmAnd Share -> Message: a geo: line, then its link on the next line.
        text="Location: geo:42.44950,-71.23100?z=16&q=42.44950,-71.23100(Sendmarker Green)
https://osmand.net/map?pin=42.44950,-71.23100#16/42.44950/-71.23100"
        adb shell am start -W -a android.intent.action.SEND -t text/plain -n $package/.DestinationActivity \
            --es android.intent.extra.TEXT "\"$text\"" > /out/lab-destination-2.txt 2>&1 || fail "SEND intent"
        routed=
        for _ in $(seq 120); do
            if [ "$(computed)" -ge 2 ]; then routed=1; break; fi
            sleep 1
        done
        [ -n "$routed" ] || fail "no route came back for the shared text"
        log "second route computed"
    elif [ -n "$ROUTE" ]; then
        adb shell am start -n $package/.ConnectionActivity --es lab_route "\"$ROUTE\"" \
            > /out/lab-route.txt 2>&1 || fail "lab route intent"
        log "route requested while projecting"
    fi

    # P7.8, P7.9: the calls script. Three notifications from the lab player; once the app
    # has noted the receiver tapping a row, one is removed on the phone; the receiver then
    # presses the pause twice, which takes it about ten seconds; then a call rings, over
    # the modem for the telecom route or as the lab player CallStyle call otherwise, and
    # this waits for the dashboard to have seen it end.
    if [ "$SCRIPT" = calls ]; then
        sleep 5
        for n in 1 2 3; do
            adb shell am broadcast -n $labplayer/.MessageReceiver -a $labplayer.POST --es tag lab$n \
                --es title "\"Sendermarker $n\"" --es text "\"Bodymarker message $n\"" > /dev/null || fail "post $n"
            sleep 1
        done
        log "three notifications posted"
        tapped=
        for _ in $(seq 90); do
            if report | grep -q "notification read aloud"; then tapped=1; break; fi
            sleep 1
        done
        [ -n "$tapped" ] || fail "no notification row was tapped"
        adb shell am broadcast -n $labplayer/.MessageReceiver -a $labplayer.CANCEL --es tag lab2 > /dev/null || fail "cancel"
        log "one notification removed on the phone"
        sleep 16
        if [ "$CALL_ROUTE" = telecom ]; then
            adb emu gsm call 5551234 > /out/gsm-call.txt 2>&1 || fail "gsm call"
        else
            adb shell am broadcast -n $labplayer/.CallReceiver -a $labplayer.RING > /dev/null || fail "ring"
        fi
        log "call ringing over the $CALL_ROUTE route"
        ended=
        for _ in $(seq 150); do
            if report | grep -q "call none"; then ended=1; break; fi
            sleep 1
        done
        [ -n "$ended" ] || fail "the dashboard never saw the call end"
        log "call over"
    fi

    # P8.1: the speech script. An older short notification, then the one the receiver taps,
    # whose text is far over the 400 characters a read is cut to; then wait for its three
    # taps to have been noted: read, stopped (or skipped), read again (or skipped).
    if [ "$SCRIPT" = speech ]; then
        # P8.3, first: the phone Speak turn prompts switch off and on again mid-projection.
        # It is the trip panel toggle, so each flip must be saved, reach Speaker and the
        # dashboard (noted as phone voice guidance), and leave the report on at the end. The
        # receiver waits up to 90 s for the notifications, so this goes before them.
        voice_card || fail "no Voice guidance card on the phone"
        shot maps-voice-session
        [ "$(checked "Speak turn prompts")" = true ] || fail "Speak turn prompts is not on as seeded"
        for state in off on; do
            tap_text "Speak turn prompts" || fail "could not flip Speak turn prompts $state"
            noted=
            for _ in $(seq 20); do
                if report | grep -q "phone voice guidance $state"; then noted=1; break; fi
                sleep 1
            done
            [ -n "$noted" ] || fail "turning Speak turn prompts $state never reached the dashboard"
            if [ "$state" = off ]; then value=false; else value=true; fi
            adb exec-out run-as $package cat shared_prefs/settings.xml > /out/app-settings-phone-$state.xml 2>/dev/null \
                || fail "no saved settings file after the phone switch went $state"
            grep -q "name=\"voice_guidance\" value=\"$value\"" /out/app-settings-phone-$state.xml \
                || fail "voice_guidance was not saved as $value from the phone"
            log "phone switch $state: saved, and noted by the projection"
        done
        sleep 5
        adb shell am broadcast -n $labplayer/.MessageReceiver -a $labplayer.POST --es tag lab1 \
            --es title "\"Sendermarker 1\"" --es text "\"Bodymarker short\"" > /dev/null || fail "post 1"
        sleep 1
        long=$(for n in $(seq 60); do printf "Bodymarker %s " "$n"; done)
        adb shell am broadcast -n $labplayer/.MessageReceiver -a $labplayer.POST --es tag lab2 \
            --es title "\"Sendermarker 2\"" --es text "\"$long\"" > /dev/null || fail "post 2"
        log "two notifications posted, the newer ${#long} characters long"
        tapped=
        for _ in $(seq 150); do
            if [ "$(report | grep -c "notification read aloud")" -ge 3 ]; then tapped=1; break; fi
            sleep 1
        done
        [ -n "$tapped" ] || fail "the three taps were not all noted: $(report | grep "notification read aloud" | tr "\n" ";")"
        log "three taps noted"
        # P8.3: Play a sample during the projection, through its engine, once the last read
        # (about 420 characters, some 11 s with the stand-in) is over, so it cuts nothing
        # off; the receiver ends the session 30 s after its last tap.
        noted_at=$(date +%s)
        scroll_to "Play a sample" || fail "no Play a sample button"
        while [ $(( $(date +%s) - noted_at )) -lt 13 ]; do sleep 1; done
        tap_texti "Play a sample" || fail "could not press Play a sample"
        sampled=
        for _ in $(seq 12); do
            if report | grep -q "voice sample"; then sampled=1; break; fi
            sleep 1
        done
        [ -n "$sampled" ] || fail "Play a sample noted nothing during the projection"
        sleep 3
        shot voice-sample-session
        # What the phone says about it, read now: the screens below leave this tab.
        if [ "$SPEECH_ENGINE" = none ]; then want="no text-to-speech engine"; else want="Spoken (lab Bluetooth override"; fi
        on_screen "$want" || fail "the phone did not say $want about the sample"
        # The probe the idle sample below relies on: a bound stand-in is listed.
        if [ "$SPEECH_ENGINE" = lab ]; then
            adb shell dumpsys activity services $labplayer | grep -q LabSpeechEngine \
                || fail "the stand-in engine is not listed while the projection holds it"
        fi
        log "sample during the projection: $(report | grep "voice sample" | sed "s/.*voice sample/voice sample/" | tr "\n" ";")"
    fi

    # P7.7: the phone screens, after every route and destination step so the taps and
    # scrolls never delay one; the session may already have ended by now.
    if [ -n "$LAB_SCREENS" ]; then
        shot home
        swipe_up; swipe_up; shot home-lower
        for tab in Setup Maps Settings; do
            name=$(echo "$tab" | tr "A-Z" "a-z")
            if tap_text "$tab" last; then shot "$name"; swipe_up; swipe_up; shot "$name-lower"; else log "no $tab tab on screen"; fi
        done
        # P8.3: the Voice guidance card on Maps and routing, top and bottom.
        if voice_card; then
            shot maps-voice
            scroll_to "Play a sample" && swipe_up && shot maps-voice-lower
        else
            log "no Voice guidance card on screen"
        fi
        tap_text Settings last || true
        # Still on Settings, scrolled to its end, where the Diagnostics switch is.
        swipe_up; swipe_up
        if tap_text Diagnostics && tap_text Diagnostics last; then
            shot diagnostics; swipe_up; swipe_up; shot diagnostics-lower
        else
            log "could not open the Diagnostics tab"
        fi
        tap_text Home last || true
        # The theme follows the system dark setting; the activity is recreated on the
        # change and keeps its tab.
        adb shell cmd uimode night yes > /dev/null 2>&1 && sleep 3 && shot home-dark
        if tap_text Settings last; then shot settings-dark; fi
        tap_text Home last || true
        adb shell cmd uimode night no > /dev/null 2>&1 || true
        sleep 2
        log "phone screens captured"
    fi

    # Wait for the attempt to end (the receiver stops the session after its script).
    idle=
    for _ in $(seq $((PROJECTION_MS / 1000 + 240))); do
        if report | grep -qx "stage=IDLE"; then idle=1; break; fi
        sleep 1
    done
    adb exec-out screencap -p > /out/after.png
    name=$(adb shell run-as $package ls files/reports | tr -d "\r" | sort | tail -n 1)
    [ -n "$name" ] || fail "no saved report"
    adb exec-out run-as $package cat "files/reports/$name" > /out/app-report.txt
    [ -n "$idle" ] || fail "the attempt did not finish"

    for line in attempt_complete=true stop_after=projection transport=lab-socket:openauto-lab \
        last_completed=projecting; do
        grep -qx "$line" /out/app-report.txt || fail "app report lacks $line"
    done
    # P6.1: the source own account of the drive, beside the receiver own picture-side
    # check. Both are needed: the receiver can see that the pane moved but not why, and
    # these counters can say the camera moved but not that anything was drawn with it.
    if [ "$SCRIPT" = track ]; then
        value() { sed -n "s/.* $1=\([0-9][0-9]*\).*/\1/p" /out/app-report.txt | head -n 1; }
        events=$(value dashboard_vehicle_events)
        moved=$(value moved)
        drawn=$(value drawn)
        grep -q "dashboard_position_source=vehicle" /out/app-report.txt \
            || fail "the map did not follow the vehicle position"
        [ "${events:-0}" -ge 50 ] || fail "only ${events:-0} vehicle events arrived"
        [ "${moved:-0}" -ge 20 ] || fail "the map camera moved only ${moved:-0} times"
        [ "${drawn:-0}" -ge 20 ] || fail "the map rasterised only ${drawn:-0} frames"
        log "track: vehicle events=$events camera moves=$moved rasterised=$drawn"
    fi
    # P7.6: the source own account of the switches, beside the receiver own picture of
    # them. Three flips, three switches (the first theme, applied at start, is not one),
    # none cut short by the hold limit, the last one to the night theme, and neither theme
    # replaced by the stock fallback because it would not parse.
    if [ "$SCRIPT" = daynight ]; then
        value() { sed -n "s/.*$1=\([0-9-][0-9]*\).*/\1/p" /out/app-report.txt | head -n 1; }
        switches=$(value map_theme_switches)
        timeouts=$(value map_theme_hold_timeouts)
        [ "${switches:-0}" -ge 3 ] || fail "the map switched theme only ${switches:-0} times"
        [ "${timeouts:-1}" = 0 ] || fail "${timeouts:-?} theme switches gave up waiting for their tiles"
        grep -q "map_engine=.* theme=oaa-dark " /out/app-report.txt \
            || fail "the map did not end on the night theme: $(grep "map_engine=" /out/app-report.txt)"
        ! grep -q "vtm-fallback" /out/app-report.txt || fail "a map theme fell back to the stock one"
        grep "^map_theme_" /out/app-report.txt | while read -r line; do log "$line"; done
    fi
    # P7.2, P7.3: the app own account of the presses the receiver made, and the two
    # choices saved where the next session and the settings screen will read them.
    if [ "$SCRIPT" = controls ]; then
        for want in "dashboard_zoom_presses=3 " "zoom=17.0 " "following=true " \
            "dashboard_pause_presses=1 " "dashboard_notifications_paused=true " \
            "dashboard_day_night=day dashboard_night=false dashboard_head_unit_night=true " \
            "dashboard_day_night_presses=1" "notifications_paused=true"; do
            grep -q -- "$want" /out/app-report.txt || fail "app report lacks $want"
        done
        adb exec-out run-as $package cat shared_prefs/settings.xml > /out/app-settings.xml 2>/dev/null \
            || fail "no saved settings file"
        grep -q "name=\"day_night\">day<" /out/app-settings.xml || fail "day/night was not saved as day"
        grep -q "name=\"notifications_paused\" value=\"true\"" /out/app-settings.xml \
            || fail "the pause was not saved"
        grep -q "name=\"last_resolved_night\" value=\"false\"" /out/app-settings.xml \
            || fail "the palette in use was not remembered as day"
        grep -E "^dashboard_(day_night|marker)|dashboard_zoom_presses|dashboard_map_view" /out/app-report.txt \
            | while read -r line; do log "$line"; done
    fi
    # P6.2: this source own account of the cluster, beside the receiver own decode of
    # it. The two answer different questions - whether the channel was driven, and
    # whether a second reader could make sense of what arrived - and neither substitutes
    # for an operator looking at a vehicle cluster, which is what G9 asks for. (No
    # apostrophes here: this whole block is a single-quoted argument to sh -c.)
    if [ "$NAVIGATION" = 1 ]; then
        # The channel is opened in every case; what happens after it depends on whether
        # the head unit offered a cluster and whether this source was told to care.
        grep -q "navigation_open=true" /out/app-report.txt \
            || fail "the navigation channel was never opened"
        if [ "$NAVIGATION_CLUSTER" = none ] && [ "$NAVIGATION_FORCE" != 1 ]; then
            # The P6 plan section 3 rule, end to end through the app: wait for the
            # cluster rather than assuming it, and send nothing at all to one that never
            # announced itself.
            grep -q "navigation_cluster_starts=0" /out/app-report.txt \
                || fail "a cluster was announced by a receiver told not to"
            grep -q "navigation_cluster_timeout=true" /out/app-report.txt \
                || fail "the silent cluster was not recorded as a timeout"
            grep -q "navigation_states_sent=- " /out/app-report.txt \
                || fail "a navigation state went to a cluster that never announced itself"
            grep -q "navigation_notifications_sent=0 navigation_distances_sent=0" /out/app-report.txt \
                || fail "maneuvers went to a cluster that never announced itself"
            log "navigation: nothing was sent to a cluster that never announced itself"
        elif [ "$NAVIGATION_FORCE" = 1 ]; then
            # The point of the switch: it went out without the head unit go-ahead, and
            # the report says so rather than looking like an ordinary session.
            grep -q "navigation_states_sent=NAV_STATE_ACTIVE" /out/app-report.txt \
                || fail "the cluster was never told a navigation state"
            grep -q "navigation_forced=true" /out/app-report.txt \
                || fail "the forced sequence was not recorded as forced"
            log "navigation: the cluster channel was driven without a go-ahead, and says so"
        else
            grep -q "navigation_states_sent=NAV_STATE_ACTIVE" /out/app-report.txt \
                || fail "the cluster was never told a navigation state"
            grep -q "navigation_focus_type=2 " /out/app-report.txt \
                || fail "navigation focus was never granted"
            log "navigation: the cluster channel was opened, focused and driven"
        fi
    fi
    # P6.5: the app own account of the drive, beside the receiver own decode of what the
    # cluster was told. The app can say it left the route and re-routed; only the receiver
    # can say the cluster heard it.
    if [ "$SCRIPT" = guidance ] || [ "$SCRIPT" = voice ]; then
        grep -q "navigation_source=route " /out/app-report.txt || fail "the cluster was not driven by the route"
        grep -q "NAV_STATE_REROUTING" /out/app-report.txt || fail "the cluster was never told REROUTING"
        grep -Eq "navigation_arrivals=[1-9]" /out/app-report.txt || fail "the cluster was never told the drive arrived"
        grep -Eq "guidance_reroutes=[1-9][0-9]* guidance_reroutes_done=[1-9]" /out/app-report.txt \
            || fail "no re-route was asked for and computed: $(grep "^guidance_reroutes" /out/app-report.txt)"
        grep -Eq "guidance_arrivals=1 " /out/app-report.txt || fail "guidance did not arrive exactly once"
        grep -q "guidance_fix_source=vehicle " /out/app-report.txt || fail "guidance did not follow the vehicle"
        grep -Eq "guidance_joined=[1-9]" /out/app-report.txt || fail "guidance never joined a route"
        grep -q "routing_origin=reroute " /out/app-report.txt || fail "the last route was not a re-route"
        for marker in Guidemarker 42.4495 71.231; do
            ! grep -q "$marker" /out/app-report.txt || fail "the report carries $marker"
        done
        grep -E "^guidance_|^navigation_(source|states|last)" /out/app-report.txt | while read -r line; do log "$line"; done
        grep -E "route (computed|failed|requested)|left the route|back on the route|arrived" /out/app-report.txt \
            | while read -r line; do log "$line"; done
    fi
    # P7.4, P7.5: the app own account of the End route press the receiver made, and of the
    # Resume press after it. The route was ended by End route (not by arrival, and not from
    # the phone), and no destination, label or app name reached the report.
    if [ "$SCRIPT" = endroute ]; then
        # Two trips and two presses since P7.4a: the second route is the one that shows the
        # cluster is asked for again after being handed back.
        for want in "dashboard_trips=2 " "dashboard_end_route_presses=2" "dashboard_trip=false " \
            "guidance_state=idle " "guidance_arrivals=0 " "dashboard end route pressed" "route cleared" \
            "dashboard_resume_presses=1 " "media_resume_presses=1" "media_resume_recent=1 " \
            "navigation_focus_requests=2 " "navigation_focus_denials=0 " "navigation_focus_handbacks=2 "; do
            grep -q -- "$want" /out/app-report.txt || fail "app report lacks $want"
        done
        grep -q "NAV_STATE_INACTIVE" /out/app-report.txt || fail "the cluster was never told INACTIVE"
        for marker in Guidemarker 42.4495 71.231; do
            ! grep -q "$marker" /out/app-report.txt || fail "the report carries $marker"
        done
        grep -E "^dashboard_(trip|media_idle)|^guidance_state|^navigation_(states|focus)|^media_(players|resume)" /out/app-report.txt \
            | while read -r line; do log "$line"; done
        grep -E "end route|route cleared|route computed" /out/app-report.txt | while read -r line; do log "$line"; done
    fi
    # P7.8, P7.9: the app own account of what the receiver pressed on the flex tile, the call
    # card and the keypad, and of the call itself; then that no sender, text, app name,
    # caller, number or keypad digit reached the report.
    if [ "$SCRIPT" = calls ]; then
        for want in "dashboard_notification_taps=1 " "notifications_read_taps=1 " "dashboard_pause_presses=2 " \
            "notification read aloud" "call ringing ($CALL_ROUTE)" "dashboard call answer pressed" \
            "dashboard call hang up pressed" "dashboard_answer_presses=1 " "dashboard_hang_up_presses=1 " \
            "call_answered=1 " "call_hung_up=1 " "call_route=$CALL_ROUTE " "dashboard_notification_rows_max=2 "; do
            grep -q -- "$want" /out/app-report.txt || fail "app report lacks $want"
        done
        grep -Eq "notifications_removed=[1-9]" /out/app-report.txt || fail "the removed notification was not seen to go"
        grep -Eq "notifications_rereads=([2-9]|[1-9][0-9])" /out/app-report.txt || fail "un-pausing did not re-read the shade"
        grep -Eq "dashboard_calls=[1-9]" /out/app-report.txt || fail "the dashboard never drew a call"
        if [ "$CALL_ROUTE" = telecom ]; then
            for want in "dashboard_mute_presses=1 " "call_mute_toggles=1 " "dashboard_keypad_opens=1 " \
                "dashboard_keypad_closes=1 " "call_keypad_presses=3 "; do
                grep -q -- "$want" /out/app-report.txt || fail "app report lacks $want"
            done
            grep -Eq "dashboard_keypad_presses=3($| )" /out/app-report.txt || fail "app report lacks three keypad presses"
            grep -Eq "call_companion_bindings=[1-9]" /out/app-report.txt || fail "Telecom never bound the in-call service"
        else
            grep -Eq "call_fallback_calls=[1-9]" /out/app-report.txt || fail "the call notification was never read"
            timeout 20 adb logcat -d -s OaaLabPlayer:I > /out/lab-player-log.txt 2>&1 || true
            for want in "call ring" "call answer" "call hang_up"; do
                grep -q "$want" /out/lab-player-log.txt || fail "the lab player never logged $want"
            done
        fi
        for marker in Sendermarker Bodymarker Labcaller "Lab Player" 5551234 555-1234 "12#"; do
            ! grep -q -- "$marker" /out/app-report.txt || fail "the report carries $marker"
        done
        grep -E "^dashboard_(flex|calls)|^notifications_|^call_" /out/app-report.txt | while read -r line; do log "$line"; done
        grep -E "call (ringing|active|ended|none)|dashboard (call|notification)|notification read aloud" /out/app-report.txt | while read -r line; do log "$line"; done
    fi
    # P8.1: what the three taps did, by the report alone. With an engine: read, stopped by
    # the second tap, read again; the grant given back as often as it was asked for. With
    # none: all three counted as no_engine and nothing asked of the audio system. Either
    # way the lab override was in force and nothing said, whose, or which app is there.
    if [ "$SCRIPT" = speech ]; then
        for want in "dashboard_notification_taps=3 " "notifications_read_taps=3 " "speech_gate=lab" \
            "speech_media=pause " "speech_read_aloud=true " "speech_focus_held=false"; do
            grep -q -- "$want" /out/app-report.txt || fail "app report lacks $want"
        done
        if grep -q "speech_ready=true" /out/app-report.txt; then
            for want in "notifications_read_aloud=2 " "notifications_read_stopped_by_tap=1 " \
                "notifications_read_full_text=2 " "notifications_read_truncated=2 " "notifications_read_skipped_no_engine=0 " \
                "notification read aloud stopped"; do
                grep -q -- "$want" /out/app-report.txt || fail "app report lacks $want"
            done
            requests=$(sed -n "s/.*speech_focus_requests=\([0-9]*\).*/\1/p" /out/app-report.txt | tail -n 1)
            abandons=$(sed -n "s/.*speech_focus_abandons=\([0-9]*\).*/\1/p" /out/app-report.txt | tail -n 1)
            [ -n "$requests" ] && [ "$requests" -ge 1 ] && [ "$requests" = "$abandons" ] \
                || fail "focus asked for $requests times and given back $abandons"
            grep -Eq "notifications_read_(completed|session_end)=1 " /out/app-report.txt \
                || fail "the last read neither completed nor was ended by the session"
            if [ "$SPEECH_ENGINE" = lab ]; then
                # What the engine was handed, by length alone: sender, a cut text and
                # "Message truncated.", so a little over 400 characters and never the 831.
                timeout 20 adb logcat -d -s OaaLabPlayer:I > /out/lab-player-log.txt 2>&1 || true
                spoken=$(sed -n "s/.*speech \([0-9]*\) characters.*/\1/p" /out/lab-player-log.txt | tr "\n" " ")
                log "the stand-in engine was handed utterances of $spoken characters"
                for length in $spoken; do
                    [ "$length" -le 460 ] || fail "an utterance of $length characters: the read was not cut"
                done
                [ "$(echo $spoken | wc -w)" -ge 2 ] || fail "the stand-in engine was handed $spoken"
            fi
            log "speech: an engine read, was stopped by a tap, and read again"
        else
            [ "$SPEECH_ENGINE" = none ] || fail "the $SPEECH_ENGINE engine never became ready"
            for want in "notifications_read_aloud=0 " "notifications_read_skipped_no_engine=3 " \
                "speech_focus_requests=0 " "notification read aloud skipped (no_engine)"; do
                grep -q -- "$want" /out/app-report.txt || fail "app report lacks $want"
            done
            log "speech: no engine; every tap counted as skipped_no_engine"
        fi
        for marker in Sendermarker Bodymarker "Lab Player"; do
            ! grep -q -- "$marker" /out/app-report.txt || fail "the report carries $marker"
        done
        # Never the package of the app that posted; the engine package is speech_engine, by design.
        ! grep -v "^speech_engine=" /out/app-report.txt | grep -q labplayer || fail "the report carries labplayer"
        grep -E "^speech_|^notifications_read|^voice_prompts|^dashboard_notification" /out/app-report.txt | while read -r line; do log "$line"; done
        grep -E "notification read aloud|speech engine|lab speech" /out/app-report.txt | while read -r line; do log "$line"; done

        # P8.3: the sample during the projection, and the switch flipped from the phone.
        # Counted as a sample, never as a prompt; the words never in the report.
        for want in "phone voice guidance off" "phone voice guidance on" "voice_prompts_toggle=on " \
            "voice_prompts_requests=0 " "voice_prompts_started=0 " "voice_samples_requests=1 "; do
            grep -q -- "$want" /out/app-report.txt || fail "app report lacks $want"
        done
        if grep -q "speech_ready=true" /out/app-report.txt; then
            for want in "voice sample spoken" "voice_samples_started=1 " "voice_samples_completed=1 " \
                "voice_samples_skipped_no_bluetooth=0 " "notifications_read_interrupted_by_guidance=0 "; do
                grep -q -- "$want" /out/app-report.txt || fail "app report lacks $want"
            done
        else
            grep -q "voice_samples_skipped_no_engine=1 " /out/app-report.txt || fail "app report lacks voice_samples_skipped_no_engine=1"
        fi
        for marker in "Main Street" "500 feet" "turn right"; do
            ! grep -q -- "$marker" /out/app-report.txt || fail "the report carries $marker"
        done
        grep -E "^voice_samples|phone voice guidance|voice sample" /out/app-report.txt | while read -r line; do log "$line"; done

        # P8.3: Play a sample with no projection. The engine is bound for the sample alone
        # and released after it; with the stand-in, how many 40-character utterances it was
        # handed shows the sample reached it. Then the lab Bluetooth override is withdrawn,
        # and the phone must say why nothing was spoken.
        voice_card || fail "no Voice guidance card after the session"
        scroll_to "Play a sample" || fail "no Play a sample button after the session"
        handed() { timeout 20 adb logcat -d -s OaaLabPlayer:I 2>/dev/null | grep -c "speech 40 characters" || true; }
        before=$(handed)
        tap_texti "Play a sample" || fail "could not press Play a sample after the session"
        if [ "$SPEECH_ENGINE" = none ]; then
            sleep 3
            on_screen "no text-to-speech engine" || fail "the phone did not say there is no engine"
            shot voice-sample-idle
            log "sample with no projection and no engine: the phone says so"
        else
            spoken=
            for _ in $(seq 20); do
                if [ "$SPEECH_ENGINE" = lab ] && [ "$(handed)" -gt "$before" ]; then spoken=1; break; fi
                [ "$SPEECH_ENGINE" = lab ] || { sleep 8; spoken=1; break; }
                sleep 1
            done
            [ -n "$spoken" ] || fail "the engine was never handed the sample with no projection"
            sleep 3
            on_screen "Spoken (lab Bluetooth override" || fail "the phone did not say the idle sample was spoken"
            shot voice-sample-idle
            # Released after it: with no projection nothing else holds the engine, so the
            # stand-in service is gone once the sample has ended.
            if [ "$SPEECH_ENGINE" = lab ]; then
                released=
                for _ in $(seq 10); do
                    if ! adb shell dumpsys activity services $labplayer | grep -q LabSpeechEngine; then released=1; break; fi
                    sleep 1
                done
                [ -n "$released" ] || fail "the engine bound for the sample was never released"
            fi
            log "sample with no projection: bound, spoken, released ($before then $(handed) samples handed to the engine)"
            adb shell am start -n $package/.ConnectionActivity --ez lab_speech_bluetooth false > /dev/null 2>&1 \
                || fail "lab intent to restore the Bluetooth gate"
            sleep 2
            tap_texti "Play a sample" || fail "could not press Play a sample without the override"
            reason=
            for _ in $(seq 15); do
                if on_screen "no Bluetooth audio connection"; then reason=1; break; fi
                sleep 1
            done
            [ -n "$reason" ] || fail "the phone did not say there is no Bluetooth audio"
            shot voice-sample-no-bluetooth
            log "sample with no Bluetooth: not spoken, and the phone says why"
        fi
    fi
    # P8.2: the voice script. The toggle pressed twice and on at the end; prompts spoken
    # through the lab engine at prepare and at the turn, the re-route and the arrival
    # announced once each, nothing refused for want of an engine or Bluetooth, and no
    # instruction, street or destination anywhere in the report. The per-stage counts are
    # logged so a run can be compared with the route; the bounds are what any pass of this
    # drive must reach, not an expected count.
    if [ "$SCRIPT" = voice ]; then
        for want in "dashboard_voice_toggle_presses=2 " "dashboard_voice_guidance=on" "voice_prompts_toggle=on " \
            "speech_gate=lab" "speech_ready=true" "voice_prompts_skipped_no_engine=0 " "voice_prompts_skipped_no_bluetooth=0 " \
            "voice_prompts_spoken_arrival=1 " "dashboard voice guidance off" "dashboard voice guidance on" \
            "voice prompt (prepare)" "voice prompt (at_turn)" "voice prompt (reroute)" "voice prompt (arrival)" \
            "speech_focus_held=false"; do
            grep -q -- "$want" /out/app-report.txt || fail "app report lacks $want"
        done
        count() { sed -n "s/.*voice_prompts_$1=\([0-9]*\).*/\1/p" /out/app-report.txt | tail -n 1; }
        [ "$(count spoken_prepare)" -ge 2 ] || fail "only $(count spoken_prepare) prepare prompts"
        [ "$(count spoken_at_turn)" -ge 2 ] || fail "only $(count spoken_at_turn) at-the-turn prompts"
        reroutes=$(sed -n "s/.*guidance_reroutes=\([0-9]*\) .*/\1/p" /out/app-report.txt | tail -n 1)
        [ "$(count spoken_reroute)" -ge 1 ] && [ "$(count spoken_reroute)" -le "${reroutes:-0}" ] \
            || fail "$(count spoken_reroute) re-route announcements for ${reroutes:-0} re-routes"
        [ "$(count started)" -le "$(count requests)" ] || fail "more prompts started than asked for"
        requests=$(sed -n "s/.*speech_focus_requests=\([0-9]*\).*/\1/p" /out/app-report.txt | tail -n 1)
        abandons=$(sed -n "s/.*speech_focus_abandons=\([0-9]*\).*/\1/p" /out/app-report.txt | tail -n 1)
        [ -n "$requests" ] && [ "$requests" -ge 1 ] && [ "$requests" = "$abandons" ] \
            || fail "focus asked for $requests times and given back $abandons"
        # Never what was said: no instruction, no distance in words, no road.
        for marker in Guidemarker 42.4495 71.231 "Turn " "turn left" "turn right" " onto " " feet" " mile" "Rerouting" \
            "You have arrived" "Street" "Road" "Avenue"; do
            ! grep -q -- "$marker" /out/app-report.txt || fail "the report carries $marker"
        done
        if [ "$SPEECH_ENGINE" = lab ]; then
            timeout 20 adb logcat -d -s OaaLabPlayer:I > /out/lab-player-log.txt 2>&1 || true
            log "the stand-in engine was handed $(grep -c "speech [0-9]* characters" /out/lab-player-log.txt || true) utterances"
        fi
        grep -E "^voice_prompts|^speech_|^dashboard_(trip|voice)" /out/app-report.txt | while read -r line; do log "$line"; done
        grep -E "voice (prompt|guidance)|speech engine|lab speech" /out/app-report.txt | while read -r line; do log "$line"; done
    fi
    # P6.3: the route came back, on the routing thread rather than the scene thread or the
    # main thread, and nothing in the report says where it went.
    if [ "$DESTINATION" = 1 ]; then
        grep -q "routing_requests=2 routing_origin=send routing_start=vehicle " /out/app-report.txt \
            || fail "the shares did not route from the vehicle: $(grep "^routing_requests" /out/app-report.txt)"
        grep -q "routing_destinations_refused=1 routing_last_refusal=address-only" /out/app-report.txt \
            || fail "the address-only share was not refused: $(grep "routing_destination" /out/app-report.txt)"
        grep -q "^routing_result=ok routing_thread=aap-route " /out/app-report.txt \
            || fail "no route came back from the routing thread: $(grep "^routing_" /out/app-report.txt | tr "\n" " ")"
        grep -Eq "map_engine=.* route_points=[1-9][0-9]* route_changes=[2-9]" /out/app-report.txt \
            || fail "the map engine never drew both routes: $(grep "map_engine=" /out/app-report.txt)"
        # Spec section 10 and the P6 plan section 9.6: no label and no coordinate of any
        # destination in the saved report, whose event log is part of it.
        for marker in Labmarker Viewmarker Sendmarker 42.3551 71.0656 42.4495 71.231; do
            ! grep -q "$marker" /out/app-report.txt || fail "the report carries $marker"
        done
        grep "^routing_\|map_engine=" /out/app-report.txt | while read -r line; do log "$line"; done
        grep -E "destination|route (computed|failed|requested)" /out/app-report.txt | while read -r line; do log "$line"; done
    elif [ -n "$ROUTE" ] && [ "$SCRIPT" != guidance ] && [ "$SCRIPT" != endroute ] && [ "$SCRIPT" != voice ]; then
        grep -q "routing_origin=lab" /out/app-report.txt || fail "the lab route was never requested"
        grep -q "^routing_result=ok routing_thread=aap-route " /out/app-report.txt \
            || fail "no route came back from the routing thread: $(grep "^routing_" /out/app-report.txt | tr "\n" " ")"
        grep "^routing_" /out/app-report.txt | while read -r line; do log "$line"; done
        grep -E "route (computed|failed)|stage=IDLE" /out/app-report.txt | while read -r line; do log "$line"; done
    fi
    # Every installed map is drawn: the engine line names both files, and no map failed.
    if [ "${MAPS_EXPECTED:-1}" = 2 ]; then
        grep -Eq "data=(region\.map,extra\.map|extra\.map,region\.map) .*error=-" /out/app-report.txt \
            || fail "the map engine did not open both maps: $(grep "map_engine=" /out/app-report.txt)"
        log "maps: both files drawn as one"
    fi
    grep -q "^projection_encoder=" /out/app-report.txt || fail "app report lacks the encoder line"
    if [ "$SOFTWARE_ENCODER" = true ]; then
        grep -qx "software_encoder=true" /out/app-report.txt || fail "software encoder not requested"
        grep -q "^projection_encoder=.*hardware=false software=true" /out/app-report.txt \
            || fail "software encoder was not selected"
    fi
    ! grep -q "PRIVATE KEY" /out/app-report.txt || fail "key material in the report"

    timeout 60 adb logcat -d > /out/logcat.txt
    if grep -E "JNI DETECTED ERROR|UnsatisfiedLinkError|Process: $package|Fatal signal .*\\($package|>>> $package <<<" /out/logcat.txt; then
        fail "app crash or JNI error in logcat"
    fi
    log "pass"
  ' || status=$?

# The receiver exits after its script; collect its result.
podman wait "$pod-receiver" > "$out/receiver-exit.txt" 2>/dev/null || true
podman rm -f "$pod-receiver" > /dev/null 2>&1 || true
receiver_status=$(cat "$out/receiver-exit.txt" 2>/dev/null || echo "unknown")
echo "emulator-lab: receiver exit=$receiver_status app exit=$status"
echo "emulator-lab: results in $out"
grep -E "receiver_(video_decoded_frames|checks|check_failed|tap_result|track|guidance|daynight|control|endroute|calls|speech|voice)" "$out/receiver.txt" 2>/dev/null || true
# P6.4: the route line in the picture the head unit decoded, not only in the app's own
# counters. Route colour (MapEngine.ROUTE_COLOR, 34,199,240) is in no part of the owned
# theme or the dashboard chrome, so the first frames, before any route, must have almost
# none of it and the last ones plenty.
if [ "$DESTINATION" = 1 ] && [ "$status" = 0 ]; then
    python3 tools/route-line-pixels.py "$out/frames" || status=1
fi
# P8.1: the row stays lit while it is read (plan section 8), in the picture the head unit
# decoded: 2.5 s after the first tap, past its 800 ms flash, it is still lit when an engine
# is reading it and dark when there is none; 2.5 s after the second tap, stopped, it is dark.
if [ "$SCRIPT" = speech ] && [ "$status" = 0 ]; then
    ready=$(grep -q "speech_ready=true" "$out/app-report.txt" && echo 1 || echo 0)
    read_lit=$(sed -n "s/^receiver_speech=lit after-read //p" "$out/receiver.txt")
    stop_lit=$(sed -n "s/^receiver_speech=lit after-stop //p" "$out/receiver.txt")
    python3 -c "import sys
ready, read, stop = sys.argv[1] == \"1\", float(sys.argv[2]), float(sys.argv[3])
ok = (read >= 0.05 and stop < 0.01) if ready else read < 0.01
print(\"emulator-lab: row lit %.3f while read, %.3f after the stop (engine ready: %s): %s\" % (read, stop, ready, \"ok\" if ok else \"FAIL\"))
sys.exit(0 if ok else 1)" "$ready" "${read_lit:-0}" "${stop_lit:-0}" || status=1
fi
[ "$status" = 0 ] && [ "$receiver_status" = 0 ]
