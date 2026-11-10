# Inference root-cause report

Status: **CPU numerical reference established. Device qualification blocked.**
Branch: `cursor/inference-root-cause-e5d3`, based on `main` (`97d6e449ad5bb8e527ce1e88a30a337c696bfd31`).
This report does not claim that a Galaxy S25 Ultra now generates correct text. No phone, `adb`, Android SDK, or NDK was available in the investigation environment.

## 1. Executive engineering verdict

The pinned llama.cpp commit produces a correct greedy continuation for the exact Qwen2.5 0.5B Instruct Q4_K_M and Q8_0 bytes, including when the decode loop is the PrismLocal loop (full-prompt ChatML tokenization, logits only on the last prompt token, `llama_sampler_sample(ctx, -1)`, then a KV-prefix second turn).

That holds on:

- x86-64 with `GGML_CPU_REPACK` placing 28 MiB of Q4_K weights in `CPU_REPACK`
- qemu-aarch64 baseline `armv8-a` (NEON, no dotprod, no Kleidi kernels, no repack buffer)
- qemu-aarch64 `armv8.6-a+dotprod+i8mm` with KleidiAI and `CPU_REPACK` both populated

The historical conclusion that K-quant kernels are the isolated cause is rejected. Q4_K_M and Q8_0 emit the same greedy token ids. KleidiAI at this commit does not implement Q4_K; the Q4_K tensors that moved to `CPU_REPACK` still decoded to "Paris".

The multilingual salad was not reproduced. The first confirmed product defect that can still put a default install on a different executor than this reference is the load policy: `useVulkan=true` with `gpuLayers=0` left `llama_model_params.devices` null and `op_offload` at its default. llama.cpp then keeps every enumerated GPU and can run host-weight matmuls on that GPU (`ggml-backend.cpp` scheduler cause `"1.off"`) while PrismLocal's backend string still says KleidiAI or CPU, because that string only reflected `n_gpu_layers`. The S25 Ultra is an Adreno device, and `patches/llama.cpp/ggml-vulkan-local-build.patch` already exists to avoid Qualcomm `mul_mat` paths. That patch is not in the submodule gitlink.

This is a confirmed control defect and a supported device hypothesis. It is not a device-reproduced proof. PR #42 said Vulkan off failed as well, and this environment cannot repeat that run. The log line they trusted cannot distinguish "CPU pinned" from "Vulkan offloading host weights".

Separate defects, also fixed here, do not produce the salad:

- Llama 3 lost `<|begin_of_text|>` on every full-prompt tokenization once the KV cache was warm, and on the first turn whenever the prompt began with `<|start_header_id|>`.
- `llama_detokenize` on each drain deleted the leading space of the first piece in that drain.
- Frequency and presence penalties were hardcoded to 0.2 with no setting.
- Temperature was clamped to a minimum of 0.05, so a requested greedy decode was still sampled.

## 2. Original failure reproduction

Not reproduced.

Historical record, taken from PR #42 `docs/verification/inference-qualification.md` at `9f2aa4540e4feff28d8bfab34e8e4e3437432733` and treated as evidence, not as a conclusion:

- Device claimed: Samsung SM-S938U, Android 17, `arm64-v8a`, `devDebug`, `com.prismai.llmhost.dev.debug`, label "ARM KleidiAI", `n_ctx=2048`, `n_batch=256`, 4 threads.
- Qwen2.5 0.5B Instruct Q4_K_M and Llama 3.2 1B Instruct Q4_K_M produced token salad ("cincinnati", "itionally", "Ann") from prompts whose ChatML rendering was reported as 34 tokens, with finite logits (max about 7.8–20.5).
- Q8_0 once answered "It's Paris, by the way..." and later, after `pm clear` and a byte-identical file, produced salad. SHA-256 `ca59ca7f13d0e15a8cfa77bd17e65d24f6844b554a7b6c12e07a5f89ff76844e`, 675,710,816 bytes.

What this investigation ran instead is in sections 5–8. The Q8 sentence above is not the greedy reference. The greedy reference is "The capital of France is Paris." A single plausible sampled sentence does not show that Q8 used a different kernel from Q4.

## 3. Inference architecture

A chat send takes this path on current `main`:

1. Compose UI submits the draft to `InferenceService`.
2. `GenerationOrchestrator.generate` builds `ChatMessage`s through `PromptBuilder`. The system string is "You are Assistant in a local Android chat. Use the recent conversation for context." The ordinary chat path does not call `buildRagContext`.
3. `NativeLlmBridge.generateChat` passes roles, contents, and sampling fields across JNI. KV type, flash attention, and `useVulkan` are load-time arguments (`nativeLoadModelWithSettings`), not per-token arguments.
4. `Engine::startGenerationChat` sanitizes the config and runs `runRealGeneration` on a worker thread.
5. `formatMessagesForGeneration` calls `llama_chat_apply_template` (the legacy detector, not Jinja). The Qwen template contains `<|im_start|>`, so the detector selects ChatML. The rendered prompt for the capital question is 187 bytes and 34 tokens.
6. Special-token policy decides `add_special`. Qwen's `tokenizer.ggml.add_bos_token` is false, and the prompt already starts with `<|im_start|>`, so no extra BOS is inserted.
7. `decodeTokensAt` evaluates the prompt in batches of `n_batch`. Only the last token of the whole prompt has `logits=1`. Positions start at `current_position`. Sequence id is 0.
8. On a cache miss, `resetRuntimeContext` calls `llama_memory_clear`. On a hit, `shrinkCommittedPrefix` calls `llama_memory_seq_rm` for the divergent tail before the suffix is decoded.
9. A new sampler chain is built. `llama_sampler_sample(ctx, -1)` samples and accepts once. The token is written to the ring, then decoded at `current_position`, then appended to `active_tokens`.
10. `decodeTokensChecked` turns drained ids into text. JNI copies the int32 ids and the UTF-8 text. Kotlin persists that text.

Mutable state that a normal `llama-cli` loop does not own: `current_position`, `active_tokens`, `ConversationState` (identity, reusable length, last-logits flag), the single-token batch, the owned sampler, and the ring-buffer control block. The worker is joined under `GenerationLifecycleGate` before the next session. The ring is atomic; the model pointer is guarded by `mu`.

`encode()` sets `llama_set_embeddings(true)` and sets it back to false on the pooling-unsupported path, the decode-failure path, and the success path. The chat orchestrator path that was read does not call it before `generateChat`.

Requested versus executed, before this change:

| Knob | Kotlin default | What a default load actually did |
| --- | --- | --- |
| Temperature | 0.70, clamped to at least 0.05 | A caller that passed 0 still sampled at 0.05 |
| GPU | `useVulkan=true`, `gpuLayers=0` | `n_gpu_layers=0`, devices not pinned, `op_offload` and `offload_kqv` left true |
| Backend string | "ARM KleidiAI" when the Kleidi macro is compiled | Compile-time label. It does not mean a Kleidi kernel ran |
| KV / flash attention | f16 / f16, flash attention off, passed at load | Context uses those load values. The C++ `GenerationConfig` defaults (q8_0 KV, flash attention on) apply only if a caller omits them |
| Penalties | UI exposes repeat penalty only (default 1.10) | Chain also applied frequency 0.2 and presence 0.2 |
| Batch / threads / context | 256 / 4 / 2048 | Passed through JNI and used |

Kleidi's own sources at this commit accelerate Q4_0 and Q8_0. A Q4_K_M file still contains some q8_0 tensors, so a Kleidi buffer can be non-empty for that file. The q4_K tensors are not Kleidi matmuls. An NDK build with empty `ARCH_FLAGS` (baseline `armv8-a`) compiles neither the dotprod/i8mm Kleidi kernels nor the ARM repack kernels, because those bodies are under `__ARM_FEATURE_DOTPROD` or `__ARM_FEATURE_MATMUL_INT8`. The baseline ARM reference in section 8 is that configuration, and it is numerically correct. "Disable Kleidi" cannot be the Q4_K fix.

## 4. Provenance

| Item | Value |
| --- | --- |
| App commit this branch starts from | `97d6e449ad5bb8e527ce1e88a30a337c696bfd31` |
| llama.cpp gitlink and the independent clone | `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3` |
| Q4_K_M file | `qwen2.5-0.5b-instruct-q4_k_m.gguf`, 491,400,032 bytes, SHA-256 `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` |
| Q8_0 file | `qwen2.5-0.5b-instruct-q8_0.gguf`, 675,710,816 bytes, SHA-256 `ca59ca7f13d0e15a8cfa77bd17e65d24f6844b554a7b6c12e07a5f89ff76844e` |
| GGUF | V3, architecture `qwen2`, file type Q4_K - Medium for the Q4 file. Tensor mix on Q4: f32 121, q5_0 133, q8_0 13, q4_K 12, q6_K 12. `n_ctx_train` 32768, `n_embd` 896, `n_layer` 24, `n_head` 14, `n_head_kv` 2, `n_swa` 0. `add_bos_token=false`, eos 151645, bos/pad 151643 |
| Host reference | Ubuntu g++ 13.3.0, x86-64. llama.cpp built with `GGML_NATIVE=OFF`, Vulkan off, OpenMP off |
| ARM reference | `aarch64-linux-gnu-g++` 13.3.0 under `qemu-aarch64-static` 8.2. Baseline CPU `neoverse-n1` with no ARM arch flags. Kleidi CPU `max` with `-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm+fp16` and `GGML_CPU_KLEIDIAI=ON` |
| This environment | x86-64, JDK 21, `adb` absent, `ANDROID_HOME` unset, NDK absent. The project asks for JDK 17 and NDK 28.2.13676358 |

The Vulkan patch under `patches/llama.cpp/` is a working-tree patch. It is not part of the gitlink. The CPU reference builds did not apply it and did not link Vulkan. A clean Android build that enables Vulkan still needs that patch for shader generation and for the Adreno `mul_mat` workaround. GPU execution remains unqualified.

Llama 3.2 1B was not downloaded. Q4 and Q8 of the same Qwen already agreed, so a second architecture was not required to reject a Qwen-only or Q4-only explanation of the host results.

## 5. Reference results

Common settings: context 2048, batch 256, flash attention off, F16 KV, `n_gpu_layers=0`, no grammar, no LoRA, fresh context, greedy (`temp=0`, `top_k=1`, repeat 1, frequency 0, presence 0, seed 1). System prompt as in section 3. User prompt "The capital of France is".

`llama-completion` and the PrismLocal-loop probe agree.

Rendered prompt:

```
<|im_start|>system
You are Assistant in a local Android chat. Use the recent conversation for context.<|im_end|>
<|im_start|>user
The capital of France is<|im_end|>
<|im_start|>assistant
```

Prompt ids (34):

```
151644,8948,198,2610,525,21388,304,264,2205,8514,6236,13,5443,279,3213,10435,369,2266,13,151645,198,151644,872,198,785,6722,315,9625,374,151645,198,151644,77091,198
```

Greedy generated ids: `785,6722,315,9625,374,12095,13`
Text: `The capital of France is Paris.`

First-token top of the distribution after prefill (x86, Q4, repack on):

| Rank | Id | Logit | Piece |
| --- | --- | --- | --- |
| 1 | 785 | 19.022085 | The |
| 2 | 59604 | 18.336630 | Paris |
| 3 | 1782 | 15.659930 | the |
| 4 | 40 | 15.027674 | I |
| 5 | 39572 | 14.794178 | London |

Other greedy checks on the same Q4 x86 build: "What is two plus two?" -> "Two plus two is four." "Continue this sequence: 1, 2, 3," -> "4, 5" and then more digits under `llama-completion`.

The checked-in fixture is `tools/inference-conformance/fixtures/qwen2.5-0.5b-instruct-greedy.txt`. `tools/inference-conformance/run-probe.sh` rebuilds the probe and exits 1 if those ids or texts move. It exits 77 when `MODEL` is unset, so CI without a 500 MB weight is a skip. Both the Q4 and Q8 files matched the fixture on x86 during this investigation (`greedy fixture matched`).

## 6. Prompt and token equivalence

On the reference, the app system prompt plus "The capital of France is" is the 187-byte ChatML string above, `add_special=0`, 34 tokens. That count matches the count recorded on the device. The device note did not publish the 34 ids, so id-level equality with the phone is not established. Id-level equality between `llama-completion` and the PrismLocal loop is established: both print the same id list.

Qwen does not want a leading BOS (`add_bos_token=false`). Forcing the special-token policy from `InferencePolicy.hpp` still leaves `add_special=0` for this ChatML prompt. Llama 3 is the opposite case and is covered by the host test, not by a Llama weight: a full prompt that starts with `<|start_header_id|>` must keep tokenizer BOS; ChatML, `[INST]`, `<s>`, and an existing `<|begin_of_text|>` must not.

No prompt-duplication or history bug appeared on the fresh first turn. The second turn re-templates system + user + assistant + new user, shares a prefix of 41 tokens with the committed cache, and decodes a suffix of 16.

## 7. First divergent logit

There is no divergent logit on the paths that were executed.

Argmax after prefill is token 785 on every CPU configuration below. The largest absolute gap versus the x86 Q4 logit 19.022085 is 0.172 (Kleidi+repack Q4, 19.193520). Rank order stays "The" then "Paris" except where a later candidate swaps; the argmax does not move. Generated ids stay `785,6722,315,9625,374,12095,13`.

Teacher-forced second turn, same predetermined boundary (the tokens the first turn actually emitted, then the new user question): argmax 11613 "Two", text "Two plus two is four." on x86 Q4, x86 Q8, ARM baseline Q4, ARM Kleidi Q4, and ARM Kleidi Q8.

The device report's max logit (about 7.8–20.5) includes 19.0, so "finite and about this large" is what a correct distribution looks like. The report did not record the argmax id. A salad string means the sampled ids were not 785,6722,... . That comparison needs a new device log that prints the id.

Sampling on a correct distribution does not create the salad. With the old app chain (temp 0.70, top-k 40, top-p 0.95, repeat 1.10, frequency 0.2, presence 0.2) the same Q4 x86 logits produced:

- seed 1: an English refusal that asks for more detail ("To provide you with a response...")
- seed 2: "The capital of France is Paris."
- seed 7: "The capital of France is Paris."
- temp 0.05, seed 1, same penalties: "The capital of France is Paris."

Penalties do not touch the first token. The sampler history starts empty; prompt tokens are not accepted into it. A wrong first piece is a logit problem or a later detokenize problem, and detokenize of 785 is "The", not "cincinnati".

## 8. CPU, KleidiAI, and repack matrix

Probe loop unless noted. Greedy. Same model bytes and the same 34 prompt ids.

| Variant | Build | Buffers actually used | Threads | Top-1 logit | Greedy text |
| --- | --- | --- | --- | --- | --- |
| Repack on, Kleidi off | x86, `GGML_CPU_REPACK` default | `CPU_REPACK` 28.05 MiB, q4_K_8x8 on `ffn_down` | 1 and 4 (4-thread run logged the same 19.022085 top logit) | 19.022085 | Paris |
| Repack off | x86 `llama-completion --no-repack` | no extra repack buffer | 1 | not re-printed | Paris |
| Repack flag on, kernels compiled out | ARM baseline, no dotprod/i8mm | all 291 tensors rejected for `CPU_REPACK`; `CPU_Mapped` 462.96 MiB | 1 | 18.995893 | Paris |
| Kleidi on, repack on | ARM `armv8.6-a+dotprod+i8mm+fp16` | Q4: `CPU_KLEIDIAI` 139.34 MiB + `CPU_REPACK` 68.97 MiB. Log: `primary q4/q8 kernel feature I8MM` | 1 (probe); 4 (`llama-completion`) | 19.193520 | Paris |
| Kleidi on, repack off | ARM Kleidi `llama-completion --no-repack` | repack disabled at load | 1 | not re-printed | "Two plus two is four." |
| Q8, Kleidi on | same ARM Kleidi build | `CPU_KLEIDIAI` 500.52 MiB | 1 (probe); 4 (`llama-completion` sequence prompt) | 19.133930 | Paris. Sequence prompt at 4 threads: "4, 5, 6, 7" |

`use_extra_bufts` defaults true and is the runtime face of repack. On the baseline ARM compile it is a flag without a usable kernel, and the loader says so and keeps CPU buffers. Turning the flag off was not required for a correct answer. This change does not force `GGML_CPU_REPACK` off and does not force `use_extra_bufts` off.

Flash attention off versus auto was checked with `llama-completion` on x86 for this model (`n_swa=0`). Both answered Paris. The app already sets flash attention disabled when the load settings say so.

A GitHub issue search from this environment for "repack garbled", "KleidiAI Q8_0", and "Adreno mul_mat" returned no hits. No upstream issue number is claimed as this bug. The Adreno workaround already in `patches/llama.cpp/ggml-vulkan-local-build.patch` is the local evidence that Vulkan `mul_mat` on Qualcomm was already known to misbehave.

## 9. KV cache and lifecycle

Executed:

- Fresh load, one greedy generation: correct.
- Same context, second question, prefix 41, `llama_memory_seq_rm` of the tail, suffix 16: correct on Q4 and Q8, x86 and both ARM builds.
- Repeated independent contexts (the probe frees the context per run; `llama-completion` was one-shot): the same ids come back.

Not executed, because they need the full `Engine` process or a device:

- Cancel mid-decode, then regenerate.
- Switch model in one process.
- Force-stop and reopen.
- Sliding-window compaction after the context is actually full.
- `encode()` followed by generate.

Code review of the first-turn miss path: `common_prefix == 0` calls `resetRuntimeContext`, which clears the KV cache and invalidates `ConversationState` before prefill. A failed prefill invalidates the cache. A failed suffix retries once from a cleared cache. Those branches were not the ones that produced the reference tokens; the successful path was.

The BOS decision used to be `(current_position == 0) || !cache.valid`, and then a marker prefix could force it off. After turn 1, position is non-zero and the cache is valid, so turn 2 tokenized the full Llama 3 prompt with `add_special=false`, the BOS id failed the prefix compare, and the replay ran without `<|begin_of_text|>`. The engine tokenizes the full rendered prompt on every turn, so the decision now always uses `shouldAddSpecialTokens(prompt, true)`. A suffix tokenizer must pass `false`. Qwen is unchanged because ChatML suppresses BOS either way.

## 10. Q8_0 inconsistency

Both quantizations, same prompt ids, same greedy ids, on x86 and on ARM Kleidi. Q8 top logit 19.023218 (x86) and 19.133930 (ARM Kleidi). The "one coherent Q8 answer" in the historical note is not this greedy string. It is a different wording ("It's Paris, by the way") plus an unrelated second sentence, which is what temperature 0.7 can do on a working model and what a lucky sample can do on a broken one. It does not separate Q8 from Q4.

Nothing in the host matrix makes Q8 fail after a reload. The post-`pm clear` salad used a byte-identical file. That rules out "the file changed" and does not rule out "the process took the Vulkan op-offload path" or "the NDK binary differs from this gcc/qemu build". Those remain open because they were not runnable here.

## 11. Hypothesis ledger

| Hypothesis | Evidence for | Evidence against | Experiment | Outcome | Confidence |
| --- | --- | --- | --- | --- | --- |
| Q4_K kernels are uniquely wrong | Historical salad on Q4_K_M | Q8 emits the same greedy ids; Q4_K repack and non-repack both say Paris | Section 8 | Rejected as the sole cause | High for the host/qemu builds |
| KleidiAI Q4 kernel | "ARM KleidiAI" in the device log | This commit has no Q4_K Kleidi kernel. Baseline ARM with Kleidi compiled out is correct. Kleidi+i8mm Q4 is correct | Section 8 | Rejected for these builds | High |
| `GGML_CPU_REPACK` / `use_extra_bufts` | Repack is on by default and has a history of weight-layout bugs in other revisions | x86 repack 28 MiB and ARM Kleidi+repack 69 MiB both match the non-repack answer | Section 8 | Not reproduced | High that it is not this failure |
| Flash attention disabled | App forces it off; upstream default is auto | `n_swa=0`; off and auto both say Paris | `llama-completion -fa off` and `-fa auto` | Rejected for this model | High |
| Prompt / missing BOS on Qwen | Salad can come from a bad prompt | 34-token ChatML matches the device count and decodes correctly with `add_special=0` | Section 6 | Rejected for Qwen | High |
| Llama 3 BOS suppressed | Code dropped `<|begin_of_text|>` for `<|start_header_id|>`, including on later turns | Qwen does not use that path. PR #42 already saw Llama 3 salad after the first-turn id became 128000 | Policy unit test; no Llama weight | Real defect, not the Qwen salad | High that the code was wrong |
| App sampler (temp 0.7, hidden 0.2 penalties) | Seed 1 at temp 0.7 does not say Paris | Output stays English. First token logit is identical. Temp 0 is stable | Section 7 | Quality defect, not the salad | High |
| Drain `llama_detokenize` | API strips the first piece's leading space per call | That yields "Hello!HowcanI", not "cincinnati" | Code plus llama.h contract | Real streaming defect | High |
| Custom KV prefix loop | Extra state beyond llama.cpp | Fresh turn and one prefix-reuse turn match `llama-completion` | Probe `--second-user` | Not reproduced for that case | Medium; cancel/shift/encode not run |
| Default Vulkan op offload while the log says CPU | `devices==null` plus `op_offload` default; scheduler `"1.off"`; Adreno patch in-tree; S25 is Adreno | Not run. PR #42 reported a Vulkan-off failure, but the old log could not prove the CPU was pinned | Code trace of `llama_prepare_model_devices` and `ggml_backend_sched` | Confirmed defect. Device effect untested | High for the defect, low for "this was the phone's salad" |
| NDK clang, 16 KB pages, or Oryon-only kernels | The phone binary is not this gcc/qemu binary | Baseline `armv8-a` gcc already matches the NDK's usual ISA and is correct | Not run | Open | Unknown |

## 12. Fixes and alternatives

Implemented, smallest set that matches the evidence:

1. `gpuLayerOffloadRequested()` is the only GPU decision. `useVulkan=true` with `gpuLayers=0` now pins the CPU device and sets `op_offload` and `offload_kqv` false, including the last-resort context retry that used to restore llama.cpp defaults. The load log prints `cpu_pinned`, `op_offload`, and `use_extra_bufts`. The backend string is documented as a compile-time label.
2. `shouldAddSpecialTokens()` no longer treats `<|start_header_id|>` as a BOS, and the engine asks for that decision on the full prompt even when the cache is warm.
3. Drain text is the concatenation of `llama_token_to_piece(..., lstrip=0, special=false)`.
4. Frequency and presence penalties are 0. Repeat penalty is unchanged.
5. Temperature may be 0, in Kotlin and in `sanitizeGenerationConfig`. The default remains 0.70. The temperature slider has 29 interior steps so 0.00 and 1.50 stay on a 0.05 grid.

Considered and not done:

- Forcing `GGML_CPU_REPACK` off or `use_extra_bufts` false. The matrix does not support it.
- Turning Kleidi off by default. The matrix does not support it, and a baseline NDK build often has no Kleidi kernels anyway.
- Replacing the prefix cache with a raw llama.cpp loop. The two-turn probe did not diverge. Deleting it would be an unforced rewrite.
- Moving the submodule to a newer llama.cpp. That would hide the comparison this report is based on.
- Porting PR #42's first-run UI, the Q8 catalog recommendation, or the conclusion that K-quant is unsupported. The Q8 recommendation was based on a hypothesis this matrix rejects. PR #42's two native fixes are included, and the BOS rule is stricter than that PR: it also applies on a warm cache.
- Applying the Vulkan patch inside the submodule. GPU layers above 0 stay unqualified until that patch is in the build that is actually flashed and a device run compares token 785.

## 13. Tests actually run

| Check | Result |
| --- | --- |
| Host CTest, sanitizers on, `PRISM_ENABLE_ENGINE_MOCK_TEST=OFF` | 10/10 passed, including `inference_policy_test` |
| `g++ -Wall -Wextra` compile of `Engine.cpp` against the pinned llama.cpp headers | Passed. Not linked. `engine_mock_test` was not built: the submodule is not checked out here and that target compiles llama.cpp from source |
| `run-probe.sh` on Q4_K_M and on Q8_0, x86, greedy, second turn | Both printed `greedy fixture matched` |
| qemu ARM probe, baseline and Kleidi, Q4 and Kleidi Q8 | Greedy ids and second-turn text matched section 5. Runtime about 4 minutes for the three runs |
| `llama-completion` greedy matrix in section 8 | Matched |
| `:app:testDevDebugUnitTest` and `:app:testPlayDebugUnitTest` | **Pass** on GitHub Actions run `42838468204` (Ubuntu, JDK 17, NDK installed by the workflow). Local host still has no Android SDK |
| Native host CTest in that run, including `engine_mock_test` linked against the submodule | **Pass** (2m53s) |
| `assembleDevDebugAndroidTest`, `assembleDevBenchmark`, `assemblePlayRelease` | **Pass** (14m3s). This compiles the changed `Engine.cpp` with the NDK for the debug androidTest and for RelWithDebInfo benchmark/release. It does not install or run on a device |
| 20 generations on device, restarts, cancellation, model switch | **Not run** |

`GenerationSettingsClampTest` ran inside the passing JVM unit-test job. Connected instrumentation was assembled and not executed.

## 14. Limitations

- No physical device and no Android emulator. The salad's first device-side logit id is still unknown.
- Vulkan was not linked in the reference, so the op-offload hypothesis is a code proof plus the existing Adreno patch, not a measured wrong logit.
- GitHub Actions compiled this branch with the NDK (debug androidTest, benchmark, and Play release). That binary was not installed or executed. 16 KB pages and the Snapdragon 8 Elite are not in the qemu configuration that passed.
- Cancel, model switch, context shift under memory pressure, and `encode()` then generate were not executed against libllama.
- Llama 3.2 weights were not loaded. The BOS rule is a unit test of the predicate.
- One prefix-reuse turn is not a proof of every `shrinkCommittedPrefix` branch.
- Passing this fixture does not qualify arbitrary GGUF files or the Vulkan path (`gpuLayers>0`).

## 15. Qualification matrix

| Model | Quant | SHA-256 | Device | Runtime | Backend | Config | Tests | Correctness | Performance | Limits | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Qwen2.5 0.5B Instruct | Q4_K_M | `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` | Host x86-64 gcc 13 | llama.cpp `bbeb89d` | CPU, repack 28 MiB | greedy, ctx 2048, batch 256, FA off, F16 KV, 1 and 4 threads | Prompt ids, top-5, greedy ids, second turn, math, sequence | Matches fixture | Not used as a gate | Not the phone | Reference pass |
| Qwen2.5 0.5B Instruct | Q4_K_M | same | qemu aarch64 baseline | same | CPU, repack kernels absent | greedy, 1 thread, probe loop | Same ids and second turn | Top-1 18.995893, argmax 785 | qemu, not a phone timing | Not NDK clang | Reference pass |
| Qwen2.5 0.5B Instruct | Q4_K_M | same | qemu aarch64 + i8mm/dotprod | same | Kleidi 139 MiB + repack 69 MiB | greedy, 1 thread probe; 4 threads `llama-completion` | Same | Top-1 19.193520, argmax 785 | qemu | Kleidi Q4_K tensors are repack, not Kleidi | Reference pass |
| Qwen2.5 0.5B Instruct | Q8_0 | `ca59ca7f13d0e15a8cfa77bd17e65d24f6844b554a7b6c12e07a5f89ff76844e` | Host x86-64 and qemu Kleidi | same | CPU / Kleidi 501 MiB | greedy, 1 thread; sequence at 4 threads | Same fixture | Same greedy ids. Top-1 19.023218 / 19.133930 | qemu | Same device gap | Reference pass |
| Qwen2.5 0.5B Instruct | Q4_K_M and Q8_0 | same hashes | SM-S938U | App binary, not this gcc build | Unknown. Old log said KleidiAI even for a zero-layer Vulkan-capable load | Historical devDebug | Salad, one non-greedy Q8 sentence | Not matched to token 785 | n/a | This investigation did not flash a build | **Blocked** |
| Llama 3.2 1B Instruct | Q4_K_M | not downloaded | none | n/a | n/a | BOS predicate only | Unit test of the header | Predicate covered. Weights not run | n/a | No numerical result | Not qualified |
| Any model | any | n/a | none | n/a | Vulkan, `gpuLayers>0` | Adreno patch not in the gitlink | Not run | Unknown | n/a | Do not treat GPU layers as qualified | **Blocked** |

## 16. Final engineering report

**ROOT CAUSE:** The pinned llama.cpp CPU, Kleidi, and repack paths are not the source of the incoherent text. They emit token 785 and the sentence "The capital of France is Paris." for these exact files. The incoherent device output was not reproduced. The default app load (`useVulkan=true`, `gpuLayers=0`) did not execute that CPU path: GPU devices stayed selected and `op_offload` stayed on, while the log still reported a CPU label. That is the first product defect that diverges from the passing reference. Llama 3 BOS handling, per-drain detokenize, hidden penalties, and the 0.05 temperature floor are additional confirmed defects and are not the salad.

**PROOF:** One probe binary, the production special-token policy, the same 34 prompt ids, and the same greedy sampler, run on x86 with repack and under qemu with and without Kleidi/repack. Argmax id 785 in every case. Q8 matches Q4. Second turn "Two plus two is four." The GPU-offload claim is the scheduler branch at `ggml_backend_sched` cause `"1.off"` together with `llama_prepare_model_devices` adding every GPU when `devices` is null. It was not measured on Adreno.

**FIX:** `runtime/InferencePolicy.hpp` and the load, tokenize, sampler, detokenize, and temperature changes in `Engine.cpp` / `GenerationSettings.kt`. Diagnostic fields `cpu_pinned` and `op_offload` are now logged.

**VERIFICATION:** Host CTest 10/10. `Engine.cpp` compiles clean against the pinned headers. Probe fixture matched twice on x86 (Q4 and Q8) and the ARM qemu runs matched the same ids. GitHub Actions run `42838468204` on `278cb5b` passed all three jobs: JVM unit tests (dev and play), native host CTest including the engine mock, and NDK benchmark/release plus the debug androidTest assemble. Nothing was installed on a phone.

**REGRESSION PROTECTION:** `inference_policy_test` locks the GPU decision and the Llama 3 versus ChatML BOS rule. `run-probe.sh` fails if the greedy ids or the second-turn sentence change, when a model file is supplied. `GenerationSettingsClampTest` locks temperature 0. The fixture records the ids a device log must match.

**LIMITATIONS:** Device effect of the CPU pin is unmeasured. Vulkan with `gpuLayers>0` is unmeasured. The NDK build compiled in CI and was not executed, so 16 KB pages and the Snapdragon 8 Elite are still untested. Cancel, shift, and embedding-then-generate are unmeasured.

**INTEGRATION:** Branch `cursor/inference-root-cause-e5d3` off `main`, PR #43, not merged. Android CI run `42838468204` is green on `278cb5b` (unit tests, native host tests, native benchmark/release). A later docs-only commit records that result and starts a second Actions run. A new pull request to `main` does trigger Actions; PR #42's empty check list did not reproduce here. This branch does not merge PR #42. It replaces that PR's inference conclusion and keeps its two native repairs (BOS, detokenize), with the BOS rule corrected for a warm cache. PR #42 remains a conflicting draft. No workflow edit is included. The submodule gitlink is unchanged and was not dirty.

**NEXT ACTION:** Flash this branch's `devDebug` build on the SM-S938U with `gpuLayers=0`, confirm `cpu_pinned=1` and `op_offload=0`, set temperature 0 and top-k 1, and require the first generated id to be 785 for the capital prompt. If that fails, the divergence is in the NDK binary or in a lifecycle path this host could not run, and the log will finally contain the id to compare.
