#!/bin/sh
# Optional ART smoke test of the debug APK in a headless x86_64 emulator (AOSP API 36, no
# Google APIs), offline. It adds an x86_64 build of libaap_jni.so to a copy of the debug
# APK, re-signs the copy with the local debug key, and checks, with CheckJNI enabled for
# all apps: install and launch, native loading, the probe, identity creation and the TLS
# self-test, Connect and Stop without an accessory, the saved report, identity reuse
# after a relaunch, and no crash in logcat.
#
# This is not device evidence: the phone is arm64 GrapheneOS, and the emulator has no
# USB accessory, head unit, or GrapheneOS policy. It cannot pass or fail any gate.
#
# Needs /dev/kvm, localhost/openauto-emulator:smoke (tools/emulator-image.sh), and
# app/build/outputs/apk/debug/app-debug.apk from tools/android-build.sh. Results are
# written to $OPENAUTO_CACHE/emulator-smoke/<UTC time>/.
set -eu
cd "$(dirname "$0")/.."
. tools/podman-env.sh
EMULATOR_IMAGE=localhost/openauto-emulator:smoke
[ -f app/build/outputs/apk/debug/app-debug.apk ] || { echo "run tools/android-build.sh first" >&2; exit 1; }
[ -e /dev/kvm ] || { echo "emulator-smoke: /dev/kvm is required" >&2; exit 1; }
out=$CACHE_ROOT/emulator-smoke/$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$out" "$CACHE_ROOT/emulator-avd"
name=openauto-emulator-smoke-$$
# Emulator helper processes can outlive a failed script; never leave the container behind.
trap 'podman rm -f "$name" > /dev/null 2>&1 || true' EXIT
podman_run --name "$name" --network=none --device /dev/kvm \
  -v "$CACHE_ROOT/android-user:/cache/android-user" -e ANDROID_USER_HOME=/cache/android-user \
  -v "$CACHE_ROOT/emulator-avd:/cache/avd" -e ANDROID_AVD_HOME=/cache/avd \
  -e ANDROID_EMULATOR_HOME=/cache/avd -e ANDROID_SDK_ROOT=/opt/android-sdk \
  -v "$out:/out" "$EMULATOR_IMAGE" sh -eu -c '
    package=com.jw6ventures.headlink
    log() { echo "emulator-smoke: $*"; }
    # adb waits forever for a device that is gone, so every capture here is bounded.
    fail() { echo "emulator-smoke: FAIL $*" >&2; timeout 20 adb logcat -d > /out/logcat.txt 2>&1 || true; exit 1; }

    tools/openssl-build.sh x86_64-linux-android
    . tools/openssl-env.sh
    cargo build -p aap-jni --release --target x86_64-linux-android --offline --locked 2>&1 | tail -n 1

    # Copy of the debug APK with an x86_64 library added (stored, page aligned), re-signed.
    work=$(mktemp -d)
    cp app/build/outputs/apk/debug/app-debug.apk "$work/unsigned.apk"
    python3 -c "import sys, zipfile
with zipfile.ZipFile(sys.argv[1], \"a\") as apk:
    apk.write(sys.argv[2], \"lib/x86_64/libaap_jni.so\", compress_type=zipfile.ZIP_STORED)
" "$work/unsigned.apk" target/x86_64-linux-android/release/libaap_jni.so
    zipalign -f -P 16 4 "$work/unsigned.apk" "$work/aligned.apk"
    apksigner sign --ks /cache/android-user/debug.keystore --ks-pass pass:android \
        --key-pass pass:android --out /out/smoke.apk "$work/aligned.apk"

    # A fresh AVD for every run.
    rm -rf /cache/avd/smoke.avd /cache/avd/smoke.ini
    mkdir -p /cache/avd/smoke.avd
    printf "avd.ini.encoding=UTF-8\npath=/cache/avd/smoke.avd\ntarget=android-36\n" > /cache/avd/smoke.ini
    printf "%s\n" "AvdId=smoke" "abi.type=x86_64" "hw.cpu.arch=x86_64" \
        "image.sysdir.1=system-images/android-36/default/x86_64/" "tag.id=default" \
        "hw.ramSize=3072" "disk.dataPartition.size=2G" "hw.lcd.width=720" "hw.lcd.height=1280" \
        "hw.lcd.density=320" "hw.keyboard=yes" "hw.gpu.enabled=yes" "hw.gpu.mode=swangle_indirect" \
        "hw.gsmModem=no" "hw.gps=no" "hw.sensors.proximity=no" \
        > /cache/avd/smoke.avd/config.ini

    # swangle without Vulkan: gfxstream with SwiftShader Vulkan crashed at startup on the
    # development host; the network-less container cannot reach the modem simulator.
    emulator -avd smoke -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
        -gpu swangle_indirect -feature -Vulkan -feature -ModemSimulator -accel on -no-metrics > /out/emulator.txt 2>&1 &
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
    # CheckJNI for apps started from now on (ActivityManager reads debug.checkjni at launch).
    timeout 30 adb root > /out/adb-root.txt 2>&1 || fail "adb root: $(cat /out/adb-root.txt)"
    sleep 3
    timeout 60 adb wait-for-device || fail "device not back after adb root"
    adb shell setprop debug.checkjni 1 || fail "setprop debug.checkjni"
    for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
        adb shell settings put global $setting 0
    done
    adb shell input keyevent 82 || true

    adb install /out/smoke.apk > /out/install.txt 2>&1 || { cat /out/install.txt >&2; fail install; }
    abi=$(adb shell pm dump $package | sed -n "s/.*primaryCpuAbi=\([^ ]*\).*/\1/p" | tr -d "\r" | head -n 1)
    log "installed; primaryCpuAbi=$abi"
    [ "$abi" = x86_64 ] || fail "unexpected ABI $abi"
    adb shell pm grant $package android.permission.POST_NOTIFICATIONS
    adb logcat -c

    dump() { # screen text to /out/ui.xml; prints it
        adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
        adb exec-out cat /sdcard/ui.xml > /out/ui.xml 2>/dev/null || true
        cat /out/ui.xml
    }
    wait_text() { # pattern seconds
        i=0
        while [ $i -lt "$2" ]; do
            if dump | grep -q "$1"; then return 0; fi
            i=$((i + 1)); sleep 1
        done
        return 1
    }
    tap() { # button label, matched case-insensitively
        bounds=$(dump | tr ">" "\n" | grep -i "text=\"$1\"" | sed -n "s/.*bounds=\"\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]\".*/\1 \2 \3 \4/p" | head -n 1)
        [ -n "$bounds" ] || fail "no button $1"
        set -- $bounds
        adb shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
    }
    # Connect is refused with a toast until the identity and self-test are ready.
    connect_stop() {
        armed=
        for _ in $(seq 60); do
            tap Connect
            if wait_text "Stage: ARMED" 3; then armed=1; break; fi
            sleep 2
        done
        [ -n "$armed" ] || fail "Connect never armed (identity not ready?)"
        tap Stop
        wait_text "Last result: CANCELLED" 20 || fail "Stop did not cancel"
        wait_text "Stage: IDLE" 10 || fail "not idle after Stop"
    }
    newest_report() { # copies the newest saved report to $1
        name=$(adb shell run-as $package ls files/reports | tr -d "\r" | sort | tail -n 1)
        [ -n "$name" ] || fail "no saved report"
        adb exec-out run-as $package cat "files/reports/$name" > "$1"
    }

    launched=$(date +%s)
    adb shell am start -W -n $package/.ConnectionActivity > /out/launch.txt
    grep -q "Status: ok" /out/launch.txt || { cat /out/launch.txt >&2; fail launch; }
    for _ in $(seq 180); do
        [ "$(adb shell run-as $package ls files/identity 2>/dev/null | tr -d "\r")" = source-identity.pem ] && break
        sleep 1
    done
    log "identity file present after about $(( $(date +%s) - launched )) s (includes RSA key generation)"
    adb exec-out screencap -p > /out/first-launch.png
    connect_stop
    adb exec-out screencap -p > /out/after-stop.png
    newest_report /out/first-report.txt
    for line in attempt_complete=true final_category=CANCELLED stop_after=discovery tls_profile=tls12-aead \
        first_use_armed=false self_test=pass identity_created=true source_identity_key=RSA-2048; do
        grep -qx "$line" /out/first-report.txt || fail "first report lacks $line"
    done
    grep -q "^tls_self_test=pass version=TLSv1.2 " /out/first-report.txt || fail "first report lacks the TLS self-test"
    grep -q "^tls_library=OpenSSL 3.5.8 " /out/first-report.txt || fail "first report lacks the OpenSSL version"
    grep -q "no accessory enumerated" /out/first-report.txt || fail "first report lacks enumeration"
    ! grep -q "PRIVATE KEY" /out/first-report.txt || fail "key material in the report"
    [ "$(adb shell run-as $package ls files/identity | tr -d "\r")" = source-identity.pem ] || fail "identity file set"

    adb shell am force-stop $package
    adb shell am start -W -n $package/.ConnectionActivity > /out/relaunch.txt
    sleep 2
    connect_stop
    newest_report /out/second-report.txt
    grep -qx "identity_created=false" /out/second-report.txt || fail "identity not reused after relaunch"
    [ "$(grep -h "^source_identity_fingerprint_sha256=" /out/first-report.txt)" = \
      "$(grep -h "^source_identity_fingerprint_sha256=" /out/second-report.txt)" ] || fail "identity changed"

    timeout 60 adb logcat -d > /out/logcat.txt
    grep -q "libaap_jni.so using class loader .*: ok" /out/logcat.txt || fail "native library load not logged"
    grep -q "Late-enabling -Xcheck:jni" /out/logcat.txt || fail "CheckJNI was not enabled for the app"
    # Only this app: Java crashes name the process; native crashes name it in parentheses.
    # Emulator HAL crashes (for example the radio HAL without a modem) are ignored.
    if grep -E "JNI DETECTED ERROR|UnsatisfiedLinkError|Process: $package|Fatal signal .*\\($package|>>> $package <<<" /out/logcat.txt; then
        fail "app crash or JNI error in logcat"
    fi
    log "pass"
  '
echo "emulator-smoke: results in $out"
