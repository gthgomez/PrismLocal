# PrismLocal On-Device Qualification Runbook (QUAL)

**Status:** ACTIVE  
**Last updated:** 2026-09-23  
**Audience:** Release engineers, QA agents, and performance evaluators

---

## 1. Objective

This runbook defines the mandatory verification procedure for qualifying local inference on physical hardware or testbed emulators before release sign-off. While unit tests and host CTests establish contract integrity, the **QUAL** gate validates real native accelerator math, thermal stability, memory governors, and crash immunity under live Android OS conditions.

---

## 2. Prerequisites

1. **Host Environment:**
   - Android SDK platform-tools (`adb`) on PATH.
   - Target device connected via USB or wireless debugging (`adb devices` showing `device`).
   - Minimum 6 GB physical RAM on test device (12–16 GB recommended for Bonsai-27B Q1_0 targets).
2. **Build Variant:**
   - Assemble canonical benchmark build:
     ```powershell
     cd D:\Workspace\Project_Android\PrismLocal
     .\gradlew.bat --no-daemon :app:assembleDevBenchmark
     ```
   - Package ID: `com.prismai.llmhost.benchmark`
   - APK location: `app/build/outputs/apk/dev/benchmark/app-dev-benchmark.apk`

---

## 3. Test Matrix & Protocol

### Step 1: Clean Install & Permission Baseline
```bash
adb uninstall com.prismai.llmhost.benchmark 2>/dev/null || true
adb install -r app/build/outputs/apk/dev/benchmark/app-dev-benchmark.apk
adb shell pm grant com.prismai.llmhost.benchmark android.permission.POST_NOTIFICATIONS
```

### Step 2: Background Crash Scan Setup
In a dedicated terminal, launch the crash and memory logcat monitor:
```bash
adb logcat -c
adb logcat -v time -s PrismLocal:* DEBUG:* AndroidRuntime:* libc:* libllmhost:*
```

### Step 3: Smoke Model Ingestion & Generation
1. Launch the app:
   ```bash
   adb shell am start -n com.prismai.llmhost.benchmark/com.prismai.llmhost.MainActivity
   ```
2. Navigate to Model Catalog or import local test GGUF (`smoke-model.gguf` or target Q1_0 / Q4_K_M).
3. Verify SHA-256 integrity check executes without UI thread stalls.
4. Execute 3 consecutive generation turns (min 32 tokens each).
5. Verify:
   - Zero `OutOfMemoryError` or SIGSEGV in logcat.
   - Stream emits losslessly without character corruption or Unicode boundary breaks.
   - Cancellation triggered mid-turn aborts native engine within <200ms.

### Step 4: Thermal & Battery Governor Verification
1. Run thread-sweep benchmark preset (`THREAD_SWEEP`).
2. Poll battery & thermal status:
   ```bash
   adb shell dumpsys battery
   adb shell dumpsys thermalservice
   ```
3. Confirm:
   - BackgroundAgentManager pauses when simulated thermal status reaches `SEVERE` (`4`).
   - BackgroundAgentManager holds queue when battery drops under 15% without AC power.

### Step 5: Process Death & Durability Recovery Test
1. Enqueue 2 background tasks via UI or test runner:
   - "Task 1: Generate summary"
   - "Task 2: Vector search indexing"
2. While Task 1 is executing, forcefully terminate the app process:
   ```bash
   adb shell am force-stop com.prismai.llmhost.benchmark
   ```
3. Restart the app:
   ```bash
   adb shell am start -n com.prismai.llmhost.benchmark/com.prismai.llmhost.MainActivity
   ```
4. Verify:
   - `background_tasks.json` was parsed on startup.
   - Task 1 was recovered to `QUEUED` state (no silent drop).
   - Task 2 remains pending in `QUEUED` state.

---

## 4. Evidence Recording & Contract

Per `docs/inference/evidence-contract.md`, record each executed run in `research/inference/results/<device>-<model>-<timestamp>.jsonl`:

- **Identity:** Model ID, SHA-256, Quantization format.
- **Device:** Manufacturer, Model, SoC, Total RAM class, Android OS level.
- **Backend:** Requested vs Applied (Vulkan vs CPU/KleidiAI).
- **Outcome:** `PASS` / `FAIL` / `BLOCKED` with exact exit status and thermal observations.
