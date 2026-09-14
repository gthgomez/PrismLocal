# PrismLocal Inference Roadmap and Agent Execution Program

**Research date:** September 14, 2026 · **Repository:** `gthgomez/PrismLocal` · **Default branch at inspection:** `main`  
**Audited commit:** `49ed799a3e633c5192c2c03317d1d50ffdc57c4c`  
**Actual llama.cpp gitlink:** `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`  
**Upstream comparison candidate:** `661643e43079a4ee6faab4c1895291767b67ea8d`

> **Evidence boundary:** This is a live-source audit, current primary-source synthesis and proposed executable engineering program. No Android app build, physical-phone inference benchmark or coding-agent implementation was executed in this research session. Static counterexamples and artifact-tool checks are labeled separately. No throughput, energy, memory or thermal improvement is claimed to have been measured. Source-derived defects are distinguished from race risks, hypotheses, reported external issues and untested opportunities. The report does not certify every competing engine’s full source tree or every model/backend combination.

**Decision:** keep llama.cpp as the primary engine; fix correctness, runtime contracts and measurement first; qualify CPU/Vulkan/OpenCL and optional upstream Hexagon through isolated tests; introduce a second engine only if an equivalent-quality integrated bake-off earns its complexity.

## Contents
1. Executive verdict
2. PrismLocal’s current inference architecture
3. Verified and falsified prior hypotheses
4. Current strengths of Prism
5. Current weaknesses and bottlenecks
6. llama.cpp upstream gap analysis
7. PocketPal analysis
8. llama.rn analysis
9. MNN analysis
10. MLC LLM analysis
11. ExecuTorch analysis
12. LiteRT-LM analysis
13. ChatterUI analysis
14. SmolChat and current equivalent analysis
15. Additional engines and optimization systems discovered
16. Adversarial pass on every engine and reconciliation
17. Cross-engine comparison matrix
18. Unexplored optimization opportunities
19. Features Prism should borrow
20. Features Prism should explicitly reject
21. PrismLocal Adaptive Inference Runtime target architecture
22. Detailed code and file mapping
23. Benchmark architecture and science
24. Correctness gates
25. Alternative-runtime decision matrix
26. Prioritized feature backlog and scoring
27. Implementation DAG and critical path
28. Parallel execution map and worktree ownership
29. Complete agent mission packets
30. Root integrator mission
31. Risk register
32. Kill criteria and experiment retention policy
33. Final recommended execution order

## 1. Executive verdict

**Keep llama.cpp as PrismLocal’s primary runtime. First repair the integration’s correctness and evidence contracts; then optimize it. Do not ship a second engine or choose a universal GPU default on the evidence currently available.**

The audit is pinned to `main` at **49ed799a3e633c5192c2c03317d1d50ffdc57c4c**, retrieved on September 14, 2026. Its actual llama.cpp submodule is **bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3**. The separately inspected current upstream comparison candidate is **661643e43079a4ee6faab4c1895291767b67ea8d**. These are three different revisions. The source notes describe an older CPU-first configuration; Gradle actually enables ARM64 KleidiAI and Vulkan by default. [P00–P03, P28, U02]

The largest presently defensible opportunity is **making the existing computation and measurements trustworthy**, not collecting backend settings. A sampled token is accepted twice; transport may lose text under backpressure or terminal draining; UTF-8 is decoded per chunk and sanitized destructively; prompt roles are flattened; context shifting can invalidate previously calculated cache-reuse assumptions; settings reload can short-circuit on an unchanged model hash; backend reporting presents a request as observation. These are integration defects or source-demonstrated failure paths, not evidence that llama.cpp’s kernels are slow. [P05–P10, P17, P19–P25, U01]

A reasonable path is: freeze an auditable baseline → fix sampling, streaming, lifetime and prompt/cache semantics → introduce requested/applied/observed execution contracts → repair model admission and benchmark cohorts → compare the existing pin against a controlled upstream update → run CPU, Vulkan, OpenCL, KV, load and thermal experiments → retain only measured Pareto improvements → add a conservative planner and bounded tuner → test phone-appropriate speculation → consider one alternate runtime only if it passes a demanding bake-off.

**Evidence limits.** This is a live-source and primary-document research audit plus an implementation program. No Android model was loaded, no Gradle/native build was executed, no profiler was attached, and no physical-device performance, energy or quality result was produced during this audit. Source counterexamples are not device reproductions. Competitor coverage includes current official repositories, interfaces, releases, deployment documents and selected source/issue inspection; it is not an exhaustive kernel-by-kernel certification. Unsupported or unverified cells remain unknown. No engine has been demonstrated here to be faster than Prism on Jonathan’s phone.

The recommendation is therefore conditional but concrete: **Option A now; an upstream Hexagon experiment before a second inference engine; a standalone MNN bake-off as the leading alternate-runtime experiment; no multi-engine framework until a candidate earns it.** llama.rn and PocketPal are particularly useful integration-policy references, not independent proof of a faster core. MNN, MLC and ExecuTorch/LiteRT-LM may win selected compiled/model-specific workloads, but conversion, packaging, vendor SDKs, quality equivalence and lifecycle obligations are part of the comparison. [U03–U06, R01–R20]

Recent change reconciliation: PRs #2–#4 address benchmark queue draining, some per-stream persistence work, deferred native teardown and emulator setup. The inspected service really has an off-main teardown job with a bounded two-second main-thread wait. Do not reopen an old “entire native free runs synchronously on main” finding unchanged. Conversely, do not treat PR descriptions or available emulator scripts as proof that instrumentation, physical-device correctness or sustained benchmarks passed. [P10, P26–P29]

**Evidence vocabulary:** S = directly inspected source/API contract; D = official documentation or release statement; I = reported issue; B = reproducible benchmark with an adequate manifest; H = engineering hypothesis. There are S/D/I/H findings in this report, but no new physical-device B results. Proposed percentage gates below are decision policies, not forecast gains.


## 2. PrismLocal’s current inference architecture

The live path is a Kotlin service and generation orchestrator, a polling JNI bridge, a C++ session/worker/ring-buffer engine, llama.cpp model/context/sampler APIs, and ggml-selected hardware backends. Android policy, native execution and compile-time backend labels currently have different authorities. The diagram below is an execution outline, not a promise that every settings field reaches every layer.

```text
SAF / download → ModelStorageManager → versioned model + manifest + metadata
  → ModelReadinessAssessor / ModelManager → NativeLlmBridge.loadModel
  → JNI GenerationConfig → Engine::loadModel / mmap fallback
  → llama model + context → ggml CPU / Vulkan / experimental OpenCL

InferenceService operation mutex → GenerationOrchestrator → PromptBuilder
  → flattened prompt + GenerationSettings → JNI startGeneration
  → native template heuristic → tokenization → prefix reuse / context policy
  → prompt llama_decode → sampler → token llama_decode
  → native token ring → drain/text conversion → Kotlin Flow → transcript/UI
```

### Complete lifecycle reconstruction
In the table, **allocation** means an identified major allocation class, not a measured byte count. Unknown observations are intentionally not replaced with estimates.

| Stage | Authority; inputs → outputs | Mutable state / major allocations | Blocking and fallback | Failure / present observation / missing observation |
|---|---|---|---|---|
| 1. Discovery/import | ModelImportManager/ModelDownloadManager feed ModelStorageManager; URI or downloaded file → installed model version | Staging file, copy buffer, version directory, manifest, SHA-256 | Storage I/O, copy, separate hash read, promotion; app-external directory or private fallback | Space/permission/copy/hash/promotion failure; progress/error codes exist; no separate copy/hash/validation phase timings. External-link entrypoint exists, but provider/FD semantics were not fully traced here. [P10,P18] |
| 2. GGUF validation | Storage reader; magic/version and bounded metadata scan → ModelValidation | Metadata summary and manifest cache | Synchronous file parsing on caller’s storage path; metadata exceptions become null metadata | “verified” can mean magic/version passed despite metadata failure. Tensor correctness belongs to native load. Missing validation-level provenance, tensor inventory, parser-order robustness. [P18] |
| 3. Load eligibility | Readiness assessor and ModelManager; file bytes, metadata, memory snapshot, settings/history → allow/reject | UI readiness/diagnostics, cached device profile | Estimate before unload; some estimates overridden by historical throughput or file-size condition | Historical speed is not current memory safety. Diagnostics expose estimated need but not actual peak allocations or conservative reclaim. [P14–P17] |
| 4. Settings selection | GenerationSettings, EngineConfigStore, orchestrator/presets → clamped settings | Persisted preferences, per-generation overrides, reloadPending | Reload asynchronously or deferred; same-model shortcut may suppress actual reload | useVulkan not persisted or passed; no immutable applied plan. Current labels record desired settings. [P04–P06,P10,P17,P20] |
| 5. Native initialization | JNI → Engine; path/config → shared Runtime | Model metadata/tensors, context, batch, sampler, adapters | Cancels/joins worker; mmap load first then non-mmap; mlock false; tensor checking true | Native load/context failures collapse into limited errors; no phase-by-phase allocation/latency ledger. [P07–P09] |
| 6. Backend selection | Build flags + llama/ggml defaults and requested GPU layers | Backend registrations, devices, execution scheduler | No explicit per-request backend/device contract; release/adreno variants differ | A compiled backend is not a usable backend. Actual selected device and supported-op coverage absent from public telemetry. [P02,P03,P09] |
| 7. Weight placement | llama model loader/ggml; model and requested layers → backend buffers | CPU/GPU/repacked/mapped weight buffers | Placement/internal fallback decided below Kotlin | Reported “offloaded” count is requested count after a capability check. CPU/GPU bytes, per-layer/device ownership and offloaded operations unverified. [P09,P17] |
| 8. Context/KV allocation | llama context creation; context,batch,KV/FA settings → context | KV/state, graph/compute buffers, output/logits, thread scratch | n_batch=n_ubatch; decode=batch threads. Context fallback to F16/FA-off and a further default-parameter attempt | Fallback may change memory/behavior without explicit applied-plan report. Actual allocated context/KV types/location absent. [P08,P09] |
| 9. Prompt formatting | Kotlin PromptBuilder/agent protocol, then native formatting → prompt bytes | Transcript/history strings, memory/tool schema, template buffer | Char-based budget; native marker heuristic or single-user model template, fallback ChatML/raw | Roles and system priority lost; user marker text can change formatting; no canonical message/template hash or exact budget trace. [P09,P19,P20] |
| 10. Tokenization | llama tokenizer; text + BOS/special-token flags → IDs | Token vector | CPU work; initial BOS behavior depends on current context position | Cache-state-dependent tokenization undermines stable prefix reuse. JNI input uses modified UTF-8. Missing original/rendered byte and token hashes. [P07,P09] |
| 11. Prefix reuse | Engine active_tokens/position and sequence removal → reusable prefix/suffix | KV sequence, active-token vector, counters | Longest common prefix scan, seq_rm; clear/reset paths | Removal return is not consistently honored; reuse calculated before a later shift/reset. Missing cache identity, evaluated-token count and invalidation reason. [P09] |
| 12. Prefill | Native worker → llama_decode chunks | Batch storage, graph/compute buffers, KV growth | decode mutex spans generation; chunk size related to logical batch; abort callback for cancellation/pressure | Partial prefill can leave invalid reuse assumptions; “TTFT” is this phase’s timing, not first displayed text. [P09,P21] |
| 13. Sampling | Fresh chain per generation; logits → token | Penalties/history, grammar state, RNG, candidate arrays | Penalties/top-k/top-p/temp/grammar/distribution; grammar failure can continue unconstrained | llama_sampler_sample already accepts; Prism explicitly accepts again. No true greedy temperature=0 setting; no explicit reproducible seed. [P04,P09,U01] |
| 14. Decode | Worker; sampled ID → next logits/KV | Active tokens, position, output counters | One token llama_decode; pressure/thread changes checked between work units | Token may enter stream/accounting before decode commits. Repetition heuristic can manufacture EOG for legitimate repetition. Missing transaction boundary. [P09] |
| 15. Streaming | Native ring + combined JNI drain + Kotlin Flow → UI/transcript text | Ring of token IDs, vectors/strings, reusable JNI arrays, Kotlin strings/Flow buffers | Drain up to 128 IDs, adaptive 2–64 ms empty polling; slow consumer retry then drop | Terminal may precede full drain. UTF-8 partial code points lost; no produced/drained/final-sequence invariant. Existing JNI timing is not token latency. [P06,P07,P25] |
| 16. Cancellation | Service operation mutex, coroutine cancellation, JNI generation ID, native abort flag | Session state, ring, worker join, metrics/transcript | Native abort + join; coroutine finally cancels/acks | Exact generation identity matters; clear/drain/producer races and raw-handle lifetime need tests. Cancelled cache must not silently remain reusable. [P06–P10] |
| 17. Memory pressure | Registered MemoryGovernor → engine.setMemoryPressure; critical polling saves transcript | Last pressure state, abort flag, UI alert | Push callbacks plus AM threshold polling; critical requests abort | No allocation-aware retry planner; callback support varies; new load admission still separate. Need PSS/native/driver accounting and pressure timeline. [P10,P11,P14–P17] |
| 18. Thermal handling | Two governor classes exist; service/generation wiring not established for foreground path | Thermal status, battery state, recommended threads, cooldown | One class has hysteresis; another may let a battery update overwrite severe OS state | Class existence is not live protection. Anonymous thermal listener in ThermalBatteryGovernor is not removed. Need a single service-owned effective safety state and instrumentation of actual thread changes. [P10,P12,P13] |
| 19. Context exhaustion | Engine custom policy around llama sequence primitives → shifted/rebuilt/failed context | KV positions, detected prefix, active tokens, pos | Quarter-discard-like policy and reset fallback; native leading-token prompt truncation | System detection/template/architecture/RoPE assumptions; suffix calculated before invalidation; reserve-boundary inconsistencies. No model-aware policy certificate. [P09] |
| 20. Unload/reload | Service/bridge ownership and Runtime RAII → freed context/model | Native handle/session, sampler, adapters, model state | Cancel/join before unload; service destruction starts off-main teardown but waits up to 2 s | Same-model load-key bug; raw in-flight calls can race destruction. Model-switch failure leaves old model unloaded. Need explicit lifecycle states and in-flight leases. [P06–P10,P17] |

### Important boundaries
The service has a real operation mutex, but not every benchmark/control path is proven to acquire the same admission lease. Native decode serialization is not a replacement for model lifetime protection. A volatile destroyed flag cannot prevent a thread that already passed its check from dereferencing a freed raw handle. Treat this as a high-priority source-level race risk requiring an interleaving test, not an already reproduced crash. [P06,P07,P10,P22]

The native runtime already has RAII ownership and a reusable single-token batch, so a proposal to “introduce RAII everywhere” or “reuse a sampler across unrelated requests” misses the actual design. Preserve fresh sampler ownership while fixing double acceptance and making cache commits transactional. [P09]

A model’s byte size is not its resident memory, and a compiled backend label is not a measurement. These distinctions must be carried across Kotlin, JNI, native structures and persisted benchmark records rather than patched only in the UI.


## 3. Verified and falsified prior hypotheses

| ID | Verdict | Current-source finding | Resolution / experiment |
|---|---|---|---|
| H1 Vulkan contract | **CONFIRMED** | useVulkan exists in GenerationSettings, but EngineConfigStore omits persistence/reload comparison and JNI/native load has no corresponding selection field. A broader same-model/hash shortcut can suppress reloads that are requested. [P04–P08,P10,P17] | Replace boolean ambiguity with backend preference + explicit device selection. Round-trip every load-key field and assert applied native values after reload. |
| H2 GPU reporting | **CONFIRMED** | Requested GPU layers plus llama_supports_gpu_offload are used as reported placement; backend naming also follows build/capability heuristics. No trustworthy layer/device/CPU/GPU/KV/compute byte ledger was found. [P09,P17,P24] | Distinguish requested, applied and observed; use unknown where public APIs lack proof. Audit actual model/context buffers and scheduler placement. |
| H3 PP/decode threads | **CONFIRMED** | One value configures n_threads and n_threads_batch, including dynamic thread setting. [P08,P09] | Expose independent values internally, not necessarily in novice UI. Paired topology-aware search; more threads is not a forecast of higher speed. |
| H4 batch/ubatch | **CONFIRMED** | n_ubatch is set equal to n_batch. [P09] | Sweep a small dependent set subject to ubatch≤batch, memory and cancellation limits; measure backend interactions. |
| H5 fit estimation | **CONFIRMED** | Existing GGUF summary already contains layers/embedding/attention/KV heads, but fit uses 256 KiB per context token and heuristic overhead; history can override safety. [P15,P18] | Architecture-aware estimate with ranges, actual allocation calibration and no performance-history safety override. |
| H6 truncation | **CONFIRMED** | Native left-truncation removes initial tokens; Kotlin has approximate history budgets but no role-preserving exact token admission. [P09,P19,P20] | Structured messages, exact native template/token budget, preserved mandatory prefix, explicit rejection when mandatory input alone exceeds capacity. |
| H7 prefix cache | **PARTIALLY TRUE** | Reuse exists. Its correctness is not established: BOS depends on existing position, sequence-removal failures are not handled robustly, and later shift/reset can invalidate the earlier suffix calculation. [P09] | Differential replay tests; record reused/evaluated tokens and cache identity. Upstream primitives help but do not own Prism’s prompt semantics. |
| H8 custom shift | **CONFIRMED**, risk not measured | Custom policy already uses upstream sequence removal/position-add primitives. Replacing calls with the same upstream primitives is not a fix. Model-aware eligibility, prefix boundaries, transactional state and replay are missing. [P09] | Default to message pruning + full replay; enable in-place shifting only for certified model/backend combinations. Native SWA is a separate capability. |
| H9 loading defaults | **CONFIRMED / NEEDS DEVICE TEST** | mmap first, non-mmap retry, mlock off, check_tensors on. No explicit repacking policy in Prism; upstream can still repack internally. [P09] | Measure load phases/page faults/peak memory. Keep mlock off; cache validation only with strong identity. Repack/mmap policy is model/backend-specific. |
| H10 Vulkan features | **CONFIRMED compile restriction; UNKNOWN benefit** | Integer-dot and cooperative-matrix feature paths are disabled in CMake. [P02] | Isolated capability-gated builds; verify runtime feature checks, operator tests and driver allowlists before any default change. |
| H11 OpenCL | **PARTIALLY TRUE** | An adreno build path exists, not the standard production default. Current upstream support is broader than older binding documentation. Prism has no measured production superiority proof. [P02,P03,U03,R04] | Retain as an experimental contender; strict backend selection and per-quant/driver correctness required. |
| H12 KleidiAI | **PARTIALLY TRUE** | ARM64 builds request KleidiAI. “Enabled” is a compile flag, not per-operation proof. Old prose saying disabled is stale. [P01–P03,P09,U05] | Log ISA/kernel eligibility; profile actual CPU_KLEIDIAI buffers and execution where possible; sweep thread counts and affinity. |
| H13 speculation | **CONFIRMED unintegrated code** | CMake compiles speculative/ngram-related sources, but the inspected live token loop is ordinary single-token sampling/decode. [P02,P09] | Measure N-gram first, then a tiny draft/MTP only for compatible models. No claim that every currently advertised upstream mode exists in Prism’s pin. |

**Additional falsification:** the inspected ordinary generation path constructs history before appending the new user message, so “the latest user prompt is necessarily duplicated by transcript construction” is not supported. Also, memory monitoring is not entirely absent: MemoryGovernor is wired into the service. Conversely, two thermal class files do not establish foreground thermal-policy integration. [P10,P11,P20]

**New high-severity findings outside H1–H13:** double sampler acceptance; fail-open grammar initialization; lossy stream/backpressure and terminal draining; state-dependent tokenizer flags; lifecycle handle race; same-model settings reload suppression; performance history bypassing admission; synthetic PP/TG and chat records mixed into unsafe model advice. All deserve fixes before an optimization ranking based on today’s measurements. [P06–P10,P15,P17,P21–P25,U01]


## 4. Current strengths of Prism

Prism already has valuable foundations worth preserving. It uses a pinned, broadly compatible GGUF runtime rather than a model-specific graph package. Import stages files and records hashes/versions; the native runtime has RAII; the generation bridge combines draining, text and state retrieval into one JNI call with reusable arrays; the service persists transcripts and receives memory callbacks; benchmark exports and flavor-qualified verification scripts exist. These are concrete implementation assets, not proof of complete correctness. [P01,P06–P11,P18,P22–P28]

The app’s configuration and model managers are separated enough to host an execution plan without a rewrite. GenerationSettings can remain the user-preference layer, ModelReadinessAssessor can become the admission explainer, DeviceProfiler can be extended rather than duplicated, BenchmarkStore can migrate to evidence-grade records, and InferenceService can remain the lifecycle authority. The project also already has native test hooks, emulator-oriented tests, a release-like benchmark flavor and Windows build guidance. [P03–P05,P10,P14–P17,P21–P27]

The strongest product asset is not a particular kernel: it is a privacy-preserving Android workflow around user-selected compatible models. Preserve arbitrary supported GGUF import, offline execution after import, transparent model choice and safe failure. A restricted compiled-model backend is an optional fast lane, not a replacement for that promise.


## 5. Current weaknesses and bottlenecks

### Severity-ranked diagnosis
| Severity | Finding | Why it comes before kernel tuning |
|---|---|---|
| P0 correctness | Token accepted twice by stateful sampler; grammar failure can become unconstrained generation | Changes generation semantics; can invalidate tool-output correctness and any quality baseline. [P09,U01] |
| P0 correctness | Stream drop after retry timeout; terminal state without complete drain; broken split-UTF-8 handling | A displayed speed increase may simply omit output. [P06,P07,P25] |
| P0 correctness | Flattened roles, left truncation, fragile prefix detection/cache-shift ordering | Model can see different instructions or incomplete context; “faster prefill” may mean less required work. [P09,P19,P20] |
| P0 reliability | Raw JNI lifetime/in-flight race; incomplete single-operation ownership | A benchmark winner that races unload is not shippable. [P06,P07,P10,P22] |
| P0 safety/contract | Current-memory admission overridden by old speed; silent context/KV fallback | A plan can become materially larger than the estimate while still being called safe. [P09,P15–P17] |
| P1 measurement | TTFT misnamed, desired rather than applied config, fake placement, mixed cohorts | Existing records cannot isolate backend/config/bridge/thermal effects. [P09,P21–P24] |
| P1 contract | Same-model/hash shortcut blocks settings reload | A sweep may compare labels rather than actual native configurations. [P05,P10,P17] |
| P1 performance opportunity | Shared PP/TG threads; tied batch/ubatch; stale dependency and conservative Vulkan paths | Plausible optimization headroom, but magnitude and winner require physical-device tests. [P02,P09,U02–U06] |
| P1 sustainability | Thermal classes without verified foreground control path and unified precedence | Thread/accelerator policy must respond to measured device state, not a class name. [P10,P12,P13,A01] |
| P2 capability | Vision JNI stubs and unconstrained embedding API assumptions | Advertise supported operations accurately; do not load a projector or run embeddings on unsuitable models. [P07,P09] |

### What cannot yet be called a bottleneck
No source reading can establish that CPU bandwidth, Vulkan transfers, JNI polling, grammar sampling, storage or thermals dominate a specific model on the S25 Ultra. Each is a candidate. Instrument timelines and classify time into admission/load/context/template/tokenize/prefill/sample/decode/transport/render, alongside thermal and memory state. The optimizations in the backlog are ordered by safety and expected information value, not invented percentage speedups.

Three further inspection targets should not be overstated: external linked-URI lifetime and seekability; whether thermal governors are instantiated in UI/background-only paths outside the inspected service; and architecture-specific embedding output shape/pooling. They have explicit missions and stop conditions rather than fabricated negative conclusions.


## 6. llama.cpp upstream gap analysis

The comparison is not “Prism versus llama.cpp”; it is **Prism’s integration of a particular llama.cpp pin versus a controlled newer pin with an otherwise identical integration**. The May snapshot and September upstream revision must be separately built and tested. Do not merge an unbounded upstream update and ten settings changes in one performance commit. [P01,P28,U01,U02]

Current upstream documentation exposes useful Android-specific routes: a native Android binding example with metadata/content-URI handling; CPU feature-aware dispatch including KleidiAI; broader Adreno OpenCL quantization coverage and an on-disk program cache; and a Snapdragon/Hexagon route that keeps GGUF rather than requiring a separate QNN-exported model. These are research leads. Their exact implementation and compatibility at the selected candidate SHA must be included in the update mission’s diff manifest. [U03–U06]

| Gap | Integration action | Qualification |
|---|---|---|
| Basic template API versus common Jinja/chat utilities | Evaluate the selected pin’s common chat/minja integration; pass structured messages and explicit tool schema | Do not assume llama_chat_apply_template is a full Jinja interpreter; pinned header explicitly says otherwise. [U01] |
| Cache/position manipulation | Use return-checked sequence/memory APIs behind Prism’s own transaction and model-eligibility contract | Upstream cannot repair a suffix computed before Prism invalidates its prefix. [P09] |
| CPU selection | Separate decode and prefill threads; inspect runtime ISA and actual kernel path | KleidiAI-enabled builds do not prove every operation uses KleidiAI. [U05] |
| Strict CPU baseline | Disable accelerator device selection and relevant op offload, not only request zero layers | Upstream build guidance warns that zero model layers need not prohibit every GPU operation. [U05] |
| OpenCL | Compare new quant coverage and private, driver-keyed program-binary cache | Missing cache directory must be observable; no global vendor-driver assumptions. [U03] |
| Hexagon | Try upstream GGUF HTP backend in a separate research flavor | Experimental, Qualcomm-specific; SDK/runtime distribution and supported ops are gates. [U06] |
| Multimodal | Evaluate upstream mtmd-style capability rather than current JNI success-shaped stubs | Extra memory, template/image-token semantics and cancellation need separate tests. [P07,R04] |
| Build hygiene | Reapply/reconcile Prism’s local Vulkan host-tool patch; remove unused speculative objects only after link-map inspection | A newer dependency can change ABI, scheduler, quantization and build assumptions independently. [P02,P26] |

**Red team.** Upstream remains a fast-changing general runtime, not an Android lifecycle manager. Internal API churn, driver defects and model architecture additions create regression risk. A permissive license and a large community do not prove a particular backend is correct on a particular phone. Keep a known-good pin, a dependency-diff report, a backend conformance suite, and a rollback build. The update’s acceptance criterion is no correctness/capability regression plus a justified maintenance/security/model-support benefit; it need not manufacture a TPS improvement to be worthwhile.


## 7. PocketPal analysis

**Role:** Android/iOS product and lifecycle policy around llama.rn, not a separate mathematical inference engine. The inspected release list shows v1.17.3 on September 10, 2026; the additionally inspected ModelStore and memory-estimator files were retrieved from the current default branch, not assumed identical to that release. [R01–R03,R42]

**Claimed and proven strengths.** Product documentation emphasizes local models and configurable accelerators. Source gives stronger evidence for a few concrete ideas: versioned context-initialization settings, device-rule provenance, an explicit benchmark ownership flag, a serialized context-operation promise, tracking an active completion before release, and accounting for draft/projector memory. These are particularly relevant to Prism’s same-model reload and benchmark/context ownership problems. [R03,R42]

**Adversarial finding.** Its estimator uses K and V head dimensions, KV heads and block-overhead-aware bytes per cache element—better input features than Prism’s constant. However, it computes one effective context as min(context, sliding_window) for all layers, then uses a simple compute-buffer estimate. That is not a safe general estimator for models mixing full attention and local attention, recurrent state, different layer shapes, backend padding or full-SWA allocation. Copy the metadata inventory and draft accounting concept, not the equation wholesale. [R42]

**Hidden cost / benchmark attack.** An app-to-app comparison can differ in llama.rn version, prompt templates, active draft model, GPU device selection, context, caches, memory calibration and auto-release. UI-reported tokens per second alone cannot attribute a gain to PocketPal. Reproduce the same pinned native backend and tokenized prompt, then measure the policy/bridge delta separately.

**Verdict: ADAPT CONCEPT.** Borrow load-configuration identity, explicit benchmark ownership and versioned device rules. Do not import its React Native stack into a Kotlin application, treat device tiers as benchmark proof, or copy an architecture-incomplete memory formula. Its maintained product integration is useful comparative evidence, not a guaranteed optimal engine configuration.


## 8. llama.rn analysis

**Role:** a native llama.cpp substrate plus React Native bindings. The inspected stable release is v0.12.9, August 4, 2026; current source and documentation also describe capabilities beyond older releases. Deployment must lock the actual binding, llama.cpp revision and native binary checksums rather than read a moving README as a release guarantee. [R04,R05]

**Proven integration ideas.** The native source exposes backend device enumeration and IDs, installs an Android abort-message/log hook, reads GGUF metadata, distinguishes mRoPE models and carries state metadata. Its current API documentation covers structured chat/tool formatting, grammar, multimodal and selected draft modes. These are examples of making underlying llama.cpp facilities usable and observable on mobile. [R04,R43]

**Weaknesses and costs.** Feature surface is broad: slot/parallel generation, draft models, image/audio handling, persistence and platform bindings multiply lifecycle cases. Current source maps unknown KV cache strings to F16 rather than a typed failure; that kind of fallback must be reported if borrowed. Some exposed GPU flags still do not prove per-tensor placement. Documentation also records OpenCL/session-state caveats, so “supports session save” cannot be generalized across every cache/backend combination. [R04,R43]

**Benchmark attack.** PocketPal and llama.rn are not independent votes against llama.cpp. They largely share the substrate. A JS/JSI app beating Prism could reflect a newer runtime, better prompt policy, working offload, speculation or fewer lost metrics—not a fundamental advantage of crossing a JS boundary. Attribute these one at a time.

**Verdict: BORROW DIRECTLY from permissively licensed upstream components where appropriate; ADAPT the integration contracts.** Prioritize device enumeration, capability-dependent context policy, abort diagnostics, structured template handling and explicit speculative counters. Do not replace Prism’s bridge merely to gain a package name. Review each imported file’s license and preserve notices; do not assume the license of a fork matches its ancestor.


## 9. MNN analysis

**Role:** a genuine alternative inference runtime with a model-conversion/export pipeline, Android application and CPU/GPU/vendor paths. The observed release is 3.6.1, July 23, 2026. The inspected current LLM source maps named backends, configures attention options, maintains chunk limits, installs chat-template context, and selects separate external paths for cached weights, KV, prefix cache and NPU artifacts. [R06–R09]

**Proven strengths versus Prism’s integration.** MNN makes runtime cache and chunk policy explicit and integrates its graph/model packaging with LLM generation. The transformer guide documents conversion, runtime memory options and backend settings rather than promising arbitrary GGUF loading. This makes it a serious controlled bake-off candidate, particularly for a curated mobile model set. [R07,R09]

**Do not misread its settings.** In the documented OpenCL example, a thread_num value can encode backend flags rather than a count of CPU workers. Do not compare that number directly to Prism’s threads. Likewise, attention_mode, dynamic quantization, export quantization, external weights and KV-mmap change different aspects of execution and quality. [R07,R09]

**Hidden costs.** A converted model is a package of graph/weights/tokenizer/config and sometimes embeddings or compiled artifacts. Its calibration, quantized tensors and operators need independent quality validation against the original checkpoint. Disk-backed KV may reduce resident pressure while increasing storage I/O, tail latency and energy; it is not free memory. Source shows cache-directory creation failure falls back to operation without that disk cache—an observable warning is better than silently mislabeling a “warm” run. [R07,R09]

**Red team and verdict: OPTIONAL BACKEND CANDIDATE, not a replacement decision.** Require same original checkpoint, documented export recipe, exact tokenizer/template, matched tasks, quality non-inferiority and the full sustained/memory/energy workload. A vendor’s 4-bit result versus a different GGUF quantization is a package comparison, not pure backend proof. MNN must clear the alternate-engine thresholds in section 32 before Prism adds any production adapter. Keep its experimental binary outside the normal APK until then.


## 10. MLC LLM analysis

**Role:** compiler/runtime system, based on TVM-style model compilation, with an Android SDK and packaged model libraries. The Android deployment contract is different from importing an arbitrary compatible GGUF at runtime. A model may require conversion and a target-specific compiled library before the Android app can execute it. [R10,R11]

**Strong ideas:** make compilation and weight preparation explicit; separate cached artifacts from runtime state; plan memory and prefill chunking; use well-defined model/runtime configuration. Serving documentation exposes context/cache/speculative controls, but server documentation is not proof that every option has a finished Android API or is appropriate for a single chat session. [R11,R12]

**Adversarial analysis.** A precompiled graph with one export quantization and static or bounded shapes may outperform a flexible loader. That can be a valid user-product advantage, but compilation time, compiled-model size, compatibility coverage and developer effort must remain in the ledger. MLC’s q4f16-style formats are not mathematically equivalent to GGUF Q4_K_M because their names share “4.” Multi-request serving and paged KV can solve workloads Prism does not presently have; do not import a serving scheduler to accelerate one ordinary chat.

**Failure probes, not fabricated bug reports:** mismatched model library/weights/config; unsupported dynamic context; first-run library initialization; generation cancellation during a compiled prefill chunk; GPU allocation failure; tokenizer/template conversion mismatch. This audit did not independently reproduce or fully inspect each of these failure paths.

**Verdict: MONITOR / bounded bake-off candidate.** Promote only for a genuinely curated deployment or a demonstrated material advantage that survives compilation, package and quality accounting. Do not replace the GGUF path for architectural elegance.


## 11. ExecuTorch analysis

**Role:** exported PyTorch programs, backend lowering and a small execution runtime. Stable documentation inspected here identifies version 1.3. Ahead-of-time export and memory planning are first-class; backend-specific programs may be needed for different hardware. Its LLM guide includes Android and Qualcomm deployment rather than only generic CNN examples. [R13–R16]

**Useful ideas:** separate model preparation from execution, inspect planned allocations, collect operator/profiling artifacts, and package only required runtime operators. CPU XNNPACK, Vulkan and Qualcomm delegates are real documented routes, but supported operators, model export recipes and backend precision determine whether an LLM actually uses the accelerator. [R14–R16]

**NPU distinction.** The Qualcomm backend uses AI Engine Direct/QNN and requires its SDK/runtime assumptions. MediaTek documentation names D9300/D9400 and NeuroPilot Express. Samsung documentation names Exynos 2500/2600, requires AI Litecore, and its example is MobileNet; that proves a delegate exists, not that arbitrary LLMs export efficiently or keep all attention on the NPU. Treat Samsung LLM superiority as unproven until operator coverage and an end-to-end LLM artifact are demonstrated. [R15,R39–R41]

**Adversarial analysis.** Export success does not guarantee the intended partition or fixed KV footprint. Partial fallback can create costly transfers. Calibration data, activation precision, unsupported operations, PTE artifacts, native libraries and vendor redistribution terms are part of the product. A tiny runtime binary can coexist with substantial total model/SDK payload.

**Verdict: OPTIONAL VENDOR-BACKEND CANDIDATE.** Prefer a narrow, well-supported model/device case, not a general alternate-engine facade. Require exported graph/partition evidence, layer/operator placement, quality results and physical-device lifecycle tests. Upstream llama.cpp Hexagon should be screened first where it can preserve Prism’s GGUF workflow with less integration overhead.


## 12. LiteRT-LM analysis

**Role:** Google AI Edge’s LLM runtime, distinct from treating generic TensorFlow Lite, MediaPipe LLM and LiteRT-LM as interchangeable names. The observed release is **v0.17.0, September 9, 2026, e9fd8c5**. Release notes describe local-attention memory changes and model-specific multimodal/MTP developments; these are not a same-device Prism speed result. [R17,R19]

**Useful integration contract:** prepared .litertlm artifacts, Kotlin engine configuration, explicit cache/native-library directories, CPU/GPU/NPU selection and conversation-level APIs. A model/runtime-specific format can support optimized execution but reduces the universality of the GGUF import path. Review API defaults around automatic tool handling; Prism’s local tool authorization boundary must remain authoritative rather than delegated to an automatic callback loop. [R17,R18]

**Adversarial evidence.** Current issue listings include Android OpenCL loader failure (#3575), cache/rewind concerns (#3561), grammar/tokenizer behavior (#3512), NPU multi-chunk prefill (#3510), and GPU rearranged-weight memory (#3508). These are user reports, not reproduced defects or a claim that all apply to v0.17.0. They provide targeted adversarial fixtures: load on an older Adreno device, multi-chunk long prefill, structured output with spaces, repeated scoring/generation, and memory accounting after GPU weight preparation. [R20]

**Hidden costs:** prepared models, supported architecture limits, export/calibration steps, delegate and SDK distribution, native load dependencies, warm-up/compilation caches and memory duplication. A vendor statement of multiple-times faster model-specific MTP is not transferable to an arbitrary 7B GGUF. Any speed claim needs acceptance rate, quality, sustained duration and actual-device evidence.

**Verdict: OPTIONAL BACKEND CANDIDATE for a curated model capability.** The strongest reason to adopt it may be a specific supported multimodal/NPU model that Prism cannot otherwise run acceptably—not a generic assertion that Google’s runtime is faster. No production integration before the standalone bake-off and packaging/license review.


## 13. ChatterUI analysis

**Current project:** Vali-98/ChatterUI. The inspected package.json identifies package version 0.8.8 and depends on **cui-llama.rn ^1.11.9**, not simply stock mybigday/llama.rn. That distinction prevents accidentally attributing the fork’s behavior to the upstream binding. It is an Expo/React Native application with local and remote model interfaces. [R21,R44]

**Ideas to examine:** configurable conversation/instruction formatting, generation-control UX, CPU information, background execution and separation of local/remote completion configuration. This audit establishes its substrate and product role; it does not certify its fork’s cache algorithm, per-token transport or accelerator kernels.

**License boundary:** Prism’s inspected license is Apache-2.0; ChatterUI’s inspected license text is AGPL-3.0. Do not casually paste ChatterUI implementation code into an Apache-only distribution. A combined derivative has obligations that require deliberate license review. Reimplement the concept or use an independently verified permissive upstream component; review cui-llama.rn separately rather than assuming its license. This is an engineering release gate, not a blanket claim that the licenses can never coexist. [P30,R45]

**Benchmark attack:** character prompts, local versus remote mode, instruct templates, stop sequences, context length and fork revision can change both speed and apparent answer quality. A screenshot of app output is not an inference-engine comparison.

**Verdict: ADAPT CONCEPT; no direct code borrowing until license review.** Its most relevant contribution is product/control behavior, not evidence for another runtime in Prism.


## 14. SmolChat and current equivalent analysis

The relevant repository remains **shubham0204/SmolChat-Android**. It was not archived when checked. Its latest retrieved default-branch commit was **b663cd64ce5a19e85cd5c932e240125800bf8e49**, dated **June 21, 2026**, preparing release v16. That is a quieter recent cadence than some competitors; it is not sufficient evidence to call the project abandoned or invent a successor. [R22,R23]

The repository’s Kotlin/native modules and llama.cpp submodule make it a useful minimal Android integration reference. Its Apache-2.0 declaration is favorable for potential reuse with notice preservation, but actual selected files and third-party libraries still need review. It is not an independent inference-kernel competitor to llama.cpp. [R22]

**Adversarial analysis:** small native wrappers may have lower integration burden but fewer capability and failure modes exposed explicitly. Before copying, run the same Unicode, cancellation, template, context overflow, process-death and model-switch tests required of Prism. A concise bridge is not proof of safe lifetime ownership. Recent inactivity at the head does not prove inferior performance, just as a busy commit history does not prove correctness.

**Verdict: ADAPT CONCEPT / MONITOR.** Use it to challenge excessive architecture in Prism, not as a reason to replace the current native engine. No reliable benchmark superiority was established in this audit.


## 15. Additional engines and optimization systems discovered

Research value is ranked by the probability of answering a concrete Prism question, not by repository stars. “Active” below refers to observed documentation/release activity, not an exhaustive contributor or bus-factor audit. Exact commit, license, package and SDK versions must be locked before an experiment.

| Rank / candidate | Local Android and hardware evidence | Formats / OSS boundary | What might beat Prism | Integrability / maintenance verdict |
|---|---|---|---|---|
| 1. Upstream llama.cpp Hexagon | Documented Android build and HTP device tooling; experimental | GGUF; llama.cpp OSS with Qualcomm SDK/runtime conditions | DSP/NPU offload without replacing model ecosystem | Highest-value accelerator discovery; BUILD EXPERIMENT. Not generic production NPU support. [U06] |
| 2. UbiquitousLearning/mllm | Mobile runtime; Android streaming and QNN AOT work documented | Own model/export path; MIT project, third-party components and vendor SDKs separate | Mobile prefill/graph lowering and NPU-aware work | Serious research contender, less proven integration convenience. SDK/build access issue and historical hang report justify strict recovery gates. [R24–R28] |
| 3. ORT GenAI + QNN | ORT supports Android QNN; GenAI supplies decode/KV/sampling loop | ONNX/prepared packages; OSS core, vendor backend components | Existing ONNX model deployment or Qualcomm graph acceleration | MONITOR / narrow export bake-off. ORT Android support does not prove every GenAI model path is packaged for Android. [R29–R32] |
| 4. PowerInfer-2 | Research demonstrates mobile sparse/heterogeneous inference concepts | Paper and public PowerInfer repository are not interchangeable; complete mobile-v2 reproducibility not verified here | Activation-aware scheduling, sparse neuron clusters, memory/I/O co-design | High paper-research value; low immediate adapter readiness. Do not advertise mobile-v2 code as fully available on this evidence. [R37,R38] |
| 5. ExecuTorch MediaTek | Documented D9300/D9400, NeuroPilot Express SDK | PTE/lowered model; vendor SDK requirement | Non-Qualcomm NPU coverage | Conditional vendor experiment if actual target devices/resources exist. [R39] |
| 6. ExecuTorch Samsung | Documented Exynos 2500/2600 delegate and op restrictions | PTE/AI Litecore SDK; not arbitrary GGUF | Potential Exynos NPU path | MONITOR; need LLM-specific export/operator/quality proof, not a CNN example. [R40,R41] |
| 7. NCNN | Mature mobile CPU/Vulkan framework; current MHA/SDPA KV-cache documentation | ncnn converted graph/weights; BSD-style project | Small auxiliary models, vision/embedder paths; lightweight runtime | Not enough evidence of a superior full arbitrary-LLM Android stack. MONITOR for auxiliary inference, not main replacement. [R33,R34] |
| 8. FastLLM | C++ Android compilation is documented, current emphasis includes CUDA/ROCm/NUMA/MoE/disk | HF/exported FastLLM packages; Apache-2.0 project | CPU kernels or heterogeneous scheduling ideas | Android buildability is not a maintained mobile GPU/NPU stack. MONITOR; reject immediate production adapter. [R35,R36] |

TensorFlow Lite/LiteRT and MediaPipe LLM are not counted as three independent performance wins alongside LiteRT-LM. A delegate must have the actual operator and model path needed by the LLM. Similarly, Qualcomm QNN, Hexagon hardware, ggml Hexagon and ExecuTorch-QNN are different layers and have different integration/model-packaging costs.

**New research routes worth testing rather than merely listing:** program-binary cache placement under Android private cache; prefill/decode phase-specific plans; incremental template prefix stability; actual kernel dispatch diagnostics; peak-memory calibration for a failed-load fallback; and a dedicated embedding context instead of mutating the chat context. These emerge from Prism’s implementation and competitor failure modes, not marketing benchmarks.


## 16. Adversarial pass on every engine and reconciliation

### Per-engine red team
| System | Claimed advantage | Supported evidence | Main attack / hidden cost / failure probe | Integration verdict |
|---|---|---|---|---|
| Prism/llama integration | Flexible private Android GGUF host | Real Kotlin/JNI/C++ path and native runtime | Output loss, sampler state, prompt/cache correctness, misleading telemetry, lifetime and admission | MUST FIX integration before ranking speed. [P02–P25] |
| Upstream llama.cpp | Broad portable optimized runtime | Backend docs, APIs, Android/Hexagon tooling | API churn; model/backend op gaps; zero layers not necessarily strict CPU; driver-dependent stability | Keep primary; borrow tested upstream facilities. [U01–U07] |
| PocketPal | Strong local-model product experience | Context ownership/device rules; architecture metadata estimator | Shared substrate; all-layer SWA underestimate; draft/context/settings confound benchmark | Adapt product policy, not blanket formulas. [R01–R03,R42] |
| llama.rn | Rich native mobile capabilities | Native enumeration, logs, metadata and documented feature APIs | Broad lifecycle surface; unknown KV fallback to F16; Android capability depends on pin/backend | Borrow permissive substrate concepts, not JS rewrite. [R04,R05,R43] |
| MNN | Mobile graph/runtime optimization | Export guide and runtime cache/chunk configuration | Conversion/quantization quality, scratch/weight preparation, disk-KV I/O, backend-flag semantics | Standalone alternate bake-off. [R06–R09] |
| MLC | Compiler-generated high performance | Android compiled-library packaging | Precompiled-vs-dynamic confound; build/packaging effort; serving features may not be mobile features | Monitor or curated-model bake-off. [R10–R12] |
| ExecuTorch | Small AOT runtime, many delegates | Export/memory planning/Android QNN docs | Partition fallback, calibrated activation precision, per-backend artifacts, SDK dependencies | Narrow optional backend candidate. [R13–R16] |
| LiteRT-LM | Optimized on-device LLM platform | Kotlin/runtime packaging and current releases | Model limits, delegate-native deps, OpenCL/cache/grammar issue reports, model-specific MTP claims | Curated capability bake-off. [R17–R20] |
| ChatterUI | Rich mobile conversation controls | Manifest confirms forked native binding | AGPL code boundary; fork drift; prompt/stop differences; no independent kernel win | Concept only pending license review. [R21,R44,R45] |
| SmolChat | Small Android llama wrapper | Native modules, unarchived repository, June release-prep commit | Smaller maintenance surface can also mean fewer certified cases; no speed evidence | Minimal-integration reference. [R22,R23] |
| MLLM | NPU-aware mobile multimodal inference | Android/QNN tasks and research | SDK access, missing/changed scripts, historical hang, conversion | Research experiment, isolated binary. [R24–R28] |
| ORT GenAI/QNN | Familiar ONNX ecosystem + accelerator | ORT Android QNN and generative loop documented | GenAI Android package/model coverage not implied by ORT CPU support; graph export costs | Monitor/narrow bake-off. [R29–R32] |
| NCNN | Lightweight mobile inference | CPU/Vulkan and KV operators | Operator support is not complete LLM product capability; tokenizer/model conversion missing from comparison | Auxiliary models only until proven. [R33,R34] |
| FastLLM | Low-dependency optimized C++ engine | Android compilation and current deployment guide | Server/NUMA/GPU priorities; no proven maintained phone accelerator superiority | Monitor, not immediate adapter. [R35,R36] |
| PowerInfer-2 | Sparse mobile heterogeneous performance | Research paper | Model sparsity/architecture dependence, missing reproducible mobile packaging, storage tradeoffs | Adapt research concept; no production copy assumption. [R37,R38] |
| MediaTek/Samsung delegate paths | Vendor NPU performance | SDK and specific SoC docs | Hardware/SDK/export limits; Samsung LLM support unproven by generic op/CNN examples | Conditional hardware research only. [R39–R41] |

For any competitor without an inspected, applicable issue, the table provides a **failure probe**, not an invented historical bug. Contributor counts, exhaustive issue triage and release-day device certification were not measured. They remain intake checks for a production dependency rather than false precision in this report.

### Attack the entire research program
The following are competing hypotheses, not rhetorical claims to settle by preference.

| Adversarial proposition | Strongest case for it | Counter-case | Resolving experiment / decision |
|---|---|---|---|
| Replace llama.cpp | A compiled/delegate engine may substantially beat it for important curated models | GGUF breadth, no conversion and existing integration have high product value | Optimized-llama versus MNN/selected delegate, same checkpoint/tasks, full quality/memory/energy/package ledger; section 32 gate |
| Never replace llama.cpp | Backend evolution can unlock accelerators without format fragmentation | Some architectures/vendor graphs may remain materially better elsewhere | Keep primary, permit a narrow optional backend if it wins; reject ideological “never” |
| CPU beats Vulkan | Short prompts, small models, transfers/shader warm-up and contention can dominate | Long prefill or compatible GPU kernels may win | Cold/warm, short/long PP and sustained TG across partial/full offload with strict CPU control |
| OpenCL is not worth it | Driver/library restrictions and another test matrix | Adreno-specific kernels/program caching and expanded quant support | Compare CPU/Vulkan/OpenCL per quant and driver; keep only supported measured cohorts |
| OpenCL is the best Snapdragon path | Vendor-focused implementation and warm program cache | HTP/CPU may win, or OpenCL loses on quality/energy/older drivers | Identical model/prompt/quality, actual placement and cold/warm/sustained tests; no universal default |
| NPU is premature | SDK/export/partition/recovery burden | Upstream ggml Hexagon reduces format/integration burden | Early capability smoke in isolated flavor, production promotion only after stability/energy gates |
| NPU should move earlier | Capability discovery may reveal a large gain with little app work | Does not fix current output and measurement defects | Research intake can run parallel; native wiring waits for lifecycle/plan baseline |
| Speculation is worthless on phones | Draft memory, proposal work and verification hurt small models/low acceptance | N-gram or compatible MTP may amortize expensive target work | Acceptance, target evals, proposal cost, TTFT, PSS, energy and sustained TG on code/repetition/general chat |
| Speculation is the biggest win | Target model bandwidth can dominate decode | Large acceptance ratios do not guarantee wall-time or energy gains | Measure accepted output tokens per total wall time/J, not accepted draft tokens alone |
| Longer context is harmful | KV memory, attention cost and bigger prompts can dominate | User task may require retained instructions/context | Answer-quality and task-completion frontier at 2K/4K/8K/fit-safe larger contexts; don't optimize speed by silent loss |
| Q4 KV damages quality | Cache error can affect long-context retrieval and precise output | Some model/task combinations tolerate it | Teacher-forced logit/perplexity deltas + long-context tasks + structured-output validity vs F16; model-specific allowlist |
| Memory estimates are optimistic | File bytes, hybrid KV, scratch and fallback changes can hide peaks | Conservative fixed overhead may also reject feasible models | Measure per-phase high-water memory, test worst-case fallback and estimate error; separate false acceptance/rejection |
| Offload assumptions are wrong | requested layers/general support aren't placement | Actual loader may still offload effectively | Device/tensor/buffer diagnostics; operator profile where possible; unknown instead of inferred counts |
| Benchmarks measure wrong work | Synthetic repeated tokens omit sampling, UI, tools and lifecycle | Microbench is useful for kernel isolation | Three nested layers: native controlled compute, native real generation, full Android chat |
| Custom context work is unsafe | Position/history/logit invariants can break after shift/cancel | Model-specific certified sliding policy can save replay | Differential clean replay after every edit/shift/cancel; disable in-place shift for unverified architectures |
| Thermals dominate all tuning | Sustained phone workload is power/thermal limited | Better kernels/less memory traffic can improve energy and sustainable speed | Long randomized crossover, thermal/frequency/power timeline and cooled bursts; retain Pareto frontier |

**Reconciliation:** no universal CPU/GPU/NPU or single-runtime winner follows from available sources. The evidence strongly supports repairing the integration and measuring these hypotheses in controlled cohorts. It does not support postponing correctness until a faster engine appears.


## 17. Cross-engine comparison matrix

**Reading rule:** D = documented or source-exposed, V = vendor/model/quant-specific, E = experimental, I = inherited through the named substrate, U = not verified in this audit, N = not the system’s native workflow. “D” is not an Android conformance certificate. The matrices deliberately avoid equating exposed APIs with tested performance. Columns PPAL/RN/CHAT/SMOL denote PocketPal, llama.rn, ChatterUI and SmolChat.

### Model ecosystem, loading and hardware
| Dimension | Prism now | llama.cpp upstream | PPAL / RN | MNN | MLC | ExecuTorch | LiteRT-LM | CHAT / SMOL |
|---|---|---|---|---|---|---|---|---|
| Native model workflow | GGUF with app limits | Supported GGUF architectures | GGUF via RN | Converted .mnn package | Converted weights + model lib | Exported .pte / weights | Prepared .litertlm | GGUF via binding / native llama |
| Arbitrary compatible model import | D, cap/metadata risks | D within supported arch/quant | D within pin/support | N: export required | N: compilation/package | N: export/lowering | N: prepared supported models | D within substrate |
| Q4_0 / Q4_K_M / Q8 | Backend-dependent GGUF | D, op/backend-dependent | I | Different export quants, not equivalent | Different compiler quants | Backend export precision | Supported package precision | I |
| IQ / TQ / 1-bit | Partial ecosystem, actual support test | Model/backend-specific | Pin/backend-specific | Release-specific quant paths | U | Model/export-specific | U | I/U |
| LoRA | Native apply path, lifecycle tests needed | D | D in RN | Documented merge/split | U for current Android path | Model/export-dependent | U for selected current model | I/U |
| Multimodal | Vision JNI stubs false | Upstream multimodal facilities | D in RN, product/pin-specific | D converted multimodal | D model-dependent | D model/export-dependent | D selected models | Fork/model-dependent / U |
| Embeddings | API exists, architecture/pooling risky | D appropriate models | D in RN | D appropriate model | Model/export-dependent | Exported embedding model | Model/API-dependent | U / separate vector module |
| Chat template | Flattened user + heuristic/basic template | Basic + common chat utilities | Structured/Jinja in RN | Jinja configuration | Model configuration | Runner/export configuration | Conversation API | Product-configured / U |
| Grammar / structured output | GBNF, fail-open issue | D | D | Runtime-specific, verify parity | Serving D, Android parity U | Runner-dependent | D, model/tokenizer tests | Binding-dependent |
| mmap | Yes first attempt | D backend-dependent | D/policy-dependent | Weight/KV options D | Loader/artifact-dependent | Loader-dependent | Prepared artifact/backend-dependent | I / I |
| mlock | Disabled | Optional | Optional in RN | U | U | U | U | I/U |
| Repacking/preparation | Implicit upstream, not planned | Backend-specific | I | Export/runtime preparation | Compile/export preparation | Export/delegate preparation | Converter/delegate preparation | I |
| Tensor verification | Every native load requested | Optional facility | Pin/config-specific | Converter/runtime checks | Export/artifact checks | Export/artifact checks | Converter/runtime checks | I/U |
| Compiled artifact cache | Not explicit policy | OpenCL D; others inspect | Backend-dependent | Cache paths D | Model libraries central | Backend artifacts central | Engine cache dir D | I/U |
| Cold / warm load telemetry | Coarse load only | Tools available | Product/bench-dependent | Native metrics available | Compilation/load separate | Profiling tools D | SDK metrics/model-dependent | U |
| CPU ARM kernels | ggml + compiled KAI | NEON / ISA/KAI paths | I | ARM optimized; release-specific KAI | Generated/target kernels | XNNPACK/portable/backend | CPU delegate/model path | I |
| DOTPROD / I8MM / SME | Build/runtime eligibility unobserved | Feature-dependent D | I | Target/release-dependent | Target compiler-dependent | Kernel/backend-dependent | Delegate-dependent | I |
| big.LITTLE / affinity | Threads heuristic; no observed native topology policy | Affinity/thread options | Product heuristics/RN controls | Backend thread policy | U | Backend/runtime-specific | U | CPU info / U |
| Separate PP/TG threads | No | D | RN parameters | Runtime-specific | Runtime-specific | Runner/backend-specific | Runtime-specific | Binding-specific |
| Vulkan | Built by default, no explicit selection | D, driver-specific | RN/product support differs | Release-documented | Android GPU path | D delegate | Not assumed; selected SDK backend | Fork-dependent / I-U |
| OpenCL / Adreno | Experimental adreno flavor | D, quant/driver-specific | D with restrictions | D | Android target-dependent | QNN GPU distinct from direct OpenCL | D GPU path on supported Android | Fork-dependent / U |
| Metal conceptual comparison | N Android | D Apple | D Apple RN | D Apple | D Apple | MPS/CoreML Apple | D Apple | N Android / N |
| Integer dot / coop matrices | Explicitly disabled Vulkan paths | Build/device-dependent | I/backend-dependent | Kernel/backend-dependent | Compiler target-dependent | Delegate-dependent | Delegate-dependent | I/U |
| Partial / full offload | Requested layers, not observed | D | RN device/layer controls | Graph/operator backend policy | Compiler/graph policy | Partition/delegate policy | Delegate/model policy | I/U |
| Qualcomm HTP/QNN | Not live Prism feature | Hexagon E | RN HTP E | Vendor path V | U | QNN V/D | NPU V/D | U |
| MediaTek / Samsung NPU | No | U | U | Vendor-specific U | U | Documented V; LLM coverage varies | Vendor-specific U | U |

Sources: Prism [P02–P25]; upstream [U01–U07]; PPAL/RN [R01–R05,R42,R43]; MNN [R06–R09]; MLC [R10–R12]; ExecuTorch [R13–R16,R39–R41]; LiteRT-LM [R17–R20]; CHAT/SMOL [R21–R23,R44,R45].

### Memory, context, decoding and mobile behavior
| Dimension | Prism now | llama.cpp upstream | PPAL / RN | MNN | MLC | ExecuTorch | LiteRT-LM | CHAT / SMOL |
|---|---|---|---|---|---|---|---|---|
| Weight/KV/compute byte reporting | Heuristic / missing actual split | API/log/backend dependent | Better metadata, not complete observed placement | Runtime/allocator dependent | Planned/runtime dependent | Planned allocations/profiling D | API/delegate-dependent | U |
| Quantized K and V | Requested q8 defaults, silent fallback | Type/FA/backend/arch-specific | RN D, estimator accounts block bytes | Quant attention/cache modes | Compiler/runtime-specific | Export/runner-specific | Model/runtime-specific | I/U |
| Paged KV | No explicit serving-style policy | Runtime/version-specific | Slots do not by themselves prove paging | U | Serving KV facilities, Android parity U | Runner/backend-specific | U | U |
| Native SWA / hybrid memory | Not recognized by app estimator/shift policy | Model-aware native implementations | Estimator simplistic all-layer SWA; RN model-specific | Architecture/config-specific | Model/compiler-specific | Export/model-specific | Current local-attention release work | I/U |
| Disk-backed KV | No | Session persistence ≠ live disk KV | State persistence ≠ paging | D, explicit mmap option | U | U | U | U |
| Model-fit admission | Fixed KV heuristic + unsafe history override | App responsibility | Product estimator, still imperfect | App responsibility | Package/planning + app responsibility | Planned memory + app responsibility | App/runtime dependent | U |
| Prefix reuse | Custom, unsafe invalidation paths | Primitives + server policy | D/pin-specific | D reuse/prefix paths | Serving D | Runner-dependent | Conversation/cache behavior | Product/binding-dependent |
| Message-aware pruning | Approximate flattened history | Application policy | Product policy | Application policy | Application policy | Application policy | Conversation API ≠ proof of priority policy | Product policy |
| Context shifting | Custom policy around upstream ops | Model/API restrictions | RN disables in some multimodal cases | Runtime-specific | Compiler/cache-specific | Runner-specific | Model/cache-specific | I/U |
| Prompt chunking | Logical=batch microbatch | Independent parameters | RN independent controls | Chunk limits D | Prefill chunk D | Runner/export dependent | Model/delegate-specific | Binding-specific |
| Standard decoding | D, sampler bug | D | D | D | D | D | D | D |
| N-gram speculation | Compiled unused | Version/mode-specific D | RN mode-specific | Speculative runtime D, exact modes verify | Serving modes | Runner-specific | Model-specific speculation | U |
| Draft / MTP / EAGLE | Not live | Compatible mode/model-specific | RN compatible draft/MTP D | Release-specific speculation | Serving draft/EAGLE/Medusa; Android U | Model/runner-specific | Selected MTP models D | U |
| Acceptance-rate evidence | None | Tools/mode-specific | RN counters | Mode-specific | Serving metrics | Runner-specific | API/model-specific | U |
| JNI/JSI copies | Reusable arrays but copies; polling | Binding-specific | Native + RN transport | JNI wrapper | JNI/runtime interface | JNI/runtime API | Kotlin/JNI API | JS/native / JNI |
| UTF-8 correctness | Per-drain decoding + lossy sanitizer | Binding responsibility | Native utilities; full stream tests still required | Wrapper-dependent | Wrapper-dependent | Wrapper-dependent | Wrapper-dependent | U |
| Backpressure / terminal drain | Known loss paths | Binding/server responsibility | Broad lifecycle surface | Wrapper-dependent | Wrapper-dependent | Wrapper-dependent | Wrapper-dependent | U |
| Cancellation | Abort callback + join, cache/lifetime issues | Backend/chunk-dependent | D, release fixes | D/runtime-dependent | Runtime/chunk-dependent | Runner/backend-dependent | API/backend-dependent | Binding-dependent |
| Foreground/background lifecycle | Service special-use + timeout/teardown | App responsibility | Product AppState/auto-release | Android app layer | App layer | App layer | App layer | App layer |
| Memory callbacks/process death | Memory push/poll and transcript persistence | App responsibility | Product policy | App layer | App layer | App layer | App layer | App layer |
| Thermal/battery adaptation | No verified unified live foreground policy | App responsibility | Product/device policy, sustained evidence U | App layer | App layer | App layer | App layer | App layer |
| Automatic device plan | Product heuristics, no evidence-keyed plan | Tools/defaults, not full app policy | Device rules/versioned settings | Runtime config | Compiled target | Export/delegate | EngineConfig | Product controls |
| Reproducible PP/TG | Synthetic native path, contaminated labels | Benchmark tooling D | Bench/product-specific | Bench tooling | Bench/serving tools | Profiling/runner | Benchmark/API tools | U |
| End-to-end TTFT/tails | Misdefined TTFT; no token tails | App instrumentation | App-dependent | App-dependent | App-dependent | App-dependent | App-dependent | App-dependent |
| Energy/sustained thermals | Not collected in inspected ledger | External app/lab work | No equivalent study established | No equivalent study established | No equivalent study established | No equivalent study established | No equivalent study established | No equivalent study established |
| Accelerator/OOM fallback | mmap retry ≠ CPU fallback; silent context changes | Backend error surface | Product/pin-dependent | Runtime/wrapper-dependent | Delegate/package-dependent | Partition/wrapper-dependent | Delegate/runtime-dependent | U |
| Maintenance cost to Prism | Existing integration; repair needed | One dependency + backend matrix | No JS port needed to borrow concepts | Second export/runtime/SDK matrix | Compiler/model-library matrix | Export/PTE/vendor matrix | Prepared-model/delegate matrix | Fork/license cost / small reference |

### Licensing and adoption gate
Prism is Apache-2.0 [P30]. llama.cpp, PocketPal and llama.rn declare permissive MIT-style project licensing; MNN, MLC and LiteRT-LM declare Apache-2.0; ExecuTorch uses a BSD-style license; SmolChat declares Apache-2.0. These are project-level declarations, not certification of every dependency or binary. ChatterUI’s inspected AGPL-3.0 file is a material direct-code-borrowing constraint. [U07,R01,R04,R06,R10,R13,R17,R22,R45]

Every adopted component needs a pinned LICENSE/NOTICE inventory, transitive licenses, changed-file notices where applicable, model-weight redistribution terms, tokenizer/data terms, vendor SDK redistribution terms and APK-native-library provenance. Marketing “open source” must not erase proprietary driver/runtime requirements. No particular SDK redistribution entitlement was established here; unresolved entitlement blocks packaging rather than being guessed.


## 18. Unexplored optimization opportunities

These are hypotheses and controlled experiment designs. The existing source motivates them; none has a measured gain in this audit.

| Area | Candidate change | Why it might help | Experiment / hidden cost / rejection condition |
|---|---|---|---|
| CPU phases | Independent decode/prefill threads | Different compute/bandwidth balance across phases | Search small thread sets on observed topology; measure energy, PP, TG and UI contention. Reject universal maximum threads. [P09,U05] |
| CPU topology | No affinity versus performance-core subset versus balanced subset | Avoid inefficient worker synchronization or little-core tail | Probe allowed CPU set; per-worker affinity, not just launcher thread. Respect cpuset/hotplug; restore on exit. Reject root/governor manipulation. |
| CPU scheduling | Normal versus appropriate Android priority and PerformanceHintManager session | Let the OS understand bounded work duration | Register actual worker TIDs, report actual work duration, distinguish PP chunks/decode loops; no fabricated deadlines or real-time priority. API-gated and benchmarked. [A03] |
| CPU kernels | KleidiAI/ISA dispatch verification | Compiled acceleration may not be selected for relevant tensor shapes | Compare supported quant/type/model/shape with logs and profiler; portable fallback retained. No global march=native or forced unsupported ISA. [U05] |
| CPU allocation | Persistent threadpool and reusable prefill batch storage | Reduce repeated setup/allocation | Profile first. Avoid keeping expensive speculative or giant batch scratch alive by default. |
| CPU memory | Quant/layout-specific bandwidth analysis | TG may read large weights every token | Compare bandwidth proxies/page faults versus arithmetic time; do not infer bandwidth saturation from model size alone. |
| GPU | Backend × offload × ubatch interaction | Full offload may lose to partial offload due to transfers/scratch | Coarse offload levels then local search; backend must report device and applied plan. No same-cell multiple changing factors without attribution. |
| Vulkan features | Integer dot and cooperative matrix paths | Faster supported shader variants | Isolated variants, supported extensions/shape tests, driver UUID/versions, CPU references. Reject global enabling based on a newer phone name. [P02,U05] |
| GPU caches | Pipeline/program cache lifecycle | Reduce cold-to-warm shader compilation costs | Hash device/driver/runtime/source/build settings, atomic private-cache write, corruption fallback, eviction budget; distinguish runtime compilation from model load. [U03] |
| OpenCL | Modern quant coverage and Adreno kernels | Better quant-specific mobile kernels | Q4_0/Q4_K_M/Q8 supported cases; unsupported tensors must yield visible fallback, not assumed coverage. Binary X2 desktop kernels do not establish Android availability. [U03] |
| GPU transfers | Avoid needless host copies / repeated staging | Reduce load peaks or PP overhead | Trace allocation/copy phases and actual scheduler. Shared physical RAM does not mean zero copy or zero bandwidth cost. |
| Compiler | O3/O2/size baseline, ThinLTO/LTO, measured PGO | Code layout, inlining or startup/binary improvements | Toolchain-pinned release-like ABIs; representative profile set, held-out models; binary size/build time included. No fast-math without quality analysis. |
| Binary | Strip release symbols with separate symbol artifacts; remove dead speculative target if unreferenced | Smaller distribution and clearer native dependency surface | Inspect link map and symbol retention first. Do not sacrifice crash symbols or assume compiling unused source means APK bloat. [P02,P03] |
| Load | mmap versus read/repack; bounded prefault/warm-up | Trade load latency, first-token faults and residency | Process-cold/page-cache-warm versus truly cold storage separately; peak PSS and faults; user-consented warm lifecycle. [P09] |
| Integrity | Hash while importing; cache tensor verification by immutable identity | Avoid repeated full-file scans | Hash+size+version+validator/runtime+policy; modified/external file revalidation. Never turn verification off for untrusted files merely to improve load charts. [P18] |
| mlock | Keep off; only isolated feasibility probe if justified | Potential page-fault predictability | Android memory pressure, privilege/limit failure and eviction harm generally outweigh unproven benefit. Never force-lock model weights as default. |
| KV | Independent K/V types and actual architecture-aware sizing | Increase feasible context/model size or reduce bandwidth | F16 reference, q8 first, selected q4 tests; FA/type/backend support and quality gates. Do not claim memory savings from nominal bit count alone. |
| Context | Exact message budget and stable prefix | Avoid unnecessary re-prefill and preserve instructions | Tokenized canonical prompt hashes, actual prefix length, 2–10 turn chats, history edits and tool schema changes. |
| Cache state | Optional session snapshot | Reduce reload/prefill for explicitly resumed chat | Privacy, disk size, startup I/O, model/template/LoRA/context/KV/runtime identity, authenticated atomic format; default no persisted KV. Session snapshots are not disk-backed live KV. |
| Prefill | Separate logical/physical chunking and cancellation checkpoints | Control graph memory and abort latency | PP and cancellation P95 together; too-small chunks can harm accelerator occupancy. |
| Hybrid prefill | CPU/GPU phase-specific execution | Accelerators may benefit PP more than TG | Only if runtime can transfer/reuse KV safely and cheaply; prototype outside production; count synchronization/transfer cost. |
| Sampling | Accept-once, explicit seed/greedy, grammar setup profiling | Correctness plus potentially reduced wasted work | Preserve defined sampling semantics; separate grammar compile/setup from token filtering; no unsafe sampler-state reuse. |
| Speculation | N-gram, then tiny draft or compatible MTP | Reduce costly target decode iterations | Proposal/verification cost, acceptance, rollback buffers, energy, memory, general chat and repetitive code; no EAGLE support assumed for arbitrary GGUF. |
| Bridge | Lossless batch drains with sequence IDs; bounded UI refresh | Reduce JNI/Compose overhead without text loss | Native generated timestamps vs drain/render; immediate first token and bounded coalescing thereafter; keep one incremental UTF-8 decoder. |
| Direct buffers | Consider only after transport profiling | Avoid array copy in proven hotspots | Complicates leases/ownership and memory ordering; reject if bridge costs below meaningful noise. Current reusable arrays already avoid some allocations. [P06,P07] |
| Lifecycle | One operation lease; service-owned thermal policy | Prevent concurrent load/benchmark/embed/teardown races | Process death, FGS failure, background transition, system trim, cancellation storm, restart; no long main-thread blocking. |
| Thermals | OS status + valid headroom + battery safety, hysteresis | Improve sustained throughput and reliability | Actual effective state uses most severe relevant source; unavailable headroom remains unknown. Rate-limit headroom and unregister listeners. [A01,A02] |
| Storage | Prefer app-private seekable immutable model; bounded import caching | mmap reliability and predictable identity | Measure SAF copy versus linked FD if supported; revoked permission, provider disappears, non-seekable stream, mutation mid-load, disk full. |
| Auxiliary models | Capability-gated dedicated embedding/vision context | Stop mutating the live chat KV for incompatible operations | Model/pooling/embedding dimension/normalization tests, budget simultaneous contexts; do not add a second engine solely for embeddings unless necessary. [P07,P09,P10] |

Do not implement all of these. Instrument first, then select a small set whose measured cost or risk justifies intervention. Eliminating a redundant full-file validation pass can matter more to perceived startup than a small TG improvement; preserving a stable conversation prefix can matter more to TTFT than an extra CPU thread. These are testable possibilities, not universal facts.


## 19. Features Prism should borrow

| Source | Borrowed idea | Prism adaptation and boundary |
|---|---|---|
| llama.cpp common utilities | Structured templates, actual backend discovery, maintained model-aware primitives | Pin APIs; native template/tokenization authority; no blind copy of server conversation policy. [U01,U04–U06] |
| llama.rn | Device enumeration/IDs, Android abort diagnostics, capability-based model features | Extend current JNI bridge; no React Native migration; unknown KV requests should be typed errors or recorded fallback. [R43] |
| PocketPal | Active-load identity, benchmark ownership, versioned device rules, draft/projector memory components | Full load-key comparison; immutable empirical profiles; own corrected architecture-specific estimate. [R03,R42] |
| MNN | Explicit cache directories and chunk limits; cache failure observability | Small llama.cpp-compatible policy experiments before an alternate engine. Never confuse disk KV with free RAM. [R07,R09] |
| MLC / ExecuTorch | Model artifact provenance, memory-plan inspection, export reproducibility | Adopt evidence discipline even while keeping dynamic GGUF. Exported fast lanes remain optional. [R11,R14] |
| LiteRT-LM | High-level conversation/runtime capability contract | Keep tool authorization, privacy and message preservation in Prism; no automatic unsupported feature claims. [R17–R20] |
| SmolChat | Small native integration as complexity challenge | Reject unnecessary framework layers; benchmark before replacing efficient existing structures. [R22] |
| ChatterUI | Explicit prompt and expert generation controls | Reimplement product ideas; no AGPL code copied without license decision. [R21,R44,R45] |

“Borrow” means retain provenance, investigate semantics and test against Prism’s workload. It does not mean importing an entire architecture because another app exposes more controls.


## 20. Features Prism should explicitly reject

Reject a giant runtime rewrite before the corrected baseline; a generic multi-engine facade before a winning second engine; unconditional Vulkan/OpenCL/NPU selection; maximum threads/layers/context defaults; global ISA or cooperative-matrix flags; root-required governor tuning; forcing mlock; unconditional Q4 KV; treating a driver’s shared-memory architecture as zero-copy proof; backend/placement labels based on requests; automatic tool execution bypassing Prism authorization; and speed claims based on truncated prompts or dropped output.

Also reject serving-oriented continuous batching/paged-KV complexity solely to accelerate a single foreground chat without a demonstrated workload, disk-backed KV as a default phone strategy, unrestricted parallel model contexts under memory pressure, and storing prompts or private KV in shared/exported telemetry without opt-in. Session state can encode sensitive conversation content even when it is not plain text.

Do not directly copy ChatterUI’s AGPL implementation into a distribution intended to remain Apache-only. Do not redistribute a vendor SDK, binary kernel or model checkpoint on the assumption that an open-source host runtime grants that permission. [P30,R45,U03,R15,R39,R40]

These rejections are current scope decisions, not declarations that the underlying research is useless. A future concrete workload and evidence can reopen an item through an isolated mission and explicit release gate.


## 21. PrismLocal Adaptive Inference Runtime target architecture

The name is less important than the ownership model. **Keep the service, existing managers and native engine. Add a small planning/diagnostic contract, not a new framework.** Use “Adaptive Runtime” as a documentation label rather than another independently deployed service.

### Data contracts
All new Kotlin contracts below belong under `com.prismai.llmhost.engine.runtime`, except model metadata under `com.prismai.llmhost.model`. They are proposed new files, not existing APIs.

**HardwareCapabilityProfile:** produced by the existing DeviceProfiler plus a small native capability probe. Contains Android build/SDK/ABI, total/available/threshold memory with timestamps, allowed CPU set/core topology when readable, runtime ISA flags, backend registrations, device IDs/driver versions/UUIDs where available, supported feature flags, thermal status/headroom with validity, battery temperature/charge/power-saving/charging state. An unavailable field is null with a reason. Product-name rules are hints with provenance, never substitutes for probing.

**ModelCapabilityProfile:** content SHA-256, format version, architecture, native context/RoPE metadata, actual tensor types/bytes and parameter count where derivable, per-layer attention/state family, K/V heads/dimensions, full/local attention layout, quantization inventory, tokenizer/template identity, required projector/adapter metadata, supported operations and confidence. Preserve unknown architecture details rather than using a dense-transformer equation indiscriminately. Compute parameter count from validated tensor inventory when possible, not a filename suffix.

**BackendCapabilities:** compiled, loadable, enumerated and usable are different booleans/states. Record device, supported precision/ops, FA/KV combinations, state copy/rollback/shift/multimodal support and allow/deny evidence. A library or device name alone is not a successful execution probe.

**InferencePlan:** immutable request including backend/device selection, decode/prefill threads, affinity policy, n_batch/n_ubatch, requested context, K/V cache types, FA policy, mmap/mlock/repack policy, model/adapter identities, speculative configuration, workload class and fallback policy. Add schema_version, plan_id and reason codes. Sampling policy is request-scoped and separate from the model-load key.

**AppliedRuntimeState:** native authority for actual context/batch/ubatch/thread values, actual KV/FA/load choices, successful backend/device configuration and fallback events. **ObservedPlacement:** optional measured layer/tensor/buffer data with source and uncertainty. Never copy a requested field into an observed field. These can live in `InferencePlan.kt` as several data classes rather than generating a file for every noun.

**RuntimeProfile:** device/driver fingerprint + model content hash + Prism/build/ABI/compiler + llama revision/backend version + load/sampling/template configuration + workload/thermal cohort. Store distribution summaries, sample counts, raw artifact IDs, confidence interval and quality/reliability status. A model ID or 12-character display hash is not an adequate cache key.

### Ownership and interfaces
| Component / proposed file | Input → output | Ownership boundary |
|---|---|---|
| Existing `DeviceProfiler.kt` + new `engine/runtime/HardwareCapabilityProfile.kt` | Android/native observations → dated capability snapshot | Observe only; cannot select/execute load or change UI preferences |
| New `model/ModelCapabilityProfile.kt` + existing storage parser | Validated GGUF inventory → architecture facts/confidence | Cannot silently infer unsupported metadata; private immutable import identity |
| New `model/ModelMemoryEstimator.kt` | Model+plan+backend → memory range, assumptions, unsupported reason | Pure estimator; no history override or load side effect |
| New `engine/runtime/InferencePlan.kt` | Typed intent/applied/observed contracts | Shared ABI schema; loadKey derived from all load-affecting fields |
| New `engine/runtime/RuntimePlanner.kt` | Capabilities/model/history/workload/safety → candidate plan or rejection | Pure decision; explain why and which evidence cohort; no JNI directly |
| New `engine/runtime/RuntimeProfileStore.kt` | Clean benchmark artifacts → versioned empirical history | Private local store, atomic writes, retention limit; no raw chat by default |
| New `engine/runtime/InferenceAutoTuner.kt` | Safe candidate set + exclusive benchmark lease → validated trial records | Bounded work; cannot bypass thermal/memory/quality or auto-promote failed candidates |
| Existing ModelManager / EngineConfigStore / InferenceService | Preferences+plan → serialized apply/load/generation | Service owns operation lease; ModelManager compares full loadKey; native owns applied truth |
| Existing NativeLlmBridge + new native `NativeHandleRegistry.*` | Opaque ID → scoped in-flight engine lease | Lifetime safe across all JNI calls; destroy closes admission then drains leases |
| New native `ConversationState.*` and `StreamProtocol.*` | Canonical tokens / committed output → cache and stream events | One native worker commits state; no Kotlin guessing about KV positions |

These additions solve current ownership and evidence problems. They are **not** a plugin interface for imagined engines. An optional backend interface is deferred until section 25’s gate is passed.

### Load, execution and fallback state machine
```text
Idle → Admission → Loading → Ready → Generating → Draining → Ready
                 ↘ Rejected      ↘ Failed / Cancelled → CacheInvalid → Ready
Any state → Closing (no new leases) → cancel/join/drain in-flight calls → Closed
```

Sampling updates do not require reloading weights. Context/KV/FA/device/offload/mmap/repack changes do. Thread changes may be applied at a safe phase boundary and recorded as timeline events. Once a native fallback changes a load-affecting field, create a new applied-plan identity and recalculate memory admission; do not continue reporting the desired plan.

Fallback is error-specific, bounded and acyclic. On graph scratch allocation failure, try smaller ubatch then batch. On incompatible KV/FA, select a supported combination only after re-admission—F16 may use more memory, not less. On GPU allocation/device failure, teardown that failed attempt before reducing offload or trying strict CPU. On context exhaustion, rebuild from a message-aware pruned prompt or explicitly ask the user to reduce required input; never silently discard a mandatory system/tool prefix. On SIGSEGV/SIGABRT, in-process exception fallback is not reliable; record crash provenance for the next start and quarantine the profile. A separate inference process is a later containment option only if crash evidence justifies IPC complexity.

After partial output, never silently restart a generation and concatenate a different completion. Mark the attempt interrupted and require an explicit retry/resume policy. User text must not be duplicated or represented as a seamless successful response.

### Memory estimation that does not pretend every model is Llama
For a conventional attention layer l with S_l actually allocated slots:

```text
KV_l ≈ S_l × [Hkv_l × dK_l × bytesK + Hkv_l × dV_l × bytesV]
```

That is an explanatory equation only. The implementation must use actual tensor layout/row size, quantization block metadata, alignment, slot padding and backend allocation rules, preferably `ggml_row_size`/actual tensor-byte calculations for the selected revision. K and V may have different dimensions/types/layouts. Sum by layer. Native full-attention layers keep full allocation even when other layers use SWA. A runtime configured to allocate a full SWA cache must not be estimated as only the sliding-window size. Recurrent/SSM, MLA/compressed latent attention and hybrid architectures require separate formulas/metadata or an explicit unknown/conservative bound. [P15,P18,R42,U01]

Total admission budget covers private resident weights/repack copies, mapped-resident contribution, KV/recurrent state, peak compute/scratch/output/embedding/projector/draft buffers, Java/UI reserve, import/load overlap and safety margin. Shared physical pages must not be counted twice merely because CPU and GPU both reference them; private driver allocations must not be omitted because ordinary RSS cannot see them. Distinguish estimates from observed PSS/RSS and document unobservable allocations. Do not estimate reclaimed memory by adding the model file size to available RAM.

### Bounded planner and auto-tuning algorithm
1. Reject unsupported/unsafe candidates first. Preserve known-good strict CPU and a conservative context/ubatch. Require a passed correctness cohort before any plan becomes eligible.
2. Inspect workload: short interactive prompt, long prefill, multi-turn reuse, sustained decode, structured output or auxiliary inference. Pick the predeclared primary objective and constraints.
3. Select at most a few backend × coarse offload × microbatch seeds. These interact; choosing a backend under one bad microbatch and never revisiting it biases the search.
4. Run small paired trials against baseline, with the same tokenized prompt and thermal acceptance window. Drop obviously dominated/failed candidates; retain failures in the ledger.
5. Tune decode/prefill threads locally around survivors, then refine batch/ubatch and load/cache policy. Do not run a full Cartesian grid.
6. Treat KV changes as separate quality-qualified branches. Treat speculation as a later branch with its own memory/quality gate, not another unconstrained integer in the first search.
7. Confirm winners on held-out prompts and cooled randomized/ABBA repeats; then run sustained tests for only the top few. Separate exploratory selection data from confirmation data to reduce winner’s-curse bias.
8. Persist evidence keyed by complete device/model/runtime identity. Prefer robust near-optimal plans over tiny noisy wins. Invalidate on driver/runtime/model changes, and downgrade stale history to a prior, not safety proof.

Suggested search budget policy: at most 12 initial burst candidates and 2 sustained finalists per explicit tuning session, with battery/temperature/elapsed-work budgets and a user cancel control. These are limits on execution, not a promise of run duration. Automatic day-to-day adaptation uses accumulated safe history; it does not launch a long benchmark whenever the user opens chat.


## 22. Detailed code and file mapping

This is the implementation boundary contract. Paths without an explicit “new” designation are existing files/directories or permitted scope; files in the **New files** column are proposed, not claimed to exist in the audited repository. Package names follow `com.prismai.llmhost` and the directory beneath it. The target classes’ responsibilities/interfaces are specified in Section 21; complete steps, checks and stop rules follow in Section 29. Existing Kotlin homes are preferred to a replacement framework.

The evidence column establishes why a problem or experiment exists, not its expected measured speedup. A research-only mission may legitimately return no production change. Shared wiring is integrator-owned even when a packet author proposes the patch.

| ID | Feature | Problem / intended value | Evidence | Allowed / likely modified files | New files | API changes | Native changes | Tests | Benchmarks | Risk | Dependencies |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PIR-00 | Freeze provenance, harness and baseline contract | Foundational reproducibility and exact attribution | P00–P03,P26–P29 | `research/inference/`; `app/src/test/cpp/`; `scripts/inference/`; `docs/inference/` | `research/inference/baseline.lock.json`; `app/src/test/cpp/CMakeLists.txt`; `scripts/inference/capture-baseline.sh`; `docs/inference/evidence-contract.md` | No production API change. Define manifest/schema and host-test registration contract. | Portable deterministic native test harness only; do not change inference behavior. | git rev-parse HEAD && git submodule status --recursive; git status --porcelain=v1; ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug; cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure; python3 research/inference/tools/validate_program.py | Capture existing behavior as untrusted historical baseline; do not rank its fake placement or mixed metrics. Physical baseline pending available device/model. | medium | none |
| PIR-01 | Evidence-grade benchmark records and clocks | Makes subsequent optimization decisions valid | P21–P24 | `app/src/main/java/com/prismai/llmhost/benchmark/`; `app/src/main/java/com/prismai/llmhost/generation/GenerationMetrics.kt`; `app/src/test/java/com/prismai/llmhost/benchmark/`; `app/src/test/java/com/prismai/llmhost/generation/GenerationMetricsTest.kt`; `research/inference/schemas/` | `app/src/main/java/com/prismai/llmhost/benchmark/BenchmarkRecord.kt`; `app/src/test/java/com/prismai/llmhost/benchmark/BenchmarkRecordTest.kt` | Versioned benchmark event/record schema; preserve legacy records as untrusted, not silently upgraded. | Specify native event fields for PIR-06; no Engine.cpp edits here. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; ./gradlew --no-daemon :app:testDevDebugUnitTest --tests "*Benchmark*" | No speed claim. Fixture-based schema and known-clock synthetic event timelines; later physical records must pass validator. | medium | PIR-00 |
| PIR-02 | Lossless streaming and native lifetime leases | Output correctness, crash prevention and trustworthy user-visible latency | P06–P09,P25 | `app/src/main/cpp/Engine.cpp`; `app/src/main/cpp/Engine.hpp`; `app/src/main/cpp/llmhost_jni.cpp`; `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`; `app/src/main/java/com/prismai/llmhost/bridge/Utf8TextPipeline.kt`; `app/src/main/cpp/runtime/`; `app/src/test/cpp/stream/`; `app/src/test/java/com/prismai/llmhost/bridge/`; `app/src/androidTest/java/com/prismai/llmhost/bridge/` | `app/src/main/cpp/runtime/NativeHandleRegistry.hpp`; `app/src/main/cpp/runtime/NativeHandleRegistry.cpp`; `app/src/main/cpp/runtime/StreamProtocol.hpp`; `app/src/main/cpp/runtime/StreamProtocol.cpp`; `app/src/test/java/com/prismai/llmhost/bridge/IncrementalUtf8Test.kt`; `app/src/androidTest/java/com/prismai/llmhost/bridge/NativeLifetimeTest.kt` | Versioned DrainResult with produced/committed/drained/final sequence and pending output; typed stream terminal. Opaque handle registry leases. | No raw delete while calls are in flight; close admission then cancel/join and quiesce. Single-consumer ring semantics; incremental UTF-8 bytes. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug; cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure; ./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.bridge.NativeLifetimeTest | Transport-only synthetic producer rates plus small-model end-to-end drain traces. Record generated/drained/rendered timestamps and backpressure/cancel P95. | high | PIR-00 |
| PIR-03 | Correct sampler semantics and fail-closed grammar | Restores model sampling and structured-output semantics | P04,P09,U01 | `app/src/main/cpp/Engine.cpp`; `app/src/main/cpp/Engine.hpp`; `app/src/main/java/com/prismai/llmhost/GenerationSettings.kt`; `app/src/main/java/com/prismai/llmhost/engine/EngineConfigStore.kt`; `app/src/main/cpp/runtime/SamplingPolicy.hpp`; `app/src/test/cpp/sampling/`; `app/src/test/java/com/prismai/llmhost/generation/`; `app/src/androidTest/java/com/prismai/llmhost/generation/` | `app/src/main/cpp/runtime/SamplingPolicy.hpp`; `app/src/androidTest/java/com/prismai/llmhost/generation/SamplingContractTest.kt` | Explicit seed and greedy mode; documented penalties/grammar/stop policy. Retain backward-compatible user sampling preferences. | Accept a token once; require a selector; grammar initialization errors remain errors; remove heuristic repeated-text EOG. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug; cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure; ./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.generation.SamplingContractTest | Correctness baseline regenerated after semantic fix. Sampling setup/token overhead recorded separately; no old-output equivalence required for the buggy penalty behavior. | medium | PIR-02 |
| PIR-04 | Structured prompts and exact message-aware budget | Correct instructions, stable prefix reuse and honest TTFT | P07,P09,P19,P20,U01 | `app/src/main/java/com/prismai/llmhost/generation/PromptBuilder.kt`; `app/src/main/java/com/prismai/llmhost/generation/GenerationOrchestrator.kt`; `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`; `app/src/main/cpp/Engine.cpp`; `app/src/main/cpp/Engine.hpp`; `app/src/main/cpp/llmhost_jni.cpp`; `app/src/main/cpp/runtime/PromptRenderer.hpp`; `app/src/main/cpp/runtime/PromptRenderer.cpp`; `app/src/test/java/com/prismai/llmhost/generation/`; `app/src/androidTest/java/com/prismai/llmhost/generation/` | `app/src/main/cpp/runtime/PromptRenderer.hpp`; `app/src/main/cpp/runtime/PromptRenderer.cpp`; `app/src/main/java/com/prismai/llmhost/generation/PromptEnvelope.kt`; `app/src/androidTest/java/com/prismai/llmhost/generation/PromptContractTest.kt` | PromptEnvelope roles/messages/tools plus explicit raw-prompt mode; native render-and-tokenize budget result. | Template authority and tokenizer flags independent of cache position; selected upstream common-chat integration with explicit compatibility fallback. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug; cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure; ./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.generation.PromptContractTest | 2–10 turn prompt hashes/token counts, short and boundary prompts, tool-heavy prompts and mixed Unicode. Record rendering/tokenization cost; quality before speed. | high | PIR-03 |
| PIR-05 | Transactional prefix cache and model-aware context policy | Correct multi-turn reuse and elimination of avoidable prefill | P09,U01 | `app/src/main/cpp/Engine.cpp`; `app/src/main/cpp/Engine.hpp`; `app/src/main/cpp/runtime/ConversationState.hpp`; `app/src/main/cpp/runtime/ConversationState.cpp`; `app/src/test/cpp/context/`; `app/src/androidTest/java/com/prismai/llmhost/generation/` | `app/src/main/cpp/runtime/ConversationState.hpp`; `app/src/main/cpp/runtime/ConversationState.cpp`; `app/src/androidTest/java/com/prismai/llmhost/generation/ContextReplayTest.kt` | Cache identity, invalidation reason, actual reused/evaluated count and explicit context policy returned to diagnostics. | Commit KV/token/position state transactionally; return-check sequence operations; recompute suffix after every reset/shift. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug; cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure; ./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.generation.ContextReplayTest | Multi-turn incremental versus replay PP, cancellation mid-PP, changed system/LoRA, native SWA/hybrid fixture. Measure saved evaluated tokens, not an inferred cache-hit label. | high | PIR-04 |
| PIR-06 | Requested/applied/observed plan and full load-key wiring | Makes configuration experiments real and protects load safety | P02–P10,P15–P17,P21–P24,U01 | `app/src/main/java/com/prismai/llmhost/GenerationSettings.kt`; `app/src/main/java/com/prismai/llmhost/engine/`; `app/src/main/java/com/prismai/llmhost/model/ModelManager.kt`; `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`; `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`; `app/src/main/cpp/Engine.cpp`; `app/src/main/cpp/Engine.hpp`; `app/src/main/cpp/llmhost_jni.cpp`; `app/src/main/cpp/CMakeLists.txt`; `app/build.gradle.kts`; `app/src/test/java/com/prismai/llmhost/engine/`; `app/src/androidTest/java/com/prismai/llmhost/engine/` | `app/src/main/java/com/prismai/llmhost/engine/runtime/InferencePlan.kt`; `app/src/main/cpp/runtime/RuntimeDiagnostics.hpp`; `app/src/main/cpp/runtime/RuntimeDiagnostics.cpp`; `app/src/androidTest/java/com/prismai/llmhost/engine/PlanRoundTripTest.kt` | Versioned plan ABI: backend/devices, threads PP/TG, batch/ubatch, context, KV/FA/load policies; native applied state and nullable observed placement. | Explicit device selection, actual context readback, independent threads/batches, bounded fallback events and benchmark phase counters. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug; cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure; ./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest; ./gradlew --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease; ./gradlew --no-daemon :app:connectedDevDebugAndroidTest | Round-trip full configuration grid without speed ranking; same-model reload must alter actual native values. CPU/Vulkan/OpenCL requests report actual usable backend or typed failure. | high | PIR-01; PIR-05; PIR-07; PIR-08 |
| PIR-07 | GGUF facts and conservative architecture-aware admission | Safer model size/context selection with fewer false assumptions | P14–P18,R42 | `app/src/main/java/com/prismai/llmhost/storage/ModelStorageManager.kt`; `app/src/main/java/com/prismai/llmhost/model/ModelReadinessAssessor.kt`; `app/src/main/java/com/prismai/llmhost/model/ModelLoadLimits.kt`; `app/src/main/java/com/prismai/llmhost/model/ModelCapabilityProfile.kt`; `app/src/main/java/com/prismai/llmhost/model/ModelMemoryEstimator.kt`; `app/src/test/java/com/prismai/llmhost/model/`; `app/src/test/java/com/prismai/llmhost/storage/`; `research/inference/fixtures/gguf/` | `app/src/main/java/com/prismai/llmhost/model/ModelCapabilityProfile.kt`; `app/src/main/java/com/prismai/llmhost/model/ModelMemoryEstimator.kt`; `app/src/test/java/com/prismai/llmhost/model/ModelMemoryEstimatorTest.kt`; `app/src/test/java/com/prismai/llmhost/storage/GgufMetadataTest.kt` | Validated metadata profile and memory interval with assumptions/confidence; validation stages instead of a single verified bit. | Specify native actual allocation/architecture readback for PIR-06; no central native edit here. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; ./gradlew --no-daemon :app:testDevDebugUnitTest --tests "*ModelMemoryEstimator*" --tests "*GgufMetadata*" | Estimator synthetic architecture fixtures plus later measured per-phase allocation calibration. Oversized model tests must use generated metadata fixtures before real large models. | high | PIR-00 |
| PIR-08 | Hardware and backend capability probes | Enables defensible per-device plans and backend eligibility | P14,U05,U06,R43 | `app/src/main/java/com/prismai/llmhost/model/DeviceProfiler.kt`; `app/src/main/java/com/prismai/llmhost/engine/runtime/HardwareCapabilityProfile.kt`; `app/src/main/cpp/runtime/HardwareCapabilityProbe.hpp`; `app/src/main/cpp/runtime/HardwareCapabilityProbe.cpp`; `app/src/test/java/com/prismai/llmhost/engine/runtime/`; `app/src/androidTest/java/com/prismai/llmhost/engine/` | `app/src/main/java/com/prismai/llmhost/engine/runtime/HardwareCapabilityProfile.kt`; `app/src/main/cpp/runtime/HardwareCapabilityProbe.hpp`; `app/src/main/cpp/runtime/HardwareCapabilityProbe.cpp`; `app/src/androidTest/java/com/prismai/llmhost/engine/CapabilityProbeTest.kt` | Dated hardware snapshot with nullable fields and provenance; backend capability probe output for PIR-06 wiring. | Enumerate actual ggml devices/registrations, ISA and accessible topology; no model allocation required for initial probe. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; ./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.engine.CapabilityProbeTest | Probe latency and allocations only; no performance ranking. Device fingerprints must not include user identifiers in exported artifacts. | medium | PIR-00 |
| PIR-09 | Unified thermal, battery and Android lifecycle safety | Thermal sustainability, predictable recovery and Android reliability | P03,P10–P13,A01–A04 | `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`; `app/src/main/java/com/prismai/llmhost/service/MemoryGovernor.kt`; `app/src/main/java/com/prismai/llmhost/service/ThermalBatteryGovernor.kt`; `app/src/main/java/com/prismai/llmhost/util/AdaptiveThermalGovernor.kt`; `app/src/test/java/com/prismai/llmhost/service/`; `app/src/androidTest/java/com/prismai/llmhost/service/` | `app/src/test/java/com/prismai/llmhost/service/RuntimeSafetyPolicyTest.kt`; `app/src/androidTest/java/com/prismai/llmhost/service/InferenceLifecycleTest.kt` | One effective safety state and policy event stream; actual applied thread changes and interruption reasons. | Use existing plan/lifetime APIs; no new raw native control path. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; ./gradlew --no-daemon :app:connectedDevDebugAndroidTest; bash scripts/emulator/run-tests.sh both | Lifecycle/thermal injected tests plus sustained physical run with real OS status and battery conditions. API26/28 compatibility needs an additional target; existing API30/36 AVDs do not prove it. | high | PIR-06 |
| PIR-10 | Controlled llama.cpp update and research harness wiring | Current backend/model capabilities with controlled regression risk | P01–P03,P26–P28,U01–U06 | `app/src/main/cpp/third_party/llama.cpp`; `patches/llama.cpp/`; `app/src/main/cpp/CMakeLists.txt`; `app/src/main/cpp/LLAMA_CPP_VERSION.md`; `app/src/main/cpp/Engine.cpp`; `app/src/main/cpp/Engine.hpp`; `app/src/main/cpp/llmhost_jni.cpp`; `app/build.gradle.kts`; `research/inference/`; `docs/inference/` | `research/inference/upstream-diff.md`; `research/inference/build-variants.json` | Minimal version adapter for selected upstream APIs; preserve Prism plan/stream contract. | Compare original pin to candidate 661643e43079a4ee6faab4c1895291767b67ea8d; reconcile local Vulkan patch and build flags; no blanket feature promotion. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug; cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure; ./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest; ./gradlew --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease; ./gradlew --no-daemon :app:connectedDevDebugAndroidTest; bash scripts/emulator/run-tests.sh both | Paired old/new runtime on identical model/quant/plan; cooled burst and at least one sustained baseline. Report APK/native size and build time. | high | PIR-09 |
| PIR-11 | CPU phase/thread/topology experiments | Potential high-ROI PP/TG/energy tuning | P09,U05,A03 | `research/inference/experiments/cpu/`; `research/inference/results/cpu/` | `research/inference/experiments/cpu/candidates.json`; `research/inference/experiments/cpu/run_cpu_sweep.py` | No production API changes; use the established plan/benchmark contract. | Research variants only; no Engine.cpp edits. | python3 research/inference/experiments/cpu/run_cpu_sweep.py --help; python3 research/inference/tools/check_run.py research/inference/results/cpu/*.jsonl | At least 5 paired burst repeats for finalists, 2 model tiers if fit, sustained top candidates; energy/thermal and P95 UI/cancel constraints. | medium | PIR-10 |
| PIR-12 | Vulkan capabilities, offload and microbatch bake-off | Potential PP and accelerator-utilization gains | P02,U05 | `research/inference/experiments/vulkan/`; `research/inference/results/vulkan/` | `research/inference/experiments/vulkan/variants.cmake`; `research/inference/experiments/vulkan/candidates.json` | No production API changes; consume backend/plan telemetry. | Research-only shader/feature variants through PIR-10 harness, not global defaults. | python3 research/inference/tools/validate_program.py; python3 research/inference/tools/check_run.py research/inference/results/vulkan/*.jsonl | Matched GGUF/model/quality; representative short/long prompts, at least 2 relevant driver cohorts for broad promotion; unsupported features skipped with reasons. | high | PIR-10 |
| PIR-13 | Adreno OpenCL quantization and cache bake-off | Potential best supported Snapdragon GPU route, not assumed | P02,P03,U03,R04 | `research/inference/experiments/opencl/`; `research/inference/results/opencl/` | `research/inference/experiments/opencl/candidates.json`; `research/inference/experiments/opencl/cache-policy.md` | No production changes; explicit OpenCL device and cache configuration through research harness. | Use selected upstream OpenCL, optional native-library declaration only in isolated research packaging. | python3 research/inference/tools/check_run.py research/inference/results/opencl/*.jsonl | Same physical device with serialized CPU/Vulkan/OpenCL trials; cold/warm cache, at least 5 paired finalists; 2 Adreno/driver cohorts for production breadth. | high | PIR-10 |
| PIR-14 | Load, storage and compiler policy experiments | Potential startup and memory gains with bounded complexity | P02,P03,P09,P18,U05,A04 | `research/inference/experiments/load_build/`; `research/inference/results/load_build/` | `research/inference/experiments/load_build/variants.json`; `research/inference/experiments/load_build/storage-cases.json` | No production changes; measured proposals for existing load policies. | mmap/repack/check/warm-up and compiler variants through research build; inspect link map before removing objects. | python3 research/inference/tools/check_run.py research/inference/results/load_build/*.jsonl; ./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest; ./gradlew --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease | Process-cold/page-cache status labeled, warm model and shader cache cohorts; realistic supported GGUF sizes; cold peak PSS/page faults plus correctness. | medium | PIR-10 |
| PIR-15 | KV quality and memory frontier | Larger usable context/model envelope with explicit quality protection | P09,P15,R42,U01 | `research/inference/experiments/kv/`; `research/inference/results/kv/` | `research/inference/experiments/kv/candidates.json`; `research/inference/experiments/kv/quality_protocol.md` | No production changes; per-model/architecture KV eligibility proposal. | Existing independent K/V/FA plan parameters only; no new custom quant kernels. | python3 research/inference/tools/check_run.py research/inference/results/kv/*.jsonl | Full quality non-inferiority protocol, context-size sweep within safe admission, measured actual allocations; no equality claim across changed precision. | high | PIR-10 |
| PIR-16 | Truthful embeddings, LoRA and vision capabilities | Reliable operation compatibility and honest RAG/multimodal behavior | P07,P09,P10,R04 | `app/src/main/cpp/runtime/EmbeddingRuntime.hpp`; `app/src/main/cpp/runtime/EmbeddingRuntime.cpp`; `app/src/main/cpp/runtime/ModelOperationCapabilities.hpp`; `app/src/androidTest/java/com/prismai/llmhost/model/`; `app/src/test/java/com/prismai/llmhost/model/`; `research/inference/capabilities/` | `app/src/main/cpp/runtime/EmbeddingRuntime.hpp`; `app/src/main/cpp/runtime/EmbeddingRuntime.cpp`; `app/src/main/cpp/runtime/ModelOperationCapabilities.hpp`; `app/src/androidTest/java/com/prismai/llmhost/model/AuxiliaryCapabilityTest.kt` | Operation capability result with typed unsupported and memory requirements; integration patch proposal only for root-owned Engine/JNI/service. | Architecture/pooling-safe auxiliary context; no accidental reuse of chat KV or fake vision success. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug; cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure; ./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.model.AuxiliaryCapabilityTest | Correctness and memory coexistence of chat/embedding/projector contexts, not a mandatory vision feature build. Report unsupported models as such. | high | PIR-10 |
| PIR-17 | Phone-appropriate speculative decoding experiment | Potential decode gain without another primary runtime | P02,P09,R04,R08 | `research/inference/experiments/speculation/`; `research/inference/results/speculation/` | `research/inference/experiments/speculation/SpeculationProbe.cpp`; `research/inference/experiments/speculation/candidates.json` | Standalone experiment using current common speculative APIs; proposed counters/plan additions only if retained. | N-gram first; compatible tiny draft/MTP second; rollback and state ownership proven before production wiring. | python3 research/inference/tools/check_run.py research/inference/results/speculation/*.jsonl | Same target model/quality and tasks; PP/TTFT cannot be hidden by reporting decode-only; small/large model and repetitive/nonrepetitive workloads; memory/energy. | high | PIR-10; PIR-15 |
| PIR-18 | Upstream Hexagon capability and sustained experiment | Potential NPU acceleration while preserving GGUF ecosystem | U06,R04 | `research/inference/experiments/hexagon/`; `research/inference/results/hexagon/` | `research/inference/experiments/hexagon/build_manifest.json`; `research/inference/experiments/hexagon/candidates.json`; `research/inference/experiments/hexagon/sdk-license-inventory.md` | No production backend or generic engine interface; use upstream GGUF device selection. | Isolated Android research executable/flavor with SDK and HTP runtime provenance. | python3 research/inference/tools/check_run.py research/inference/results/hexagon/*.jsonl | Backend-only GGUF controlled bake-off; physical Qualcomm hardware required. Two driver/SoC cohorts for broad promotion, one can justify labeled experimental allowlist only. | high | PIR-10 |
| PIR-19 | Evidence-keyed runtime planner and profile store | Automatic defensible defaults instead of settings burden | P14–P17,P21–P24,R03,R42 | `app/src/main/java/com/prismai/llmhost/engine/runtime/RuntimePlanner.kt`; `app/src/main/java/com/prismai/llmhost/engine/runtime/RuntimeProfileStore.kt`; `app/src/test/java/com/prismai/llmhost/engine/runtime/`; `docs/inference/` | `app/src/main/java/com/prismai/llmhost/engine/runtime/RuntimePlanner.kt`; `app/src/main/java/com/prismai/llmhost/engine/runtime/RuntimeProfileStore.kt`; `app/src/test/java/com/prismai/llmhost/engine/runtime/RuntimePlannerTest.kt` | Pure selectPlan(profile, model, workload, safety, history) and versioned private empirical profile store. | No native changes; consume truthful applied/observed contracts. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; ./gradlew --no-daemon :app:testDevDebugUnitTest --tests "*RuntimePlanner*" | Replay experiment records offline and held-out device/workload scenarios. Physical confirmation of any selected nonbaseline plan required before production eligibility. | medium | PIR-11; PIR-12; PIR-13; PIR-14; PIR-15 |
| PIR-20 | Bounded auto-tuner with exclusive operation ownership | Learns better per-device defaults with bounded user cost | P10,P22,R03,A01 | `app/src/main/java/com/prismai/llmhost/engine/runtime/InferenceAutoTuner.kt`; `app/src/main/java/com/prismai/llmhost/benchmark/BenchmarkRunner.kt`; `app/src/test/java/com/prismai/llmhost/engine/runtime/`; `app/src/androidTest/java/com/prismai/llmhost/benchmark/` | `app/src/main/java/com/prismai/llmhost/engine/runtime/InferenceAutoTuner.kt`; `app/src/test/java/com/prismai/llmhost/engine/runtime/InferenceAutoTunerTest.kt`; `app/src/androidTest/java/com/prismai/llmhost/benchmark/AutoTunerLifecycleTest.kt` | Bounded tuning job/candidate budget, progress/cancel, trial-disposition contract; no hidden long background benchmark. | Use existing serialized plan/benchmark APIs and safety state; no new raw calls. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; ./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.benchmark.AutoTunerLifecycleTest | Replay deterministic candidate simulator plus authorized physical bounded tuning; verify search overhead, thermal cost and no regression versus manual known-good profile. | medium | PIR-19 |
| PIR-21 | Standalone MNN versus optimized llama.cpp bake-off | Tests whether a second engine earns its cost | R06–R09 | `research/inference/experiments/mnn/`; `research/inference/results/mnn/` | `research/inference/experiments/mnn/export_manifest.json`; `research/inference/experiments/mnn/bakeoff_protocol.md` | Standalone benchmark interface only; no Prism production API or dependency change. | Independent MNN Android runner with pinned export/runtime/backends; no normal APK linkage. | python3 research/inference/tools/check_run.py research/inference/results/mnn/*.jsonl | Section 25/32 alternate threshold: ≥25% sustained TG or ≥30% UI-equivalent TTFT or ≥20% task energy, with quality non-inferiority and normally ≤5% PSS regression. | high | PIR-19 |
| PIR-22 | Compiled/vendor alternative screening and one bounded pilot | Discovers a justified curated fast lane without fragmentation | R10–R20,R24–R41 | `research/inference/experiments/alternate_screen/`; `research/inference/results/alternate_screen/` | `research/inference/experiments/alternate_screen/candidates.json`; `research/inference/experiments/alternate_screen/decision.md` | No production abstraction; comparison intake contract and one selected pilot. | Select a justified LiteRT-LM/ExecuTorch/MLC or MLLM/ORT candidate; SDK/device access must be real. | python3 research/inference/tools/check_run.py research/inference/results/alternate_screen/*.jsonl | Same thresholds as PIR-21; actual model/package/SDK/driver and partition/operator placement where applicable. Unique capability requires separately accepted product criterion. | high | PIR-19 |
| PIR-23 | Conditional minimal alternate-engine adapter | Optional measured capability, not framework expansion | Sections 25,32 | `app/src/experimental/`; `research/inference/alternate-adapter/`; `docs/inference/` | `app/src/experimental/java/com/prismai/llmhost/engine/ExperimentalSession.kt`; `research/inference/alternate-adapter/acceptance.md` | Only the minimal interface required by two proven implementations: load/generate/cancel/close/capabilities/metrics; optional packaging. | Retained candidate runner behind isolated flavor/module; integrator owns production wiring. | ./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug; cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug; cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure; ./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest; ./gradlew --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease; ./gradlew --no-daemon :app:connectedDevDebugAndroidTest; bash scripts/emulator/run-tests.sh both | Repeat retained bake-off in the integrated app; standalone advantage must survive bridge/UI/lifecycle/package integration. | high | PIR-21; PIR-22 |


## 23. Benchmark architecture and science

### Three nested benchmarks
**L0: kernel/runtime isolation.** Build the same selected llama.cpp revision and backend config as Prism. Teacher-forced decode and synthetic PP can isolate compute, but must be labeled as such. A repeated-BOS PP workload is not an ordinary chat prompt. Repair the native benchmark’s tokenization-resize handling, context admission, cancellation and prompt-TPS export before using it. [P09,P22–P24]

**L1: native real generation.** Same native Engine, real canonical prompt tokens, sampler, grammar, stop policy and output count, without Kotlin/UI transport. This isolates Prism’s native integration and sampling overhead.

**L2: Android end-to-end chat.** Include admission, prompt/RAG construction, template/tokenization, native execution, JNI, Flow, transcript updates and first visible rendered text. Include cold model startup and already-loaded chat as separate cohorts. Direct agent-tool bypasses are not model TTFT results.

Attribution comes from L0→L1→L2 deltas under matched plans, not from comparing unrelated apps. User-perceived TTFT and native first-sampled-token latency both matter; neither replaces the other.

### Experimental factors and model cohorts
Use approximate model tiers 1–2B, 3–4B and 7–8B **only where the device can admit them safely**. Include at least a dense GQA model, a native-SWA or hybrid-attention model, and a special-format compatibility model relevant to Prism’s catalog. Include Prism’s Q1/Bonsai load-policy case as a separate stress case, not an equivalent-quality substitute for a 7B model. ModelLoadLimits’ 4200 MiB cap can exclude some 7–8B packages; removing it precedes no unsafe experiment. [P16]

For each model pin the original checkpoint revision, license, GGUF/converted artifact SHA-256, tokenizer/template hashes, quantizer/converter revision and recipe/calibration. Q4_0, Q4_K_M and Q8_0 are useful within-runtime compatibility cohorts; IQ/TQ/1-bit entries are included only if the exact backend/model supports them. A changed quantization is a separate quality treatment. For MNN/MLC/ExecuTorch/LiteRT-LM, convert from the same original checkpoint where supported and record tensor/activation/cache precision differences. Do not force a fake “identical quantization” label.

No model artifact hashes are invented in this package. The provided manifest template is intentionally unresolved; a benchmark runner must refuse to produce a comparable result until actual hashes and build/device identity are populated.

### Workload suite
| Suite | Work | Purpose |
|---|---|---|
| PP | 128/512/2048 token exact prompts, longer fit-safe prompt; zero or short generation | Prefill throughput, scratch and scheduling |
| TG | Fixed prompt; 128/256 accepted output tokens or explicitly labeled early stop | Decode speed, token gaps, sampler cost |
| Interactive | Short prompt + 64–128 output; loaded and model-cold | User-visible TTFT and bridge/UI latency |
| Conversation | 2–10 turns with stable prefix, history edit, system change, tool schema/result | True saved prefill and correctness after invalidation |
| Structured | Grammar/JSON, repeated code, Unicode, stop markers | Output validity and sampling/transport semantics |
| Boundary | Exactly below/at/above context budget; mandatory prefix too large | Admission, reserve arithmetic and no silent truncation |
| Lifecycle | Cancel during load/PP/decode/drain, model switch, service stop, memory pressure | Recovery and resource release |
| Sustained | Repeated controlled chats/decode with time-series reporting | Thermal plateau, throughput decay, energy and tail latency |
| Load | Process-cold/page-cache-warm, warm model, explicitly controlled storage-cold when possible | mmap/repack/validation/shader-cache costs |

### Timing definitions
Use monotonic clocks and explicit synchronization. Log wall-clock UTC only for provenance. Native timestamps should be mapped to a documented clock domain; do not subtract unrelated Kotlin/native clocks without calibration.

`ui_ttft = first_visible_text_at − user_submit_at`. Also report first_token_generated, first_nonempty_text_drained and first_rendered_frame separately. Template/RAG time must not disappear by starting the clock after building the prompt. `native_prefill_tps = actually_evaluated_prompt_tokens / prefill_active_seconds`; report input tokens and reused tokens separately. A fully cached prefix is a reuse result, not infinite PP throughput. `TG` should specify whether it includes sampling, loop overhead and first-token generation; use accepted output count, not proposed speculative tokens.

Per-token latency derives from generated/committed timestamps, not JNI drain batch arrivals. P50/P95 are meaningful with sample counts and run-level confidence intervals; P99 on a 128-token run is unstable, so report only with adequate pooled comparable samples and preserve per-run distributions. Transport gaps are a different metric and should not replace native token gaps.

### Required record
The machine-readable schema in this package contains run identity; full model/runtime/build hashes; requested/applied plans; placement value and observation source; prompt/template/token hashes; PP/input/reused/evaluated/output/proposed/accepted counts; phase timestamps/durations; cold/warm load; native and UI TTFT; PP/TG; token latencies; peak RSS/PSS/native/GPU memory with unavailable reasons; thermal/frequency/power series; battery/charging/screen/network conditions; failures/OOM/crashes/fallback; correctness cohort; and raw artifact references. Unknown values are null with reasons, never zero as a synonym for unavailable.

### Reproducibility and analysis
Run one benchmark owner per device. Build/analysis subagents may run in parallel on the host, but never heat the same phone concurrently. Use a stable starting thermal window, controlled screen brightness/refresh, radio/network state, charging state, ambient conditions and background apps. Record accessible CPU/GPU frequency and scheduler traces, page faults, memory and thermal status; permissions or unsupported counters must be explicit.

Do not call a force-stopped app “storage cold”: its mapped file may still be in the OS page cache. Avoid root-only drop_caches requirements for representative app tests. Clearly label process-cold/page-cache-unknown. Include shader cache miss/hit and first-model/context initialization as separate factors.

Use randomized order or ABBA paired blocks, at least five clean burst repeats for initial confirmation, additional repeats when confidence intervals are wide, and a predeclared sustained campaign (for example 15–30 minutes or a measured thermal plateau) within safety stop rules. Those durations are proposed benchmark protocol, not predicted completion times. Report sustained steady-state and decay from the cooled burst; do not select only the fastest repetition. The unit of inference is the independent run/device, not thousands of correlated token samples treated as independent trials.

For energy, an external power measurement with controlled battery state is strongest. Battery current/voltage estimates are noisier and may include screen/radio/system draw. Record sampling cadence, sign convention, integration method and baseline subtraction assumptions. Battery temperature is not SoC junction temperature. Compare joules per accepted output token and per completed task, not just instantaneous power.

Physical Android ARM64 devices are required for performance conclusions. The new x86_64 high/low AVDs are useful for API/lifecycle/low-memory correctness, not Adreno, KleidiAI, HTP, energy or thermal claims. Test 4 KiB and 16 KiB native compatibility and the actual minimum SDK separately; CMake alignment flags alone do not validate every packaged dependency. [P03,P26–P29,A04]

### Quality non-inferiority and statistics
Pre-register task success, structured-output validity, long-context retention and teacher-forced likelihood/logit diagnostics. For an exact algorithm on the same backend/precision, require deterministic greedy equivalence where defined. For floating-point backend changes, report tolerance-aware logits and quality outcomes, not a universal byte-identical-text requirement. Near-tied logits can change greedy output without proving a semantic bug.

For a new quantization/backend package, propose a quality non-inferiority margin before seeing results, such as at most one percentage-point absolute degradation on a sufficiently sized task set and no loss in mandatory safety/format invariants. An underpowered test with zero observed failures is not proof of that margin. Report sample counts and confidence bounds; use a larger evaluation or retain “insufficient evidence.”


## 24. Correctness gates

Performance work must pass the relevant gates below. Each test must produce a failing fixture on the old behavior where feasible, a passing fixture on the candidate, and native/applied-plan evidence. An emulator-only pass cannot certify accelerator math.

| Gate | Required cases | Acceptance |
|---|---|---|
| G01 provenance/build | Clean pinned baseline, submodule/patch/build flags, release-like ABI and R8 | Exact reproducible manifest; no dirty/unrecorded native source; no ignored build failures |
| G02 lifetime/operation | Drain/cancel/thread/pressure/encode racing destroy; repeated create/destroy; benchmark versus model switch | No use-after-free/double-free/deadlock; no new leases after closing; native worker joined; bounded main-thread work |
| G03 lossless stream | Slow consumer > old retry window; ring wrap; >128 queued tokens at EOF; cancellation during drain | Produced/committed/drained/final sequence invariants; no missing/duplicated text; terminal exactly once after required drain |
| G04 Unicode | Every split boundary of multi-byte characters, emoji/non-BMP, combining marks, literal replacement character and NUL policy | Concatenated bytes decode as canonical reference; explicit invalid-byte policy; no silent normalization removing valid content |
| G05 sampling | Greedy seed-controlled cases, penalties history, grammar state, empty/malformed grammar, valid repeated code/JSON | Accept once; defined chain with selector; invalid required grammar fails closed; repetition is not fabricated EOG |
| G06 template/budget | System/developer/user/assistant/tool roles, real template variants, marker text inside user input, exact/overflow budgets | Canonical token IDs match reference; mandatory prefix and atomic tool pairs preserved; oversized required input rejected explicitly |
| G07 prefix/cache | Full replay versus reuse after each turn/edit/system/template/LoRA/model/KV change; identical prompt; no-token extension | Matching logits/tokens within defined backend tolerance; correct last-logit handling; explicit invalidation identity |
| G08 shift/context | Fresh saturated prompt, repeated shift, abort mid-prefill, failed seq_rm, SWA/hybrid/recurrent/mRoPE models | No invalid positions/token count; recompute reuse after reset; unverified shifting disabled; clean-replay reference passes |
| G09 load contract | Toggle every backend/KV/FA/context/batch/ubatch/thread field, same model/hash reload, fallback | Requested/applied/observed separation; full loadKey behavior; CPU baseline actually CPU; native fallback re-admitted and recorded |
| G10 memory/validation | Corrupt/truncated/oversized GGUF metadata, shuffled key order, tensor errors, external mutation/revocation, disk full | Typed rejection or quarantine; no parser crash; bounds enforced; no old-speed override of current memory safety |
| G11 backend math | Representative tensor ops and model-level prompts per quant/FA/KV/device | Correctness against CPU/reference within declared tolerance; no unsupported-op silent success; fallback recorded |
| G12 KV quality | F16 versus q8 then selected q4; short and long retrieval/task suite | Predeclared non-inferiority plus no structural/safety regressions; per-model/cache allowlist |
| G13 speculation | Greedy exactness where promised, sampling distribution tests where stochastic, cancel/reject/rollback chains | Same target semantics; rejected tokens never committed; proposal/acceptance/verification counters consistent |
| G14 Android | FGS notification/type failure, timeout, task removed, suspend/resume, trim/low-memory, thermal interrupt, process death | One authoritative lifecycle/safety state; transcript safe; no auto-resume duplicate response; listeners/resources released |
| G15 auxiliary capabilities | Wrong chat model used for embeddings, pooling variants, adapter switch, absent/wrong projector, concurrent chat | Unsupported returns explicit error; correct shape/pooling/normalization; isolated context or safe serialization; memory budget enforced |
| G16 benchmark integrity | Unknown terminal, fallback, partial output, stale model hash, schema migration, timing clocks | Invalid/unknown runs excluded from winners but preserved as failures; no synthetic/chat/thermal cohort contamination |

**Critical state invariants:** active_tokens reflects committed context positions, not merely sampled/emitted candidates; cached-prefix identity includes the exact model/template/tokenization/adapter/context semantics; native terminal output cannot be acknowledged while required queued output remains; a requested backend is never presented as observed; every retry has a bounded cause and distinct applied plan.

A decoded token can be displayed before the next-token logits are ready in some designs, but Prism must then explicitly distinguish displayed output from a committed reusable KV prefix. It cannot use a single ambiguous active-token vector for both after cancellation. This is a contract choice, not an instruction to add avoidable latency to every first token.


## 25. Alternative-runtime decision matrix

Scores are **ordinal engineering judgments**, not measured engine rankings: 5 is favorable, 1 unfavorable. The most important criterion is preserving Prism’s arbitrary-compatible-GGUF primary workflow. “Upside” expresses potential and uncertainty, not proven speed.

| Option | Potential upside | GGUF flexibility | APK/complexity burden | Testing/security surface | Conversion/vendor burden | Current decision |
|---|---|---|---|---|---|---|
| A. llama.cpp only, repaired/optimized | Moderate–high, unmeasured | 5 | 5 relative to alternatives | One core, several backends | Low except vendor experiments | **ADOPT NOW** |
| B. llama.cpp + MNN | Potentially high on curated models | 5 primary / restricted fast lane | 2–3 | Two runtimes and conversion matrix | Medium | Standalone bake-off; no shipping adapter yet |
| C. llama.cpp + separate vendor runtime | Potentially high on supported devices | 5 primary / restricted fast lane | 2 | Delegate/native/model packaging risks | High | Only for measured capability/cohort benefit |
| D. generic multi-engine framework | Unproven indirect benefit | Could preserve primary | 1 initially | Broad API/edge-case burden | High | **REJECT PREMATURE ABSTRACTION** |
| E. llama.cpp primary + ggml Hexagon research flavor | Potentially high, experimental | 4–5 for supported GGUF | 3–4 | Same runtime, additional backend/SDK | Medium–high | **FIRST NPU EXPERIMENT** |

**Formal promotion gate.** Compare against the best *corrected and optimized* llama.cpp plan, not current broken defaults. An alternate runtime must deliver either at least 25% sustained TG gain, at least 30% end-to-end TTFT reduction on the declared workload, or at least 20% lower energy per completed equivalent task, with confidence bounds supporting a material result. These are proposed thresholds to justify substantial engineering cost. A unique essential model capability may qualify separately, but must not be called a speed win.

Require predeclared quality non-inferiority, no mandatory correctness regressions, no uncontained lifecycle failure, and normally no more than 5% higher peak PSS unless a documented product capability/memory tradeoff is expressly accepted. Test at least two relevant physical device/driver cohorts and two model tiers for a broad default. A one-device niche can ship only as a labeled allowlisted optional package, not as “best Android engine.”

Assess total installed APK/native/compiled-model size, conversion time, ongoing model-release workload, offline/privacy behavior, dependency/license provenance and contributor cost. An engine saving 8% in a short burst while demanding a second conversion/test ecosystem should not be integrated. Keep a fork-free standalone prototype or close the experiment.

If one backend passes, introduce only the smallest required session/load/generate/cancel/metrics interface proven by two implementations. Do not design all possible multimodal, server, NPU and batching capabilities into a universal abstraction in advance.


## 26. Prioritized feature backlog and scoring

**Correctness and truthful evidence are release prerequisites, not optional items that can lose to a faster kernel in a weighted score.** Priority classes override the numerical tie-breaker below. These scores are engineering priors, not benchmark results or probability estimates.

Benefit dimensions use 0–3 (none through high); effort/maintenance use 1–5; regression risk uses 1–3; confidence is 0–1 in the underlying problem/opportunity, not in a promised performance gain. The tie-break score is `100 × confidence × (speed + TTFT + memory + thermal + 2×reliability + compatibility) / (2×effort + maintenance + 2×risk)`. No measurement has established the magnitude of those potential gains.

| Mission | Class | Speed | TTFT | Memory | Thermal | Reliability | Compatibility | Effort | Maintenance | Regression risk | Confidence | Priority score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| PIR-00 | MUST FIX | 0 | 0 | 0 | 0 | 3 | 3 | 1 | 1 | 1 | 0.99 | 178.2 |
| PIR-01 | MUST FIX | 0 | 1 | 0 | 0 | 3 | 2 | 2 | 1 | 1 | 0.99 | 127.3 |
| PIR-02 | MUST FIX | 0 | 1 | 1 | 0 | 3 | 3 | 4 | 2 | 3 | 0.99 | 68.1 |
| PIR-03 | MUST FIX | 1 | 0 | 0 | 0 | 3 | 3 | 2 | 1 | 2 | 0.99 | 110.0 |
| PIR-04 | MUST FIX | 0 | 2 | 1 | 0 | 3 | 3 | 4 | 2 | 3 | 0.99 | 74.2 |
| PIR-05 | MUST FIX | 1 | 3 | 1 | 1 | 3 | 3 | 4 | 2 | 3 | 0.98 | 91.9 |
| PIR-06 | MUST FIX | 1 | 1 | 2 | 1 | 3 | 3 | 4 | 2 | 3 | 0.99 | 86.6 |
| PIR-07 | MUST FIX | 0 | 1 | 3 | 1 | 3 | 3 | 3 | 2 | 3 | 0.99 | 99.0 |
| PIR-08 | HIGH ROI | 1 | 1 | 1 | 1 | 2 | 3 | 2 | 1 | 2 | 0.95 | 116.1 |
| PIR-09 | MUST FIX | 0 | 1 | 1 | 3 | 3 | 3 | 3 | 2 | 2 | 0.95 | 110.8 |
| PIR-10 | HIGH ROI | 2 | 2 | 1 | 1 | 2 | 3 | 4 | 3 | 3 | 0.8 | 61.2 |
| PIR-11 | EXPERIMENT | 2 | 2 | 0 | 2 | 1 | 2 | 2 | 1 | 2 | 0.7 | 77.8 |
| PIR-12 | EXPERIMENT | 2 | 3 | 1 | 1 | 0 | 1 | 3 | 3 | 3 | 0.55 | 29.3 |
| PIR-13 | EXPERIMENT | 3 | 3 | 1 | 2 | 0 | 1 | 3 | 3 | 3 | 0.6 | 40.0 |
| PIR-14 | EXPERIMENT | 1 | 3 | 2 | 1 | 2 | 2 | 3 | 2 | 2 | 0.7 | 75.8 |
| PIR-15 | EXPERIMENT | 1 | 1 | 3 | 2 | 1 | 2 | 3 | 2 | 3 | 0.6 | 47.1 |
| PIR-16 | MUST FIX | 0 | 1 | 2 | 0 | 3 | 3 | 3 | 2 | 3 | 0.95 | 81.4 |
| PIR-17 | EXPERIMENT | 3 | 0 | 0 | 1 | 0 | 1 | 4 | 3 | 3 | 0.45 | 13.2 |
| PIR-18 | LONG TERM | 3 | 3 | 1 | 3 | 0 | 2 | 4 | 4 | 3 | 0.5 | 33.3 |
| PIR-19 | HIGH ROI | 2 | 2 | 2 | 2 | 3 | 3 | 3 | 2 | 2 | 0.8 | 113.3 |
| PIR-20 | HIGH ROI | 2 | 2 | 2 | 2 | 2 | 2 | 3 | 3 | 2 | 0.7 | 75.4 |
| PIR-21 | EXPERIMENT | 3 | 3 | 2 | 2 | 0 | 1 | 5 | 4 | 3 | 0.45 | 24.8 |
| PIR-22 | LONG TERM | 3 | 3 | 2 | 3 | 0 | 1 | 5 | 5 | 3 | 0.4 | 22.9 |
| PIR-23 | LONG TERM | 2 | 2 | 1 | 2 | 0 | 1 | 5 | 5 | 3 | 0.3 | 11.4 |
| PIR-INT | MUST FIX | 0 | 0 | 0 | 0 | 3 | 3 | 4 | 2 | 3 | 0.99 | 55.7 |

**MUST FIX:** lossless transport/lifetime; sampling and grammar; structured prompt budgeting; transactional cache; full runtime load key; truthful metrics; conservative admission; service-owned safety; truthful optional capability support. **HIGH ROI:** actual capability probes, independent prefill/decode controls, measured runtime selection and bounded tuning, after the must-fix gates. **EXPERIMENT:** CPU affinity, offload/microbatch grids, Vulkan features, Adreno OpenCL, load/compiler policy, KV quantization and phone speculation. **LONG TERM / narrow pilot:** Hexagon qualification and alternate converted engines. Hexagon research can start early in an isolated worktree; broad production qualification remains expensive.

**REJECT now:** global maximum GPU/thread/context defaults; universal Q4 KV; silent unconstrained grammar; benchmark filtering that hides failures; new JS/React Native layer; unconditional multi-engine framework; unsupported SM(E)/I8MM compiler flags; persistent plaintext KV by default; disk-backed hot KV as a default phone optimization; treating proprietary vendor binaries as open-source kernels. See Sections 20 and 32 for conditions that could reopen a research question.


## 27. Implementation DAG and critical path

Waves express dependency order, not elapsed time or a requirement to wait for every unrelated experiment. Completion of a research dependency means its evidence and disposition are accepted: REJECT can be a successful experiment outcome. It does not mean the experimental code must be merged. Implementation prerequisites, by contrast, require accepted code and passing mandatory gates.

| Wave | Mission | Dependencies | Type | Complexity | Risk | Expected value | Success criterion | Rollback / stop |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | PIR-00 — Freeze provenance, harness and baseline contract | none | implementation | S | medium | Foundational reproducibility and exact attribution | Exact SHA/patch/build manifest exists and is immutable. | Unresolved model provenance; dirty source not captured; missing toolchain/device prevents relevant gate. |
| 1 | PIR-01 — Evidence-grade benchmark records and clocks | PIR-00 | implementation | M | medium | Makes subsequent optimization decisions valid | Unknown, partial, fallback-unreported and mismatched-identity runs cannot become planner winners. | Do not invent applied settings from requested preferences or treat absent memory as zero. |
| 1 | PIR-02 — Lossless streaming and native lifetime leases | PIR-00 | implementation | L | high | Output correctness, crash prevention and trustworthy user-visible latency | G02–G04 pass; zero missing/duplicated bytes/tokens across >1000 deterministic stress iterations. | Stop on any UAF, deadlock, silently discarded valid text or uncancellable wait. |
| 2 | PIR-03 — Correct sampler semantics and fail-closed grammar | PIR-02 | implementation | M | medium | Restores model sampling and structured-output semantics | G05 passes; a deterministic single-accept reference matches candidate. | Do not bless changed stochastic outputs as a performance win or hide grammar errors. |
| 3 | PIR-04 — Structured prompts and exact message-aware budget | PIR-03 | implementation | L | high | Correct instructions, stable prefix reuse and honest TTFT | G04/G06 pass for at least representative template families plus explicit unsupported-template behavior. | Template support absent at the selected pin must be explicit; no invented Jinja API signatures or silent ChatML substitution. |
| 4 | PIR-05 — Transactional prefix cache and model-aware context policy | PIR-04 | implementation | L | high | Correct multi-turn reuse and elimination of avoidable prefill | G07/G08 pass; clean replay and reuse match within declared precision tolerance. | Any unexplained logit/token divergence, out-of-range position or stale cache after cancellation blocks merge. |
| 5 | PIR-06 — Requested/applied/observed plan and full load-key wiring | PIR-01; PIR-05; PIR-07; PIR-08 | implementation | L | high | Makes configuration experiments real and protects load safety | G09/G10/G16 pass; no requested value masquerades as observed. | No backend setter or diagnostic API invented without checking selected headers. |
| 1 | PIR-07 — GGUF facts and conservative architecture-aware admission | PIR-00 | implementation | L | high | Safer model size/context selection with fewer false assumptions | G10 passes; key-order changes do not alter facts. | Missing architecture details block precise estimate; use conservative bounds or reject, not guessed equations. |
| 1 | PIR-08 — Hardware and backend capability probes | PIR-00 | implementation | M | medium | Enables defensible per-device plans and backend eligibility | Unavailable fields have reasons; no name-only GPU/NPU assumption. | Do not use privileged/root interfaces or force-load untrusted libraries from arbitrary paths. |
| 6 | PIR-09 — Unified thermal, battery and Android lifecycle safety | PIR-06 | implementation | M | high | Thermal sustainability, predictable recovery and Android reliability | G02/G14 pass; one policy owns safety and callback registration. | Do not fake thermal sensor values as physical measurements or bypass FGS/SDK restrictions. |
| 7 | PIR-10 — Controlled llama.cpp update and research harness wiring | PIR-09 | implementation | L | high | Current backend/model capabilities with controlled regression risk | G01–G11/G14/G16 relevant gates pass; no unexplained model regression. | Compiler success alone is insufficient. Missing physical device blocks accelerator promotion. |
| 8 | PIR-11 — CPU phase/thread/topology experiments | PIR-10 | experiment | M | medium | Potential high-ROI PP/TG/energy tuning | At least 5% material low-risk gain beyond noise on declared objective, no correctness or meaningful tail/energy regression; otherwise report no winner. | No root/governor writes; no architecture flags unsupported by device; no universal winner extrapolation. |
| 8 | PIR-12 — Vulkan capabilities, offload and microbatch bake-off | PIR-10 | experiment | L | high | Potential PP and accelerator-utilization gains | Complex kernel changes require about 10–15% material objective gain and G11 pass; simple offload presets use 5% low-risk gate. | Extension name alone is not proof of correct shader execution; stop on any math/driver crash. |
| 8 | PIR-13 — Adreno OpenCL quantization and cache bake-off | PIR-10 | experiment | L | high | Potential best supported Snapdragon GPU route, not assumed | Material sustained/TTFT/energy benefit with G11/G14; all fallback and unsupported quant restrictions documented. | No platform libOpenCL or license entitlement → BLOCKED, not automatic sideload of arbitrary libraries. |
| 8 | PIR-14 — Load, storage and compiler policy experiments | PIR-10 | experiment | M | medium | Potential startup and memory gains with bounded complexity | Low-risk load changes meet 5% material startup/TTFT improvement or clear integrity/reliability benefit without peak-memory harm. | No flags justified only by intuition; no unsafe skipped validation, fast-math, root drop_caches requirement or missing symbols. |
| 8 | PIR-15 — KV quality and memory frontier | PIR-10 | experiment | L | high | Larger usable context/model envelope with explicit quality protection | G12 passes with adequate sample/confidence; material memory or energy benefit and no mandatory semantic regressions. | No global Q4 KV or min(window,context) estimate across every layer; unsupported fallback cannot exceed admission unnoticed. |
| 8 | PIR-16 — Truthful embeddings, LoRA and vision capabilities | PIR-10 | implementation | M | high | Reliable operation compatibility and honest RAG/multimodal behavior | G15 passes; no unrelated chat KV mutation or wrong embedding shape/pooling. | Missing compatible embedding/vision fixture blocks support claims; do not produce synthetic embeddings or successful stub responses. |
| 9 | PIR-17 — Phone-appropriate speculative decoding experiment | PIR-10; PIR-15 | experiment | L | high | Potential decode gain without another primary runtime | G13 passes; about 15% material sustained or task-energy improvement to justify complexity, unless implementation is demonstrably tiny and low-risk. | Stop on distribution/exactness failure, memory admission violation, poor acceptance with net slowdown or thermal regression. |
| 8 | PIR-18 — Upstream Hexagon capability and sustained experiment | PIR-10 | experiment | L | high | Potential NPU acceleration while preserving GGUF ecosystem | G11/G14 pass; meaningful sustained/energy/TTFT benefit and manageable SDK/package burden. | Unavailable SDK/permission or unknown redistribution entitlement → BLOCKED. |
| 9 | PIR-19 — Evidence-keyed runtime planner and profile store | PIR-11; PIR-12; PIR-13; PIR-14; PIR-15 | implementation | M | medium | Automatic defensible defaults instead of settings burden | Planner never selects unsafe/uncertified/stale-incompatible plans; reasons reproducible. | Do not average across quantization/quality/runtime/thermal cohorts or promote a largest single TPS sample. |
| 10 | PIR-20 — Bounded auto-tuner with exclusive operation ownership | PIR-19 | implementation | M | medium | Learns better per-device defaults with bounded user cost | Budget/lease/cancellation/safety tests pass; no search explosion or silent settings drift. | Stop if autotuning disrupts an active chat, starts without the required intent, or ignores thermal/memory constraints. |
| 10 | PIR-21 — Standalone MNN versus optimized llama.cpp bake-off | PIR-19 | experiment | L | high | Tests whether a second engine earns its cost | Equivalent tasks and original checkpoint documented; no fake same-quant claim. | Unsupported export/unknown license/no physical benchmark → BLOCKED or REJECT, not a fabricated comparison. |
| 10 | PIR-22 — Compiled/vendor alternative screening and one bounded pilot | PIR-19 | experiment | L | high | Discovers a justified curated fast lane without fragmentation | A reproducible and licensed candidate only, with bounded scope and honest deferred entries. | Unavailable vendor SDK/rights/device or missing LLM operator path blocks the pilot. |
| 11 | PIR-23 — Conditional minimal alternate-engine adapter | PIR-21; PIR-22 | conditional | L | high | Optional measured capability, not framework expansion | No adapter without material integrated benefit or explicitly accepted unique capability. | No winner → report SKIPPED/REJECT and make no app changes. |
| 12 | PIR-INT — Root integrator and release evidence authority | PIR-16; PIR-20 | integration | L | high | Coherent tested runtime rather than a pile of experiments | Every accepted optimization has source/config/quality/raw benchmark evidence and explicit support scope. | Any output loss, UAF, unsafe admission, hidden fallback, quality failure or untraceable benchmark blocks release. |

### Critical path
`PIR-00 → PIR-02 → PIR-03 → PIR-04 → PIR-05 → PIR-06 → PIR-09 → PIR-10 → completed dispositions for PIR-11…15 → PIR-19 → PIR-20 → PIR-INT`.

PIR-01, PIR-07 and PIR-08 join at PIR-06. PIR-16 joins before final release; its honest unsupported-capability gates must not wait for a speculative new feature. PIR-17, PIR-18 and PIR-21–23 are **optional** for a safe single-runtime release. A no-benefit or blocked alternate bake-off is a valid result and does not authorize an adapter.

A severe existing defect may be hot-fixed and released before the whole planner program, provided the root integrator runs the affected conformance/build/lifecycle gates. Do not hold a double-accept or output-loss fix hostage to an NPU experiment.


## 28. Parallel execution map and worktree ownership

The lead agent is the only merge authority. All coding agents use isolated worktrees and immutable starting SHAs. No actor shares an active checkout, native output directory or device benchmark slot. Model files live in an explicitly approved read-only external model cache, not duplicated into every worktree.

| Dispatch lane | Missions | Parallel rule | Shared-file handling |
|---|---|---|---|
| Provenance coordinator | 00 | First; also creates the new portable test harness | No production runtime changes |
| Records leaf | 01 | Parallel with 02,07,08 after 00 | No Engine/JNI edits; schemas queued for 06 |
| Model intelligence leaf | 07 | Parallel with 01,02,08 | Storage/admission only; central wiring in 06 |
| Hardware leaf | 08 | Parallel with 01,02,07 | New probe modules; central wiring in 06 |
| Runtime correctness serial lane | 02→03→04→05→06→09→10 | **One central-file writer at a time** | Engine.cpp, Engine.hpp, JNI, bridge, service, Gradle/CMake |
| CPU/GPU/load/KV research | 11–15 | Source/build work parallel after 10; physical trials serialized | Leaf experiments/configs only; proposals integrated by root |
| Capability conformance | 16 | Parallel with research leaves | New modules/tests; root wires central calls |
| Speculation / HTP | 17 / 18 | Independent worktrees after prerequisites | Separate experimental modules; no production flag changes |
| Planner | 19→20 | After experiment reports, including useful rejected results | Pure planning first; root wires service |
| Alternate runtime research | 21 / 22 | Standalone runners after optimized baseline | No normal APK dependencies |
| Conditional adapter | 23 | Only one winning candidate and explicit RETAIN | Isolated module/flavor; root wires build |
| Root integration | INT | Reviews every merge checkpoint, not just the end | Owns all conflict resolution and release evidence |

The package’s worktree helper defaults to printing a plan. With `--apply`, it creates a new branch/worktree only after checking the repository/base/dependency ledger. It **does not spawn agents, run benchmarks, merge, push, delete worktrees, install SDKs or spend money**. The orchestration agent dispatches the resulting mission packet to its available worker tool and records the worker identity.

Bound concurrency to actual host RAM/disk: begin with two native builds at most and one physical device trial. Increase only after observing build peak RSS and free-disk reserve; do not run five native compiler/linker jobs by habit. Treat this as an adjustable starting limit, not a measured requirement for the user’s hardware.

Merge order: 00; accepted leaf 01/07/08 without central edits; 02→03→04→05; 06; 09; 10; accepted capability leaf16 and selected experiment leaves; 19→20; root conformance. Optional17/18/23 enter only through their own acceptance review. Never resolve a conflict by discarding a previously accepted guard.


## 29. Complete agent mission packets

Each packet below is self-contained and also shipped separately in `missions/`. The root agent must provide the immutable dependency checkpoint and the completed-dependency ledger before dispatch. The command names in a packet are either verified repository tasks or explicitly new test targets to implement; their appearance is not a PASS claim.

### PIR-00 — Freeze provenance, harness and baseline contract

**Objective:** Freeze provenance, harness and baseline contract.

**Why it matters:** Foundational reproducibility and exact attribution. Evidence: P00–P03,P26–P29. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** MUST FIX / 0 / S / medium.

**Starting commit:** 49ed799a3e633c5192c2c03317d1d50ffdc57c4c

**Dependencies:** None. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `research/inference/`
- `app/src/test/cpp/`
- `scripts/inference/`
- `docs/inference/`

**New files:** `research/inference/baseline.lock.json`; `app/src/test/cpp/CMakeLists.txt`; `scripts/inference/capture-baseline.sh`; `docs/inference/evidence-contract.md`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** No production API change. Define manifest/schema and host-test registration contract.

**Native changes:** Portable deterministic native test harness only; do not change inference behavior.

**Implementation steps:**
1. Verify main is the audited SHA or record the exact intervening diff; use a worktree from the audited base for the historical control.
2. Resolve submodule gitlink, local patch status, all native build flags, NDK/CMake/JDK and flavor; hash relevant source/build artifacts.
3. Create host C++ test target for transport/cache policy fixtures without Android-only dependencies; add a self-test and sanitizer capability detection.
4. Establish private artifact directories, unresolved model/device manifest templates, command log format and baseline run labels.
5. Run flavor-qualified unit/build gates and attempt the existing connected-test runner only on authorized available devices; record BLOCKED prerequisites rather than bypassing them.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
git rev-parse HEAD && git submodule status --recursive
git status --porcelain=v1
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure
python3 research/inference/tools/validate_program.py
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Capture existing behavior as untrusted historical baseline; do not rank its fake placement or mixed metrics. Physical baseline pending available device/model.

**Acceptance criteria:**
1. Exact SHA/patch/build manifest exists and is immutable.
2. Portable harness self-test passes; real build/connected gate status reported truthfully.
3. No production optimization or settings-default change.

**Failure/stop conditions:**
1. Unresolved model provenance; dirty source not captured; missing toolchain/device prevents relevant gate.
2. Never download private models or run chargeable services without authorization.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-01 — Evidence-grade benchmark records and clocks

**Objective:** Evidence-grade benchmark records and clocks.

**Why it matters:** Makes subsequent optimization decisions valid. Evidence: P21–P24. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** MUST FIX / 1 / M / medium.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-00. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/java/com/prismai/llmhost/benchmark/`
- `app/src/main/java/com/prismai/llmhost/generation/GenerationMetrics.kt`
- `app/src/test/java/com/prismai/llmhost/benchmark/`
- `app/src/test/java/com/prismai/llmhost/generation/GenerationMetricsTest.kt`
- `research/inference/schemas/`

**New files:** `app/src/main/java/com/prismai/llmhost/benchmark/BenchmarkRecord.kt`; `app/src/test/java/com/prismai/llmhost/benchmark/BenchmarkRecordTest.kt`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Versioned benchmark event/record schema; preserve legacy records as untrusted, not silently upgraded.

**Native changes:** Specify native event fields for PIR-06; no Engine.cpp edits here.

**Implementation steps:**
1. Separate native compute, native generation and chat cohorts; preserve original labels during migration.
2. Add full identities, requested/applied/observed fields, timing clock domain, outcome, fallback, memory/thermal availability and raw artifact links.
3. Rename prompt-evaluation timing and reserve true UI TTFT for submit-to-visible timestamps; keep absent fields null.
4. Make unknown terminal reasons ineligible for successful performance aggregation; do not feed synthetic or failed records into readiness.
5. Persist atomically with versioned recovery; test interrupted writes and malformed/legacy records.
6. Keep temporary adapter signatures compiling until PIR-06 wires native fields; mark those records untrusted.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
./gradlew --no-daemon :app:testDevDebugUnitTest --tests "*Benchmark*"
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** No speed claim. Fixture-based schema and known-clock synthetic event timelines; later physical records must pass validator.

**Acceptance criteria:**
1. Unknown, partial, fallback-unreported and mismatched-identity runs cannot become planner winners.
2. PP input/evaluated/reused tokens and PP TPS are not mislabeled as characters or lost.
3. Clock and migration tests pass.

**Failure/stop conditions:**
1. Do not invent applied settings from requested preferences or treat absent memory as zero.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-02 — Lossless streaming and native lifetime leases

**Objective:** Lossless streaming and native lifetime leases.

**Why it matters:** Output correctness, crash prevention and trustworthy user-visible latency. Evidence: P06–P09,P25. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** MUST FIX / 1 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-00. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/bridge/Utf8TextPipeline.kt`
- `app/src/main/cpp/runtime/`
- `app/src/test/cpp/stream/`
- `app/src/test/java/com/prismai/llmhost/bridge/`
- `app/src/androidTest/java/com/prismai/llmhost/bridge/`

**New files:** `app/src/main/cpp/runtime/NativeHandleRegistry.hpp`; `app/src/main/cpp/runtime/NativeHandleRegistry.cpp`; `app/src/main/cpp/runtime/StreamProtocol.hpp`; `app/src/main/cpp/runtime/StreamProtocol.cpp`; `app/src/test/java/com/prismai/llmhost/bridge/IncrementalUtf8Test.kt`; `app/src/androidTest/java/com/prismai/llmhost/bridge/NativeLifetimeTest.kt`.

**Files to avoid:**
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Versioned DrainResult with produced/committed/drained/final sequence and pending output; typed stream terminal. Opaque handle registry leases.

**Native changes:** No raw delete while calls are in flight; close admission then cancel/join and quiesce. Single-consumer ring semantics; incremental UTF-8 bytes.

**Implementation steps:**
1. Reproduce old slow-consumer drop and EOF-with-more-than-128-pending-token behavior with a deterministic producer/consumer fixture.
2. Define terminal semantics for success, cancellation and partial output; acknowledge only after the required final sequence is drained.
3. Replace timeout-based text dropping with bounded suspending/backpressure behavior that remains cancellable.
4. Preserve incomplete UTF-8 suffix across drains; canonicalize JNI input correctly for supplementary Unicode; remove destructive replacement-character filtering.
5. Wrap every JNI path, including pressure/thread/diagnostic/encode calls, in a lifetime lease; prohibit new calls after Closing.
6. Stress cancellation, destroy and ring wrap; retain existing RAII and off-main teardown rather than adding another ownership scheme.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure
./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.bridge.NativeLifetimeTest
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Transport-only synthetic producer rates plus small-model end-to-end drain traces. Record generated/drained/rendered timestamps and backpressure/cancel P95.

**Acceptance criteria:**
1. G02–G04 pass; zero missing/duplicated bytes/tokens across >1000 deterministic stress iterations.
2. Terminal emitted exactly once at the correct final boundary.
3. No sanitizer-reported lifetime/ring race in supported test configurations; unsupported sanitizer recorded, not called passed.

**Failure/stop conditions:**
1. Stop on any UAF, deadlock, silently discarded valid text or uncancellable wait.
2. Do not substitute unbounded memory growth for backpressure.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-03 — Correct sampler semantics and fail-closed grammar

**Objective:** Correct sampler semantics and fail-closed grammar.

**Why it matters:** Restores model sampling and structured-output semantics. Evidence: P04,P09,U01. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** MUST FIX / 2 / M / medium.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-02. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/java/com/prismai/llmhost/GenerationSettings.kt`
- `app/src/main/java/com/prismai/llmhost/engine/EngineConfigStore.kt`
- `app/src/main/cpp/runtime/SamplingPolicy.hpp`
- `app/src/test/cpp/sampling/`
- `app/src/test/java/com/prismai/llmhost/generation/`
- `app/src/androidTest/java/com/prismai/llmhost/generation/`

**New files:** `app/src/main/cpp/runtime/SamplingPolicy.hpp`; `app/src/androidTest/java/com/prismai/llmhost/generation/SamplingContractTest.kt`.

**Files to avoid:**
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Explicit seed and greedy mode; documented penalties/grammar/stop policy. Retain backward-compatible user sampling preferences.

**Native changes:** Accept a token once; require a selector; grammar initialization errors remain errors; remove heuristic repeated-text EOG.

**Implementation steps:**
1. Verify the selected llama header contract and add an acceptance-count test around the sampler chain.
2. Remove the duplicate explicit accept after llama_sampler_sample or switch to a documented apply/accept sequence—not both.
3. Add reproducible greedy and seeded stochastic modes without clamping greedy temperature back to .05.
4. Separate required grammar failures from optional absence; no unconstrained fallback when structured output was requested.
5. Replace four-token repetition-as-EOG with an explicit optional user-visible stop policy or remove it; valid code/JSON repetition must remain valid.
6. Preserve fresh sampler-per-generation ownership and reset policy; test history penalties and grammar across repeated requests.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure
./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.generation.SamplingContractTest
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Correctness baseline regenerated after semantic fix. Sampling setup/token overhead recorded separately; no old-output equivalence required for the buggy penalty behavior.

**Acceptance criteria:**
1. G05 passes; a deterministic single-accept reference matches candidate.
2. Invalid required grammar yields a typed failure with no unconstrained output.
3. Greedy/seed round-trip tests and valid repeated code pass.

**Failure/stop conditions:**
1. Do not bless changed stochastic outputs as a performance win or hide grammar errors.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-04 — Structured prompts and exact message-aware budget

**Objective:** Structured prompts and exact message-aware budget.

**Why it matters:** Correct instructions, stable prefix reuse and honest TTFT. Evidence: P07,P09,P19,P20,U01. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** MUST FIX / 3 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-03. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/java/com/prismai/llmhost/generation/PromptBuilder.kt`
- `app/src/main/java/com/prismai/llmhost/generation/GenerationOrchestrator.kt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/runtime/PromptRenderer.hpp`
- `app/src/main/cpp/runtime/PromptRenderer.cpp`
- `app/src/test/java/com/prismai/llmhost/generation/`
- `app/src/androidTest/java/com/prismai/llmhost/generation/`

**New files:** `app/src/main/cpp/runtime/PromptRenderer.hpp`; `app/src/main/cpp/runtime/PromptRenderer.cpp`; `app/src/main/java/com/prismai/llmhost/generation/PromptEnvelope.kt`; `app/src/androidTest/java/com/prismai/llmhost/generation/PromptContractTest.kt`.

**Files to avoid:**
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** PromptEnvelope roles/messages/tools plus explicit raw-prompt mode; native render-and-tokenize budget result.

**Native changes:** Template authority and tokenizer flags independent of cache position; selected upstream common-chat integration with explicit compatibility fallback.

**Implementation steps:**
1. Represent system/developer/user/assistant/tool messages and tool-call/result pairs structurally rather than flattening roles into prose.
2. Use the actual model template and exact tokenizer count to admit a prompt plus reserved output; hash template/rendered bytes/token IDs.
3. Prune optional context and oldest complete conversation groups while retaining mandatory instructions and valid tool pairs.
4. If mandatory input exceeds capacity, return a typed error; do not erase leading tokens.
5. Remove substring-triggered template bypass; raw mode must be explicit, not activated by user marker text.
6. Move submit timing before prompt/RAG preparation and keep those phase timings; preserve the existing no-duplicate-current-user ordering.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure
./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.generation.PromptContractTest
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** 2–10 turn prompt hashes/token counts, short and boundary prompts, tool-heavy prompts and mixed Unicode. Record rendering/tokenization cost; quality before speed.

**Acceptance criteria:**
1. G04/G06 pass for at least representative template families plus explicit unsupported-template behavior.
2. System/tool prefix and pair integrity preserved at exact context boundaries.
3. No hidden native left truncation or cache-dependent BOS.

**Failure/stop conditions:**
1. Template support absent at the selected pin must be explicit; no invented Jinja API signatures or silent ChatML substitution.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-05 — Transactional prefix cache and model-aware context policy

**Objective:** Transactional prefix cache and model-aware context policy.

**Why it matters:** Correct multi-turn reuse and elimination of avoidable prefill. Evidence: P09,U01. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** MUST FIX / 4 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-04. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/runtime/ConversationState.hpp`
- `app/src/main/cpp/runtime/ConversationState.cpp`
- `app/src/test/cpp/context/`
- `app/src/androidTest/java/com/prismai/llmhost/generation/`

**New files:** `app/src/main/cpp/runtime/ConversationState.hpp`; `app/src/main/cpp/runtime/ConversationState.cpp`; `app/src/androidTest/java/com/prismai/llmhost/generation/ContextReplayTest.kt`.

**Files to avoid:**
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Cache identity, invalidation reason, actual reused/evaluated count and explicit context policy returned to diagnostics.

**Native changes:** Commit KV/token/position state transactionally; return-check sequence operations; recompute suffix after every reset/shift.

**Implementation steps:**
1. Write a clean full-replay reference using canonical tokens and compare prefix reuse after every conversation mutation.
2. Separate emitted tokens from committed reusable KV state; invalidate on aborted/failed prefill or decode unless a proved valid prefix is retained.
3. Check sequence-remove/position-operation results and update active tokens only after successful mutation.
4. After any shift or reset, recompute reusable prefix, suffix and last-logit availability; test identical prompts and all-cached requests.
5. Replace heuristic system-prefix detection with explicit token boundaries from the prompt envelope.
6. Default unverified architectures to message pruning and replay; certify any retained in-place shifting per model/backend/RoPE/SWA family.
7. Add fresh saturated-context arithmetic cases and repeated-shift accounting invariants.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure
./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.generation.ContextReplayTest
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Multi-turn incremental versus replay PP, cancellation mid-PP, changed system/LoRA, native SWA/hybrid fixture. Measure saved evaluated tokens, not an inferred cache-hit label.

**Acceptance criteria:**
1. G07/G08 pass; clean replay and reuse match within declared precision tolerance.
2. No suffix-only evaluation after prefix reset.
3. Unverified shift policy is disabled and observable.

**Failure/stop conditions:**
1. Any unexplained logit/token divergence, out-of-range position or stale cache after cancellation blocks merge.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-06 — Requested/applied/observed plan and full load-key wiring

**Objective:** Requested/applied/observed plan and full load-key wiring.

**Why it matters:** Makes configuration experiments real and protects load safety. Evidence: P02–P10,P15–P17,P21–P24,U01. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** MUST FIX / 5 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-01, PIR-05, PIR-07, PIR-08. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/java/com/prismai/llmhost/GenerationSettings.kt`
- `app/src/main/java/com/prismai/llmhost/engine/`
- `app/src/main/java/com/prismai/llmhost/model/ModelManager.kt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/build.gradle.kts`
- `app/src/test/java/com/prismai/llmhost/engine/`
- `app/src/androidTest/java/com/prismai/llmhost/engine/`

**New files:** `app/src/main/java/com/prismai/llmhost/engine/runtime/InferencePlan.kt`; `app/src/main/cpp/runtime/RuntimeDiagnostics.hpp`; `app/src/main/cpp/runtime/RuntimeDiagnostics.cpp`; `app/src/androidTest/java/com/prismai/llmhost/engine/PlanRoundTripTest.kt`.

**Files to avoid:**
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Versioned plan ABI: backend/devices, threads PP/TG, batch/ubatch, context, KV/FA/load policies; native applied state and nullable observed placement.

**Native changes:** Explicit device selection, actual context readback, independent threads/batches, bounded fallback events and benchmark phase counters.

**Implementation steps:**
1. Migrate legacy Vulkan preferences conservatively and persist every intended field; false maps to no Vulkan/explicit safe CPU unless a separately chosen backend exists.
2. Derive complete loadKey including model, adapters, backend/device, context, KV/FA, batch/ubatch and load policies; fix same-model/hash reload suppression.
3. Wire hardware/model profiles and native diagnostics; unknown placement stays unknown, compiled/loadable/usable are distinct.
4. Remove readiness/history/file-size bypasses; re-admit after every actual context/KV/backend fallback.
5. Unify service admission for generation, benchmark, embedding, load and teardown; all raw JNI paths use PIR-02 leases.
6. Wire true benchmark events/counters and explicit strict-CPU control; expose experimental variant knobs only in research builds.
7. Do not advertise vision or unsupported embedding modes as available; return typed capability errors until PIR-16.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure
./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest
./gradlew --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease
./gradlew --no-daemon :app:connectedDevDebugAndroidTest
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Round-trip full configuration grid without speed ranking; same-model reload must alter actual native values. CPU/Vulkan/OpenCL requests report actual usable backend or typed failure.

**Acceptance criteria:**
1. G09/G10/G16 pass; no requested value masquerades as observed.
2. Independent PP/TG thread and batch/ubatch values are verified natively.
3. Fallback is bounded, recorded and re-admitted; settings reload works for unchanged model content.

**Failure/stop conditions:**
1. No backend setter or diagnostic API invented without checking selected headers.
2. No unvalidated experimental feature enabled in production.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-07 — GGUF facts and conservative architecture-aware admission

**Objective:** GGUF facts and conservative architecture-aware admission.

**Why it matters:** Safer model size/context selection with fewer false assumptions. Evidence: P14–P18,R42. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** MUST FIX / 1 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-00. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/java/com/prismai/llmhost/storage/ModelStorageManager.kt`
- `app/src/main/java/com/prismai/llmhost/model/ModelReadinessAssessor.kt`
- `app/src/main/java/com/prismai/llmhost/model/ModelLoadLimits.kt`
- `app/src/main/java/com/prismai/llmhost/model/ModelCapabilityProfile.kt`
- `app/src/main/java/com/prismai/llmhost/model/ModelMemoryEstimator.kt`
- `app/src/test/java/com/prismai/llmhost/model/`
- `app/src/test/java/com/prismai/llmhost/storage/`
- `research/inference/fixtures/gguf/`

**New files:** `app/src/main/java/com/prismai/llmhost/model/ModelCapabilityProfile.kt`; `app/src/main/java/com/prismai/llmhost/model/ModelMemoryEstimator.kt`; `app/src/test/java/com/prismai/llmhost/model/ModelMemoryEstimatorTest.kt`; `app/src/test/java/com/prismai/llmhost/storage/GgufMetadataTest.kt`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Validated metadata profile and memory interval with assumptions/confidence; validation stages instead of a single verified bit.

**Native changes:** Specify native actual allocation/architecture readback for PIR-06; no central native edit here.

**Implementation steps:**
1. Make architecture-key parsing order-independent and bounds-check counts/string lengths/array sizes/offsets against file size and integer overflow.
2. Separate magic/header/metadata/hash/tensor verification states; metadata parse failure cannot claim full model verification.
3. Extract per-layer attention/state information and K/V dimensions when available; retain unknown rather than invented dense defaults.
4. Implement conventional dense/GQA/MQA estimator with quant block overhead/layout padding, then explicit SWA/hybrid/recurrent unknown handling.
5. Add actual-state inputs for repack/compute/load peaks; stop adding whole active file size as reclaimed RAM.
6. Remove performance-history safety override; do not relax 4200 MiB cap until measured admission error and lifecycle data justify it.
7. Hash during import where safe; strong immutable identity controls cached verification; linked mutable files require revalidation.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
./gradlew --no-daemon :app:testDevDebugUnitTest --tests "*ModelMemoryEstimator*" --tests "*GgufMetadata*"
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Estimator synthetic architecture fixtures plus later measured per-phase allocation calibration. Oversized model tests must use generated metadata fixtures before real large models.

**Acceptance criteria:**
1. G10 passes; key-order changes do not alter facts.
2. Unknown/hybrid/SWA cases cannot produce an unjustified SAFE label.
3. No previous fast run overrides present memory constraints.

**Failure/stop conditions:**
1. Missing architecture details block precise estimate; use conservative bounds or reject, not guessed equations.
2. No unsafe real-model load to test an optimistic bound.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-08 — Hardware and backend capability probes

**Objective:** Hardware and backend capability probes.

**Why it matters:** Enables defensible per-device plans and backend eligibility. Evidence: P14,U05,U06,R43. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** HIGH ROI / 1 / M / medium.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-00. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/java/com/prismai/llmhost/model/DeviceProfiler.kt`
- `app/src/main/java/com/prismai/llmhost/engine/runtime/HardwareCapabilityProfile.kt`
- `app/src/main/cpp/runtime/HardwareCapabilityProbe.hpp`
- `app/src/main/cpp/runtime/HardwareCapabilityProbe.cpp`
- `app/src/test/java/com/prismai/llmhost/engine/runtime/`
- `app/src/androidTest/java/com/prismai/llmhost/engine/`

**New files:** `app/src/main/java/com/prismai/llmhost/engine/runtime/HardwareCapabilityProfile.kt`; `app/src/main/cpp/runtime/HardwareCapabilityProbe.hpp`; `app/src/main/cpp/runtime/HardwareCapabilityProbe.cpp`; `app/src/androidTest/java/com/prismai/llmhost/engine/CapabilityProbeTest.kt`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Dated hardware snapshot with nullable fields and provenance; backend capability probe output for PIR-06 wiring.

**Native changes:** Enumerate actual ggml devices/registrations, ISA and accessible topology; no model allocation required for initial probe.

**Implementation steps:**
1. Separate stable device/driver facts from transient memory/thermal/power state and avoid a 90-second cached memory admission snapshot.
2. Probe allowed CPU set/ISA/topology using supported interfaces; tolerate restricted sysfs and hotplug.
3. Enumerate backend device IDs, driver versions and relevant Vulkan/OpenCL capabilities; distinguish compiled from loadable and execution-tested.
4. Represent HTP/QNN/vendor library availability as experimental capability, not guaranteed model support.
5. Add fixture tests for denied permissions, missing libraries, no GPU and invalid thermal headroom.
6. Supply leaf modules and ABI field proposal to PIR-06; do not edit shared JNI/CMake wiring concurrently.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.engine.CapabilityProbeTest
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Probe latency and allocations only; no performance ranking. Device fingerprints must not include user identifiers in exported artifacts.

**Acceptance criteria:**
1. Unavailable fields have reasons; no name-only GPU/NPU assumption.
2. Probe works without model load and does not crash on absent vendor libraries.

**Failure/stop conditions:**
1. Do not use privileged/root interfaces or force-load untrusted libraries from arbitrary paths.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-09 — Unified thermal, battery and Android lifecycle safety

**Objective:** Unified thermal, battery and Android lifecycle safety.

**Why it matters:** Thermal sustainability, predictable recovery and Android reliability. Evidence: P03,P10–P13,A01–A04. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** MUST FIX / 6 / M / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-06. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/src/main/java/com/prismai/llmhost/service/MemoryGovernor.kt`
- `app/src/main/java/com/prismai/llmhost/service/ThermalBatteryGovernor.kt`
- `app/src/main/java/com/prismai/llmhost/util/AdaptiveThermalGovernor.kt`
- `app/src/test/java/com/prismai/llmhost/service/`
- `app/src/androidTest/java/com/prismai/llmhost/service/`

**New files:** `app/src/test/java/com/prismai/llmhost/service/RuntimeSafetyPolicyTest.kt`; `app/src/androidTest/java/com/prismai/llmhost/service/InferenceLifecycleTest.kt`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** One effective safety state and policy event stream; actual applied thread changes and interruption reasons.

**Native changes:** Use existing plan/lifetime APIs; no new raw native control path.

**Implementation steps:**
1. Resolve every thermal-governor call site and consolidate ownership into the inference service; retire duplicates only after migration.
2. Combine OS thermal status, valid headroom and battery safety so a benign battery update cannot overwrite a severe OS condition.
3. Rate-limit headroom, handle NaN/unsupported status, add hysteresis and unregister all callbacks/scopes.
4. Apply safe thread changes at phase boundaries and record them; do not lower context mid-request silently.
5. Verify FGS types/permissions/API guards, including minSdk26 versus native platform29, timeout, start failure and process death.
6. Eliminate unnecessary main-thread teardown wait while retaining safe off-main lifetime completion; persist interrupted transcript state without duplicate auto-resume.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
./gradlew --no-daemon :app:connectedDevDebugAndroidTest
bash scripts/emulator/run-tests.sh both
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Lifecycle/thermal injected tests plus sustained physical run with real OS status and battery conditions. API26/28 compatibility needs an additional target; existing API30/36 AVDs do not prove it.

**Acceptance criteria:**
1. G02/G14 pass; one policy owns safety and callback registration.
2. Critical safety interruption is visible and cache invalidation correct.
3. No benchmark proceeds after unsafe thermal/battery state.

**Failure/stop conditions:**
1. Do not fake thermal sensor values as physical measurements or bypass FGS/SDK restrictions.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-10 — Controlled llama.cpp update and research harness wiring

**Objective:** Controlled llama.cpp update and research harness wiring.

**Why it matters:** Current backend/model capabilities with controlled regression risk. Evidence: P01–P03,P26–P28,U01–U06. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** HIGH ROI / 7 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-09. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/cpp/third_party/llama.cpp`
- `patches/llama.cpp/`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/LLAMA_CPP_VERSION.md`
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/build.gradle.kts`
- `research/inference/`
- `docs/inference/`

**New files:** `research/inference/upstream-diff.md`; `research/inference/build-variants.json`.

**Files to avoid:**
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Minimal version adapter for selected upstream APIs; preserve Prism plan/stream contract.

**Native changes:** Compare original pin to candidate 661643e43079a4ee6faab4c1895291767b67ea8d; reconcile local Vulkan patch and build flags; no blanket feature promotion.

**Implementation steps:**
1. Create paired branches/build artifacts with identical repaired Prism integration and old/new llama pins.
2. Review header/model/backend/build diffs and enumerate local patches; update stale snapshot prose from generated build facts.
3. Adapt only necessary API changes; retain explicit CPU control, observed telemetry and all correctness fixtures.
4. Provide research-only feature/build parameter injection for independent CPU/Vulkan/OpenCL/load experiments; keep production defaults conservative.
5. Verify arm64/x86_64, release-like benchmark and Play build/R8, native host tests and physical small-model smoke.
6. Compare old/new correctness, model support, memory/load and coarse PP/TG; choose a known-good candidate, not automatically newest master.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure
./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest
./gradlew --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease
./gradlew --no-daemon :app:connectedDevDebugAndroidTest
bash scripts/emulator/run-tests.sh both
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Paired old/new runtime on identical model/quant/plan; cooled burst and at least one sustained baseline. Report APK/native size and build time.

**Acceptance criteria:**
1. G01–G11/G14/G16 relevant gates pass; no unexplained model regression.
2. Every patch and build knob recorded; no silent downgrade of tensor checks or fallback reporting.
3. A rejected update preserves the old known-good runtime and a complete rejection report.

**Failure/stop conditions:**
1. Compiler success alone is insufficient. Missing physical device blocks accelerator promotion.
2. Do not merge update and unrelated default changes in one commit.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-11 — CPU phase/thread/topology experiments

**Objective:** CPU phase/thread/topology experiments.

**Why it matters:** Potential high-ROI PP/TG/energy tuning. Evidence: P09,U05,A03. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** EXPERIMENT / 8 / M / medium.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-10. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `research/inference/experiments/cpu/`
- `research/inference/results/cpu/`

**New files:** `research/inference/experiments/cpu/candidates.json`; `research/inference/experiments/cpu/run_cpu_sweep.py`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** No production API changes; use the established plan/benchmark contract.

**Native changes:** Research variants only; no Engine.cpp edits.

**Implementation steps:**
1. Confirm strict CPU execution and actual KleidiAI/ISA eligibility on the selected model types.
2. Compare a small topology-aware set of decode/prefill thread pairs, including unpinned baseline and allowed performance-core subsets.
3. Measure oversubscription, CPU migrations, page faults, worker startup, PP/TG and UI latency where accessible.
4. Test PerformanceHintManager only with real worker TIDs and supported APIs; record unavailable scheduling controls.
5. Confirm promising pairs on held-out prompts and sustained runs; propose per-cohort presets rather than a global thread maximum.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
python3 research/inference/experiments/cpu/run_cpu_sweep.py --help
python3 research/inference/tools/check_run.py research/inference/results/cpu/*.jsonl
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** At least 5 paired burst repeats for finalists, 2 model tiers if fit, sustained top candidates; energy/thermal and P95 UI/cancel constraints.

**Acceptance criteria:**
1. At least 5% material low-risk gain beyond noise on declared objective, no correctness or meaningful tail/energy regression; otherwise report no winner.
2. Actual PP/TG threads and affinity in each record.

**Failure/stop conditions:**
1. No root/governor writes; no architecture flags unsupported by device; no universal winner extrapolation.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-12 — Vulkan capabilities, offload and microbatch bake-off

**Objective:** Vulkan capabilities, offload and microbatch bake-off.

**Why it matters:** Potential PP and accelerator-utilization gains. Evidence: P02,U05. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** EXPERIMENT / 8 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-10. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `research/inference/experiments/vulkan/`
- `research/inference/results/vulkan/`

**New files:** `research/inference/experiments/vulkan/variants.cmake`; `research/inference/experiments/vulkan/candidates.json`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** No production API changes; consume backend/plan telemetry.

**Native changes:** Research-only shader/feature variants through PIR-10 harness, not global defaults.

**Implementation steps:**
1. Capture Vulkan device/driver/extensions and actual model/backend placement evidence.
2. Compare disabled-feature control with supported integer-dot/coop-matrix paths individually, then only justified combinations.
3. Coarsely search partial/full offload × microbatch; include strict CPU control and cold/warm shader-cache states.
4. Run per-op and model correctness for supported quants/FA/KV; record unsupported shapes/fallback.
5. Measure sustained TG, long PP, load/compile cost, peak memory, energy and cancellation tail; retain driver-specific evidence.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
python3 research/inference/tools/validate_program.py
python3 research/inference/tools/check_run.py research/inference/results/vulkan/*.jsonl
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Matched GGUF/model/quality; representative short/long prompts, at least 2 relevant driver cohorts for broad promotion; unsupported features skipped with reasons.

**Acceptance criteria:**
1. Complex kernel changes require about 10–15% material objective gain and G11 pass; simple offload presets use 5% low-risk gate.
2. No driver failure or sustained/energy regression hidden by a burst win.

**Failure/stop conditions:**
1. Extension name alone is not proof of correct shader execution; stop on any math/driver crash.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-13 — Adreno OpenCL quantization and cache bake-off

**Objective:** Adreno OpenCL quantization and cache bake-off.

**Why it matters:** Potential best supported Snapdragon GPU route, not assumed. Evidence: P02,P03,U03,R04. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** EXPERIMENT / 8 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-10. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `research/inference/experiments/opencl/`
- `research/inference/results/opencl/`

**New files:** `research/inference/experiments/opencl/candidates.json`; `research/inference/experiments/opencl/cache-policy.md`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** No production changes; explicit OpenCL device and cache configuration through research harness.

**Native changes:** Use selected upstream OpenCL, optional native-library declaration only in isolated research packaging.

**Implementation steps:**
1. Resolve OpenCL headers/runtime library provenance and optional manifest declarations; no untrusted downloaded vendor binaries.
2. Test current supported Q4_0/Q4_K_M/Q8 cases rather than assuming the older Q4_0/Q6_K-only note.
3. Configure a private program-cache directory keyed by model-independent kernel/device/driver/build identity and record cache hits/misses.
4. Compare strict CPU, Vulkan and OpenCL at equivalent model/quality/context; test FA/KV/session-state constraints explicitly.
5. Run load/cancel/reload/driver-error probes and sustained memory/energy tests; produce an allowlist or reject the backend.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
python3 research/inference/tools/check_run.py research/inference/results/opencl/*.jsonl
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Same physical device with serialized CPU/Vulkan/OpenCL trials; cold/warm cache, at least 5 paired finalists; 2 Adreno/driver cohorts for production breadth.

**Acceptance criteria:**
1. Material sustained/TTFT/energy benefit with G11/G14; all fallback and unsupported quant restrictions documented.
2. Cache failure degrades observably to compile-from-source, not silent warm-run relabeling.

**Failure/stop conditions:**
1. No platform libOpenCL or license entitlement → BLOCKED, not automatic sideload of arbitrary libraries.
2. No default promotion from desktop X2 binary-kernel claims.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-14 — Load, storage and compiler policy experiments

**Objective:** Load, storage and compiler policy experiments.

**Why it matters:** Potential startup and memory gains with bounded complexity. Evidence: P02,P03,P09,P18,U05,A04. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** EXPERIMENT / 8 / M / medium.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-10. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `research/inference/experiments/load_build/`
- `research/inference/results/load_build/`

**New files:** `research/inference/experiments/load_build/variants.json`; `research/inference/experiments/load_build/storage-cases.json`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** No production changes; measured proposals for existing load policies.

**Native changes:** mmap/repack/check/warm-up and compiler variants through research build; inspect link map before removing objects.

**Implementation steps:**
1. Separate file copy, hash, tensor verification, backend/shader initialization, weight preparation and context allocation timings.
2. Compare mmap/read/repack and bounded prefault where supported; keep mlock off baseline and record memory/fault effects.
3. Test trusted immutable cached verification versus full verification without weakening untrusted import checks.
4. Compare portable release O2/O3/ThinLTO or LTO and optional representative PGO; keep symbol artifacts and build provenance.
5. Audit minSDK native load and 4/16 KiB libraries, plus linked SAF seekability/mutation/revocation/disk-full behavior.
6. Measure binary/package size, cold/warm user TTFT and peak memory; do not claim storage cold after only force-stop.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
python3 research/inference/tools/check_run.py research/inference/results/load_build/*.jsonl
./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest
./gradlew --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Process-cold/page-cache status labeled, warm model and shader cache cohorts; realistic supported GGUF sizes; cold peak PSS/page faults plus correctness.

**Acceptance criteria:**
1. Low-risk load changes meet 5% material startup/TTFT improvement or clear integrity/reliability benefit without peak-memory harm.
2. Compiler changes maintain all correctness gates and portable ABI/ISA behavior.

**Failure/stop conditions:**
1. No flags justified only by intuition; no unsafe skipped validation, fast-math, root drop_caches requirement or missing symbols.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-15 — KV quality and memory frontier

**Objective:** KV quality and memory frontier.

**Why it matters:** Larger usable context/model envelope with explicit quality protection. Evidence: P09,P15,R42,U01. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** EXPERIMENT / 8 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-10. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `research/inference/experiments/kv/`
- `research/inference/results/kv/`

**New files:** `research/inference/experiments/kv/candidates.json`; `research/inference/experiments/kv/quality_protocol.md`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** No production changes; per-model/architecture KV eligibility proposal.

**Native changes:** Existing independent K/V/FA plan parameters only; no new custom quant kernels.

**Implementation steps:**
1. Establish F16 quality/memory reference and test supported q8 configurations first.
2. Test selected asymmetric K/V and q4 combinations only after native type/FA/backend capability checks.
3. Compare dense GQA, native SWA/hybrid and unsupported/recurrent handling using actual allocation behavior.
4. Run long-context retrieval/instruction retention, teacher-forced likelihood/logit analysis, structured output and normal chat tasks.
5. Measure memory interval prediction error and sustained speed/energy; propose per-model allowlists with fallback re-admission.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
python3 research/inference/tools/check_run.py research/inference/results/kv/*.jsonl
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Full quality non-inferiority protocol, context-size sweep within safe admission, measured actual allocations; no equality claim across changed precision.

**Acceptance criteria:**
1. G12 passes with adequate sample/confidence; material memory or energy benefit and no mandatory semantic regressions.
2. Unknown quality remains EXPERIMENTAL, never auto-enabled.

**Failure/stop conditions:**
1. No global Q4 KV or min(window,context) estimate across every layer; unsupported fallback cannot exceed admission unnoticed.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-16 — Truthful embeddings, LoRA and vision capabilities

**Objective:** Truthful embeddings, LoRA and vision capabilities.

**Why it matters:** Reliable operation compatibility and honest RAG/multimodal behavior. Evidence: P07,P09,P10,R04. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** MUST FIX / 8 / M / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-10. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/cpp/runtime/EmbeddingRuntime.hpp`
- `app/src/main/cpp/runtime/EmbeddingRuntime.cpp`
- `app/src/main/cpp/runtime/ModelOperationCapabilities.hpp`
- `app/src/androidTest/java/com/prismai/llmhost/model/`
- `app/src/test/java/com/prismai/llmhost/model/`
- `research/inference/capabilities/`

**New files:** `app/src/main/cpp/runtime/EmbeddingRuntime.hpp`; `app/src/main/cpp/runtime/EmbeddingRuntime.cpp`; `app/src/main/cpp/runtime/ModelOperationCapabilities.hpp`; `app/src/androidTest/java/com/prismai/llmhost/model/AuxiliaryCapabilityTest.kt`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Operation capability result with typed unsupported and memory requirements; integration patch proposal only for root-owned Engine/JNI/service.

**Native changes:** Architecture/pooling-safe auxiliary context; no accidental reuse of chat KV or fake vision success.

**Implementation steps:**
1. Trace encode callers and determine which actual model architectures/pooling modes the selected llama API permits.
2. Add capability checks and correct embedding shape/pooling/normalization reference fixtures; unsupported chat models fail explicitly.
3. Prefer a dedicated bounded embedding context only if memory permits; otherwise serialize a documented save/rebuild path without corrupting chat state.
4. Test LoRA apply/replace/failure transaction and cache invalidation; no partial adapter success reported as full success.
5. Keep vision unsupported until real projector/model/template/image-token processing is implemented and validated; remove misleading availability, not merely return false silently.
6. Deliver leaf modules and exact wiring diff proposal for the integrator; no parallel Engine.cpp edits.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure
./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.model.AuxiliaryCapabilityTest
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Correctness and memory coexistence of chat/embedding/projector contexts, not a mandatory vision feature build. Report unsupported models as such.

**Acceptance criteria:**
1. G15 passes; no unrelated chat KV mutation or wrong embedding shape/pooling.
2. Unsupported projector/operation is explicit in capability/UI/runtime contract.
3. Optional new capability is not enabled without measured memory and correctness.

**Failure/stop conditions:**
1. Missing compatible embedding/vision fixture blocks support claims; do not produce synthetic embeddings or successful stub responses.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-17 — Phone-appropriate speculative decoding experiment

**Objective:** Phone-appropriate speculative decoding experiment.

**Why it matters:** Potential decode gain without another primary runtime. Evidence: P02,P09,R04,R08. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** EXPERIMENT / 9 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-10, PIR-15. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `research/inference/experiments/speculation/`
- `research/inference/results/speculation/`

**New files:** `research/inference/experiments/speculation/SpeculationProbe.cpp`; `research/inference/experiments/speculation/candidates.json`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Standalone experiment using current common speculative APIs; proposed counters/plan additions only if retained.

**Native changes:** N-gram first; compatible tiny draft/MTP second; rollback and state ownership proven before production wiring.

**Implementation steps:**
1. Inventory modes actually supported by the selected upstream revision and model; compile-time source presence is not integration.
2. Start with bounded N-gram proposals on repetitive code/copy tasks and general chat negative controls.
3. For compatible draft/MTP, account for target/draft weights, both caches, rollback state and verification batch memory before admission.
4. Record proposed/accepted/rejected tokens, target calls, proposal and verification time, cancellation and thermal traces.
5. Test greedy equivalence where exact, and valid rejection-corrected sampling semantics rather than assuming same seed means identical stochastic output.
6. Propose production integration only if sustained wall-time/energy improves and correctness/rollback gates pass.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
python3 research/inference/tools/check_run.py research/inference/results/speculation/*.jsonl
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Same target model/quality and tasks; PP/TTFT cannot be hidden by reporting decode-only; small/large model and repetitive/nonrepetitive workloads; memory/energy.

**Acceptance criteria:**
1. G13 passes; about 15% material sustained or task-energy improvement to justify complexity, unless implementation is demonstrably tiny and low-risk.
2. Rejected proposals never reach transcript or reusable cache.

**Failure/stop conditions:**
1. Stop on distribution/exactness failure, memory admission violation, poor acceptance with net slowdown or thermal regression.
2. No arbitrary EAGLE/MTP model compatibility assumption.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-18 — Upstream Hexagon capability and sustained experiment

**Objective:** Upstream Hexagon capability and sustained experiment.

**Why it matters:** Potential NPU acceleration while preserving GGUF ecosystem. Evidence: U06,R04. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** LONG TERM / 8 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-10. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `research/inference/experiments/hexagon/`
- `research/inference/results/hexagon/`

**New files:** `research/inference/experiments/hexagon/build_manifest.json`; `research/inference/experiments/hexagon/candidates.json`; `research/inference/experiments/hexagon/sdk-license-inventory.md`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** No production backend or generic engine interface; use upstream GGUF device selection.

**Native changes:** Isolated Android research executable/flavor with SDK and HTP runtime provenance.

**Implementation steps:**
1. Verify accessible Qualcomm hardware, SDK/toolchain and runtime redistribution rights; do not assume QNN export is required for ggml Hexagon.
2. Build the documented selected-revision backend and inspect supported tensor/quant/operation paths.
3. Run a small safe smoke model with explicit HTP device/session IDs; distinguish virtual sessions from physical accelerator cores.
4. Record load/allocations, CPU fallback operations and unavailable telemetry; a zero reported HTP buffer is not proof of zero device memory.
5. Compare optimized CPU/OpenCL/Vulkan using identical GGUF where supported; test long PP, sustained TG, cancel, failed device initialization and restart quarantine.
6. Keep all native/vendor artifacts outside production package pending acceptance and notices.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
python3 research/inference/tools/check_run.py research/inference/results/hexagon/*.jsonl
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Backend-only GGUF controlled bake-off; physical Qualcomm hardware required. Two driver/SoC cohorts for broad promotion, one can justify labeled experimental allowlist only.

**Acceptance criteria:**
1. G11/G14 pass; meaningful sustained/energy/TTFT benefit and manageable SDK/package burden.
2. Any partial operator fallback and telemetry uncertainty are recorded.

**Failure/stop conditions:**
1. Unavailable SDK/permission or unknown redistribution entitlement → BLOCKED.
2. Driver hang/crash or unexplained output divergence stops promotion.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-19 — Evidence-keyed runtime planner and profile store

**Objective:** Evidence-keyed runtime planner and profile store.

**Why it matters:** Automatic defensible defaults instead of settings burden. Evidence: P14–P17,P21–P24,R03,R42. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** HIGH ROI / 9 / M / medium.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-11, PIR-12, PIR-13, PIR-14, PIR-15. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/java/com/prismai/llmhost/engine/runtime/RuntimePlanner.kt`
- `app/src/main/java/com/prismai/llmhost/engine/runtime/RuntimeProfileStore.kt`
- `app/src/test/java/com/prismai/llmhost/engine/runtime/`
- `docs/inference/`

**New files:** `app/src/main/java/com/prismai/llmhost/engine/runtime/RuntimePlanner.kt`; `app/src/main/java/com/prismai/llmhost/engine/runtime/RuntimeProfileStore.kt`; `app/src/test/java/com/prismai/llmhost/engine/runtime/RuntimePlannerTest.kt`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Pure selectPlan(profile, model, workload, safety, history) and versioned private empirical profile store.

**Native changes:** No native changes; consume truthful applied/observed contracts.

**Implementation steps:**
1. Import only eligible clean benchmark cohorts; an experiment may complete REJECT/BLOCKED and contributes no winning plan.
2. Key profiles by full model/runtime/device/driver/build/template/quality identity; migrate old records as untrusted.
3. Apply hard capability/memory/safety constraints before ranking PP, TG, TTFT, energy and sustained reliability.
4. Use a small Pareto frontier and workload-specific policy; prefer robust baseline when results are stale or statistically inconclusive.
5. Return explicit reasons and fallback sequence; expose expert override without bypassing hard safety.
6. Persist atomically with retention and privacy controls; test changed driver/model/runtime invalidation and no-history devices.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
./gradlew --no-daemon :app:testDevDebugUnitTest --tests "*RuntimePlanner*"
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Replay experiment records offline and held-out device/workload scenarios. Physical confirmation of any selected nonbaseline plan required before production eligibility.

**Acceptance criteria:**
1. Planner never selects unsafe/uncertified/stale-incompatible plans; reasons reproducible.
2. No-history and rejected-experiment paths select conservative known-good behavior.
3. One device result is not generalized to an unrelated device.

**Failure/stop conditions:**
1. Do not average across quantization/quality/runtime/thermal cohorts or promote a largest single TPS sample.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-20 — Bounded auto-tuner with exclusive operation ownership

**Objective:** Bounded auto-tuner with exclusive operation ownership.

**Why it matters:** Learns better per-device defaults with bounded user cost. Evidence: P10,P22,R03,A01. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** HIGH ROI / 10 / M / medium.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-19. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/java/com/prismai/llmhost/engine/runtime/InferenceAutoTuner.kt`
- `app/src/main/java/com/prismai/llmhost/benchmark/BenchmarkRunner.kt`
- `app/src/test/java/com/prismai/llmhost/engine/runtime/`
- `app/src/androidTest/java/com/prismai/llmhost/benchmark/`

**New files:** `app/src/main/java/com/prismai/llmhost/engine/runtime/InferenceAutoTuner.kt`; `app/src/test/java/com/prismai/llmhost/engine/runtime/InferenceAutoTunerTest.kt`; `app/src/androidTest/java/com/prismai/llmhost/benchmark/AutoTunerLifecycleTest.kt`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Bounded tuning job/candidate budget, progress/cancel, trial-disposition contract; no hidden long background benchmark.

**Native changes:** Use existing serialized plan/benchmark APIs and safety state; no new raw calls.

**Implementation steps:**
1. Implement constrained staged search with at most 12 initial candidates and 2 sustained finalists by default.
2. Search backend/offload/microbatch interactions before local thread refinement; postpone KV/speculation to separate quality-qualified branches.
3. Acquire the sole device/engine benchmark lease and snapshot/restore user settings and conversation state safely.
4. Stop on thermal, battery, memory, cancel or lifecycle limits; persist partial trials and explicit stop reason.
5. Separate exploration from held-out confirmation; promote only after correctness and confidence gates.
6. Require explicit user intent for sustained tuning and avoid repeated warm-up or searching on every launch.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
./gradlew --no-daemon :app:connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.prismai.llmhost.benchmark.AutoTunerLifecycleTest
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Replay deterministic candidate simulator plus authorized physical bounded tuning; verify search overhead, thermal cost and no regression versus manual known-good profile.

**Acceptance criteria:**
1. Budget/lease/cancellation/safety tests pass; no search explosion or silent settings drift.
2. No failed/incomplete trial enters winning profile store.

**Failure/stop conditions:**
1. Stop if autotuning disrupts an active chat, starts without the required intent, or ignores thermal/memory constraints.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-21 — Standalone MNN versus optimized llama.cpp bake-off

**Objective:** Standalone MNN versus optimized llama.cpp bake-off.

**Why it matters:** Tests whether a second engine earns its cost. Evidence: R06–R09. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** EXPERIMENT / 10 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-19. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `research/inference/experiments/mnn/`
- `research/inference/results/mnn/`

**New files:** `research/inference/experiments/mnn/export_manifest.json`; `research/inference/experiments/mnn/bakeoff_protocol.md`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Standalone benchmark interface only; no Prism production API or dependency change.

**Native changes:** Independent MNN Android runner with pinned export/runtime/backends; no normal APK linkage.

**Implementation steps:**
1. Lock MNN source/release, model export and toolchain; inventory licenses and every prepared artifact.
2. Start from the same original checkpoint as an admitted GGUF model and document quantization/calibration/tokenizer/template differences.
3. Implement equivalent request/stop/output/quality workloads and all required memory/load/energy/thermal measurements.
4. Compare against the planner’s best confirmed llama.cpp plan, not the historical defaults.
5. Run cold/warm and sustained tests, corrupted artifact, unsupported backend, cancellation and model-switch recovery.
6. Compute alternate-engine gate with confidence bounds; output RETAIN/MODIFY/REJECT/BLOCKED and full integration cost ledger.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
python3 research/inference/tools/check_run.py research/inference/results/mnn/*.jsonl
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Section 25/32 alternate threshold: ≥25% sustained TG or ≥30% UI-equivalent TTFT or ≥20% task energy, with quality non-inferiority and normally ≤5% PSS regression.

**Acceptance criteria:**
1. Equivalent tasks and original checkpoint documented; no fake same-quant claim.
2. Broad promotion requires 2 device cohorts/2 model tiers; narrow benefit remains explicitly scoped.
3. Only retained result can authorize PIR-23.

**Failure/stop conditions:**
1. Unsupported export/unknown license/no physical benchmark → BLOCKED or REJECT, not a fabricated comparison.
2. Do not add MNN to production merely because the standalone app launches.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-22 — Compiled/vendor alternative screening and one bounded pilot

**Objective:** Compiled/vendor alternative screening and one bounded pilot.

**Why it matters:** Discovers a justified curated fast lane without fragmentation. Evidence: R10–R20,R24–R41. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** LONG TERM / 10 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-19. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `research/inference/experiments/alternate_screen/`
- `research/inference/results/alternate_screen/`

**New files:** `research/inference/experiments/alternate_screen/candidates.json`; `research/inference/experiments/alternate_screen/decision.md`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** No production abstraction; comparison intake contract and one selected pilot.

**Native changes:** Select a justified LiteRT-LM/ExecuTorch/MLC or MLLM/ORT candidate; SDK/device access must be real.

**Implementation steps:**
1. Refresh exact releases/commits and license/SDK access for LiteRT-LM, ExecuTorch-QNN, MLC and relevant discovered candidates.
2. Score specific model/device capability, export support, artifact size, maintenance and independent performance evidence; do not count backend marketing as an LLM benchmark.
3. Select at most one pilot within the declared resource budget; record why others are deferred/rejected.
4. Export/package the same checkpoint where supported, or explicitly label a unique-capability rather than speed comparison.
5. Apply the identical quality/load/lifecycle/sustained/energy protocol and alternate-engine thresholds as MNN.
6. Report whether the candidate beats optimized llama.cpp including format/conversion/security costs; recommend no adapter when uncertain.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
python3 research/inference/tools/check_run.py research/inference/results/alternate_screen/*.jsonl
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Same thresholds as PIR-21; actual model/package/SDK/driver and partition/operator placement where applicable. Unique capability requires separately accepted product criterion.

**Acceptance criteria:**
1. A reproducible and licensed candidate only, with bounded scope and honest deferred entries.
2. No generic Android-NPU superiority claim from a CNN delegate or vendor report.

**Failure/stop conditions:**
1. Unavailable vendor SDK/rights/device or missing LLM operator path blocks the pilot.
2. Do not create a fleet of alternate backends to satisfy a matrix.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


### PIR-23 — Conditional minimal alternate-engine adapter

**Objective:** Conditional minimal alternate-engine adapter.

**Why it matters:** Optional measured capability, not framework expansion. Evidence: Sections 25,32. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** LONG TERM / 11 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-21, PIR-22. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/experimental/`
- `research/inference/alternate-adapter/`
- `docs/inference/`

**New files:** `app/src/experimental/java/com/prismai/llmhost/engine/ExperimentalSession.kt`; `research/inference/alternate-adapter/acceptance.md`.

**Files to avoid:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Only the minimal interface required by two proven implementations: load/generate/cancel/close/capabilities/metrics; optional packaging.

**Native changes:** Retained candidate runner behind isolated flavor/module; integrator owns production wiring.

**Implementation steps:**
1. Require a signed-off RETAIN disposition from an alternate bake-off and a complete license/security/package inventory.
2. Define only shared semantics actually supported by llama.cpp and the winning candidate; unsupported capabilities stay explicit.
3. Keep GGUF primary and existing imports/chats functional without installing alternate artifacts.
4. Use separate optional packaging where feasible; add content-hash/compatibility checks for converted/compiled models.
5. Integrate lossless streaming, prompt semantics, cancellation, lifetime, fallback and truthful telemetry through the proven contract.
6. Run full conformance and before/after optimized baseline; document removal procedure and ongoing contributor burden.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure
./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest
./gradlew --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease
./gradlew --no-daemon :app:connectedDevDebugAndroidTest
bash scripts/emulator/run-tests.sh both
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Repeat retained bake-off in the integrated app; standalone advantage must survive bridge/UI/lifecycle/package integration.

**Acceptance criteria:**
1. No adapter without material integrated benefit or explicitly accepted unique capability.
2. Primary GGUF flow and all mandatory correctness gates pass; app works offline without alternate package.

**Failure/stop conditions:**
1. No winner → report SKIPPED/REJECT and make no app changes.
2. Integrated gain disappearing, license uncertainty or lifecycle failures kills the adapter.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.


## 30. Root integrator mission

### PIR-INT — Root integrator and release evidence authority

**Objective:** Root integrator and release evidence authority.

**Why it matters:** Coherent tested runtime rather than a pile of experiments. Evidence: P26,P27 and all acceptance gates. The source audit is pinned to `49ed799a3e633c5192c2c03317d1d50ffdc57c4c` with llama.cpp `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`. This packet proposes implementation/tests; it is not evidence that those tests have already run.

**Priority / wave / complexity / risk:** MUST FIX / 12 / L / high.

**Starting commit:** Integrator-issued immutable checkpoint containing accepted dependency code; completed experiments may contribute only a disposition report. Record resolved SHA before edits.

**Dependencies:** PIR-16, PIR-20. Before edits, the integrator records the resolved immutable base SHA. Implementation dependencies require accepted code. Experiment dependencies may terminate REJECT/BLOCKED with a complete report; their code is not automatically merged. Never replace a missing dependency with an assumption.

**Allowed scope and files likely to modify:**
- `app/src/main/cpp/Engine.cpp`
- `app/src/main/cpp/Engine.hpp`
- `app/src/main/cpp/llmhost_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt`
- `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt`
- `app/build.gradle.kts`
- `research/inference/`
- `docs/inference/`
- `patches/llama.cpp/`
- `app/src/test/`
- `app/src/androidTest/`

**New files:** `docs/inference/FINAL_REPORT.md`; `research/inference/completed.json`; `research/inference/release-evidence.json`.

**Files to avoid:**
- `app/src/main/cpp/third_party/llama.cpp/** (unless this mission explicitly owns the dependency update)`
- `unrelated UI, cloud/server features, credentials, model weights and user transcripts`

**API changes:** Own all shared wiring, compatibility/migrations and accepted production plan policy.

**Native changes:** Integrate only accepted native leaf work; retain known-good rollback pin and reject unsupported optimization patches.

**Implementation steps:**
1. At every checkpoint verify dependency SHAs, clean scoped diffs, tests, benchmark schemas/raw artifacts and independent review; never accept a prose-only PASS.
2. Merge central contract fixes serially, then accepted leaf modules; the root owns Engine.cpp/JNI/bridge/service/build wiring and resolves conflicts once.
3. Do not cherry-pick experimental kernel/backend changes merely because a mission finished; RETAIN/MODIFY/REJECT/BLOCKED dispositions govern integration.
4. Run flavor-qualified unit, native host, release-like build/R8, connected lifecycle, minimum-SDK and page-size gates with actual available resources.
5. Rerun corrected baseline and each retained plan on physical devices with before/after exact builds; preserve failure/quality/thermal/energy evidence.
6. Update architecture/source-pin/build notes, model/backend eligibility and fallback policy; include APK/native size, support scope and known unknowns.
7. Produce final release decision, migration and rollback notes. Do not publish binaries, change repo visibility, spend money or delete models/worktrees without separate authorization.

**Required tests / commands:** run from the PrismLocal checkout root, not a composite workspace root. These are existing Gradle tasks or explicitly proposed tests/harnesses that this program must create; a named new test must exist before claiming the command passed. Windows equivalent uses `.\gradlew.bat`; the full existing gate is `pwsh -File scripts/verify.ps1`.

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
cmake -S app/src/test/cpp -B build/prism-native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/prism-native-tests && ctest --test-dir build/prism-native-tests --output-on-failure
./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest
./gradlew --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease
./gradlew --no-daemon :app:connectedDevDebugAndroidTest
bash scripts/emulator/run-tests.sh both
python3 research/inference/tools/validate_program.py
python3 research/inference/tools/check_run.py research/inference/results/release/*.jsonl
```

The portable C++ harness is created by PIR-00. The integration agent copies this package’s `tools/` and `schemas/` into `research/inference/` before those helper commands. Missing device/model/toolchain/Play configuration is BLOCKED, never silently skipped or counted as PASS. Do not invent credentials or claim emulator results establish ARM/GPU performance.

**Benchmark requirements:** Full before/after corrected baseline, not only old broken default. Include all retained physical-device cohorts and mandatory quality/lifecycle gates; optional engines only if PIR-23 retained.

**Acceptance criteria:**
1. Every accepted optimization has source/config/quality/raw benchmark evidence and explicit support scope.
2. All mandatory gates pass; missing physical proof blocks performance claims and relevant release promotion.
3. Final report lists rejected, modified, blocked and untested ideas, not only successes.

**Failure/stop conditions:**
1. Any output loss, UAF, unsafe admission, hidden fallback, quality failure or untraceable benchmark blocks release.
2. Optional experiments may remain rejected/deferred without blocking a safe single-runtime release.

**Deliverables:**
1. Scoped commit/branch with baseline and result SHA, or a report-only REJECT/BLOCKED disposition
2. Mission report with implementation changes, commands, exit codes and test artifacts
3. Benchmark JSONL/raw trace/quality artifacts when required; explicit NOT_RUN otherwise
4. Requested/applied/observed plan and provenance; rollback instructions; license notices for borrowed code

**Evidence to record:**
1. Audit base and resolved dependency SHAs; clean/dirty state; toolchain/build/ABI/runtime pin
2. Physical device and driver identity, model/content/template/quantization hashes where applicable
3. All attempts including failures/fallbacks, not only a selected fastest run
4. Before/after fixtures and quality result; independent reviewer decision

**Review and rollback:** Return a machine-readable decision of RETAIN, MODIFY, REJECT or BLOCKED plus test statuses PASS/FAIL/BLOCKED/NOT_RUN. Record raw artifacts and exact exit codes. Root integration owns shared-file wiring and the final merge. A failed experiment ends with a useful report, not forced implementation. Keep the known-good branch/build; never force-push, disable tests, rewrite reference outputs without explanation, drop failed trials or delete a user model to make a gate pass.

### Required integration report shape
For every mission, record the starting SHA, resulting SHA, diff scope, evidence artifact hashes, tests/exit codes, performance comparison cohort, quality result, review decision, merge SHA and rollback SHA. The lead must examine failed and cancelled trials as well as successful ones. A screenshot of a tok/s value is not an evidence bundle.

Release evidence must distinguish: historical-buggy baseline; corrected-neutral baseline; optimized single-runtime release candidate; optional alternate-engine candidate. An output change caused by fixing double acceptance is a correctness change, not an optimization-induced regression against the old buggy oracle. Retest against the pinned API semantics and canonical prompt/quality fixtures.

Do not require every optional research branch to finish before a valid primary-runtime improvement ships. Equally, do not use a passing debug/mock test to waive a missing physical accelerator, lifecycle or sustained-performance gate. Include explicit known-unknowns and support-scope exclusions.


## 31. Risk register

| Risk | Likelihood / impact judgment | Detection | Mitigation / owner | Release rule |
| --- | --- | --- | --- | --- |
| Incorrect reference oracle | High impact; existing output may be wrong | Pinned sampler/template comparison and trace invariants | 03–05 / integrator; corrected baseline separate from historical | Never bless old buggy output as the only truth |
| Output loss or invalid Unicode | Source-supported risk, high impact | Slow consumer, every-byte split, ring wrap, EOF backlog | 02; final sequence and incremental decoder | Any unexplained loss blocks |
| JNI lifetime race / deadlock | Interleaving risk, critical | Concurrent close/control/generate tests; supported sanitizers | 02/INT; leased handles and bounded lifecycle design | No raw-handle gap or deadlock accepted |
| Architecture-specific cache corruption | High impact, model dependent | Canonical rebuild vs cached/shifted context; hybrid/SSM fixtures | 05/15; allowlisted semantics, rebuild when uncertain | Unknown architecture cannot silently use custom shift |
| OOM/LMK despite fit estimate | High impact, volatile conditions | Allocation/pressure timeline and measured peak; crash reason | 07/09/19; conservative budget and live re-admission | Historical speed cannot override hard safety |
| Fallback increases memory | High impact; F16 can be larger | Requested/applied delta plus memory forecast | 06/07/19; re-admit every candidate | No hidden candidate or unbounded retry |
| Driver fault cannot be caught | Backend dependent, critical | Crash tombstone / ApplicationExitInfo where supported | 09/12/13/18; next-start quarantine and CPU known-good | C++ exception catch is not process fault recovery |
| Misleading accelerator telemetry | Confirmed source issue | Placement proof vs capability flags | 06/08/10; report unknown explicitly | No requested-as-observed value |
| Thermal ordering confounds result | High for phone comparisons | Randomized blocks, start-state matching, sustained trace | 09–15; exclusive device lease and safety abort | No burst-only default promotion |
| Build/submodule patch drift | Medium, high integration impact | Pin/patch/toolchain hash and all release-like variants | 00/10/INT | New pin must pass native/R8/API/page-size gates |
| Model conversion changes quality | High for cross-engine comparisons | Same original revision, calibration/export manifest, quality tests | 21/22 | No same-quant fiction; non-inferiority required |
| SDK/code/model license mismatch | Potentially blocking | Per-component SBOM/notices and redistribution review | 18/21–23/INT | No unverified redistribution or direct AGPL copy into Apache-only release |
| Planner stale or unsafe winner | Medium; high impact | Fingerprint mismatch, current pressure change, invalidation tests | 19/20 | Safety filter runs before historical preference |
| Auto-tuner harms active chat | High user impact | Lease/settings-restoration/cancel/lifecycle tests | 20 | No unsolicited sustained search |
| Private prompt/KV leakage in artifacts | High impact | Artifact scrubber plus explicit private/public split | 00/INT; no plaintext KV export by default | Do not upload private text/models |
| Scope explosion and maintainer burden | High project risk | Count supported model/backend/build combinations and owned code | Root /23; one optional winner maximum initially | No evidence → no production abstraction |
| Unsupported Android API/build assumptions | High impact on older devices | Actual minimum-SDK and API-gated feature tests,16KiB artifact/runtime tests | 09/10/INT | Compile success alone is insufficient |
| Sparse results mistaken for broad superiority | High epistemic risk | Coverage table with NOT_RUN/blocked cohorts and intervals | All experiments /INT | Claims limited to tested configurations |


## 32. Kill criteria and experiment retention policy

These thresholds are **proposed engineering decisions**, not observed results. Pre-register a primary metric and a quality/correctness budget for each experiment; never switch the primary metric after seeing the results. Small changes can be valuable when cheap, but larger maintenance costs demand larger and repeatable wins.

| Experiment class | Retain when | Modify / narrow when | Kill or defer when |
|---|---|---|---|
| Low-risk policy/configuration | At least ~5% meaningful improvement beyond measured noise, or concrete correctness/reliability benefit; no mandatory regression | Benefit only in one model/driver/workload → keyed profile | CI spans noise after adequate repeats; user-visible regression; extra knobs without benefit |
| New kernel/compiler variant | Normally ≥10–15% relevant sustained/TTFT benefit, build/quality/lifecycle clean | Benefit limited to ISA/quant/shape → targeted dispatch | Illegal instruction, driver crash, quality failure, unsupported toolchain burden or no held-out win |
| OpenCL production support | Confirmed material device/quant advantage over best CPU/Vulkan, clean lifecycle and maintainable build | Snapdragon/driver allowlist and explicit fallback | No material win after optimized comparison; unstable drivers; claimed formats fail |
| Speculation | Normally ≥15% sustained/task-cost improvement, exactness where required, useful acceptance and low memory overhead | Repetitive/rewrite workload only → workload-gated N-gram | Added RAM/energy/latency exceeds benefit; grammar/distribution correctness missing; acceptance too low |
| KV quantization | Peak memory/context benefit with predeclared quality non-inferiority and no unsupported architecture fallback | Q8 or model-specific type rather than global Q4 | Material quality degradation, invalid layout/FA support, hidden F16 OOM retry |
| Load/repacking cache | Total cold/warm lifecycle win including preprocess, storage and correctness/identity cost | Warm-start-heavy models only; size-capped cache | Cache hit unreliable, private state leaked, net start latency or storage burden worse |
| Hexagon within llama.cpp | Verified supported GGUF/device/driver path with a sustained or energy advantage and reliable fallback | Experimental allowlist; no broad support promise | SDK/redistribution unavailable, HTP mapping/unsupported-op issues, no material gain |
| Alternate engine | ≥25% sustained TG **or** ≥30% request-to-first-visible latency **or** ≥20% task-energy reduction; same task quality; normally ≤5% peak-PSS regression | Two model tiers/two device cohorts for broad support; one validated cohort stays narrow. A unique essential capability is a separate explicit product decision | Gain measured only against broken defaults; conversion quality uncertain; integrated gain disappears; ongoing burden exceeds benefit |
| Generic multi-engine abstraction | Two retained implementations demonstrate shared concrete needs | Minimal interface and optional packaging only | No second engine earns adoption: reject the abstraction |

A higher-memory option is not automatically rejected when it unlocks an explicitly desired capability, but that exception requires an informed product tradeoff and fresh admission—not a silent relaxation. A 1-percentage-point quality margin is a possible initial task-score budget, not a universal scientific truth; use enough samples and an appropriate confidence interval, and tighten to exact invariants for grammar, cache, system messages and lossless transport. An underpowered “no significant difference” result does not establish non-inferiority.

**Safety kills override all gains:** any new lost/duplicated text, UAF, corrupted prompt/cache, unconstrained tool output after grammar failure, invalid tokenization, hidden backend fallback, unbounded retry, unsafe load admission or severe thermal-policy violation stops promotion. A benchmark abort is data, not permission to remove the guard.

If an experiment has no device/model/SDK access, use **BLOCKED / NOT_RUN**, not a negative performance verdict. A report-only rejected or blocked experiment completes the knowledge dependency for the planner while leaving the known-good plan unchanged. Do not keep unsuccessful backend branches resident in the normal APK “just in case.”


## 33. Final recommended execution order

**First make the runtime truthful and correct; then make it adaptive; only then decide whether it needs another engine.**

1. Freeze the audited source/toolchain and new evidence contract (00). In parallel, develop records (01), conservative model intelligence (07) and real hardware probes (08), while one runtime owner repairs lossless streaming/lifetime (02).
2. Repair sampling and grammar (03), structured message/template budgeting (04) and cache/continuation transactions (05). Preserve upstream primitives, fresh sampler ownership and existing RAII. Stop treating existing malformed output as a performance oracle.
3. Wire one requested/applied/observed plan through Kotlin→JNI→native; fix same-model reload identity and explicit backend selection (06). Consolidate service-owned thermal/battery/memory/lifecycle safety (09). Establish the **corrected neutral baseline**.
4. Compare a pinned upstream update with that corrected baseline and wire a narrow research harness (10). Then run independent CPU, Vulkan, OpenCL, load/compiler and KV trials (11–15). Physical runs are serialized and thermally controlled. Fix unsupported embedding/vision/LoRA claims in parallel (16).
5. Merge only winning configurations and maintainable leaf improvements. Build the profile-keyed planner (19) and bounded, cancellable tuner (20) from all completed evidence—including failures and rejections. Default users get an observed, defensible plan, not a page of unexplained switches.
6. Run phone-appropriate N-gram/speculative work (17) and upstream GGUF Hexagon qualification (18) only as isolated experiments. They are not prerequisites for delivering a faster and safer primary runtime.
7. Compare standalone MNN and at most one justified compiled/vendor candidate against **optimized** llama.cpp (21–22). Add a minimal optional adapter (23) only if the measured integrated advantage or essential capability clears the threshold. Otherwise retain one engine.
8. The root integrator reruns full correctness, build, lifecycle and physical before/after gates, publishes the evidence table and retains a known-good rollback (INT). This report authorizes no automatic public release, repository mutation, paid service, model deletion or private benchmark upload.

The target is a strong Android runtime with broad compatible-GGUF use, not a claim of universal benchmark leadership. This program maximizes the probability of meaningful progress because each step either removes a proven integration defect, improves the ability to measure reality, or answers a bounded uncertainty at a controlled maintenance cost.

## Source ledger and evidence qualifications

Source references throughout the report map to the primary materials below. Prism source links are pinned to the audited commit. Some external source inspections used a moving default branch; recorded blob/release identifiers and access date identify that limitation. A release number is not interchangeable with a moving-source feature. Reported issue behavior is not treated as reproduced. No vendor speed claim is used as a measured Prism result.

| ID | Source | Evidence / scope |
| --- | --- | --- |
| P01 | [app/src/main/cpp/LLAMA_CPP_VERSION.md](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/cpp/LLAMA_CPP_VERSION.md) | pinned source. Prose build description is not authoritative; independently checked submodule gitlink. |
| P02 | [app/src/main/cpp/CMakeLists.txt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/cpp/CMakeLists.txt) | pinned source. Compile-time backend flags and speculative static target. |
| P03 | [app/build.gradle.kts](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/build.gradle.kts) | pinned source. Gradle overrides CMake defaults; release-like flavors differ. |
| P04 | [app/src/main/java/com/prismai/llmhost/GenerationSettings.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/GenerationSettings.kt) | pinned source. Settings defaults and clamping. |
| P05 | [app/src/main/java/com/prismai/llmhost/engine/EngineConfigStore.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/engine/EngineConfigStore.kt) | pinned source. Persistence and reload comparator. |
| P06 | [app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt) | pinned source. Lifecycle locks, polling, bounded data retry, terminal handling. |
| P07 | [app/src/main/cpp/llmhost_jni.cpp](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/cpp/llmhost_jni.cpp) | pinned source. Raw native handle; modified UTF-8 input; byte-array drain; vision stubs. |
| P08 | [app/src/main/cpp/Engine.hpp](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/cpp/Engine.hpp) | pinned source. Native configuration and drain contracts. |
| P09 | [app/src/main/cpp/Engine.cpp](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/cpp/Engine.cpp) | pinned source. Read in contiguous ranges across load, generation, cache, benchmark, streaming and telemetry. |
| P10 | [app/src/main/java/com/prismai/llmhost/service/InferenceService.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/service/InferenceService.kt) | pinned source. Inspected initialization, settings/load/generation operations and teardown. |
| P11 | [app/src/main/java/com/prismai/llmhost/service/MemoryGovernor.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/service/MemoryGovernor.kt) | pinned source. Push and poll memory monitoring. |
| P12 | [app/src/main/java/com/prismai/llmhost/service/ThermalBatteryGovernor.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/service/ThermalBatteryGovernor.kt) | pinned source. Class behavior inspected; foreground-generation call-site coverage not established. |
| P13 | [app/src/main/java/com/prismai/llmhost/util/AdaptiveThermalGovernor.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/util/AdaptiveThermalGovernor.kt) | pinned source. Class behavior inspected; app-wide call-site coverage not established. |
| P14 | [app/src/main/java/com/prismai/llmhost/model/DeviceProfiler.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/model/DeviceProfiler.kt) | pinned source. Android memory, battery, thermal and product-name heuristics. |
| P15 | [app/src/main/java/com/prismai/llmhost/model/ModelReadinessAssessor.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/model/ModelReadinessAssessor.kt) | pinned source. Fit and historical throughput override. |
| P16 | [app/src/main/java/com/prismai/llmhost/model/ModelLoadLimits.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/model/ModelLoadLimits.kt) | pinned source. 4200 MiB hard cap and tier gates. |
| P17 | [app/src/main/java/com/prismai/llmhost/model/ModelManager.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/model/ModelManager.kt) | pinned source. Same-model/hash shortcut; load admission and diagnostics. |
| P18 | [app/src/main/java/com/prismai/llmhost/storage/ModelStorageManager.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/storage/ModelStorageManager.kt) | pinned source. Read import, manifest, metadata and validation sections; external-link implementation not fully traced. |
| P19 | [app/src/main/java/com/prismai/llmhost/generation/PromptBuilder.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/generation/PromptBuilder.kt) | pinned source. Flattened roles, approximate token budgeting. |
| P20 | [app/src/main/java/com/prismai/llmhost/generation/GenerationOrchestrator.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/generation/GenerationOrchestrator.kt) | pinned source. Read request construction, metrics initialization and generation routing. |
| P21 | [app/src/main/java/com/prismai/llmhost/generation/GenerationMetrics.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/generation/GenerationMetrics.kt) | pinned source. TTFT / prompt-evaluation conflation. |
| P22 | [app/src/main/java/com/prismai/llmhost/benchmark/BenchmarkRunner.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/benchmark/BenchmarkRunner.kt) | pinned source. Benchmark launch and queue behavior. |
| P23 | [app/src/main/java/com/prismai/llmhost/benchmark/BenchmarkRunAnalytics.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/benchmark/BenchmarkRunAnalytics.kt) | pinned source. Unknown terminal states enter success averages. |
| P24 | [app/src/main/java/com/prismai/llmhost/benchmark/BenchmarkStore.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/benchmark/BenchmarkStore.kt) | pinned source. v4 persistence/export and native PP/TG conversion. |
| P25 | [app/src/main/java/com/prismai/llmhost/bridge/Utf8TextPipeline.kt](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/app/src/main/java/com/prismai/llmhost/bridge/Utf8TextPipeline.kt) | pinned source. Lossy Unicode/control-character normalization. |
| P26 | [AGENTS.md](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/AGENTS.md) | pinned source. Flavor-qualified commands and upstream local patch requirement. |
| P27 | [scripts/verify.ps1](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/scripts/verify.ps1) | pinned source. Actual full verification commands. |
| P00 | [Audited main branch](https://github.com/gthgomez/PrismLocal/commit/49ed799a3e633c5192c2c03317d1d50ffdc57c4c) | pinned repository metadata. Main tip retrieved at audit start; Sept 14 2026 06:14:41 UTC. |
| P28 | [Actual llama.cpp gitlink](https://api.github.com/repos/gthgomez/PrismLocal/contents/app/src/main/cpp/third_party/llama.cpp?ref=49ed799a3e633c5192c2c03317d1d50ffdc57c4c) | pinned gitlink. bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3 |
| P29 | [Recent PRs 1–4](https://github.com/gthgomez/PrismLocal/pulls?q=is%3Apr) | PR descriptions / inspected changed integration. Descriptions are not independent evidence that Android gates ran. |
| U01 | [Pinned llama.h sampling and template contracts](https://github.com/ggml-org/llama.cpp/blob/bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3/include/llama.h) | pinned upstream header. llama_sampler_sample both samples and accepts; basic chat API is not a full Jinja parser. |
| U02 | [Upstream comparison commit](https://github.com/ggml-org/llama.cpp/commit/661643e43079a4ee6faab4c1895291767b67ea8d) | pinned commit metadata. Retrieved Sept 14 2026 06:24:06 UTC; experimental comparison candidate, not automatically approved. |
| U03 | [llama.cpp OpenCL backend](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md) | official documentation / repository |
| U04 | [llama.cpp Android integration](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md) | official documentation / repository |
| U05 | [llama.cpp build, CPU/KleidiAI and Vulkan guidance](https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md) | official documentation / repository |
| U06 | [llama.cpp Snapdragon / Hexagon backend](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md) | official documentation / repository |
| U07 | [llama.cpp project / license](https://github.com/ggml-org/llama.cpp) | official documentation / repository |
| R01 | [PocketPal product repository](https://github.com/a-ghorbani/pocketpal-ai) | official documentation / repository |
| R02 | [PocketPal releases](https://github.com/a-ghorbani/pocketpal-ai/releases) | release notes. Observed v1.17.3, Sept 10 2026, fa46438. |
| R03 | [PocketPal ModelStore source](https://github.com/a-ghorbani/pocketpal-ai/blob/main/src/store/ModelStore.ts) | source inspected. Read lines 1–240; blob a0318c22565a3df4e11e664eeb318e67108da86f; current default branch, not release-pinned. |
| R04 | [llama.rn native substrate / API](https://github.com/mybigday/llama.rn) | official documentation / repository |
| R05 | [llama.rn releases](https://github.com/mybigday/llama.rn/releases) | release notes. Observed stable v0.12.9, Aug 4 2026, 2a20c13; do not conflate stable with prerelease tip. |
| R06 | [MNN project](https://github.com/alibaba/MNN) | official documentation / repository |
| R07 | [MNN transformer export/runtime guide](https://github.com/alibaba/MNN/blob/master/transformers/README.md) | official documentation / repository |
| R08 | [MNN releases](https://github.com/alibaba/MNN/releases) | release notes. Observed 3.6.1, July 23 2026, d407447. |
| R09 | [MNN LLM runtime source](https://github.com/alibaba/MNN/blob/master/transformers/llm/engine/src/llm.cpp) | source inspected. Read lines 1–220; blob cedc2a545fbdc9bc0d1adceda6739abc0a2a5f23; moving default branch. |
| R10 | [MLC LLM project](https://github.com/mlc-ai/mlc-llm) | official documentation / repository |
| R11 | [MLC Android SDK](https://llm.mlc.ai/docs/deploy/android.html) | official documentation / repository |
| R12 | [MLC deployment / serving configuration](https://llm.mlc.ai/docs/deploy/rest.html) | official documentation / repository |
| R13 | [ExecuTorch project](https://github.com/pytorch/executorch) | official documentation / repository |
| R14 | [ExecuTorch export and memory-planning architecture](https://docs.pytorch.org/executorch/stable/intro-how-it-works.html) | official documentation / repository |
| R15 | [ExecuTorch Qualcomm backend](https://docs.pytorch.org/executorch/stable/backends-qualcomm.html) | official documentation / repository |
| R16 | [ExecuTorch LLM deployment guide](https://docs.pytorch.org/executorch/stable/llm/getting-started.html) | official docs. Stable documentation identifies version 1.3; not an assertion of newest unreleased code. |
| R17 | [LiteRT-LM project](https://github.com/google-ai-edge/LiteRT-LM) | official documentation / repository |
| R18 | [LiteRT-LM Kotlin API guide](https://ai.google.dev/edge/litert-lm/android) | official API guide. Canonical Kotlin Android guide linked by the live project README; API/source versions must be locked for implementation. |
| R19 | [LiteRT-LM releases](https://github.com/google-ai-edge/LiteRT-LM/releases) | release notes. Observed v0.17.0, Sept 9 2026, e9fd8c5. |
| R20 | [LiteRT-LM issue reports](https://github.com/google-ai-edge/LiteRT-LM/issues) | issue reports. Relevant reports #3575, #3561, #3512, #3510 and #3508; reports are not reproduced findings. |
| R21 | [ChatterUI](https://github.com/Vali-98/ChatterUI) | official documentation / repository |
| R22 | [SmolChat Android](https://github.com/shubham0204/SmolChat-Android) | official documentation / repository |
| R23 | [SmolChat last default-branch commit](https://github.com/shubham0204/SmolChat-Android/commit/b663cd64ce5a19e85cd5c932e240125800bf8e49) | pinned commit metadata. June 21 2026 release v16 preparation; repository not archived at inspection. |
| R24 | [MLLM mobile runtime](https://github.com/UbiquitousLearning/mllm) | official documentation / repository |
| R25 | [MLLM releases](https://github.com/UbiquitousLearning/mllm/releases) | official documentation / repository |
| R26 | [MLLM Android QNN build task](https://github.com/UbiquitousLearning/mllm/blob/main/tasks/build_sdk_android_qnn_aot.yaml) | build artifact discovery. Android QNN AOT task exists; no local build performed. |
| R27 | [MLLM reproducibility issue](https://github.com/UbiquitousLearning/mllm/issues/637) | issue report. SDK/access and build prerequisites reported Feb 9 2026. |
| R28 | [MLLM QNN device-hang report](https://github.com/UbiquitousLearning/mllm/issues/309) | issue report. July 23 2025; historical reported failure, not proof current version fails. |
| R29 | [ONNX Runtime GenAI](https://github.com/microsoft/onnxruntime-genai) | official documentation / repository |
| R30 | [ORT QNN execution provider](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html) | official documentation / repository |
| R31 | [ORT Android build](https://onnxruntime.ai/docs/build/android.html) | official documentation / repository |
| R32 | [ONNX Runtime Generate API preview](https://onnxruntime.ai/docs/genai/) | official documentation / repository |
| R33 | [NCNN mobile CPU/Vulkan runtime](https://github.com/Tencent/ncnn) | official documentation / repository |
| R34 | [NCNN KV cache documentation](https://github.com/Tencent/ncnn/wiki/kvcache) | official documentation / repository |
| R35 | [FastLLM](https://github.com/ztxz16/fastllm) | official documentation / repository |
| R36 | [FastLLM English guide](https://github.com/ztxz16/fastllm/blob/master/README_EN.md) | official documentation / repository |
| R37 | [PowerInfer-2 research paper](https://arxiv.org/html/2406.06282v1) | research paper. 2024 paper; not a same-model Android Prism benchmark. |
| R38 | [PowerInfer source repository](https://github.com/Tiiny-AI/PowerInfer) | source repository. Do not equate the public desktop runtime with a fully reproducible mobile PowerInfer-2 release. |
| R39 | [ExecuTorch MediaTek backend](https://docs.pytorch.org/executorch/stable/backends-mediatek.html) | official documentation / repository |
| R40 | [ExecuTorch Samsung Exynos backend](https://docs.pytorch.org/executorch/stable/android-samsung-exynos.html) | official documentation / repository |
| R41 | [Samsung delegate operator restrictions](https://docs.pytorch.org/executorch/stable/backends/samsung/samsung-op-support.html) | official documentation / repository |
| A01 | [Android thermal guidance](https://developer.android.com/games/optimize/adpf/thermal) | official documentation / repository |
| A02 | [PowerManager API](https://developer.android.com/reference/android/os/PowerManager) | official documentation / repository |
| P30 | [LICENSE](https://github.com/gthgomez/PrismLocal/blob/49ed799a3e633c5192c2c03317d1d50ffdc57c4c/LICENSE) | pinned source. Apache-2.0 at audited commit. |
| R42 | [PocketPal memory-estimator source](https://github.com/a-ghorbani/pocketpal-ai/blob/main/src/utils/memoryEstimator.ts) | source inspected. Complete source; blob 2a4a5786163c34c290b7791dfaf9a10a802be266. |
| R43 | [llama.rn native source](https://github.com/mybigday/llama.rn/blob/main/cpp/rn-llama.cpp) | source inspected. Read lines 1–250; blob a64f257903cbb2c6298f9c6c44e72791beb53ff7. |
| R44 | [ChatterUI package manifest](https://github.com/Vali-98/ChatterUI/blob/master/package.json) | source inspected. 0.8.8 package version; cui-llama.rn ^1.11.9; blob 891dbbc666b396f4d804f1fbb965fcee194a85a0. |
| R45 | [ChatterUI license](https://github.com/Vali-98/ChatterUI/blob/master/LICENSE) | license text inspected. AGPL-3.0 text; blob 0ad25db4bd1d86c452db3f9602ccdbe172438f52. |
| A03 | [Android PerformanceHintManager](https://developer.android.com/reference/android/os/PerformanceHintManager) | official documentation / repository |
| A04 | [Android 16 KiB page-size compatibility](https://developer.android.com/guide/practices/page-sizes) | official documentation / repository |

## Final summary tables

### Table A — Engine synthesis

| Engine | Best ideas | Major weaknesses | What Prism should take | Verdict |
| --- | --- | --- | --- | --- |
| Prism / llama.cpp integration | Direct Kotlin/JNI, GGUF, RAII, ring transport | Wrong sampler/stream/cache/load/metrics contracts | Repair correctness and preserve lean integration | FIX FIRST |
| Upstream llama.cpp | ARM/GGML backends, GGUF, mature primitives, HTP path | Moving APIs and device-specific support | Pinned update; real device/placement and benchmark plumbing | PRIMARY |
| PocketPal | Context ownership, device rules, empirical policy | Mixed-SWA estimator risk; product policy is not kernel speed | Adapt versioned applied settings and benchmark lease | ADAPT |
| llama.rn | Device enumeration, template/mtmd/spec plumbing | Native dependency churn; compiled ≠ usable | Borrow native ideas; no new JS layer | BORROW / ADAPT |
| MNN | Mobile CPU/OpenCL, chunking, weight/KV/cache controls | Converted models, quantization and artifact cost | Standalone equivalent-quality bake-off | OPTIONAL CANDIDATE |
| MLC | Compiler scheduling, prepared GPU artifacts | Compilation/package/model-support cost | Compiled-runner experiment only if specific need | MONITOR / PILOT |
| ExecuTorch | Export/memory plan, XNNPACK, QNN/delegates | Vendor/export/operator coverage and package burden | Narrow supported-device pilot | OPTIONAL CANDIDATE |
| LiteRT-LM | Android API, curated GPU/NPU and multimodal flow | Prepared packages; driver/KV issue reports | Capability-first pilot, never bypass tool authority | OPTIONAL CANDIDATE |
| ChatterUI | Chat/runtime UX, cpu-info integration | Forked substrate; AGPL code reuse constraints | Clean-room product concepts, not wholesale code | ADAPT CONCEPT |
| SmolChat | Small Kotlin/native design | No proven kernel advantage; modest cadence | Keep implementation simple; useful baseline | MONITOR |
| MLLM / ORT GenAI | Vendor/offload/export research | SDK/API/model compatibility burden | Screen one concrete model/device path | MONITOR / PILOT |
| NCNN / FastLLM / PowerInfer-2 | Mobile ops, kernels, scheduling research | Not demonstrated drop-in Prism replacements | Specific concepts with reproducibility/license review | RESEARCH ONLY |

### Table B — Prism optimization backlog

| Priority | Optimization | Expected value | Evidence confidence | Complexity | Risk |
| --- | --- | --- | --- | --- | --- |
| P0 | Lossless stream, lifetime, sampler, strict grammar | Correct output and crash prevention | High source confidence | High | High |
| P0 | Message-aware prompts and transactional cache | System/context correctness and reliable reuse | High source confidence | High | High |
| P0 | Applied plans, load key and honest benchmark ledger | Real settings, trustworthy optimization decisions | High source confidence | Medium–high | Medium |
| P0 | Memory admission and service-owned safety | Fewer OOM/lifecycle/thermal failures | High problem confidence; needs device tests | Medium–high | High |
| P1 | Thread/batch separation and hardware probes | Tunable PP/TG/TTFT/memory tradeoffs | API/source strong; gain unmeasured | Medium | Medium |
| P1 | Pinned upstream upgrade + CPU/Vulkan/OpenCL bake-off | Better kernels/offload where device supports | Opportunity strong; winner unknown | High | High |
| P1 | Load/cache/compiler and KV experiments | Startup or usable-memory improvement | Model/device dependent | Medium–high | Medium–high |
| P1 | Profile-keyed planner + bounded tuner | Automatic defensible defaults | Architecture rationale; requires clean data | Medium–high | Medium |
| P2 | N-gram/speculation / Hexagon | Workload- or SoC-specific sustained wins | Experimental | High | High |
| P3 | MNN / one compiled alternative | Potential specialized fast lane | No equivalent Prism-device proof yet | Very high | Very high |

### Table C — Execution DAG

| Wave | Mission | Dependencies | Parallel? | Primary files | Gate |
| --- | --- | --- | --- | --- | --- |
| 0 | 00 | none | No | research/inference; new host harness | Identity and build evidence |
| 1 | 01 /07 /08; 02 | 00 | Leaves yes; core one owner | benchmark; model/storage; probes; bridge/native | Schema, admission, probes; lossless/lifetime |
| 2–4 | 03→04→05 | 02 | Core serial | Engine; prompt/template/cache modules | Sampler→messages→cache conformance |
| 5–7 | 06→09→10 | 01/05/07/08 | Core serial | Settings/load/JNI/service/build | Applied plan; safety; corrected baseline |
| 8 | 11–16 /18 | 10 | Code yes; device no | Leaf experiment modules; capability tests | Physical quality/memory/thermal evidence |
| 9 | 19; optional17 | 11–15; 10/15 | Independent leaves | Planner/profile store; speculation | Confirmed candidate set; exactness |
| 10 | 20 /21 /22 | 19 | Code yes; device no | Tuner; standalone alternate runners | Bounded search; alternate benefit threshold |
| 11 | 23 conditional | 21/22 + retained winner | One adapter only | Experimental flavor/module | Integrated benefit and conformance |
| 12 | INT | 16/20; optional winners | Integrator only | Shared wiring; docs/evidence | Full build/lifecycle/quality and physical rerun |

### Table D — Final target

| Area | Current Prism | Target Prism | Why |
| --- | --- | --- | --- |
| Correctness | Double acceptance; lossy chunks; cache/grammar risks | Conformant sampling, lossless stream, fail-closed constraints | Performance must preserve intended behavior |
| Runtime contract | Preferences and labels can differ from live runtime | Immutable requested/applied/observed plan and full load key | Settings changes must actually take effect |
| Memory | Fixed KV estimate, optimistic reclaim/history overrides | Architecture-aware bounds + measured peak + live admission | Larger usable models without unsafe promises |
| CPU/GPU | Coupled threads/batches, heuristic placement labels | Measured per-phase and per-device plans | No universal GPU/thread winner |
| Context | Flattened roles, left truncation, custom shift policy | Canonical messages, protected spans, transactional model-aware cache | Preserve instruction and conversation meaning |
| Android | Partial safety/lifecycle mechanisms; wiring gaps | One service-owned safety/lifetime/benchmark authority | Sustainable and recoverable operation |
| Evidence | Mixed requested/synthetic/chat metrics | Versioned cohort-separated physical measurements | Explain where a real improvement came from |
| Defaults | Manual settings and broad heuristics | Profile-keyed planner with bounded learning | Hide low-level complexity from ordinary users |
| Engines | llama.cpp primary | llama.cpp primary; optional second only after material win | Protect GGUF flexibility and maintainer capacity |
