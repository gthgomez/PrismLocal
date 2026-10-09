# Reliability Sprint PR 3 — Verification and CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make CI able to prove something about the engine, by fixing the failing native host job on evidence rather than assumption, extending sanitizers to the target that runs real `Engine.cpp`, and correcting a smoke assertion that can never pass.

**Architecture:** This PR changes no production behavior. It changes how the build is measured. The failing `native-host-tests` job is instrumented first to establish the actual cause of the compiler kill, then fixed against that evidence. Sanitizer coverage is extended to `engine_mock_test`. One wrong instrumentation assertion is corrected.

**Tech Stack:** CMake 3.22+, CTest, GitHub Actions, ASan/UBSan, Kotlin instrumentation tests.

**Spec:** `docs/superpowers/specs/2026-10-06-reliability-sprint-design.md`
**Independent of PR 1 and PR 2** in terms of code, except Task 2's CMake change should land after PR 1 so the Engine target reflects the new encode path.

## Global Constraints

- Baseline `f13545f` + PRs 1 and 2 merged.
- All CI paths relative to the repository root; C++ relative to `app/src/main/cpp/`.
- **Do not assume the compiler kill is OOM.** It is a SIGKILL with an unexplained two-minute gap before it. Task 1 exists to establish the cause.
- Do not remove a test to make CI green. Every fix here must increase what CI proves.
- Never edit `app/src/main/cpp/third_party/llama.cpp/`. It is a vendored submodule.
- The sanitizer gap is a **coverage** problem in the opposite direction from the build failure. Fixing one does not fix the other; do not conflate them.

---

## File Structure

| File | Responsibility |
| ---- | -------------- |
| `.github/workflows/android-ci.yml` | `native-host-tests` job: diagnostics, bounded build |
| `app/src/test/cpp/CMakeLists.txt` | Extends sanitizer flags to `engine_mock_test` |
| `app/src/androidTest/.../RealInferenceSmokeTest.kt` | Corrects the impossible terminal assertion |
| `scripts/instrument_native_build.sh` (new) | Reports memory and kill reason around the native build |

---

### Task 1: Instrument the native host build, then fix the compiler kill

**Why:** this is the only failing CI job, and the cause is not established. Guessing here risks a fix that works for the wrong reason.

**Files:**
- Create: `scripts/instrument_native_build.sh`
- Modify: `.github/workflows/android-ci.yml:74-95` (`native-host-tests` job)

**Interfaces:**
- Consumes: the existing `cmake -S app/src/test/cpp -B build/prism-native-tests` invocation at `android-ci.yml:86-89`.
- Produces: an artifact with memory telemetry and the kill reason; a bounded build that survives.

- [ ] **Step 1: Write the instrumentation script**

Create `scripts/instrument_native_build.sh`:

```bash
#!/usr/bin/env bash
# Instrument the native host-test build.
#
# The CI log shows cc1plus SIGKILLed at ~97% of the llama.cpp sources, with a
# two-minute gap between the last build line and the kill. That is consistent
# with memory pressure but does not prove it. This records the evidence needed
# to decide, rather than assuming.
set -uo pipefail

BUILD_DIR="${1:-build/prism-native-tests}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/ci-artifacts}"
mkdir -p "$ARTIFACT_DIR"

report() {
  local label="$1"
  {
    echo "=== $label ==="
    echo "utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "free_mb: $(free -m | awk '/^Mem:/{print $7}')"
    echo "total_mb: $(free -m | awk '/^Mem:/{print $2}')"
    echo "loadavg: $(cat /proc/loadavg)"
    echo "nproc: $(nproc)"
    if [ -f /sys/fs/cgroup/memory.max ]; then
      echo "cgroup_memory_max: $(cat /sys/fs/cgroup/memory.max)"
      echo "cgroup_memory_current: $(cat /sys/fs/cgroup/memory.current)"
      echo "cgroup_memory_events:"
      cat /sys/fs/cgroup/memory.events 2>/dev/null || echo "(unavailable)"
    fi
  } >> "$ARTIFACT_DIR/native-build-memory.log"
}

report "before-configure"

# cgroup memory.events oom_kill is the direct evidence of an OOM kill. Without
# it, a SIGKILL is just a SIGKILL.
if [ -f /sys/fs/cgroup/memory.events ]; then
  cp /sys/fs/cgroup/memory.events "$ARTIFACT_DIR/memory.events.before" 2>/dev/null || true
fi

report "before-build"

# --parallel is bounded explicitly. An unbounded parallel build against the full
# vendored llama.cpp graph is the leading suspect; capping it is safe regardless
# of whether it is the actual cause.
JOBS="${JOBS:-2}"
echo "building with --parallel $JOBS"
cmake --build "$BUILD_DIR" --parallel "$JOBS" 2>&1 | tee "$ARTIFACT_DIR/native-build.log"
build_status="${PIPESTATUS[0]}"

report "after-build"

if [ -f /sys/fs/cgroup/memory.events ]; then
  cp /sys/fs/cgroup/memory.events "$ARTIFACT_DIR/memory.events.after" 2>/dev/null || true
  echo "=== memory.events delta ==="
  diff "$ARTIFACT_DIR/memory.events.before" "$ARTIFACT_DIR/memory.events.after" \
    > "$ARTIFACT_DIR/memory.events.delta" 2>/dev/null || true
  cat "$ARTIFACT_DIR/memory.events.delta"
fi

echo "build_status=$build_status"
exit "$build_status"
```

- [ ] **Step 2: Wire the script into the job with artifacts uploaded on failure**

Replace the `native-host-tests` build step at `android-ci.yml:91-92`:

```yaml
      - name: Build Native Host Tests
        run: ./scripts/instrument_native_build.sh build/prism-native-tests

      - name: Collect build diagnostics
        if: always()
        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
        with:
          name: native-build-diagnostics
          path: build/ci-artifacts/
          if-no-files-found: ignore
          retention-days: 14
```

The pinned `upload-artifact` SHA must match the repo's existing pinning
convention. If no other job uploads artifacts, verify the SHA resolves before
using it.

- [ ] **Step 3: Read the evidence and decide the fix — do not skip this step**

Push the branch and read `native-build-diagnostics` from the run. Decide from
`memory.events.delta`:

| Evidence | Meaning | Fix |
| -------- | ------- | --- |
| `oom_kill` incremented | Genuine OOM | Keep `--parallel 2`; if still failing, reduce to 1 or split the Engine target into its own job |
| `oom_kill` unchanged, still SIGKILL | Not memory | Investigate the two-minute gap: runner image regression, a hung `cc1plus`, or the job being cancelled externally |
| Build succeeds with `--parallel 2` | Over-parallelism | Keep `--parallel 2`; note in the workflow comment why |

Then add the chosen fix to the workflow and record which row of the table the
evidence supported. **Do not commit a fix justified by a guess.**

- [ ] **Step 4: Confirm the sanitizer hypothesis is not what is being fixed**

Re-verify before closing the task:

```bash
grep -n "PRISM_SANITIZER_FLAGS" app/src/test/cpp/CMakeLists.txt
```
Expected: the flags appear only inside `prism_add_native_test` (lines 77-80).
The llama sub-build and `engine_mock_test` are unsanitized, so ASan/UBSan is
**not** part of the kill's cause. If this grep now shows sanitizer flags on the
llama sub-build, someone has changed it since the spec was written — re-verify
the whole analysis.

- [ ] **Step 5: Confirm the job now passes**

Push and confirm `Native Host Tests (CTest)` reports success, with ctest output
showing every test including `engine_mock_test`.

- [ ] **Step 6: Commit**

```bash
git add scripts/instrument_native_build.sh .github/workflows/android-ci.yml
git commit -m "ci: fix the native host-test compiler kill on evidence

native-host-tests is the only failing job: cc1plus is SIGKILLed at ~97%
of the llama.cpp sources, 2m56s into a 15m budget, with a two-minute gap
between the last build line and the kill. The job has no needs: and does
run, so this is not a dependency problem.

Add an instrumentation script that records free memory, cgroup limits and
memory.events around the build, and upload diagnostics on failure.
cgroup memory.events oom_kill is the direct evidence of an OOM; without
it a SIGKILL is just a SIGKILL.

Bound --parallel explicitly to 2. The killed compile is a plain Debug
build: PRISM_SANITIZER_FLAGS is applied only inside prism_add_native_test,
so neither the llama sub-build nor engine_mock_test is sanitized and
ASan/UBSan is not part of the cause.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Extend sanitizers to the Engine target

**Why:** `engine_mock_test` is the only target that compiles and runs real `Engine.cpp`, and it receives only `-Wall -Wextra`. Everything sanitizer-related in CI covers header-only helpers.

**Files:**
- Modify: `app/src/test/cpp/CMakeLists.txt:143-157` (`engine_mock_test` target)

**Interfaces:**
- Consumes: `PRISM_SANITIZER_FLAGS` defined at `:35-53`.
- Produces: `engine_mock_test` built with ASan/UBSan when `-DPRISM_ENABLE_SANITIZERS=ON`.

- [ ] **Step 1: Confirm the gap**

```bash
sed -n '143,157p' app/src/test/cpp/CMakeLists.txt
```
Expected: `target_compile_options(engine_mock_test PRIVATE -Wall -Wextra)` and no
sanitizer flags.

- [ ] **Step 2: Add the flags**

After `target_compile_options(engine_mock_test PRIVATE -Wall -Wextra)` at `:155`, add:

```cmake
    # engine_mock_test compiles the real Engine.cpp and is the only target that
    # exercises production engine code. Without this, PRISM_ENABLE_SANITIZERS
    # silently covers only the header-only helpers and the Engine target gets no
    # instrumentation at all.
    if(PRISM_SANITIZER_FLAGS)
        target_compile_options(engine_mock_test PRIVATE ${PRISM_SANITIZER_FLAGS})
        target_link_options(engine_mock_test PRIVATE ${PRISM_SANITIZER_FLAGS})
    endif()
```

- [ ] **Step 3: Verify it builds clean under sanitizers, and note the cost**

```bash
cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug -DPRISM_ENABLE_SANITIZERS=ON -DPRISM_REQUIRE_SANITIZERS=ON
cmake --build build/prism-native-tests --parallel 2
ctest --test-dir build/prism-native-tests --output-on-failure
```
Expected: all tests pass. Sanitizers on `Engine.cpp` are expensive — this may
make the memory situation in Task 1 worse. If the build is now killed *because*
of sanitizers, that is a real trade-off and must be recorded: instrument the
Engine target on a larger runner (`ubuntu-latest-8-cores`) rather than
abandoning coverage, or run sanitized and unsanitized as two CMake
configurations.

Do not silently drop the flags to make the build pass.

- [ ] **Step 4: Verify without sanitizers still works**

```bash
cmake -S app/src/test/cpp -B build/prism-native-nosan -DCMAKE_BUILD_TYPE=Debug -DPRISM_ENABLE_SANITIZERS=OFF
cmake --build build/prism-native-nosan --parallel 2
ctest --test-dir build/prism-native-nosan --output-on-failure
```
Expected: all pass. The default developer path must not require sanitizers.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/cpp/CMakeLists.txt
git commit -m "test: sanitize the Engine target, not just the helpers

PRISM_SANITIZER_FLAGS is applied inside prism_add_native_test, so it only
ever reached the header-only runtime helpers. engine_mock_test compiles
and runs the real Engine.cpp and received just -Wall -Wextra, meaning the
only target exercising production engine code had no ASan/UBSan
coverage at all.

Apply the same flags to engine_mock_test. Instrumentation on Engine.cpp is
expensive; if that strains the runner, use a larger runner or a separate
sanitized configuration rather than dropping the coverage.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Fix the impossible smoke assertion

**Why:** `RealInferenceSmokeTest.kt:44` asserts an `EOF` terminal while requesting `maxTokens = 1`, which guarantees a `MAX_TOKENS` terminal. The assertion can never pass, so the test is dead weight that only fails once someone runs it.

**Files:**
- Modify: `app/src/androidTest/java/com/prismai/llmhost/RealInferenceSmokeTest.kt:35-47`

**Interfaces:**
- Consumes: `Engine.cpp:579-582` — `StreamTerminal::Eof` → `"EOF"`, `StreamTerminal::MaxTokens` → `"MAX_TOKENS"`.
- Produces: a smoke test whose terminal expectation matches the requested token budget.

- [ ] **Step 1: Confirm the mismatch**

```bash
sed -n '35,47p' app/src/androidTest/java/com/prismai/llmhost/RealInferenceSmokeTest.kt
```
Expected: `maxTokens = 1` and `assertTrue("expected EOF terminal", ... == "EOF")`.

With one requested token, the engine stops on the token limit and emits
`MAX_TOKENS`. `EOF` only occurs if the model hits a stop token first, which a
single-token budget makes unlikely. The assertion encodes a wish, not the
contract.

- [ ] **Step 2: Fix it to assert the real contract**

Replace `:44`:

```kotlin
            // maxTokens = 1 means the engine stops on the token budget and emits
            // MAX_TOKENS. EOF only occurs if the model produces a stop token
            // first, which this budget does not allow. Assert the real contract.
            assertTrue(
                "expected MAX_TOKENS terminal when the token budget is reached",
                chunks.any { it.isTerminal && it.terminalReason == "MAX_TOKENS" },
            )
```

- [ ] **Step 3: Make the terminal assertion cover both cases**

A model *can* stop early. Assert the weaker, always-true contract alongside the
specific one:

```kotlin
            assertTrue(
                "generation must end with a terminal chunk",
                chunks.any { it.isTerminal },
            )
            assertTrue(
                "terminal reason must be a known value",
                chunks.filter { it.isTerminal }
                    .all { it.terminalReason == "EOF" || it.terminalReason == "MAX_TOKENS" },
            )
```

- [ ] **Step 4: Compile instrumentation without running it**

```bash
./gradlew --no-daemon :app:assembleDevDebugAndroidTest
```
Expected: BUILD SUCCESSFUL. Record that instrumentation is **compiled but not
executed** — the real-model smoke fixtures are not in the tracked tree, so this
test cannot pass here regardless.

- [ ] **Step 5: Check the other smoke assertion in the same file**

`realGenerationCancellationReachesCancelled` (`:49+`) asserts a `Cancelled`
terminal. Verify against `Engine.cpp` that `StreamTerminal::Cancelled` is what
the engine emits for a mid-generation cancel. If that one is also wrong, fix it
here — leaving a second impossible assertion in the file would mean re-running
this whole task.

- [ ] **Step 6: Commit**

```bash
git add app/src/androidTest/java/com/prismai/llmhost/RealInferenceSmokeTest.kt
git commit -m "test(smoke): assert the terminal the engine actually emits

RealInferenceSmokeTest requested maxTokens = 1 and then asserted an EOF
terminal. The engine stops on the token budget and emits MAX_TOKENS
(Engine.cpp:582); EOF requires the model to produce a stop token first,
which a one-token budget does not allow. The assertion could never pass.

Assert MAX_TOKENS for the budget-limited case, and add the invariants
that hold either way: exactly a terminal chunk, with a known reason.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Document what CI does and does not prove

**Why:** AGENTS.md requires that real-inference claims rest on current evidence. Right now the repo has dated evidence docs and a native job that fails, which makes "CI passes" an unsafe thing to assert.

**Files:**
- Create: `docs/qualification/VERIFICATION_STATUS.md`
- Modify: `QA_CHECKLIST.md` (link from the top)

**Interfaces:**
- Consumes: job conclusions from run `37477970025`; the plan's verification results.
- Produces: one place that states what is proven, what is compiled-but-not-run, and what needs hardware.

- [ ] **Step 1: Write the status document**

Create `docs/qualification/VERIFICATION_STATUS.md`:

```markdown
# Verification status

Current as of the reliability sprint. Every claim below is backed by a command
that was actually run, or is explicitly marked as unverified.

## Proven by CI

| Check | How | Notes |
| ----- | --- | ---- |
| JVM unit tests | `:app:testDevDebugUnitTest`, `:app:testPlayDebugUnitTest` | Runs on every push |
| Release-like native builds | `assembleDevBenchmark`, `assemblePlayRelease` | Compiles the RelWithDebInfo native config and R8 rules that debug never touches |
| Native host tests | `ctest` in `native-host-tests` | Covers header-only runtime helpers plus `engine_mock_test`, which runs real `Engine.cpp` on a mock model |
| ASan/UBSan on the Engine target | `engine_mock_test` under `PRISM_ENABLE_SANITIZERS=ON` | Added by the reliability sprint |

## Compiled but NOT executed

- **Android instrumentation.** `assembleDevDebugAndroidTest` compiles
  `app/src/androidTest`, including `RealInferenceSmokeTest`. No emulator or
  device runs them in CI.
- **Real-model smoke fixtures are not in the tracked tree.** The tiny GGUF the
  smoke test requires is not committed, so `RealInferenceSmokeTest` cannot pass
  in CI even after its terminal assertion was corrected.

## Not proven anywhere in CI

- **Real decoding.** `engine_mock_test` executes the real `Engine.cpp` token
  ring, backpressure, cancellation and lifecycle logic, but the mock path
  performs no llama decode. No CI job runs llama.cpp against a real model.
- **Embedding output.** After the decoder-path change, no test asserts that a
  real model produces a correct embedding vector.
- **Device behavior.** Keyboard insets, rotation, chat switching, and memory
  recovery on real hardware are unverified. See the release gate in the sprint
  spec.
- **Memory-pressure recovery on device.** The reconciler's JVM tests cover the
  state machine; whether native actually stops cancelling after recovery needs
  hardware under real memory pressure.

## Known gaps, deliberately not closed here

- Sanitized `Engine.cpp` builds are expensive. If CI cannot afford them at the
  default runner size, they should move to a larger runner rather than be
  dropped.
- The real-model smoke fixtures remain unsupplied. Adding them requires
  committing a model binary or fetching it in CI, which has licensing and
  reproducibility implications that are out of scope for a reliability sprint.

## Release gate

The authoritative gate is the user journey in
`docs/superpowers/specs/2026-10-06-reliability-sprint-design.md`, run on a
qualified APK on physical hardware. CI is a necessary precondition, not a
substitute.
```

- [ ] **Step 2: Link it from the QA checklist**

Add one line at the top of `QA_CHECKLIST.md`:

```markdown
> Current verification status — what CI does and does not prove — is tracked in
> [VERIFICATION_STATUS.md](./qualification/VERIFICATION_STATUS.md).
```

- [ ] **Step 3: Verify the doc's claims before committing**

Re-run each command listed under "Proven by CI" and confirm the results. If any
fails, either fix it or move that row. A status document that overstates
coverage is worse than none.

- [ ] **Step 4: Commit**

```bash
git add docs/qualification/VERIFICATION_STATUS.md QA_CHECKLIST.md
git commit -m "docs: state plainly what CI proves and what it does not

CI compiles Android instrumentation but never executes it, and the
real-model smoke fixtures are not in the tracked tree, so those tests
cannot pass regardless of their assertions. No job runs llama.cpp against
a real model: engine_mock_test exercises the real Engine.cpp control flow
but performs no decode. Keyboard, rotation and memory-recovery behavior
are unverified on hardware.

Record that in one place so 'CI passes' is never mistaken for the app
working, per the repo rule that real-inference claims need current
device evidence.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Full sprint verification

**Why:** the sprint is only as good as its evidence. This closes the loop across all three PRs.

**Files:** none. Verification only.

- [ ] **Step 1: Run the complete gate**

```bash
pwsh ./scripts/verify.ps1
```
If PowerShell is unavailable, run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest
./gradlew --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease
```
Expected: all succeed.

- [ ] **Step 2: Run native host tests both ways**

```bash
cmake --build build/prism-native-tests --parallel 2 && ctest --test-dir build/prism-native-tests --output-on-failure
cmake --build build/prism-native-nosan --parallel 2 && ctest --test-dir build/prism-native-nosan --output-on-failure
```
Expected: both pass.

- [ ] **Step 3: Confirm CI is green on the merged result**

Check that all three jobs pass on the sprint's merge commit:
`Unit Tests`, `Native Builds`, `Native Host Tests`.

- [ ] **Step 4: Run the release gate and fill in the table**

The authoritative gate is the real user journey from the spec: fresh install →
import a verified small GGUF → load → multi-turn streaming → Stop → retry →
background/resume → rotate with a draft → reopen the app. Then document
processing, low-memory recovery, and failed-download recovery. Samsung Keyboard
and Gboard, portrait/landscape, large text, both navigation modes.

**This requires the S25 Ultra and cannot be completed in this environment.**

- [ ] **Step 5: Report honestly**

State exactly which checks ran and passed. Then state plainly what remains
unverified: everything requiring real hardware, real decoding, or real memory
pressure. Do not describe the sprint as complete until the device journey has
been run and recorded.

- [ ] **Step 6: Do not push or open PRs without user approval**