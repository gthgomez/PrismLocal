#!/usr/bin/env bash
#
# Create the two Prism Local on-device test AVDs:
#
#   prism_high  high-end / modern-flagship tier  (API 36, 4 GB RAM, 4 cores, pixel_7)
#   prism_low   low-end / budget tier            (API 30, 1.5 GB RAM, 2 cores, pixel_3a)
#
# The two tiers exist to exercise the app's RAM/thermal/readiness paths on a
# capable phone and on a constrained one. Everything is overridable via the
# environment (see below). Idempotent: safe to re-run; AVDs are recreated with
# --force and existing system images are reused.
#
# Requires an Android SDK with cmdline-tools. Set ANDROID_SDK_ROOT / ANDROID_HOME
# (defaults to $HOME/android-sdk).
#
# Usage:
#   scripts/emulator/create-avds.sh
#   HIGH_API=35 LOW_API=29 LOW_DEVICE=Nexus\ 5 scripts/emulator/create-avds.sh
#
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"

HIGH_AVD="${HIGH_AVD:-prism_high}"
HIGH_API="${HIGH_API:-36}"
HIGH_TAG="${HIGH_TAG:-google_apis}"
HIGH_ABI="${HIGH_ABI:-x86_64}"
HIGH_DEVICE="${HIGH_DEVICE:-pixel_7}"
HIGH_RAM_MB="${HIGH_RAM_MB:-4096}"
HIGH_CORES="${HIGH_CORES:-4}"
HIGH_HEAP_MB="${HIGH_HEAP_MB:-512}"

LOW_AVD="${LOW_AVD:-prism_low}"
LOW_API="${LOW_API:-30}"
LOW_TAG="${LOW_TAG:-google_apis}"
LOW_ABI="${LOW_ABI:-x86_64}"
LOW_DEVICE="${LOW_DEVICE:-pixel_3a}"
LOW_RAM_MB="${LOW_RAM_MB:-1536}"
LOW_CORES="${LOW_CORES:-2}"
LOW_HEAP_MB="${LOW_HEAP_MB:-256}"

HIGH_IMAGE="system-images;android-${HIGH_API};${HIGH_TAG};${HIGH_ABI}"
LOW_IMAGE="system-images;android-${LOW_API};${LOW_TAG};${LOW_ABI}"

die() { echo "error: $*" >&2; exit 1; }

[[ -x "$SDKMANAGER" ]] || die "sdkmanager not found at $SDKMANAGER (set ANDROID_SDK_ROOT)"
[[ -x "$AVDMANAGER" ]] || die "avdmanager not found at $AVDMANAGER (set ANDROID_SDK_ROOT)"

accept_licenses() {
    # sdkmanager exits non-zero if `yes` closes the pipe early; tolerate that.
    ( yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1 ) || true
}

install_packages() {
    local image="$1"
    echo "==> installing emulator, platform-tools, $image"
    "$SDKMANAGER" --sdk_root="$SDK_ROOT" --install "emulator" "platform-tools" "$image"
}

set_config() {
    local file="$1" key="$2" value="$3"
    touch "$file"
    if grep -q "^${key}=" "$file"; then
        sed -i "s|^${key}=.*|${key}=${value}|" "$file"
    else
        printf '%s=%s\n' "$key" "$value" >> "$file"
    fi
}

create_avd() {
    local name="$1" image="$2" device="$3" ram="$4" cores="$5" heap="$6"
    echo "==> creating AVD '$name' ($device, $image, ${ram}MB RAM, ${cores} cores)"
    # "no" answers the "create a custom hardware profile?" prompt.
    echo "no" | "$AVDMANAGER" create avd --force --name "$name" --package "$image" --device "$device"

    local config="$HOME/.android/avd/${name}.avd/config.ini"
    set_config "$config" "hw.ramSize" "$ram"
    set_config "$config" "hw.cpu.ncore" "$cores"
    set_config "$config" "vm.heapSize" "$heap"
    set_config "$config" "hw.keyboard" "yes"
    set_config "$config" "disk.dataPartition.size" "6G"
}

accept_licenses
install_packages "$HIGH_IMAGE"
install_packages "$LOW_IMAGE"
create_avd "$HIGH_AVD" "$HIGH_IMAGE" "$HIGH_DEVICE" "$HIGH_RAM_MB" "$HIGH_CORES" "$HIGH_HEAP_MB"
create_avd "$LOW_AVD" "$LOW_IMAGE" "$LOW_DEVICE" "$LOW_RAM_MB" "$LOW_CORES" "$LOW_HEAP_MB"

echo
echo "==> AVDs:"
"$AVDMANAGER" list avd
