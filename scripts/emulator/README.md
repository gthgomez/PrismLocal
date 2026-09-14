# On-device test AVDs

Two Android Virtual Devices model the phone tiers this app is tested against:

| AVD | Tier | Image | Device profile | RAM | Cores |
|-----|------|-------|----------------|-----|-------|
| `prism_high` | modern flagship ("Samsung-class") | `android-36;google_apis;x86_64` | `pixel_7` | 4 GB | 4 |
| `prism_low` | low-end / budget | `android-30;google_apis;x86_64` | `pixel_3a` | 1.5 GB | 2 |

The high tier exercises normal generation/readiness paths; the low tier stresses
the memory governor, model-fit assessment, and thermal/thread limits. Every value
is overridable via environment variables (see the scripts).

## Hard requirement: hardware acceleration (KVM)

The emulator needs `/dev/kvm`. On bare-metal Linux or a VM with **nested
virtualization enabled**, this exists. On a VM without it, the emulator cannot
run (x86_64 images refuse to start).

Check the host before doing anything:

```bash
"$ANDROID_SDK_ROOT/emulator/emulator" -accel-check
# Linux x86_64:  KVM requires a CPU that supports vmx or svm   <-- not usable
#                accel: 0 KVM (version ...) is installed      <-- usable
```

> This repository's current dev sandbox is a Hyper-V guest **without** nested
> virtualization (`kvm_intel` cannot load, no `/dev/kvm`), so the AVDs can be
> created here but not booted. Run the scripts on a KVM-capable host or a
> KVM-enabled CI runner.

## Host packages

The emulator bundles almost all of its dependencies under
`$ANDROID_SDK_ROOT/emulator/lib64`; the one common system library it needs is
`libpulse`:

```bash
sudo apt-get install -y libpulse0
```

## 1. Create the AVDs

Requires an Android SDK with `cmdline-tools`. Set `ANDROID_SDK_ROOT` (or
`ANDROID_HOME`); otherwise `$HOME/android-sdk` is assumed.

```bash
scripts/emulator/create-avds.sh
```

This installs `emulator`, `platform-tools`, and the two system images, then
creates/recreates `prism_high` and `prism_low`. It is idempotent.

Override examples:

```bash
# Smaller download / different API levels
HIGH_API=35 LOW_API=29 scripts/emulator/create-avds.sh
# A genuinely ancient low tier
LOW_DEVICE="Nexus 5" LOW_RAM_MB=1024 LOW_CORES=1 scripts/emulator/create-avds.sh
```

## 2. Build + run the connected tests

```bash
scripts/emulator/run-tests.sh high   # boot prism_high, run :app:connectedDevDebugAndroidTest
scripts/emulator/run-tests.sh low    # boot prism_low
scripts/emulator/run-tests.sh both   # sequential: high, then low
```

Notes:

- The first run builds the native `libllmhost.so`. AGP pins NDK `28.2.13676358`
  and CMake `3.22.1`; accept the SDK licenses once
  (`yes | sdkmanager --licenses`) and Gradle installs them automatically, as the
  CI `native-builds` job does.
- Gradle's `connectedAndroidTest` requires exactly one device, so `both` runs the
  tiers sequentially.
- Emulator logs are written to `/tmp/emulator-<avd>.log`.
- Useful env vars: `BOOT_TIMEOUT_S` (default 900), `EMULATOR_ARGS`, `ALLOW_NO_KVM=1`.

### Smoke-model asset (required by the inference tests)

`RealInferenceSmokeTest` and `ServiceSwitchModelTest` read a tiny real GGUF from
`app/src/androidTest/assets/smoke-model/`:

```
smoke-model/manifest.json
smoke-model/tinyllama-v0.q8_0.gguf   # 6,750,304 bytes
```

That directory is **gitignored**, so a fresh clone does not have it and those
tests will fail. Stage the model before running the full suite (or filter to
non-inference classes with a runner argument):

```bash
scripts/emulator/run-tests.sh high -- \
  -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.ModelStorageManagerTest
```

## Manual control

```bash
export ANDROID_SDK_ROOT="$HOME/android-sdk"
"$ANDROID_SDK_ROOT/emulator/emulator" -avd prism_low -no-window -no-audio -no-snapshot &
"$ANDROID_SDK_ROOT/platform-tools/adb" wait-for-device
```

`avdmanager list avd` lists the definitions; `~/.android/avd/<name>.avd/config.ini`
holds the per-AVD hardware overrides.
