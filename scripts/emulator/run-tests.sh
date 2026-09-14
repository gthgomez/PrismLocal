#!/usr/bin/env bash
#
# Boot a Prism Local test AVD and run the connected instrumentation tests on it.
#
# The AVDs are created by scripts/emulator/create-avds.sh. This script boots one
# tier at a time (Gradle's connectedAndroidTest requires a single device), waits
# for full boot, runs the tests, then shuts the emulator down.
#
# REQUIREMENTS
#   * A KVM-capable Linux host (hardware acceleration). This host/VM has no
#     nested virtualization, so the emulator cannot run here.
#   * An Android SDK with cmdline-tools + emulator (create-avds.sh installs them).
#   * The instrumented inference tests need the gitignored smoke model at
#     app/src/androidTest/assets/smoke-model/ (manifest.json + tinyllama-v0.q8_0.gguf).
#     Without it, RealInferenceSmokeTest / ServiceSwitchModelTest will fail. Build
#     the app first and let AGP auto-install the pinned NDK/CMake.
#
# Usage:
#   scripts/emulator/run-tests.sh high
#   scripts/emulator/run-tests.sh low
#   scripts/emulator/run-tests.sh both
#   scripts/emulator/run-tests.sh high -- --tests "*ChatManager*"
#
set -euo pipefail

TIER="${1:-high}"
shift || true
EXTRA=()
if [[ "${1:-}" == "--" ]]; then
    shift
    EXTRA=("$@")
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
EMULATOR_PORT="${EMULATOR_PORT:-5554}"
SERIAL="emulator-${EMULATOR_PORT}"
BOOT_TIMEOUT_S="${BOOT_TIMEOUT_S:-900}"
AVD_HIGH="${HIGH_AVD:-prism_high}"
AVD_LOW="${LOW_AVD:-prism_low}"

# Optional extra emulator flags, space-separated.
EMULATOR_ARGS_ARR=()
if [[ -n "${EMULATOR_ARGS:-}" ]]; then
    # shellcheck disable=SC2206
    EMULATOR_ARGS_ARR=(${EMULATOR_ARGS})
fi

die() { echo "error: $*" >&2; exit 1; }

[[ -x "$ADB" ]] || die "adb not found at $ADB (set ANDROID_SDK_ROOT)"
[[ -x "$EMULATOR" ]] || die "emulator not found at $EMULATOR (set ANDROID_SDK_ROOT)"

if [[ ! -e /dev/kvm ]]; then
    echo "warning: /dev/kvm is missing; the emulator needs hardware acceleration." >&2
    echo "         Run 'emulator -accel-check' on the target host. Set ALLOW_NO_KVM=1 to try anyway." >&2
    [[ "${ALLOW_NO_KVM:-0}" == "1" ]] || exit 1
fi

if ! ldconfig -p 2>/dev/null | grep -q 'libpulse\.so\.0'; then
    echo "warning: libpulse.so.0 not found; the emulator will fail to start." >&2
    echo "         Install it, e.g. 'sudo apt-get install -y libpulse0' (the emulator's other libs are bundled)." >&2
fi

# cd to the repo root (two levels up from scripts/emulator).
cd "$(dirname "$0")/../.."

if [[ ! -f app/src/androidTest/assets/smoke-model/tinyllama-v0.q8_0.gguf ]]; then
    echo "warning: smoke-model assets are missing under app/src/androidTest/assets/smoke-model/." >&2
    echo "         Inference tests will fail; stage the smoke GGUF before running." >&2
fi

wait_for_boot() {
    echo "==> waiting for $SERIAL to boot (timeout ${BOOT_TIMEOUT_S}s)"
    "$ADB" -s "$SERIAL" wait-for-device
    local deadline=$((SECONDS + BOOT_TIMEOUT_S))
    until [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
        if (( SECONDS >= deadline )); then
            die "emulator $SERIAL did not finish booting within ${BOOT_TIMEOUT_S}s"
        fi
        sleep 5
    done
    # Let the package manager settle before installing.
    "$ADB" -s "$SERIAL" shell 'while [[ "$(getprop init.svc.bootanim)" != "stopped" ]]; do sleep 2; done' || true
    echo "==> $SERIAL booted"
}

shutdown_emulator() {
    "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" wait-for-disconnect >/dev/null 2>&1 || true
}

run_tier() {
    local avd="$1"
    echo "===================================================================="
    echo " tier: $avd"
    echo "===================================================================="
    shutdown_emulator
    "$ADB" start-server >/dev/null 2>&1 || true

    "$EMULATOR" -avd "$avd" -port "$EMULATOR_PORT" \
        -no-window -no-audio -no-boot-anim -no-snapshot \
        -gpu swiftshader_indirect "${EMULATOR_ARGS_ARR[@]}" \
        >/tmp/emulator-"$avd".log 2>&1 &

    wait_for_boot
    ./gradlew --no-daemon :app:connectedDevDebugAndroidTest "${EXTRA[@]}"
    shutdown_emulator
}

case "$TIER" in
    high) run_tier "$AVD_HIGH" ;;
    low)  run_tier "$AVD_LOW" ;;
    both) run_tier "$AVD_HIGH"; run_tier "$AVD_LOW" ;;
    *)    die "unknown tier '$TIER' (expected high|low|both)" ;;
esac

echo "==> done"
