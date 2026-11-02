#!/bin/bash
set -euo pipefail

# Verify the ABI and required JNI symbols of the remote native transports.
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if ! command -v readelf >/dev/null 2>&1; then
    echo "check-native-libs: readelf not found; skipping" >&2
    exit 0
fi

RDP_DIR="$REPO_ROOT/rdp-kotlin/jniLibs"
RDP_ENTRY_POINT='Java_sh_haven_core_rdp_RdpBitmapBridge_blitRegion'

# ABI directory -> the machine name readelf prints for it.
rdp_expected_machine() {
    case "$1" in
        arm64-v8a) echo 'AArch64' ;;
        armeabi-v7a) echo 'ARM' ;;
        x86_64) echo 'Advanced Micro Devices X86-64' ;;
        x86) echo 'Intel 80386' ;;
        *) echo '' ;;
    esac
}

# Initialised out here so the "did this gate inspect anything?" assertion at the
# bottom can read it even when RDP_DIR is absent (set -u would otherwise abort).
rdp_status=0
rdp_checked=0

if [ -d "$RDP_DIR" ]; then
    for abi_dir in "$RDP_DIR"/*/; do
        [ -d "$abi_dir" ] || continue
        abi="$(basename "$abi_dir")"
        so="$abi_dir/librdp_transport.so"

        if [ ! -f "$so" ]; then
            echo "FAIL: rdp-kotlin/jniLibs/$abi has no librdp_transport.so"
            rdp_status=1
            continue
        fi
        rdp_checked=$((rdp_checked + 1))

        want="$(rdp_expected_machine "$abi")"
        got="$(readelf -h "$so" 2>/dev/null | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p')"
        if [ -n "$want" ] && [ "$got" != "$want" ]; then
            echo "FAIL: rdp-kotlin/jniLibs/$abi/librdp_transport.so is a '$got' binary, expected '$want'"
            rdp_status=1
        fi

        # Exact match on the symbol-name column. Matching the whole line with a
        # regex anchor is easy to get wrong — "\$" inside double quotes is a
        # literal dollar, not an end-of-line anchor, which silently made this
        # fail on correct libraries.
        if ! readelf --dyn-syms -W "$so" 2>/dev/null \
            | awk -v want="$RDP_ENTRY_POINT" '{ sub(/@.*/, "", $8); if ($8 == want) found = 1 } END { exit !found }'; then
            echo "FAIL: rdp-kotlin/jniLibs/$abi/librdp_transport.so does not export $RDP_ENTRY_POINT"
            rdp_status=1
        fi
    done

    if [ "$rdp_checked" -eq 0 ] && [ "$rdp_status" -eq 0 ]; then
        echo "check-native-libs: no rdp libraries found under $RDP_DIR" >&2
    elif [ "$rdp_status" -ne 0 ]; then
        cat >&2 <<'MSG'

Rebuild all three from source and commit the result:
    cd rdp-kotlin/rust && cargo ndk -o ../jniLibs \
        -t arm64-v8a -t armeabi-v7a -t x86_64 build --release
MSG
        exit 1
    else
        echo "✓ $rdp_checked rdp native libraries match their ABI and export the JNI entry point."
    fi
fi

# ---------------------------------------------------------------------------
if [ "$rdp_checked" -eq 0 ]; then
    cat >&2 <<MSG

check-native-libs: FAILED — no native libraries were inspected at all.

Nothing was found under:
    $RDP_DIR

This checks BUILD OUTPUT, so it has to run after a build. By hand:
    ./gradlew :app:assembleArm64FullDebug -PtargetAbi=arm64
MSG
    exit 1
fi
