#!/bin/sh
# Controlled lab receiver: the OpenAuto Prodigy protocol library at the spec pin, with
# the lab credentials patch, driven by tools/lab-receiver/receiver.cpp. Sources, build
# output, generated credentials, and traces live under $OPENAUTO_CACHE/receiver, never in
# the workspace. TCP is a lab transport, not wireless Android Auto.
#
#   tools/lab-receiver.sh fetch         networked: pinned sources into the cache
#   tools/lab-receiver.sh build         offline: apply patch, build lab-receiver
#   tools/lab-receiver.sh demo [ARGS]   offline: receiver requesting $LAB_REQUEST_VERSION
#                                       (default 1.1) on loopback, then
#                                       target/release/source-harness connect ARGS
#
# demo settings: LAB_STOP_AFTER (version|auth|discovery|projection, default discovery),
# LAB_HOLD_MS (default 12000), LAB_PEER (pin: pin the receiver's generated certificate,
# the C1 lab trial, default; first-use: accept it for this session only; mismatch: pin
# a different fingerprint, a negative test).
#
# With LAB_STOP_AFTER=projection the receiver registers Prodigy's video, input, sensor,
# now-playing and (with LAB_AUDIO=1) PCM audio handlers, decodes the H.264 it receives
# with libavcodec, and runs
# LAB_SCRIPT (taps, the default: tap each scene button and check the decoded picture;
# focus: drop and restore video focus; stall: withhold acknowledgements; transport: press
# media buttons and check that both the now-playing channel and the decoded picture
# follow; audio: measure the received PCM against the tone the source says it is sending
# (P5.3); navigation: decode the scripted maneuver sequence on the instrument cluster
# channel and time it against the advertised floor (P6.2); none). LAB_MARGINS (default
# 0x0, Ford-like: 74x0), LAB_TOUCH (advertised touch size, default the content size),
# LAB_TOUCH_SPACE (space the receiver reports touches in: content, what Ford SYNC 4.0
# sends and the default; frame; or advertised), LAB_MEDIA_INFO=1 (open the
# now-playing channel without the transport script), and LAB_PROJECTION_MS
# (default 20000) shape the run. A nonzero exit from
# the receiver means a decode, input, or audio check failed.
#
# LAB_NAVIGATION=1 (or LAB_SCRIPT=navigation) advertises the instrument cluster channel
# on ChannelId::Navigation (9), announces a cluster, grants navigation focus, and decodes
# the scripted maneuver sequence the source sends (P6.2). The receiver's advertised
# minimum_interval_ms is 1500, slower than the source's own default, so its own clock
# checks that the head unit's floor is the one that binds.
# LAB_NAVIGATION_CLUSTER=none makes the receiver never announce a cluster, which is what
# Prodigy does unaided and may be what the vehicle does; the source then sends nothing
# unless LAB_NAVIGATION_FORCE=1.
#
# LAB_AUDIO=1 opens one PCM MEDIA audio channel and streams the lab tone on it; the
# received PCM is written to $OPENAUTO_CACHE/receiver/audio.pcm. LAB_UNDERFLOW_AFTER=N
# makes the receiver send 0x800B AUDIO_UNDERFLOW after N buffers, which no open-source
# head unit does on its own, so without it that path is never exercised.
#
# LAB_SENSORS names the sensor set (P5.2): restriction is driving status and night data
# through Prodigy's own handler; all advertises Ford's nine types, of which the receiver
# populates seven and accepts the other two in silence, so the source's
# sensor_populated/sensor_silent line can be checked against receiver_sensors_populated.
# LAB_SENSOR_LOCATION=1 additionally asks the source to report latitude and longitude;
# without it the harvest must show them redacted (spec §10).
# The source identity persists in $OPENAUTO_CACHE/receiver/source-identity across runs.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
RECEIVER_IMAGE=localhost/openauto-lab-receiver:p4
PRODIGY_COMMIT=3afc5dd8bbacb5ce68e109189b18b14b251fabef
# Gitlink recorded for libs/prodigy-oaa-protocol/proto in the Prodigy commit above.
OAA_PROTO_COMMIT=5ff4aa218dd33913237993f2968bf70e16dc464e
work=$CACHE_ROOT/receiver
mkdir -p "$work"

receiver_run() {
    podman run --rm --userns=keep-id --security-opt label=disable \
        -v "$PWD:/workspace" -w /workspace -v "$work:/receiver" "$@"
}

case ${1:-} in
fetch)
    fetch() { # dir url commit
        [ -d "$work/$1/.git" ] || git init -q "$work/$1"
        git -C "$work/$1" fetch -q --depth=1 "$2" "$3"
        git -C "$work/$1" checkout -q --detach "$3"
        [ "$(git -C "$work/$1" rev-parse HEAD)" = "$3" ] || { echo "unexpected $1 commit" >&2; exit 1; }
    }
    fetch openauto-prodigy https://github.com/mrmees/openauto-prodigy.git "$PRODIGY_COMMIT"
    [ "$(git -C "$work/openauto-prodigy" ls-tree HEAD libs/prodigy-oaa-protocol/proto | awk '{print $3}')" = "$OAA_PROTO_COMMIT" ] \
        || { echo "proto gitlink changed" >&2; exit 1; }
    fetch open-android-auto https://github.com/mrmees/open-android-auto.git "$OAA_PROTO_COMMIT"
    echo "lab-receiver: fetched prodigy $PRODIGY_COMMIT, proto $OAA_PROTO_COMMIT"
    ;;
build)
    [ "$(git -C "$work/openauto-prodigy" rev-parse HEAD)" = "$PRODIGY_COMMIT" ] || { echo "run fetch first" >&2; exit 1; }
    git -C "$work/openauto-prodigy" checkout -q -- .
    git -C "$work/openauto-prodigy" apply "$PWD/tools/lab-receiver/prodigy-lab-credentials.patch"
    rm -rf "$work/openauto-prodigy/libs/prodigy-oaa-protocol/proto"
    mkdir -p "$work/openauto-prodigy/libs/prodigy-oaa-protocol/proto"
    cp -R "$work/open-android-auto/oaa" "$work/open-android-auto/LICENSE" \
        "$work/openauto-prodigy/libs/prodigy-oaa-protocol/proto/"
    receiver_run --network=none "$RECEIVER_IMAGE" sh -ec '
        cmake -S tools/lab-receiver -B /receiver/build -G Ninja -DCMAKE_BUILD_TYPE=Release \
            -DPRODIGY_DIR=/receiver/openauto-prodigy > /receiver/cmake.log
        cmake --build /receiver/build --target lab-receiver > /receiver/build.log
        ls -l /receiver/build/lab-receiver
      '
    ;;
demo)
    shift
    [ -x target/release/source-harness ] || { echo "run tools/check.sh first" >&2; exit 1; }
    # The track script (P6.1) measures the app's own map pane; source-harness draws the
    # demo scene and has no map in it, so the check would fail on a scene that was never
    # asked to follow anything.
    [ "${LAB_SCRIPT:-taps}" = track ] && {
        echo "LAB_SCRIPT=track needs the app dashboard: tools/emulator-lab.sh track" >&2
        exit 1
    }
    # The guidance script (P6.5) needs a route the app computed; source-harness has none.
    [ "${LAB_SCRIPT:-taps}" = guidance ] && {
        echo "LAB_SCRIPT=guidance needs the app: tools/emulator-lab.sh guidance" >&2
        exit 1
    }
    receiver_run --network=none -e LAB_REQUEST_VERSION="${LAB_REQUEST_VERSION:-1.1}" \
        -e LAB_STOP_AFTER="${LAB_STOP_AFTER:-discovery}" -e LAB_HOLD_MS="${LAB_HOLD_MS:-12000}" \
        -e LAB_PEER="${LAB_PEER:-pin}" -e LAB_SCRIPT="${LAB_SCRIPT:-taps}" \
        -e LAB_MARGINS="${LAB_MARGINS:-0x0}" -e LAB_TOUCH="${LAB_TOUCH:-}" \
        -e LAB_TOUCH_SPACE="${LAB_TOUCH_SPACE:-content}" \
        -e LAB_SENSORS="${LAB_SENSORS:-}" -e LAB_MEDIA_INFO="${LAB_MEDIA_INFO:-}" \
        -e LAB_AUDIO="${LAB_AUDIO:-}" -e LAB_UNDERFLOW_AFTER="${LAB_UNDERFLOW_AFTER:-0}" \
        -e LAB_NAVIGATION="${LAB_NAVIGATION:-}" \
        -e LAB_NAVIGATION_CLUSTER="${LAB_NAVIGATION_CLUSTER:-start}" \
        -e LAB_NAVIGATION_FORCE="${LAB_NAVIGATION_FORCE:-}" \
        -e LAB_SENSOR_LOCATION="${LAB_SENSOR_LOCATION:-}" \
        -e LAB_PROJECTION_MS="${LAB_PROJECTION_MS:-20000}" \
        "$RECEIVER_IMAGE" sh -c '
        set -u
        cd /receiver
        mkdir -p credentials
        # Fresh generated receiver (TLS client) identity; never a copied key.
        openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 7 \
            -subj "/CN=OpenAutoAndroid lab receiver" \
            -keyout credentials/receiver-key.pem -out credentials/receiver-cert.pem 2>/dev/null
        export OAA_LAB_CERT_PEM=/receiver/credentials/receiver-cert.pem
        export OAA_LAB_KEY_PEM=/receiver/credentials/receiver-key.pem
        pin=$(openssl x509 -in credentials/receiver-cert.pem -noout -fingerprint -sha256 \
            | sed "s/.*=//; s/://g" | tr "A-F" "a-f")
        echo "receiver_certificate_sha256=$pin"
        set -- connect 127.0.0.1:5288 --stop-after "$LAB_STOP_AFTER" "$@"
        if [ "$LAB_STOP_AFTER" = projection ]; then
            set -- "$@" --projection-ms "$LAB_PROJECTION_MS"
            [ -n "$LAB_SENSORS" ] && set -- "$@" --sensors "$LAB_SENSORS"
            [ -n "$LAB_SENSOR_LOCATION" ] && set -- "$@" --sensor-location
            # The transport script needs the now-playing channel open on both sides. The
            # interval is shortened so the script does not spend its deadline waiting for
            # the default one-second rate limit.
            if [ -n "$LAB_MEDIA_INFO" ] || [ "$LAB_SCRIPT" = transport ]; then
                set -- "$@" --media-info --media-interval-ms 200
            fi
            if [ -n "$LAB_AUDIO" ] || [ "$LAB_SCRIPT" = audio ]; then
                set -- "$@" --audio
            fi
            if [ -n "$LAB_NAVIGATION" ] || [ "$LAB_SCRIPT" = navigation ]; then
                set -- "$@" --navigation
                [ -n "$LAB_NAVIGATION_FORCE" ] && set -- "$@" --navigation-force
            fi
        elif [ "$LAB_STOP_AFTER" != version ]; then
            set -- "$@" --hold-ms "$LAB_HOLD_MS"
        fi
        if [ "$LAB_STOP_AFTER" != version ]; then
            set -- "$@" --identity-dir /receiver/source-identity
            case $LAB_PEER in
            pin) set -- "$@" --peer-pin "$pin" ;;
            first-use) set -- "$@" --trust-first-use ;;
            mismatch) set -- "$@" --peer-pin 0000000000000000000000000000000000000000000000000000000000000000 ;;
            *) echo "unknown LAB_PEER $LAB_PEER" >&2; exit 1 ;;
            esac
        fi
        rm -f trace.jsonl video.h264 audio.pcm
        receiver_args="--listen 127.0.0.1:5288 --request-version $LAB_REQUEST_VERSION --timeout-ms 90000 --trace /receiver/trace.jsonl"
        if [ "$LAB_STOP_AFTER" = projection ]; then
            receiver_args="$receiver_args --projection --script $LAB_SCRIPT --margins $LAB_MARGINS --touch-space $LAB_TOUCH_SPACE --video-out /receiver/video.h264"
            [ -n "$LAB_TOUCH" ] && receiver_args="$receiver_args --touch $LAB_TOUCH"
            # The receiver advertises the matching set: "all" is the nine types Ford
            # advertises, two of them accepted and then silent, which is the case P5.2
            # has to tell apart from populated. (No apostrophes here: this whole block is
            # a single-quoted argument to sh -c.)
            case ${LAB_SENSORS:-} in
            "") ;;
            all) receiver_args="$receiver_args --sensors all" ;;
            *) receiver_args="$receiver_args --sensors restriction" ;;
            esac
            [ -n "$LAB_MEDIA_INFO" ] && receiver_args="$receiver_args --media-info"
            if [ -n "$LAB_AUDIO" ] || [ "$LAB_SCRIPT" = audio ]; then
                receiver_args="$receiver_args --audio --audio-out /receiver/audio.pcm"
                receiver_args="$receiver_args --underflow-after $LAB_UNDERFLOW_AFTER"
            fi
            if [ -n "$LAB_NAVIGATION" ] || [ "$LAB_SCRIPT" = navigation ]; then
                receiver_args="$receiver_args --navigation"
                receiver_args="$receiver_args --navigation-cluster $LAB_NAVIGATION_CLUSTER"
                # Forcing is the source being told to send regardless, so the receiver
                # expects a sequence even with no cluster announced.
                [ -n "$LAB_NAVIGATION_FORCE" ] && receiver_args="$receiver_args --navigation-expect sequence"
            fi
        fi
        # shellcheck disable=SC2086 # deliberate word splitting of the built argument list
        build/lab-receiver $receiver_args > receiver.out 2> receiver.err &
        receiver=$!
        for _ in $(seq 50); do grep -q "^listening=" receiver.out 2>/dev/null && break; sleep 0.1; done
        /workspace/target/release/source-harness "$@" > harness.out 2>&1
        harness=$?
        wait $receiver
        receiver_status=$?
        echo "== receiver (exit $receiver_status)"; cat receiver.out
        if [ "$LAB_STOP_AFTER" = projection ] && [ -s video.h264 ]; then
            echo "== ffmpeg decode of the received stream"
            ffmpeg -v error -xerror -i video.h264 -f null - > ffmpeg.out 2>&1
            ffmpeg_status=$?
            head -20 ffmpeg.out
            echo "ffmpeg_exit=$ffmpeg_status video_bytes=$(wc -c < video.h264)"
        fi
        echo "== receiver log"; grep -E "AASession|Messenger|Cryptor|TLS|Version|ControlChannel" receiver.err || true
        echo "== receiver trace"; cat trace.jsonl
        echo "== source-harness $* (exit $harness)"; cat harness.out
        [ "$receiver_status" = 0 ] || exit "$receiver_status"
        exit $harness
      ' demo "$@"
    ;;
*)
    echo "usage: tools/lab-receiver.sh fetch|build|demo [source-harness options]" >&2
    exit 1
    ;;
esac
