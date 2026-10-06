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
| 1 | native/model | `Engine.cpp`, `MemoryGovernor.kt`, `InferenceService.kt` (memory + storage seams only), `ModelStorageManager.kt`, `HuggingFaceModelCatalog.kt`, `HuggingFaceDownloadWorker.kt`, `ModelDownloadManager.kt` |
| 2 | UI/chat | `ChatScreen.kt`, `PromptComposer.kt`, `AttachmentTextExtractor.kt`, `ControlPlaneSheet.kt`, `MessageItem.kt`, `ServiceUiState.kt`, `AndroidManifest.xml` |
| 3 | verification | `.github/workflows/android-ci.yml`, `app/src/test/cpp/CMakeLists.txt`, `app/src/androidTest/.../RealInferenceSmokeTest.kt` |

PR 2 and PR 3 do not overlap in files. PR 1 and PR 2 both touch
`InferenceService.kt` but in disjoint regions; PR 1 lands first.

## Baseline verification

Run at `f13545f` before any change:

- `:app:testDevDebugUnitTest` — pass
- `:app:assembleDevDebug` — pass, native `arm64-v8a` and `x86_64`, 3m42s

## Corrections to the source audit

The audit is the input to this sprint, not the specification. Three findings
were checked against the pinned sources and revised.

### Native host build is not broken

The audit reports "the native build fails when its compiler is killed" and that
native tests are skipped as a consequence. Both are wrong on this host: the
native build succeeds, and the native tests live in a *separate* CI job
(`native-host-tests`, `needs: unit-tests`) that is skipped for an unrelated
reason. The real defect is CI job topology, addressed in PR 3. Do not attempt a
host build repair; there is nothing to repair.

### Finding 1: the assert is already guarded; the unsafe path is elsewhere

The audit points at `n_ubatch >= n_tokens` (`llama-context.cpp:1269`).
`Engine.cpp:2160` already rejects `actual > runtime->batch_size`, and
`ctx_params.n_ubatch == config.batch_size` (`Engine.cpp:929`), so that
`GGML_ASSERT` is unreachable.

The reachable unsafe path is different. `llama_context::encode` forces
`cparams.causal_attn = false` (`llama-context.cpp:1299`) and then requests
`LLM_GRAPH_TYPE_ENCODER` (`:1302`). For `llama` (`models/llama.cpp:93`),
`qwen2`, `qwen3` and `bert`, `build_arch_graph` ignores the requested graph type
and returns the ordinary decoder graph. Only T5 (`models/t5.cpp:109-116`)
dispatches on it. So a decoder-only chat model receives non-causal attention
and, because `Engine::encode` sets `llama_set_embeddings(ctx, true)`, a pooling
stage over a graph with no pooled output.

`llama_model_has_encoder()` (`llama.h:606`) is the capability predicate for
exactly this and `Engine.cpp` never calls it.

Conclusion: the finding is real and stays first in priority, but the fix is a
capability gate, not an "isolated embedding context" — that presupposes an
encoder model the app does not ship.

### Retry-download and multi-import are consequences, not separate bugs

`ModelDownloadState.Failure` carries `entryName` but not `entryId`, so the UI
falls back to the dropdown selection. Adding the id to the failure state fixes
it. Likewise "Importing N" importing one is a single `firstOrNull()`; importing
each selected GGUF sequentially respects the existing single-flight guard.

## PR 1 — native and model reliability

### 1.1 Embedding capability gate

`Engine::encode` rejects non-encoder models before touching `llama_encode`.
The check is `llama_model_has_encoder(runtime->model)`, evaluated once at model
load and cached on `ModelRuntime` as `supports_encoder`; `encode` consults the
cached flag. A model without an encoder yields an empty vector, which is the
same "failure" signal the existing callers already handle.

Because an empty embedding currently degrades silently — `RagManager` counts it
as `failedCount` and `PromptBuilder` swallows it — the gate is paired with an
explicit user-facing reason. `NativeLlmBridge.encode` keeps its
"empty means failure" contract; a new capability query exposes why. Document
ingest and knowledge-pack search surface "not supported for this model" rather
than a silent no-op.

Note that `RagManager.query` encodes the raw user prompt unbounded. The
capability gate makes this safe, but the chunk-size re-chunking the
`Engine.cpp:2156` comment invites is not implemented and is not in scope.

### 1.2 Memory pressure reconciliation

Today two paths write native pressure: the `onTrimMemory`/`onLowMemory` push
callback and the polling flow. Only polling is deduplicated
(`distinctUntilChanged()`), so a CRITICAL push can be followed by a poll that
is filtered as "unchanged" and never clears it; native generation then cancels
forever.

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
twice: before the copy (`ModelStorageManager.kt:228`) and after it
(`:244-248`). The post-copy check asks for the full model size *again* after
that size is already on disk, so a model that fits fails after the entire copy.

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

### Deletions in PR 1

- `linkExternalModelUri` (`ModelStorageManager.kt:365-403`) and its callers.
  It writes `uri.toString()` as the *contents* of `model.gguf` and records
  `sha256 = "linked_saf_uri"` / `status = "saf_linked"`, so nothing downstream can
  detect the file is not a GGUF. Removing it removes the only producer of that
  state. Copy-import (`importModel`) is untouched.
- `LightweightEmbeddingEngine` is dead code — referenced only in audit
  markdown. Removed so it cannot be mistaken for the embedding path.

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
  existing single-flight guard, so the "Importing N" count becomes true.
- Retry Download uses the failed entry's id from the PR 1 change, not the
  dropdown selection (which defaults to the first catalog entry).
- "Report saved locally" actually saves: the report is appended to the existing
  append-only `SecurityAuditLog` with message id and timestamp, replacing the
  `Toast` that claimed a save which never happened.

### Excluded from PR 2

**Voice input.** Wiring `RECORD_AUDIO` permission plus transferring the
recognized result into an editable draft is a feature, not a reliability fix,
and the audit itself flags the permission flow as incomplete. Tracked separately.

## PR 3 — verification

- Native host tests must actually run. They are currently gated behind a job
  that never executes; make job dependencies reflect the real failure.
- Sanitizer flags cover the header-only helpers but not the Engine target built
  by `engine_mock_test`, which is the target that executes real `Engine.cpp`.
- `RealInferenceSmokeTest.kt:44` expects an `EOF` terminal where the engine
  reports `MAX_TOKENS`. The assertion is wrong, not the engine.
- Instrumentation is compiled but not executed, and the real-model smoke
  fixtures are not in the tracked tree. Both are stated as limits, not fixed
  here.

## Verification plan per PR

PR 1: `:app:testDevDebugUnitTest` plus `assembleDevDebug`. New JVM tests for the
import reserve arithmetic, the pressure reconciler merge/recovery, and the
persisted download spec round-trip. Native change is reasoned from the traced
sources; device verification is marked not run.

PR 2: `:app:testDevDebugUnitTest` plus `assembleDevDebug`. Tests for draft
ownership across chat switches, accepted-send retention on refusal, the
attachment limit applied pre-processing, and retry targeting. Instrumentation
is compiled, not executed.

PR 3: `scripts/verify.ps1` gate — unit tests, `assembleDevBenchmark`,
`assemblePlayRelease` — plus the native host tests with sanitizers, run
directly.

## Release gate

Real user journey on one qualified APK: fresh install → import a verified small
GGUF → load → multi-turn streaming → Stop → retry → background/resume → rotate
with a draft → reopen. Then document processing, low-memory recovery and
failed-download recovery. Samsung Keyboard and Gboard, portrait/landscape, large
text, both navigation modes.

Device verification on the S25 Ultra is required before claiming any native or
layout fix works. Nothing in this design has been reproduced on hardware.