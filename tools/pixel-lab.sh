#!/bin/sh
# Optional lab projection run of the app on the physical phone: the installed debug APK
# streams its scene to the controlled lab receiver over adb, which decodes the H.264 with
# libavcodec, taps each scene button, and checks that the decoded picture changes. The
# receiver listens on the host's loopback and the app reaches it through an abstract local
# socket forwarded by adbd (`adb reverse`), so the APK still has no INTERNET permission and
# no network transport. A lab-socket attempt never pins a head-unit certificate, so it
# cannot disturb the Ford pin.
#
# This is device evidence for the app's encoder, EGL renderer, media submission, and input
# mapping on real hardware and real codecs — it caught the transport duplicate-send defect
# (0.3.4) that the emulator lab never hit. It is not vehicle evidence: no head unit is
# involved, so it cannot pass G4 or G5. What it is for is the last check before a drive.
#
# The Ford SYNC 4.0 geometry is the default, so a run reproduces the vehicle's touch
# arithmetic: LAB_MARGINS (default 74x0), LAB_TOUCH (default 1920x882), LAB_TOUCH_SPACE
# (default content), LAB_SCRIPT (taps, focus, stall, none), LAB_PROJECTION_MS (default
# 30000). Results, including the report pulled off the phone, go to
# $OPENAUTO_CACHE/pixel-lab/<UTC time>/.
#
# Needs a device on adb with the debug APK already installed (tools/android-build.sh, then
# `adb install -r --user 0`) and localhost/openauto-lab-receiver:p4 with
# `tools/lab-receiver.sh build`.
#
#   tools/pixel-lab.sh
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
RECEIVER_IMAGE=localhost/openauto-lab-receiver:p4
PACKAGE=com.jw6ventures.headlink
SOCKET=openauto-lab
PORT=5288
work=$CACHE_ROOT/receiver
out=$CACHE_ROOT/pixel-lab/$(date -u +%Y%m%dT%H%M%SZ)
projection_ms=${LAB_PROJECTION_MS:-30000}

[ -x "$work/build/lab-receiver" ] || { echo "run tools/lab-receiver.sh build first" >&2; exit 1; }
adb get-state > /dev/null 2>&1 || { echo "pixel-lab: no device on adb" >&2; exit 1; }
# The phone has several Android users; everything here is user 0's (see the reports note).
adb shell pm list packages --user 0 2>/dev/null | grep -q "^package:$PACKAGE$" ||
    { echo "pixel-lab: $PACKAGE is not installed for user 0" >&2; exit 1; }
mkdir -p "$out"

name=pixel-lab-$$
cleanup() {
    podman rm -f "$name" > /dev/null 2>&1 || true
    adb reverse --remove "localabstract:$SOCKET" > /dev/null 2>&1 || true
}
trap cleanup EXIT

echo "pixel-lab: $(adb shell dumpsys package $PACKAGE | sed -n 's/ *versionName=/version /p' | head -1)"
before=$(adb shell run-as $PACKAGE ls files/reports 2> /dev/null | tr -d '\r' | sort | tail -1)

# The receiver, with freshly generated credentials, on the host's loopback. Not --rm: the
# run's exit status and its check counters have to outlive the container.
podman run -d --name "$name" --network host --security-opt label=disable --userns=keep-id \
    -v "$work:/receiver" -v "$out:/out" "$RECEIVER_IMAGE" sh -ec "
    cd /out
    openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 7 \
        -subj '/CN=HeadLink lab receiver' \
        -keyout receiver-key.pem -out receiver-cert.pem 2>/dev/null
    export OAA_LAB_CERT_PEM=/out/receiver-cert.pem OAA_LAB_KEY_PEM=/out/receiver-key.pem
    exec /receiver/build/lab-receiver --listen 127.0.0.1:$PORT --request-version 1.1 \
        --timeout-ms $((projection_ms + 60000)) --trace /out/trace.jsonl --projection \
        --script '${LAB_SCRIPT:-taps}' --margins '${LAB_MARGINS:-74x0}' \
        --touch '${LAB_TOUCH:-1920x882}' --touch-space '${LAB_TOUCH_SPACE:-content}' \
        --video-out /out/video.h264 > /out/receiver.out 2> /out/receiver.err
  " > /dev/null
for _ in $(seq 80); do grep -q "^listening=" "$out/receiver.out" 2> /dev/null && break; sleep 0.25; done
grep -q "^listening=" "$out/receiver.out" 2> /dev/null ||
    { echo "pixel-lab: receiver did not listen" >&2; cat "$out/receiver.err" >&2; exit 1; }

adb reverse "localabstract:$SOCKET" "tcp:$PORT" > /dev/null
# Two starts, deliberately. The lab intent is refused unless the controller is IDLE with its
# identity loaded, and an activity that is already foregrounded is only brought to the front
# — its extras are dropped. So: stop, start plain, let the identity load, then start again
# with the extras, which reaches onNewIntent (the activity is singleTop).
adb shell am force-stop --user 0 $PACKAGE
adb shell am start --user 0 -n $PACKAGE/.ConnectionActivity > /dev/null
sleep 8
started=$(adb shell am start --user 0 -n $PACKAGE/.ConnectionActivity \
    --es lab_socket $SOCKET --es lab_stop_after projection \
    --ez lab_software_encoder true --el lab_projection_ms "$projection_ms" 2>&1 | tr -d '\r')
case $started in
*"brought to the front"*) echo "pixel-lab: the extras were dropped; the app was already on top" >&2; exit 1 ;;
esac

# Wait for the receiver to finish its script rather than guessing at a sleep.
status=
for _ in $(seq $(((projection_ms + 90000) / 1000))); do
    state=$(podman inspect -f '{{.State.Status}}' "$name" 2> /dev/null || echo gone)
    [ "$state" = running ] || { status=$(podman inspect -f '{{.State.ExitCode}}' "$name" 2> /dev/null || echo 1); break; }
    sleep 1
done
[ -n "$status" ] || { echo "pixel-lab: receiver did not finish" >&2; status=1; }

echo "== receiver (exit $status)"
cat "$out/receiver.out"
[ -s "$out/receiver.err" ] && { echo "== receiver stderr"; cat "$out/receiver.err"; }
if [ -s "$out/video.h264" ]; then
    echo "== ffmpeg decode of the received stream"
    podman run --rm --network=none --security-opt label=disable -v "$out:/out:ro" \
        "$RECEIVER_IMAGE" sh -ec 'ffmpeg -v error -xerror -i /out/video.h264 -f null - 2>&1 | head -20'
    echo "ffmpeg_exit=$?"
fi

# The app's own report for the run, which is where the touch mapping shows up. The app
# rewrites it once the session reaches IDLE, a moment after the receiver exits, and the
# half-written copy has the scene but none of the session counters — so wait for a report
# with no placeholder `final_category=-` left in it rather than taking the first read.
latest=$(adb shell run-as $PACKAGE ls files/reports 2> /dev/null | tr -d '\r' | sort | tail -1)
if [ -n "$latest" ] && [ "$latest" != "$before" ]; then
    for _ in $(seq 20); do
        adb shell run-as $PACKAGE cat "files/reports/$latest" > "$out/$latest"
        grep -q '^final_category=-' "$out/$latest" || break
        sleep 1
    done
    grep -q '^final_category=-' "$out/$latest" &&
        echo "pixel-lab: the report was still being written; counters may be missing" >&2
    echo "== app report $latest"
    grep -E '^(version_name|projection_video|scene_taps|scene_calibration|input_touch_space|input_raw_range|input_last_touch|final_category)' "$out/$latest" || true
else
    echo "pixel-lab: no new report on the phone" >&2
    status=${status:-1}
fi

echo "results=$out"
exit "$status"
