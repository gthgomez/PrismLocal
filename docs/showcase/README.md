# Showcase capture status

Source inspected: [`a6bd73d4473ae83f8635c009a15edd565bebb645`](https://github.com/gthgomez/PrismLocal/tree/a6bd73d4473ae83f8635c009a15edd565bebb645).
Inspection date: 2026-10-30. This is a source/documentation pass, not a recorded app run.

## Evidence available

No verified runtime screenshots or clips are checked into this repository at the
inspected revision. No new runtime media was created for this pass.

## Execution blocker

The selected cloud environment has JDK 21 but no Android SDK installation,
`ANDROID_HOME`/`ANDROID_SDK_ROOT`, `adb`, `emulator`, `sdkmanager`, `/dev/kvm`,
or attached Android device. This repository’s existing JDK 17 setup and Android
prerequisites were inspected rather than replaced with a large toolchain download.

Required next setup: JDK 17, Android SDK platform 36, NDK 28.2.13676358, CMake 3.22.1, initialized native submodules/local patches, and an Android 8.0+ device with enough RAM and storage for the selected GGUF. Follow the existing native setup documentation.

No Android build or real inference was executed for this showcase. Historical device evidence does not certify the current checkout.

## Capture a genuine demonstration

Use a licensed, small GGUF already available to the operator. Record the model identity/hash, device, build revision, and generation settings; show model loading, a harmless prompt, streamed output, and cancellation. Do not substitute a scripted answer for inference or expose stored chats/tokens.

After installing and manually opening the debug build on the test device, record
screens using Android's actual display output (requires Android platform tools):

```sh
mkdir -p docs/showcase
adb exec-out screencap -p > docs/showcase/screen.png
# Stop recording with Ctrl-C after the short walkthrough.
adb shell screenrecord --time-limit 20 /sdcard/showcase.mp4
adb pull /sdcard/showcase.mp4 docs/showcase/demo.mp4
adb shell rm /sdcard/showcase.mp4
```

Inspect every frame before publication. Keep the clip short and compress it for
GitHub; do not add large model/APK/build/ROM/save files. Add captions identifying
source commit, build variant, device/Android version, fixture origin, and which
steps were really exercised. Scripted UI states and design references must stay
labeled separately from device and end-to-end evidence.

## Source and verification references

[Project context](../../PROJECT_CONTEXT.md) and [third-party notices](../../THIRD_PARTY_NOTICES.md).

This pass changes README/capture documentation only. Local paths and links are
checked; Android Gradle, connected, release, and hardware gates remain unrun.
