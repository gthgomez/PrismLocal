# PrismLocal reliability sprint — design

Date: 2026-10-06
Baseline commit: `f13545f` (origin/main)
Status: design approved, not yet implemented

## Goal

Make importing, chatting, stopping and recovering boringly dependable. Not new
model features, and not a replacement of the inference architecture. The leased
native handles, lifecycle gates, bounded streaming buffers, output
acknowledgement, UTF-8 handling and generation-scoped cancellation are
foundations to preserve.

## Scope decision

Three sequenced pull requests rather than one. Each is independently
verifiable and revertable, so a native regression stays distinguishable from a
UI regression.

| PR | Owner | Files |
| -- | ----- | ----- |
| 1 | native/model | `app/src/main/cpp/Engine.cpp`, `storage/VectorStore.kt`, `storage/LightweightEmbeddingEngine.kt` (deleted), `service/MemoryGovernor.kt`, `service/InferenceService.kt` (memory + storage seams only), `storage/ModelStorageManager.kt`, `HuggingFaceModelCatalog.kt`, `HuggingFaceDownloadWorker.kt`, `model/ModelDownloadManager.kt` |
| 2 | UI/chat | `ui/ChatScreen.kt`, `ui/composer/PromptComposer.kt`, `AttachmentTextExtractor.kt`, `ui/controlplane/ControlPlaneSheet.kt`, `ui/chat/MessageItem.kt`, `ui/ServiceUiState.kt`, `SecurityAuditLog.kt`, `service/InferenceService.kt` (generateSafelyAndAwait pre-flight, listModels, link removal), `storage/ModelStorageManager.kt` (link removal), `AndroidManifest.xml` |
| 3 | verification | `.github/workflows/android-ci.yml`, `app/src/test/cpp/CMakeLists.txt`, `app/src/androidTest/java/com/prismai/llmhost/RealInferenceSmokeTest.kt` |

All paths are relative to `app/src/main/java/com/prismai/llmhost/`.

`InferenceService.kt` and `storage/ModelStorageManager.kt` appear in both PR 1
and PR 2. In both cases the regions are disjoint (PR 1: memory-pressure
reconciler wiring, download and import seams; PR 2: generation pre-flight,
`listModels`, and link removal), PR 1 lands first, and each PR's diff is
reviewed separately.

## Baseline verification

Run locally at `f13545f` before any change:

- `:app:testDevDebugUnitTest` — pass
- `:app:assembleDevDebug` — pass, native `arm64-v8a` and `x86_64`, 3m42s

CI job conclusions for the `f13545f` push (`37477970025`), retrieved via `gh`:

- `Unit Tests & Golden Conformance` — success
- `Native Builds (benchmark & release)` — success
- `Native Host Tests (CTest)` — **failure**

Local builds succeed; the native host test job fails on CI. Both are recorded
because they point at different problems.

## Corrections to the source audit

The audit is the input to this sprint, not the specification. Three findings
were checked against the pinned sources and revised.

### The native host build fails only on CI

The audit's report that "the native build fails when its compiler is killed" is
accurate, and it is CI-specific: `assembleDevDebug` builds the native library
successfully on this host. The failure is in the `native-host-tests` job. See
"Native host tests run, and they are the job that fails" below for the job
topology and the observed log. Addressed in PR 3.

### Finding 1: the crash assert is already guarded; the real anomaly is `llama_encode`

The audit points at `n_ubatch >= n_tokens` (`llama-context.cpp:1269`).
`Engine.cpp:2160` already rejects `actual > runtime->batch_size`, and
`ctx_params.n_ubatch == config.batch_size` (`Engine.cpp:929`), so that
`GGML_ASSERT` is unreachable.

The reachable anomaly is that `Engine::encode` calls `llama_encode` on models
that have no encoder. `llama_context::encode` forces `cparams.causal_attn =
false` (`llama-context.cpp:1299`) and requests `LLLM_GRAPH_TYPE_ENCODER`
(`:1302`), but for `llama` (`models/llama.cpp:93`), `qwen2`, `qwen3` and `bert`,
`build_arch_graph` ignores the requested graph type and returns the ordinary
decoder graph. Only T5 (`models/t5.cpp:109-116`) dispatches on it.

Because the crash theory is dead and nothing is reproduced on hardware, the
remedy is to make encode *correct* for decoder models rather than to disable
document processing. See 1.1.

### `llama_model_has_encoder` is the wrong predicate

An earlier draft of this spec proposed gating embeddings on
`llama_model_has_encoder()`. That is wrong and was rejected on review.
`llama_model_has_encoder` (`llama-model.cpp:2429-2435`) returns true only for
`LLM_ARCH_T5` and `LLM_ARCH_T5ENCODER`. It is an enc-dec predicate, not an
embedding-capable predicate: BERT — the canonical embedding architecture —
returns false, as do all embedding-only models.

Gating on it would reject every embedding-capable model, and because
`RagManager` is the only caller of `Engine::encode`
(`InferenceService.kt:342-345`), it would disable all document ingestion and
knowledge-pack search for every model the app ships. That is a functional
regression in exchange for an unreproduced risk. Rejected.

A related claim in the earlier draft was also inaccurate: `llama_model::build_graph`
calls `build_pooling` unconditionally (`llama-model.cpp:2063`), though
`build_pooling` returns immediately when `cparams.embeddings` is false.

### The upstream-correct pattern is `llama_decode`, not `llama_encode`

`examples/embedding/embedding.cpp` is the reference. It errors out for
enc-dec models (`:154`) and computes every other model's embeddings through
`batch_decode` (`:37`), which calls `llama_decode` (`:46`) with embeddings
enabled and reads per-token vectors via `llama_get_embeddings_ith` (`:57-60`).
`llama_encode` appears nowhere in that example. `llama_get_embeddings`
(`llama.h:998`) returns NULL when `pooling_type == LLAMA_POOLING_TYPE_NONE`,
which is the generative-model case.

So calling `llama_encode` on a decoder-only model is the bug, not a hazard to
be gated off.

### Native host tests run, and they are the job that fails

Both the original audit and an earlier draft of this spec misdiagnosed this. An
earlier draft claimed `native-host-tests` never executed because of
`needs: unit-tests`. That is a misreading: the only `needs:` in the workflow is
on `native-builds` (`android-ci.yml:46`); `native-host-tests` has no `needs:` and
is runnable.

Checked against GitHub Actions rather than inferred. For the run of `f13545f`
(`37477970025`) the job conclusions are:

```
Unit Tests & Golden Conformance | success
Native Builds (benchmark & release) | success
Native Host Tests (CTest)         | failure
```

`native-host-tests` does run, and it is the only failing job. The same pattern
holds for the three preceding runs. So the original audit's framing was right
about the symptom and wrong about the mechanism: the native build genuinely
does fail on CI, in this job.

The failure is a compiler kill, not a host or dependency problem:

```
[ 96%] Building CXX object .../models/xtverse.cpp.o
[ 96%] .../t5.cpp.o
c++: fatal error: Killed signal terminated program cc1plus
gmake[2]: *** [.../models/ernie4-5.cpp.o] Error 1
##[error]The runner has received a shutdown signal.
##[error]Process completed with exit code 143.
```

The build reaches ~96% of the llama.cpp model sources and then `cc1plus` is
killed. This is not the job timeout: the job declares `timeout-minutes: 15` and
ran for 2m56s. `ernie4-5.cpp` is 164 lines, so the file itself is not
pathological; the cost is that `PRISM_ENABLE_ENGINE_MOCK_TEST` (default ON)
compiles the entire vendored llama.cpp graph with ASan+UBSan enabled, which is
newly expensive and newly memory-hungry. GitHub-hosted `ubuntu-24.04` runners
are memory-constrained, and the observed SIGKILL of the compiler is consistent
with memory pressure.

This remains a hypothesis about the precise cause. It is not proven OOM, and
the remediation must not assume it. See PR 3.

### Retry-download and multi-import are consequences, not separate bugs

`ModelDownloadState.Failure` carries `entryName` but not `entryId`, so the UI
falls back to the dropdown selection. Adding the id to the failure state fixes
it. Likewise "Importing N" importing one is a single `firstOrNull()`; importing
each selected GGUF sequentially respects the existing single-flight guard.

## PR 1 — native and model reliability

### 1.1 Encode through the decoder path for decoder-only models

`Engine::encode` routes decoder-only models through `llama_decode` with
embeddings enabled, mirroring `examples/embedding/embedding.cpp`, instead of
`llama_encode`.

- Enable embeddings via `llama_set_embeddings(ctx, true)` as today.
- Build the batch with `logits[i]` set for every token, so per-token vectors are
  retrievable; with `pooling_type == LLAMA_POOLING_TYPE_NONE`,
  `llama_get_embeddings` (`llama.h:998`) returns NULL and the per-token accessor
  `llama_get_embeddings_ith` is required.
- Clear the KV cache first — the upstream example does this
  (`embedding.cpp:42`) because the cache is irrelevant for embedding and
  would otherwise let prompt-cache reuse leak between unrelated documents.
- Mean-pool per-token vectors into a single vector, preserving current
  behavior for `RagManager`'s fixed-width vectors and the existing
  `VectorStore` layout.
- Keep the existing `actual > runtime->batch_size` rejection; it is what keeps
  the batch within `n_ubatch`, and it is load-bearing.

Enc-dec models (T5) keep a separate, honest failure: upstream declines to
embed them (`embedding.cpp:154`), so `Engine::encode` returns empty rather than
producing nonsense. This is a genuine unsupported case, not a
widely-triggered one, and it is not a feature regression because the app ships
no T5 model.

Behavior change, stated explicitly: document embedding vectors change value,
so any vector store written by a previous build must be re-ingested. Version
the stored embedding dimension and reject mismatched rows on read instead of
comparing vectors across incompatible builds. `VectorStore` is in PR 1's scope
for this reason.

`RagManager.query` encodes the raw user prompt with no length bound. That risk
returns once the decoder path is live, so it is in scope here: clamp the query
text to the same bound the chunker uses rather than encoding an arbitrarily
long prompt.

### 1.1a User-facing messaging is PR 2

An empty embedding currently degrades silently — `RagManager` counts it as
`failedCount` and `PromptBuilder` swallows it behind `runCatching`. Surfacing a
reason is a UI/transport concern and lives in PR 2 (§2.6), not here. PR 1 keeps
the "empty vector means failure" contract intact and adds no UI.

### 1.2 Memory pressure reconciliation

Today two paths write native pressure: the `onTrimMemory`/`onLowMemory` push
callback (`service/InferenceService.kt:628-630`) and the polling flow (`:633-645`).
Only polling is deduplicated (`distinctUntilChanged()`, `:633`), the push path
skips the transcript save entirely, and a CRITICAL push followed by a NORMAL
poll is suppressed as unchanged — so native pressure stays at 3 and generation
cancels forever.

Make one component authoritative. A single `MemoryPressureReconciler` owns the
level sent to native and merges push and poll inputs by max severity, with an
explicit recovery transition: a NORMAL poll after a non-NORMAL push always
writes level 0 to native rather than being suppressed as a duplicate. The
existing `criticalMemoryAlertActive` transcript-save/UI-alert guard stays, and
the push path gains the transcript save it currently skips.

This is a behavior change, made explicitly: native pressure can now be cleared
by a recovery poll that previously had no effect.

### 1.3 Import space accounting

`ModelStorageManager` checks `usable > bytes + MIN_FREE_SPACE_AFTER_IMPORT`
twice, via `hasUsableSpaceFor` (`storage/ModelStorageManager.kt:878-890`): before
the copy, at `:237`, and after it, at `:246`. The post-copy check asks for the
full model size *again* after that size is already on disk, so the post-copy
gate can only pass with roughly twice the model size free and a model that
genuinely fits fails after the entire copy has completed.

`promoteDirectory` is a rename and needs no additional bytes. The post-copy
check therefore becomes a reserve-only check: `usable > MIN_FREE_SPACE_AFTER_IMPORT`.
The pre-copy check keeps the full requirement, since that is where the bytes are
still to be written. `sizeFor` returning `-1` (provider omits `SIZE`) keeps its
current behavior.

### 1.4 Restart-safe custom downloads

`HuggingFaceModelCatalog.customEntries` is a process-local map
(`HuggingFaceModelCatalog.kt:383`), but `HuggingFaceDownloadWorker` persists
only the entry id. WorkManager work that survives process death — the reason
WorkManager is used — fails at `HuggingFaceDownloadWorker.kt:87-90` for any
custom entry.

Persist the complete download specification (repo, filename, expected bytes,
license/parameters/quantization metadata, curated flag) and resolve from the
persisted store, falling back to the in-memory map. Custom entries become
thread-safe, since `createCustomEntry` is called from the UI and `find` from the
worker thread. On read, the store rebuilds `curated = false`, which preserves the
existing fail-closed integrity policy.

### 1.5 Failure state carries identity

`ModelDownloadState.Failure` gains `entryId`. `ModelDownloadManager` populates
it from the already-available `KEY_ENTRY_ID` (`ModelDownloadManager.kt:109`).
This is the data half of the retry fix; the UI half is PR 2.

### Deletions

- `linkExternalModelUri` (`storage/ModelStorageManager.kt:365-403`) and its
  callers. It writes `uri.toString()` as the *contents* of `model.gguf` and
  records `sha256 = "linked_saf_uri"` / `status = "saf_linked"`, so nothing
  downstream can detect the file is not a GGUF.

  This removal is **PR 2**, not PR 1. Its callers are
  `InferenceService.linkExternalModel` (`:686-687`) and a wired UI path in
  `ui/ChatScreen.kt` (launcher `:276-281`, trigger `:638-640`). `ChatScreen.kt`
  is a PR 2 file, so removing the storage method in PR 1 would either leave it
  dangling or pull a PR 2 file into PR 1. The whole chain — storage, service and
  UI — comes out together in PR 2. Copy-import (`importModel`) is untouched.
- `storage/LightweightEmbeddingEngine.kt` is dead: its only references are
  `ADVERSARIAL_AUDIT_2026-07-30.md`, `FINDINGS_REPORT_2026-07-31.md` (which
  already marks PRISM-14 false) and this spec. No code, test or benchmark
  references it. Deleted in PR 1 so it cannot be mistaken for the embedding
  path. The historical audit documents are left unedited — they are dated
  findings, and rewriting them would destroy the record — with a one-line
  breadcrumb noting the removal.

## PR 2 — chat and composer

### 2.1 Draft ownership and the accepted-send boundary

The send handler clears the draft and attachments, then calls
`generateSafely`, which is fire-and-forget. The two refusals — no model
(`GenerationOrchestrator.kt:207`) and context too large (`:249`) — both happen
later, so the user loses composed text and attachments to a transient snackbar.

Three changes:

1. **Validate before clearing.** `generateSafelyAndAwait` gains a pre-flight
   outcome the UI can observe synchronously. The draft is cleared only after the
   request is accepted; a refusal leaves it intact.
2. **Drafts are chat-owned and durable.** Draft text and attachments move from
   unkeyed `remember` into state keyed by chat id, backed by
   `rememberSaveable` so rotation and activity recreation survive it.
   `AndroidManifest.xml` declares no `configChanges`, so rotation recreates the
   activity today and every `remember` is lost. The draft for the active chat is
   what loads; switching chats cannot carry a draft across.
3. **Only one owner.** Per AGENTS.md, the screen does not make cancellation or
   terminal-state decisions. Draft clearing follows the service's acceptance,
   it does not re-decide validity in the UI.

### 2.2 Keyboard-safe layout

The composer already uses `imePadding()` and `navigationBarsPadding()`
(`ChatScreen.kt:568-569`), which is valid. What is missing:

- An explicit edge-to-edge and resize strategy. The activity declares no
  `configChanges` and there is no `enableEdgeToEdge()`; inset behavior is
  implicit and theme-dependent.
- The snackbar sits in the outer `Box` (`ChatScreen.kt:752`), outside the
  IME-padded composer, so "too long for the context window" renders behind the
  keyboard. It moves inside the keyboard-safe region and gains a dismiss action.
- `minChatHeight = maxHeight * 0.70f` (`:171`) fights the IME. Short-height
  layouts compact the header and collapse the attachment tray.
- The attachment tray is a `Column`, not a `LazyRow`
  (`PromptComposer.kt:165-227`), so six rows consume the available height.

Header compaction and tray collapse are driven by available height, not by a
fixed threshold constant.

### 2.3 Auto-scroll that does not yank the reader

Keep the latest message visible when following the conversation; do not scroll
someone away from older messages they are reading. Scrolling is suppressed when
the user has scrolled away from the bottom and resumes when they return.

### 2.4 Background attachment processing

`AttachmentTextExtractor.fromUri` runs `openInputStream` and
`BitmapFactory.decodeStream` on the main thread, up to six files, inside the
picker callback. Extraction moves to cancellable background work, and the
attachment limit is applied *before* files are processed rather than after.

### 2.5 One observable installed-model state

The cached model list is a `remember` snapshot refreshed by `refreshKey`
(`ChatScreen.kt:179-181`), bumped only on `ImportState.Success`. Deletion,
linking and download success do not publish it, so deleted models linger. A
single observable installed-model state replaces the snapshot.

`listModels()` does synchronous filesystem I/O plus a manifest parse per model
and is currently called from `LaunchedEffect` on the main thread; it moves off
the main thread as part of this.

### 2.6 Truthful controls

- Multi-GGUF import processes each selected URI sequentially, respecting the
  existing single-flight guard (`model/ModelImportManager.kt:36-44`), so the
  "Importing N" count becomes true.
- Retry Download uses the failed entry's id from PR 1's change, not the
  dropdown selection (which defaults to the first catalog entry,
  `ui/controlplane/ControlPlaneSheet.kt:635`).
- Remove the link-external chain in full: `linkExternalModelUri`
  (`storage/ModelStorageManager.kt:365-403`), `InferenceService.linkExternalModel`
  (`:686-694`), the launcher and trigger in `ui/ChatScreen.kt` (`:276-281`,
  `:638-640`), and the `onLinkModel` parameter in
  `ui/controlplane/ControlPlaneSheet.kt` (`:100`, `:266-275`).
- "Report saved locally" actually saves: the report is appended to the existing
  append-only `SecurityAuditLog.kt` with message id and timestamp, replacing the
  `Toast` at `ui/chat/MessageItem.kt:78` that claimed a save which never
  happened.
- Surface document-ingest failure honestly (from §1.1a): when embedding yields
  nothing, document ingest and knowledge-pack search report why, instead of
  `RagManager` counting a silent `failedCount` and `PromptBuilder` swallowing it
  behind `runCatching`.

### Excluded from PR 2: voice input

Wiring `RECORD_AUDIO` plus transferring the recognized result into an editable
draft is a feature, not a reliability fix, and folding it into PR 2 would make
PR 2 the scope problem this sprint exists to avoid. Tracked separately.

"Excluded" does not mean "does not exist". The following remain in the tree,
untouched: `tools/VoiceIoManager.kt`, `ui/voice/VoiceOverlay.kt`,
`InferenceService.startVoiceInput()` (`:1825`), the `RECORD_AUDIO` manifest
declaration (`AndroidManifest.xml:10`), and the permission check in
`agent/tools/VoiceTools.kt:23`. `MainActivity` requests only
`POST_NOTIFICATIONS` (`:104-112`), so the UI mic path can start a recognizer
without a granted runtime permission — an incomplete flow that is acceptable
only while the feature is unwired, and is a prerequisite for the follow-up.

One defect is worth recording regardless of the feature decision, because it is
a resource leak rather than a missing feature: `startListening()` assigns a new
`SpeechRecognizer` when not already listening, and `shutdown()`
(`VoiceIoManager.kt:243-245`) calls `stopListening()`, which returns early at
`:172` when `isListening` is false. A recognizer created and then abandoned —
permission denied, recognizer error, or activity teardown mid-listen — is never
destroyed. PR 2 adds the destroy-on-abandon path. That is a leak fix, not the
voice feature.

## PR 3 — verification

The target is `native-host-tests`, the job that actually fails (§"Native host
tests run"). Its remediation is written against the observed log, not against
the dependency theory.

- **Stop the compiler kill.** `cc1plus` is SIGKILLed at ~96% of the llama.cpp
  model sources. Candidate causes, to be distinguished rather than assumed:
  memory pressure from compiling the full vendored graph under ASan+UBSan;
  `--parallel` over-provisioning; or the runner-image change
  (`ubuntu-24.04`, image `20260927.320.1`). First step is instrumentation —
  capture free memory and the kill reason in the job — because "resource
  pressure is plausible" is not a diagnosis. Depending on what that shows:
  bound `--parallel`, or split the Engine mock target into its own job with a
  larger runner, or drop sanitizers for the llama.cpp sub-build while keeping
  them on the test targets that actually need them.
- **Sanitizers do not reach the target that matters.** `PRISM_SANITIZER_FLAGS`
  is applied by `prism_add_native_test` but never to `engine_mock_test`
  (`app/src/test/cpp/CMakeLists.txt:143-157`), which is the only target that
  compiles and runs real `Engine.cpp`. Extend the sanitizer options to it.
- **`RealInferenceSmokeTest.kt:44` expects an `EOF` terminal where the engine
  reports `MAX_TOKENS`.** The assertion is wrong, not the engine.
- **Instrumentation is compiled but not executed**, and the real-model smoke
  fixtures are not in the tracked tree. Both are recorded as explicit limits,
  not fixed here.

## Verification plan per PR

All file references in this plan are relative to `app/src/main/java/com/prismai/llmhost/`.

PR 1 touches `Engine.cpp`, so per AGENTS.md it runs the **full gate**,
`scripts/verify.ps1` — unit tests, `assembleDevBenchmark`, `assemblePlayRelease`
— not just the debug loop. Debug-only builds never compile the RelWithDebInfo
native config, R8/ProGuard rules, or the vulkan-shaders-gen host tool, so a
`assembleDevDebug` pass does not satisfy the gate for a native change. New JVM
tests cover the import reserve arithmetic, the pressure reconciler merge and
recovery transition, the persisted download spec round-trip, and the embedding
dimension versioning. Native changes are reasoned from the traced sources;
device verification is marked not run.

PR 2 runs `:app:testDevDebugUnitTest` plus `assembleDevDebug`. Tests cover draft
ownership across chat switches, accepted-send retention on refusal, the
attachment limit applied pre-processing, and retry targeting. Instrumentation is
compiled, not executed.

PR 3 runs the native host tests with sanitizers directly, via `ctest`, and
confirms the compiler kill is resolved.

## Release gate

Real user journey on one qualified APK: fresh install → import a verified small
GGUF → load → multi-turn streaming → Stop → retry → background/resume → rotate
with a draft → reopen. Then document processing, low-memory recovery and
failed-download recovery. Samsung Keyboard and Gboard, portrait/landscape, large
text, both navigation modes.

Device verification on the S25 Ultra is required before claiming any native or
layout fix works. Nothing in this design has been reproduced on hardware.