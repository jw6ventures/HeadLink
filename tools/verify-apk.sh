#!/bin/sh
# Packaging invariants for the APKs. Runs inside the Android image (aapt2, zipalign,
# apksigner, llvm-readelf, llvm-nm on PATH). Does not prove the APK runs on a device.
set -eu
apk=$1
fail() {
    echo "verify-apk: $apk: $*" >&2
    exit 1
}
badging=$(aapt2 dump badging "$apk")
line() { printf '%s\n' "$badging" | grep -qx "$1" || fail "expected badging line: $1"; }
printf '%s\n' "$badging" | grep -q "^package: name='org.openautoandroid.source' " || fail "package name"
line "minSdkVersion:'33'"
line "targetSdkVersion:'36'"
line "native-code: 'arm64-v8a'"
# The spec §5.2 baseline plus P5.5's four optional dashboard grants. Still notably no
# INTERNET (APP10: map data is operator-supplied), no Bluetooth and no microphone. Each
# addition here is a deliberate, separately optional integration that degrades without
# its grant (APP09); this list is the guard that makes adding a fifth a visible act.
permissions=$(printf '%s\n' "$badging" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | LC_ALL=C sort | tr '\n' ' ')
[ "$permissions" = "android.permission.ACCESS_COARSE_LOCATION android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_NETWORK_STATE android.permission.FOREGROUND_SERVICE android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE android.permission.POST_NOTIFICATIONS android.permission.READ_PHONE_STATE " ] \
    || fail "unexpected permission set: $permissions"
printf '%s\n' "$badging" | grep -q "uses-permission: name='android.permission.INTERNET'" \
    && fail "INTERNET permission present"

printf '%s\n' "$badging" | grep -qx "  uses-feature: name='android.hardware.usb.accessory'" \
    || fail "required uses-feature android.hardware.usb.accessory"

manifest=$(aapt2 dump xmltree --file AndroidManifest.xml "$apk")
# Each service element flattened to one line carrying its own attributes. A nested
# element (intent-filter) starts a new "E:" line and so ends the block, which is what we
# want: only the service's own attributes decide whether it is safely declared.
services=$(printf '%s\n' "$manifest" | awk '
    /E: / && collecting { print line; collecting = 0; line = "" }
    /E: service / { collecting = 1 }
    collecting { sub(/^ +/, ""); line = line " " $0 }
    END { if (collecting) print line }
')
# Exactly two services, each pinned by name and by the attributes that make it safe.
# Anything else appearing here is a packaging regression, not a detail.
[ "$(printf '%s\n' "$services" | grep -c 'E: service ')" = 2 ] || fail "service count"
projection=$(printf '%s\n' "$services" | grep '"org.openautoandroid.source.ProjectionService"') \
    || fail "ProjectionService not declared"
printf '%s\n' "$projection" | grep -q ':exported([^)]*)=false\( \|$\)' || fail "ProjectionService must not be exported"
printf '%s\n' "$projection" | grep -q ':foregroundServiceType([^)]*)=0x00000010' \
    || fail "ProjectionService foregroundServiceType must be connectedDevice only"

# P5.1's notification listener. It is bound by the system and by nothing else: the
# BIND_NOTIFICATION_LISTENER_SERVICE guard is what makes an unexported, callback-free
# service the narrowest form spec §5.2 permits. Without the guard any app could bind it.
media=$(printf '%s\n' "$services" | grep '"org.openautoandroid.source.MediaAccessService"') \
    || fail "MediaAccessService not declared"
printf '%s\n' "$media" | grep -q ':exported([^)]*)=false\( \|$\)' || fail "MediaAccessService must not be exported"
printf '%s\n' "$media" | grep -q ':permission([^)]*)="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"' \
    || fail "MediaAccessService must be guarded by BIND_NOTIFICATION_LISTENER_SERVICE"
printf '%s\n' "$manifest" | grep -q '"android.service.notification.NotificationListenerService"' \
    || fail "MediaAccessService notification listener intent filter"
# Bound by the system, never started by us, so it declares no foreground service type.
if printf '%s\n' "$media" | grep -q ':foregroundServiceType'; then
    fail "MediaAccessService must not declare a foreground service type"
fi
printf '%s\n' "$manifest" | grep -q '"android.hardware.usb.action.USB_ACCESSORY_ATTACHED"' || fail "accessory attach filter"
# Release builds shorten resource paths, so resolve the file through the resource table.
filter_path=$(aapt2 dump resources "$apk" | awk '$NF == "xml/accessory_filter" { getline; print $3; exit }')
[ -n "$filter_path" ] || fail "accessory_filter resource"
filter=$(aapt2 dump xmltree --file "$filter_path" "$apk")
printf '%s\n' "$filter" | grep -q 'manufacturer="Android"' || fail "accessory filter manufacturer"
printf '%s\n' "$filter" | grep -q 'model="Android Auto"' || fail "accessory filter model"

# Two native libraries now: this project's own, and VTM's 45 KB tessellator (P5.5).
# Listed exactly, so a renderer that starts dragging further ABIs or .so files in is a
# build failure rather than a silent APK growth.
[ "$(unzip -Z1 "$apk" | grep '^lib/' | LC_ALL=C sort | tr '\n' ' ')" = "lib/arm64-v8a/libaap_jni.so lib/arm64-v8a/libvtm-jni.so " ] \
    || fail "native library set: $(unzip -Z1 "$apk" | grep '^lib/' | tr '\n' ' ')"
unzip -p "$apk" assets/NOTICES.txt | grep -q '^   OpenSSL 3.5.8' || fail "third-party notices asset"
for library in libaap_jni.so libvtm-jni.so; do
    unzip -v "$apk" "lib/arm64-v8a/$library" | grep -q ' Stored ' || fail "library is compressed: $library"
done
zipalign -c -P 16 4 "$apk" || fail "zip alignment"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unzip -q "$apk" 'lib/*' 'classes*.dex' -d "$work"
so=$work/lib/arm64-v8a/libaap_jni.so
llvm-readelf --file-header "$so" | grep -q 'Machine:.*AArch64' || fail "ELF machine"
needed=$(llvm-readelf --dynamic-table "$so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')
# OpenSSL is linked statically: no platform or bundled libssl/libcrypto may be needed.
for lib in $needed; do
    case $lib in
        libc.so | libdl.so | libm.so | liblog.so) ;;
        *) fail "unexpected NEEDED $lib" ;;
    esac
done
# Natives are registered by JNI_OnLoad with explicit signatures; it is the only export.
exports=$(llvm-nm -D --defined-only "$so" | awk '$2 == "T" { print $3 }' | tr '\n' ' ')
[ "$exports" = "JNI_OnLoad " ] || fail "unexpected native exports: $exports"
if llvm-readelf --program-headers "$so" | awk '$1 == "LOAD" { print $NF }' | grep -qvx 0x4000; then
    fail "LOAD segments not 16 KiB aligned"
fi
grep -a -q 'OpenSSL 3\.5\.8 ' "$so" || fail "pinned OpenSSL 3.5.8 version text not in the library"
if cat "$work"/classes*.dex | grep -a -q -e 'com/google/android/gms' -e 'gearhead'; then
    fail "Google Play services or gearhead reference in dex"
fi
case $apk in
    *-unsigned.apk) signer="unsigned" ;;
    *) signer=$(apksigner verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate DN: //p')
       [ -n "$signer" ] || fail "signature" ;;
esac
echo "verify-apk: ok $(basename "$apk") needed=[${needed% }] signer=[$signer]"
