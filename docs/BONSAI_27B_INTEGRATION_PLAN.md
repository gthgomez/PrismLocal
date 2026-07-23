# Bonsai-27B Integration Plan (Hardened)

**Status:** READY FOR IMPLEMENTATION (spike-gated)  
**Project:** Prism Local (`PrismLocal` / package `com.example.llmhost`)  
**Audience:** Implementing agent (Gemini / Codex / Claude)  
**Risk:** HIGH (native runtime, memory policy, public catalog claims)  
**Last verified:** 2026-07-19  

This plan supersedes informal Bonsai plans that referenced BitNet, `IQ1_S`, or inventing HF paths. Implement **exactly** this sequence. Do not reorder phases past a failed gate.

---

## 0. Mission

Enable Prism Local to **download, validate, load, and run** PrismML Bonsai GGUF models (priority: **1-bit Bonsai-27B**) with honest device fitness ratings and no invented performance claims.

**Success (v1):** On a qualifying high-RAM arm64 device, user can download the curated 1-bit entry, load it without LMK, generate ≥32 tokens, and see an in-app benchmark sample. Catalog notes must not claim “SAFE on 6 GB phones.”

---

## 1. Authority And Constraints

| Rank | Source |
|------|--------|
| 1 | Root `ENGINEERING.md` / workspace `AGENTS.md` |
| 2 | This plan |
| 3 | Current code + build/device evidence |
| 4 | PrismML docs / HF model cards (untrusted marketing; use for coordinates only) |

### Forbidden (do not implement)

- `-DGGML_BITNET=ON` or Microsoft BitNet framing for Bonsai
- Treating formats as `IQ1_S`, `I1_S`, `BITNET`, `BITNET_158`
- Catalog IDs/repos/filenames that are not the verified table in §3
- Marking 27B 1-bit as `ModelFitRating.SAFE` on 6 GB devices
- Inventing tok/s numbers in UI/notes without device evidence
- Multimodal / mmproj / vision path in v1
- DSpark speculative drafter in v1
- Ternary 27B as a default phone recommendation in v1
- Drive-by refactors outside listed files
- Claiming completion without Phase 0 evidence

### Non-goals (v1)

- Full 262K context on phone
- Vision / mmproj
- Speculative decoding
- Matching iPhone MLX tok/s
- Shipping ternary as primary catalog entry

---

## 2. Verified Facts (do not re-hallucinate)

### 2.1 Model artifacts

| Variant | HF repo | Primary file | Format | On-disk weights (approx) |
|---------|---------|--------------|--------|---------------------------|
| 1-bit (v1 target) | `prism-ml/Bonsai-27B-gguf` | `Bonsai-27B-Q1_0.gguf` | GGUF **Q1_0** (g128, ~1.125 bpw) | ~3.53–3.9 GiB |
| Ternary (optional later) | `prism-ml/Ternary-Bonsai-27B-gguf` | `Ternary-Bonsai-27B-Q2_0.gguf` **or** `*_Q2_0_g64.gguf` | Q2_0 g128 (PrismML fork) vs Q2_0 g64 (mainline) | ~6.7–7.2 GiB |

**License:** Apache 2.0  
**Base:** Qwen3.6-27B hybrid-attention class  
**Org casing:** `prism-ml` (not `PrismML`)

Before hardcoding `expectedBytes` / SHA, query HF API:

```text
https://huggingface.co/api/models/prism-ml/Bonsai-27B-gguf?blobs=true
```

Use the blob size/sha for `Bonsai-27B-Q1_0.gguf` exactly.

### 2.2 Peak memory (planning priors, not marketing)

Text-only language model, short context (published PrismML / demo tables):

| Build | Weights | ~4K peak | Implication for Android |
|-------|---------|----------|-------------------------|
| 1-bit Q1_0 | ~3.5–3.9 GiB | ~4.8–5.2 GiB | Needs flagship **12–16 GB** total RAM class |
| Ternary Q2_0 | ~6.7–7.2 GiB | ~7.8–8.4 GiB | Not a phone default |

Phone “runs on iPhone” demos use **MLX** and high-RAM Pro devices. Do **not** copy that claim into Android catalog notes.

### 2.3 Current Prism Local blockers (code-backed)

| Blocker | Location | Value |
|---------|----------|-------|
| Load hard cap | `model/ModelManager.kt`, `model/ModelReadinessAssessor.kt` | `MODEL_LOAD_HARD_CAP_BYTES = 3584L * 1024L * 1024L` (~3.50 GiB) |
| Stale docs | `RUNTIME_LIMITS.md` | Still says 3.0 GiB |
| Quant hints | `ModelReadinessAssessor.ggufFileTypeHint` | No Q1_0 / Q2_0 mapping |
| Catalog | `HuggingFaceModelCatalog.kt` | No Bonsai entries |
| Native | `app/src/main/cpp/CMakeLists.txt` | Vendored llama.cpp; KleidiAI optional; **no BitNet flag** |
| Unit tests | `app/src/test/...` | **No** `ModelReadinessAssessorTest` yet |

1-bit weights alone exceed the hard cap → **must change policy** or load always rejects.

### 2.4 Engine compatibility (as of 2026-07)

- **Q1_0 (binary):** mainline llama.cpp support exists; vendored tree already defines `GGML_TYPE_Q1_0` with `QK1_0 = 128`.
- **Q2_0 ternary:** format pairing matters:
  - `*-Q2_0.gguf` (g128) → PrismML fork
  - `*-Q2_0_g64.gguf` → mainline
- Hybrid Qwen3.6 arch must load on the **pinned** llama.cpp commit. If spike fails on arch, bump vendor or defer 27B.

**Do not** assume BitNet kernels are required for Bonsai.

---

## 3. Implementation Phases

Execute in order. Each phase has **entry**, **work**, **exit**. Stop on red exit.

---

### Phase 0 — Spike: can we load at all?

**Entry:** Clean working tree or dedicated branch. Device or host capable of holding ≥6 GiB process memory preferred.

**Work (read-mostly; minimal code only if needed for a one-off load):**

1. Download artifact:
   ```text
   hf download prism-ml/Bonsai-27B-gguf Bonsai-27B-Q1_0.gguf --local-dir <temp>
   ```
   Or browser/API equivalent. Record exact byte size + SHA-256.
2. Attempt load through existing app path if possible (import GGUF → resolve → native load).
   - If hard cap blocks before native: temporarily note that rejection is expected; for spike only, a **local debug override** is allowed **on a feature branch**, must not ship without Phase 2.
3. Capture evidence file under `PrismLocal/validation/` or `PrismLocal/docs/evidence/` (gitignored binaries OK; **commit the text log**):
   - Device model, total/available RAM
   - Load result (success / error string)
   - Peak RSS or Android memory dump if available
   - Context length used
   - Tokens generated + wall ms (if any)
   - llama.cpp / libllmhost identity if logged

**Exit GREEN:** Model loads and emits ≥1 token **or** failure is fully diagnosed (quant vs arch vs OOM vs cap) with log excerpt.

**Exit RED / STOP:** Unknown crash without diagnosis. Do not open catalog PR.

**Deliverable:** `docs/evidence/bonsai-27b-phase0-<date>.md` with pass/fail and next-phase decision:

| Spike result | Next |
|--------------|------|
| Loads on current vendor tree | Phase 1A (no fork) |
| Fails quant/arch missing | Phase 1B (bump mainline or evaluate PrismML fork) |
| Only OOM / hard cap | Phase 2 first, re-spike load |
| Unfixable without huge fork risk | Pivot to Bonsai-8B/4B (same formats, smaller) — see §7 |

---

### Phase 1 — Native engine readiness

**Goal:** Production engine path that can initialize Q1_0 Bonsai without experimental BitNet flags.

#### 1A — Prefer mainline-compatible path (default)

**Files (only if required by spike):**

- `app/src/main/cpp/CMakeLists.txt` — vendor commit pin update only if needed
- `app/src/main/cpp/third_party/llama.cpp/**` — vendor bump (large; isolate commit)
- `app/src/main/cpp/Engine.cpp` / `Engine.hpp` — only if load options (mmap, n_ctx, kv) need Bonsai-safe defaults
- `app/src/main/cpp/llmhost_jni.cpp` — only if JNI surface must expose new load diagnostics

**Rules:**

- Keep `GGML_OPENMP OFF` on Android.
- Prefer existing KleidiAI path for arm64 if already used (`LLMHOST_ENABLE_KLEIDIAI`).
- Do **not** add `GGML_BITNET`.
- Do **not** enable OpenCL unless already validated for this model.
- After vendor bump: `assembleDebug` must pass; existing real-inference smoke must not regress.

#### 1B — Fork path (only if 1A cannot load ternary later or Q1_0 needs PrismML-only kernels)

**Approval required before merging fork:** explicit user OK (HIGH risk).

If approved:

- Document exact PrismML-Eng/llama.cpp branch + commit in CMake comment / README note.
- Prefer subtree/vendor replace with recorded commit, not ad-hoc file copies.
- ABI: do not mix stock ggml with PrismML ggml.

**Exit GREEN:** Debug APK loads `Bonsai-27B-Q1_0.gguf` through normal import path (after Phase 2 cap change if needed) and generates ≥32 tokens on device.

---

### Phase 2 — Memory policy (mandatory for 27B)

**Problem:** Cap 3584 MiB < ~3.8 GiB weights.

**Files:**

- `app/src/main/java/com/example/llmhost/model/ModelManager.kt`
- `app/src/main/java/com/example/llmhost/model/ModelReadinessAssessor.kt`
- `RUNTIME_LIMITS.md` (align with code)

**Required policy (v1):**

1. **Hard cap:** Raise enough for 1-bit 27B weights **only if** readiness still fail-closes when projected peak RAM is unsafe. Suggested starting point:
   - `MODEL_LOAD_HARD_CAP_BYTES = 4200L * 1024L * 1024L` (or exact weight + small margin)
   - Keep **both** `ModelManager` and `ModelReadinessAssessor` constants **identical** (extract shared constant if clean and ≤3-file discipline allows; otherwise duplicate with same value + comment “keep in sync”).
2. **Do not** set ternary 27B as loadable by default until Phase 5 optional work.
3. **Fitness ratings (enforced in `estimateModelFit`):**
   - Prefer required RAM = `modelBytes + runtimeOverhead + contextEstimate + threadScratch` (existing formula), with Q1_0-specific overhead multiplier (packed weights; **do not** assume FP16 expand).
   - Device tiers (encode in reason strings / notes, not only enums):
     - ≤8 GB total device RAM class → 27B 1-bit is **TOO_LARGE** or strongly **RISKY** (prefer TOO_LARGE unless measured available RAM after unload clears safeBudget with margin).
     - 12 GB class → at most **RISKY** at short context defaults.
     - 16 GB+ → may be **SAFE** only if `requiredRam <= safeBudget` with real available RAM.
   - **Never** force `SAFE` solely because file size is 3.9 GB.
4. Update `RUNTIME_LIMITS.md` load-size section to match the new cap and explain peak ≠ file size.

**Exit GREEN:** Unit tests prove: (a) 3.9 GiB Q1_0 is not auto-capped solely by old 3584 MiB if policy raised; (b) low available RAM still yields TOO_LARGE/RISKY; (c) constants documented.

---

### Phase 3 — Quant recognition And readiness math

**Files:**

- `app/src/main/java/com/example/llmhost/model/ModelReadinessAssessor.kt`
- New: `app/src/test/java/com/example/llmhost/model/ModelReadinessAssessorTest.kt` (create; plan previously invented this file)

**Work:**

1. Extend `ggufFileTypeHint` for Q1_0 / Q2_0 (and TQ1_0 if enum present and cheap). Use llama.cpp / GGUF `general.file_type` integer values from vendored headers — **read headers, do not guess**.
2. Extend `quantizationHint` regex if needed so filenames like `Bonsai-27B-Q1_0.gguf` parse as `Q1_0`.
3. `quantizationOverheadMultiplier` / `quantizationSpeedMultiplier`:
   - Q1_0: low storage overhead factor (weights stay packed); speed factor conservative default (e.g. slightly better than Q4 bandwidth-wise but **do not** claim 11 tok/s).
   - Q2_0: similar conservative defaults.
4. Optional: if metadata exposes huge `contextLength` (262K), clamp active context used in estimates to app max (`GenerationSettings` max, currently 16384) so estimates stay mobile-real.

**Exit GREEN:** Unit tests cover quant parse + fit rating for synthetic model sizes without device.

---

### Phase 4 — Curated catalog (only after Phase 0–1 load path is known good)

**File:**

- `app/src/main/java/com/example/llmhost/HuggingFaceModelCatalog.kt`

**v1 entries (minimum one; second optional):**

```kotlin
HuggingFaceModelEntry(
    id = "bonsai_27b_q1_0",
    name = "Bonsai 27B (1-bit Q1_0)",
    repoId = "prism-ml/Bonsai-27B-gguf",
    fileName = "Bonsai-27B-Q1_0.gguf",
    expectedBytes = /* exact from HF API */,
    expectedSha256 = /* if available from HF; else null */,
    license = "Apache-2.0",
    parameters = "27B",
    quantization = "Q1_0 (1.125 bpw)",
    notes = "Flagship devices only (prefer 12–16 GB RAM). Peak memory exceeds file size (~5 GiB class at short context). Text-only in this build. Quality is strong for size but not full FP16.",
),
```

**Optional smaller ladder (recommended if 27B spike is painful):**

| id | repo | purpose |
|----|------|---------|
| `bonsai_8b_q1_0` | `prism-ml/Bonsai-8B-gguf` | Mid-range validation |
| `bonsai_4b_q1_0` | `prism-ml/Bonsai-4B-gguf` | Broad device default candidate |

Confirm exact filenames on HF before adding (same Q1_0 naming pattern expected).

**Ternary catalog (Phase 5 only):**

- Separate repo `prism-ml/Ternary-Bonsai-27B-gguf`
- File must match engine path (g64 vs g128)
- Notes must say laptop/high-RAM only

**Do not** use:

- `PrismML/Bonsai-27B-GGUF`
- `bonsai-27b-1bit.gguf`
- “~11 tok/s” in notes without measured Android evidence

**Exit GREEN:** Catalog entry resolves; download worker can fetch metadata (network); import succeeds on device when user confirms.

---

### Phase 5 — Optional ternary / polish

Only after v1 1-bit is green:

1. Choose ternary file (`Q2_0_g64` vs fork `Q2_0`).
2. Raise hard cap further **only** with readiness FAIL on insufficient RAM.
3. Catalog entry with strong warnings.
4. Optional UI: device-tier badge in control plane (only if already easy; no redesign).

---

### Phase 6 — Verification matrix (required for “done”)

Run from `PrismLocal`:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon assembleDebug
```

If device available:

```powershell
.\gradlew.bat --no-daemon assembleDebugAndroidTest
.\gradlew.bat --no-daemon connectedDebugAndroidTest
```

| Check | Pass criteria |
|-------|----------------|
| Unit | New readiness tests pass; existing unit tests pass |
| Build | `assembleDebug` succeeds |
| Load | 1-bit GGUF imports, resolves, native load OK on target device |
| Gen | ≥32 tokens, terminal reason EOF or MAX_TOKENS |
| Fit | Low-RAM synthetic case not marked SAFE |
| Catalog | Correct repo/file; download metadata verifies size |
| Docs | `RUNTIME_LIMITS.md` matches hard cap |
| No BitNet | CMake has no `GGML_BITNET` for this work |
| Regression | Tiny model smoke still valid if instrumentation run |

**Completion report template (mandatory):**

```text
RISK: HIGH
STATUS: done | partial | blocked

CHANGED FILES:
- ...

PHASE0 EVIDENCE:
- path + load result

VERIFICATION:
- Command: ...
- Result: pass/fail
- Evidence: ...

OPEN RISKS:
- ...
```

---

## 4. File Touch Budget

| Phase | Expected files | Notes |
|-------|----------------|-------|
| 0 | evidence markdown only | Code only if temporary debug override |
| 1 | CMake + vendor tree and/or Engine | Vendor bump = large isolated commit |
| 2 | ModelManager, ModelReadinessAssessor, RUNTIME_LIMITS.md | Shared constant preferred |
| 3 | ModelReadinessAssessor + new unit test | |
| 4 | HuggingFaceModelCatalog.kt | |
| 5 | catalog + maybe cap | Optional |

If a change exceeds this map, stop and re-plan.

---

## 5. Suggested PR / Commit Sequence

1. `docs: bonsai phase0 evidence` (or spike notes)
2. `native: enable Q1_0 Bonsai load` (vendor/engine only if needed)
3. `fix: raise model load budget + readiness for Q1_0`
4. `test: ModelReadinessAssessor Q1_0 / cap policy`
5. `feat: catalog Bonsai-27B Q1_0 entry`
6. Optional ternary / smaller Bonsai ladder

Do not squash evidence away. Do not mix vendor bump with catalog in one messy commit if avoidable.

---

## 6. Agent Operating Rules (Gemini-specific)

From project `AGENTS.md`:

- Do not invent JNI signatures.
- Do not invent CMake ABI flags.
- Verify llama.cpp symbols against **vendored** headers under `app/src/main/cpp/third_party/llama.cpp/`.
- Verification gate for native: `assembleDebug` at minimum.

Additional:

- Prefer reading files over assuming prior chat plans.
- If HF filenames differ from this plan, **trust live HF API**, update catalog accordingly, note in completion report.
- If spike cannot obtain the 27B file (auth/private), fall back to public smaller Bonsai size with same quant format and document the pivot.

---

## 7. Pivot Ladder (if 27B is blocked)

Implement in this order until one GREEN load exists:

1. `Bonsai-8B` Q1_0 GGUF  
2. `Bonsai-4B` Q1_0 GGUF  
3. `Bonsai-1.7B` Q1_0 GGUF  

Then ship catalog + readiness for that size; keep 27B as experimental/flagship follow-up. Same phases 2–4 apply with smaller caps.

---

## 8. Acceptance Criteria (product)

**Must have:**

- [ ] Phase 0 evidence file committed (text)
- [ ] Q1_0 Bonsai loads on at least one real arm64 device or documented emulator limitation
- [ ] Hard cap + readiness updated; low RAM fail-closed
- [ ] Catalog entry with correct `prism-ml/...` coordinates
- [ ] Unit tests for readiness policy
- [ ] `assembleDebug` + unit tests green
- [ ] Notes do not claim 6 GB SAFE or invented tok/s

**Nice to have:**

- [ ] Smaller Bonsai catalog entries
- [ ] In-app benchmark sample recorded
- [ ] Ternary entry with correct g64/g128 choice

**Explicitly out of scope until separate plan:**

- [ ] Vision mmproj
- [ ] Speculative DSpark
- [ ] 100K+ context on phone
- [ ] BitNet models

---

## 9. Decision Log (pre-filled)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Primary format | Q1_0 GGUF, not BitNet/IQ1 | PrismML shipping format |
| v1 model | 1-bit 27B flagship; ternary deferred | RAM + format risk |
| Engine default | Mainline/vendor bump first | Lower long-term cost than full fork |
| Catalog order | After load proof | Avoid 404/OOM product traps |
| Fitness | Peak-aware, never SAFE-by-filesize | Android LMK risk |
| Performance claims | Measure only | No marketing numbers in UI |

---

## 10. Quick Reference — Correct Catalog Coordinates

```text
1-bit 27B:
  repo:  prism-ml/Bonsai-27B-gguf
  file:  Bonsai-27B-Q1_0.gguf

Ternary 27B (later):
  repo:  prism-ml/Ternary-Bonsai-27B-gguf
  file:  Ternary-Bonsai-27B-Q2_0.gguf        # PrismML fork path
     or  Ternary-Bonsai-27B-Q2_0_g64.gguf    # mainline path — prefer if engine is mainline
```

Fork reference (only if Phase 1B): `https://github.com/PrismML-Eng/llama.cpp` branch `prism`.  
Demo/docs: `https://github.com/PrismML-Eng/Bonsai-demo`, `https://docs.prismml.com/models/bonsai-27b`.

---

## 11. First Action For Implementing Agent

1. Create branch (e.g. `feat/bonsai-q1-integration`).
2. Execute **Phase 0 only**.
3. Write `docs/evidence/bonsai-27b-phase0-<date>.md`.
4. Report GREEN/RED with evidence path; only then continue Phase 1–4.

Do not start with catalog edits.
