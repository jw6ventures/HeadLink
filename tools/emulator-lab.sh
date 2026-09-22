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
#   tools/emulator-lab.sh [taps|focus|stall|audio|track|navigation|guidance|none]
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
# LAB_DRIVE_SPEED sets the cruise in m/s (default 18).
#
# LAB_MAP_EXTRA=<file.map> installs a second map beside LAB_MAP. The app draws every
# installed map as one, so this checks that the pane still renders and follows the
# vehicle with more than one file behind it, and that the report names both.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
EMULATOR_IMAGE=localhost/openauto-emulator:smoke
RECEIVER_IMAGE=localhost/openauto-lab-receiver:p4
SCRIPT=${1:-taps}
# The track script drives for about half a minute after the map has settled, so it gets
# a longer window than the scripts that finish as soon as their taps land.
if [ "$SCRIPT" = track ]; then DEFAULT_MS=90000; else DEFAULT_MS=40000; fi
# The guidance drive is several kilometres long and ends only when the app says it arrived.
if [ "$SCRIPT" = guidance ]; then DEFAULT_MS=600000; fi
PROJECTION_MS=${LAB_PROJECTION_MS:-$DEFAULT_MS}
# The app-owned audio path: on for --script audio, and available alongside the others so
# a run also covers audio interleaving with fragmenting video keyframes.
if [ -n "${LAB_AUDIO:-}" ] || [ "$SCRIPT" = audio ]; then AUDIO=1; else AUDIO=0; fi
# The app-driven cluster path (P6.2), on the same footing.
if [ -n "${LAB_NAVIGATION:-}" ] || [ "$SCRIPT" = navigation ] || [ "$SCRIPT" = guidance ]; then NAVIGATION=1; else NAVIGATION=0; fi
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
elif [ "$SCRIPT" = track ] || [ "$SCRIPT" = guidance ]; then
    echo "emulator-lab: the $SCRIPT script needs LAB_MAP=<region>.map covering 42.42,-71.21" >&2
    exit 1
fi
DRIVE_ARGS=
if [ "$SCRIPT" = guidance ]; then
    [ -n "${LAB_ROUTING:-}" ] || { echo "emulator-lab: the guidance script needs LAB_ROUTING" >&2; exit 1; }
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
# Only loopback, shared by the receiver and the emulator.
podman pod create --name "$pod" --network none --userns keep-id > /dev/null

# The receiver, with freshly generated credentials, waiting for the app.
podman run -d --pod "$pod" --name "$pod-receiver" --security-opt label=disable \
  -v "$work:/receiver" -v "$out:/out" "$RECEIVER_IMAGE" sh -ec '
    cd /out
    openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 7 \
        -subj "/CN=OpenAutoAndroid lab receiver" \
        -keyout receiver-key.pem -out receiver-cert.pem 2>/dev/null
    export OAA_LAB_CERT_PEM=/out/receiver-cert.pem OAA_LAB_KEY_PEM=/out/receiver-key.pem
    openssl x509 -in receiver-cert.pem -noout -fingerprint -sha256 \
        | sed "s/.*=//; s/://g" | tr "A-F" "a-f" > receiver-fingerprint.txt
    /receiver/build/lab-receiver --listen 127.0.0.1:5288 --request-version 1.1 --projection \
        --script '"$SCRIPT"' --timeout-ms '"$((PROJECTION_MS + 120000))"' --hold-ms 2000 --tolerance 24 \
        --video-config '"${LAB_VIDEO_CONFIG:-800x480}"' \
        '"$([ "$AUDIO" = 1 ] && echo '--audio --audio-out /out/audio.pcm')"' \
        '"$([ "$NAVIGATION" = 1 ] && echo "--navigation --navigation-cluster $NAVIGATION_CLUSTER --navigation-expect $NAVIGATION_EXPECT")"' \
        '"$([ -n "${LAB_SENSORS:-all}" ] && echo "--sensors ${LAB_SENSORS:-all}")"' \
        '"$([ -n "${LAB_TRACK:-}" ] && echo '--track')"' '"$DRIVE_ARGS"' \
        --trace /out/receiver-trace.jsonl --video-out /out/video.h264 > /out/receiver.txt 2> /out/receiver-err.txt
    status=$?
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
  -e MAPS_EXPECTED="$([ -n "${LAB_MAP_EXTRA:-}" ] && echo 2 || echo 1)" \
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
        "hw.gsmModem=no" "hw.gps=no" "hw.sensors.proximity=no" \
        > /cache/avd/smoke.avd/config.ini

    emulator -avd smoke -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
        -gpu swangle_indirect -feature -Vulkan -feature -ModemSimulator -accel on -no-metrics \
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
        --el lab_projection_ms "$PROJECTION_MS" > /out/lab-intent.txt 2>&1 || fail "lab intent"
    log "lab projection attempt started (${PROJECTION_MS} ms)"

    ui() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true; adb exec-out cat /sdcard/ui.xml 2>/dev/null; }
    projecting=
    for _ in $(seq 120); do
        if ui | grep -q "Stage: PROJECTING"; then projecting=1; break; fi
        sleep 1
    done
    [ -n "$projecting" ] || { adb exec-out screencap -p > /out/not-projecting.png; fail "the app never reached PROJECTING"; }
    log "app reached PROJECTING"
    adb exec-out screencap -p > /out/projecting.png
    # The route summary is below the fold of the connection screen, where uiautomator
    # cannot see it; the saved report is rewritten on every event and carries it.
    computed() {
        latest=$(adb shell run-as $package ls files/reports 2>/dev/null | tr -d "\r" | sort | tail -n 1)
        [ -n "$latest" ] || { echo 0; return; }
        adb exec-out run-as $package cat "files/reports/$latest" 2>/dev/null | grep -c "route computed in" || true
    }
    # Asked for only now, so the search runs while frames are being rendered and encoded.
    if [ "$SCRIPT" = guidance ]; then
        # P6.5: one destination, shared as OsmAnd shares it, while the vehicle is parked
        # at the start of the drive. The receiver pulls away when the cluster goes active.
        text="Location: geo:42.44950,-71.23100?z=16&q=42.44950,-71.23100(Guidemarker Green)
https://osmand.net/map?pin=42.44950,-71.23100#16/42.44950/-71.23100"
        adb shell am start -W -a android.intent.action.SEND -t text/plain -n $package/.DestinationActivity \
            --es android.intent.extra.TEXT "\"$text\"" > /out/lab-destination.txt 2>&1 || fail "SEND intent"
        routed=
        for _ in $(seq 180); do
            if [ "$(computed)" -ge 1 ]; then routed=1; break; fi
            sleep 1
        done
        [ -n "$routed" ] || fail "no route came back for the shared destination"
        log "route computed; the receiver drives once the cluster is active"
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

    # Wait for the attempt to end (the receiver stops the session after its script).
    idle=
    for _ in $(seq $((PROJECTION_MS / 1000 + 240))); do
        if ui | grep -q "Stage: IDLE"; then idle=1; break; fi
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
    if [ "$SCRIPT" = guidance ]; then
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
    elif [ -n "$ROUTE" ] && [ "$SCRIPT" != guidance ]; then
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
grep -E "receiver_(video_decoded_frames|checks|check_failed|tap_result|track|guidance)" "$out/receiver.txt" 2>/dev/null || true
# P6.4: the route line in the picture the head unit decoded, not only in the app's own
# counters. Route colour (MapEngine.ROUTE_COLOR, 34,199,240) is in no part of the owned
# theme or the dashboard chrome, so the first frames, before any route, must have almost
# none of it and the last ones plenty.
if [ "$DESTINATION" = 1 ] && [ "$status" = 0 ]; then
    python3 tools/route-line-pixels.py "$out/frames" || status=1
fi
[ "$status" = 0 ] && [ "$receiver_status" = 0 ]
