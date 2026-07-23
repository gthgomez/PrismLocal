# Bonsai-27B Phase 0 — Evidence Log

**Date:** 2026-07-19 (updated with code corrections)  
**Plan:** `docs/BONSAI_27B_INTEGRATION_PLAN.md`  
**Target:** `prism-ml/Bonsai-27B-gguf` / `Bonsai-27B-Q1_0.gguf`

---

## Status

| Gate | Result |
|------|--------|
| Artifact coordinates verified (HF API) | **PASS** |
| Engine type support (static code audit) | **PASS** (Q1_0 present in vendored tree) |
| Preflight hard cap allows weights | **PASS** (code + unit tests) |
| Readiness / quant mapping correct | **PASS** (code + unit tests) |
| Catalog entry correct | **PASS** (code + unit tests) |
| **Device download + native load + ≥1 token** | **NOT RUN** |
| Device ≥32 tokens / in-app benchmark | **NOT RUN** |

**Phase 0 load spike: OPEN** — packaging and policy are ready; **runtime load on a physical arm64 device is still required** before claiming product v1 complete.

---

## 1. HF artifact (API, not marketing)

Queried: `https://huggingface.co/api/models/prism-ml/Bonsai-27B-gguf?blobs=true`

| Field | Value |
|-------|--------|
| File | `Bonsai-27B-Q1_0.gguf` |
| Size (bytes) | `3803452480` (~3.54 GiB) |
| SHA-256 | `17ef842e47450caeb8eaa3ebfbbab5d2f2278b62b79be107985fb69a2f819aa0` |
| Architecture (card) | `dspark` (hybrid-attention Qwen3.6 class) |
| License | Apache-2.0 |

---

## 2. Static engine audit (no process load)

Vendored `llama.cpp` pin: `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`

- `GGML_TYPE_Q1_0` / `QK1_0 = 128` present
- `LLAMA_FTYPE_MOSTLY_Q1_0 = 40` in `llama.h`
- **No** `GGML_BITNET` in project CMake (correct; Bonsai is Q1_0, not BitNet)
- Hybrid `dspark` arch: **load success not proven** until device spike

---

## 3. Code policy (implemented)

| Item | Value |
|------|--------|
| Hard cap | `ModelLoadLimits.HARD_CAP_BYTES` = 4200 MiB (Manager + Assessor) |
| Large-model available gate | &lt;6 GiB free → TOO_LARGE; &lt;10 GiB free → at most RISKY |
| `file_type` Q1_0 | **40** (not 24 — 24 is IQ1_S) |
| Catalog | `bonsai_27b_q1_0` with exact size + SHA |

---

## 4. Automated verification (this workspace)

```text
cd PrismLocal
.\gradlew.bat --no-daemon :app:testDebugUnitTest assembleDebug
# BUILD SUCCESSFUL in 24s (2026-07-20) — includes BonsaiModelFitTest
```

Unit coverage: catalog coordinates + SHA, hard cap vs Bonsai size, `ggufFileTypeHint(40)=Q1_0` / `24=IQ1_S`, low/mid/high available-RAM ratings.

---

## 5. Device spike checklist (remaining — operator)

On a **12–16 GB class** arm64 device with ≥8 GiB free storage:

1. Install debug APK from `assembleDebug`
2. Download curated **Bonsai 27B (1-bit Q1_0)** (or sideload GGUF)
3. Confirm SHA matches expected
4. Load model; record logcat for load success/fail
5. Generate ≥32 tokens; record tok/s and peak RSS if available
6. Append section below with device model, Android version, total/available RAM, result

```text
### Device run (fill in)
- Device:
- Total / available RAM before load:
- Load result:
- Tokens / wall ms:
- Peak RSS / LMK:
- Log excerpt:
```

---

## 6. Decision for next phase

| If device load… | Then |
|-----------------|------|
| Succeeds | Phase 0 **GREEN**; keep catalog; optional in-app benchmark |
| Fails quant/arch | Phase 1B (vendor bump / fork) |
| Fails OOM only | Tighten fitness further; do not market as mid-range |
| Not yet run | **Do not claim complete** — STATUS partial |

---

## Conclusion

Policy, catalog, and quant mapping are aligned with the hardened plan and unit-tested.  
**Runtime Phase 0 (actual GGUF load) remains incomplete.** Product messaging must stay “flagship / experimental until device smoke.”
