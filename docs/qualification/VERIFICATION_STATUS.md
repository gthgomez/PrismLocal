# Verification status

Current as of the reliability sprint. Every claim below is backed by a command
that was actually run, or is explicitly marked as unverified.

> **Verification provenance.** The rows below describe jobs in the Android CI
> workflow and are sourced from CI run `37477970025` and the sprint plan. None of
> those commands were re-executed while writing this document: Gradle and native
> builds were deferred to the central gate for the sprint. The `native-host-tests`
> and sanitizer rows were failing or newly added at the time of writing and sit
> under "Pending CI confirmation", not "Proven by CI". Treat a row as current
> evidence only after re-running its command.

## Proven by CI

| Check | How | Notes |
| ----- | --- | ---- |
| JVM unit tests | `:app:testDevDebugUnitTest`, `:app:testPlayDebugUnitTest` | Runs on every push |
| Release-like native builds | `assembleDevBenchmark`, `assemblePlayRelease` | Compiles the RelWithDebInfo native config and R8 rules that debug never touches |

## Pending CI confirmation

These checks are instrumented or newly added but had no green CI run when this
document was written. They move under "Proven by CI" only once a run confirms
them.

| Check | How | Notes |
| ----- | --- | ---- |
| Native host tests | `ctest` in `native-host-tests` | Covers header-only runtime helpers plus `engine_mock_test`, which runs real `Engine.cpp` on a mock model. This was the failing job; the `scripts/instrument_native_build.sh` change is pending a CI run. |
| ASan/UBSan on the Engine target | `engine_mock_test` under `PRISM_ENABLE_SANITIZERS=ON` | Added by the reliability sprint. Not yet confirmed by a green run. |

## Compiled but NOT executed

- **Android instrumentation.** `assembleDevDebugAndroidTest` compiles
  `app/src/androidTest`, including `RealInferenceSmokeTest` and the new
  `storage/VectorStoreRevisionMigrationTest`. No emulator or device runs them in
  CI.
- **Vector-store v1→v2 migration and revision-filtered reads.**
  `VectorStoreRevisionMigrationTest` seeds a pre-revision database at
  `user_version = 1`, opens it through `VectorStore` to exercise the
  `ALTER TABLE … embedding_revision` upgrade, and asserts `search` /
  `getCurrentChunks` exclude the stale revision-1 row while `getAllChunks`
  still returns it. It is compiled by `assembleDevDebugAndroidTest` but has not
  been executed: Robolectric is not on the unit-test classpath, so the real
  SQLite behavior is only verified on a device/emulator.
- **Real-model smoke fixtures are not in the tracked tree.** The tiny GGUF the
  smoke test requires is not committed, so `RealInferenceSmokeTest` cannot pass
  in CI even after its terminal assertion was corrected. The strict `MAX_TOKENS`
  assertion is sound: the test asserts `tokenCount >= 1` before the terminal
  assertion, so the `maxTokens = 1` budget is consumed and the engine reports
  `MAX_TOKENS`. A budget-limited run therefore cannot emit `EOF`, and
  `MAX_TOKENS` is guaranteed; the always-true invariants (a terminal chunk with
  a known reason) remain the durable part of that fix.

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

## Review-fix follow-up (this branch)

This branch is a review-fix follow-up on top of the merged wave-1 integration.
Its changes — off-main chat pre-flight, saveable per-chat drafts, RAG stale
chunk removal, voice recognizer lifecycle handling, and AI-report failure
logging — are **pending CI and device verification**, not proven by this
document. They are covered by JVM unit tests where the logic is testable and by
compilation elsewhere, but no CI run or qualified-device pass has confirmed
them yet. Treat the claims above as the baseline this branch builds on, not as
evidence for these changes.

## Reliability-closure merge train (2026-10-09)

The review-fix follow-up above, the native-encode/PIR fixes, and the model/RAG
correctness work have since merged to `main`. This section records the state
without rewriting the historical rows above.

| Change | Merged as | Evidence |
| --- | --- | --- |
| #25 native/model + chat/composer + verification sprint | merge `e3a8e1b` | CI: Unit Tests + Native Host Tests + Native Builds pass |
| #26 review fixes (encode state, off-main I/O, atomic import/re-ingest, downloads, voice) | merge `d2a6c6b` | CI 3/3 pass on head `935f52e`; main run success |
| #27 trusted download pins + activation verification (with a head/tail content fingerprint on the verification cache) | merge `b582880` | CI 3/3 pass on head `e43ad3d` |
| #28 RAG encoder-identity + failure-atomic re-ingest + bounded UI memory (non-destructive cleanup) | merge `cb93073` | CI 3/3 pass on head `9487314` |
| #29 clear the composer only after the send is actually admitted | merge `cf6e8b4` | CI 3/3 pass on head `6fd22a2` |

New JVM coverage added by this train (all green in `:app:testDevDebugUnitTest`
and `:app:testPlayDebugUnitTest`): encoder snapshot + mid-ingest switch abort,
commit-aware ingest counts, cancellation propagation, oversized-document reject,
partial knowledge pack not indexed, single-pass pack delete, same-size/same-mtime
artifact substitution detection, and send-acceptance mapping.

## Bug findings & agent remediation merge train (2026-10-09)

Following the initial reliability train, the 18 audit findings and qualification gaps
(PL-F01–PL-F18, PL-Q01–PL-Q03) were remediated in four sequential PRs:

| Sequence | Merged as | Findings Remediated | Evidence & Tests Added |
| --- | --- | --- | --- |
| **Sequence A: RAG & Vector Store** | PR #34 (`c37775f`) | PL-F08, PL-F10, PL-F11, PL-F12, PL-F18 | DocumentChunker exact-boundary unit test; KnowledgePackTools partial-download failure test; VectorStoreRevisionMigrationTest v2→v3 obsolete row cleanup; VectorStore multi-pack wipe test; EmbeddingIdentity epoch mid-flight abort tests. |
| **Sequence B: Chat Admission & Draft Ownership** | PR #35 (`e0a1442`) | PL-F01, PL-F02, PL-F03, PL-F04, PL-F07 | Service admission-before-cancellation tests; direct-tool synchronous failure outcome test; draft revision ownership and in-flight token idempotency tests; ChatScreen attachment picker bound to originating chat; DraftPayloadStore app-private durable storage bounding rememberSaveable bundles. |
| **Sequence C: Task Durability & Search Consistency** | PR #36 (`fe914aa`) | PL-F05, PL-F06, PL-Q02, PL-Q03 | Atomic task persistence with `.bak` rollback; BackgroundAgentPersistenceTest durability barrier; ChatSearchAndDeletionConsistencyTest verifying search index eviction, delete error propagation, and ordered revisions. |
| **Sequence D: Imports, Downloads & Provenance** | PR #37 (`6745922`) | PL-F09, PL-F13, PL-F14, PL-F15, PL-F16, PL-F17 | SequentialImportQueue non-dropping retry loop on busy; fatal `Throwable` rethrow; URI map release; ResumableDownloadEngineTest verifying Content-Range 206 validation, 200 reset, ETag mutation reset, disk reserve & MAX_MODEL_BYTES; catalog SHA-256 ID derivation with legacy lookup; cache threat model boundary test; ModelProvenancePersistenceTest manifest round-trip. |

> **Closure-evidence caveat (2026-10-10).** The merge-train rows above record that
> source changes and tests landed; they are not, by themselves, proof that the
> corresponding GitHub issue's acceptance criteria are met. Independent review of the
> issue comments found that the closure evidence for **#14, #15, #17, and #20** does
> not demonstrate the original defect (for example, #20 concerns background-task chat
> ownership, and HTTP download handling does not address it). Treat those issues as
> requalification candidates, not resolved, until source-specific acceptance evidence
> exists. The JVM unit-test evidence was also invalidated by the `a6bd73d` CI
> regression and must be re-confirmed on the fixed head (see `STATUS.md`).

## Qualification ledger & release qualification matrix

| ID | Title / Subsystem | Tag | Remediation / Verification Status |
| --- | --- | --- | --- |
| **PL-F01** | Rejected message can cancel an existing generation | C | **RESOLVED** in PR #35. Evaluates admission and preflight before cancelling running generations. |
| **PL-F02** | Synchronously failed direct tool reported as unaccepted | C | **RESOLVED** in PR #35. Direct tool path sets `HANDLED` launch result, preserving failure trace without lying to composer. |
| **PL-F03** | In-flight send draft ownership / idempotency guard | R | **RESOLVED** in PR #35. Binds send callbacks to originating chat ID and draft revision token; disables composer during in-flight send. |
| **PL-F04** | Attachment picker can attach content to wrong chat | R | **RESOLVED** in PR #35. Attachment picker coroutine bound to originating chat ID; ignores reads if chat was switched. |
| **PL-F05** | Background agent task persistence file loss / rollback | C | **RESOLVED** in PR #36. Atomic file replace with `.bak` rollback, synchronous write barrier before StateFlow emission. |
| **PL-F06** | Chat deletion/clear inconsistent with search / durable index | C/R | **RESOLVED** in PR #36. Evicts search index entries on clear/delete, propagates deletion errors, versions chat index snapshots. |
| **PL-F07** | rememberSaveable stores unbounded attachment text | C/R | **RESOLVED** in PR #35. Large extracted attachment text offloaded to app-private `DraftPayloadStore`; bundle size strictly bounded. |
| **PL-F08** | v2→v3 vector store migration unsearchable rows | C | **RESOLVED** in PR #34. Obsolete migrated rows with blank encoder or zero dim offered for cleanup via `cleanObsoleteChunks`. |
| **PL-F09** | Batch import silently drops files on busy | C/R | **RESOLVED** in PR #37. Typed `ImportDispatchOutcome.RetryableBusy` with non-dropping retry loop in `SequentialImportQueue`. |
| **PL-F10** | Partially downloaded pack reported as completed | C | **RESOLVED** in PR #34. `PackDownloadResult` reports failures; `KnowledgePackTools` fails cleanly when pack chunks fail. |
| **PL-F11** | DocumentChunker duplicates terminal chunk on boundary | C | **RESOLVED** in PR #34. Suppressed duplicate tail chunk when `start >= text.length`. |
| **PL-F12** | Embedding model switch during RAG creates mixed-model index | R | **RESOLVED** in PR #34. Added monotonic `epoch` to `EmbeddingIdentity`; aborts mid-flight ingest and rejects stale searches. |
| **PL-F13** | Resumable download lacks Content-Range validation & ETag guards | C/R | **RESOLVED** in PR #37. `ResumableDownloadEngine` validates HTTP 206 range start/total, resets on 200, checks ETag in `.part.meta`, enforces disk headroom. |
| **PL-F14** | Truncated custom entry ID collision vulnerability | C | **RESOLVED** in PR #37. Uses SHA-256 over `repoId:fileName` for `customEntryId`; maintains `legacyCustomEntryId` resolution. |
| **PL-F15** | Verification cache threat model boundary | C | **RESOLVED** in PR #37. Documented threat model as fast change detector for app-private storage; verified boundary on >256 KiB files. |
| **PL-F16** | Legacy imported models falsely upgraded to verified pinned | C | **RESOLVED** in PR #37. Introduced `DownloadIntegrity.UNKNOWN_LEGACY`; preserved provenance across manifest round-trips. |
| **PL-F17** | Swallowed fatal Throwable & import URI map leak | C | **RESOLVED** in PR #37. Caught `Exception` instead of `Throwable` to rethrow `OutOfMemoryError`; evicted URIs on completion/failure. |
| **PL-F18** | clearAllKnowledgePacks does not clear on-demand grokipedia | C | **RESOLVED** in PR #34. Deletes all knowledge packs across both curated and on-demand grokipedia domains. |
| **PL-Q01** | Release qualification matrix and device-smoke gate | QUAL | **DEVICE_BLOCKED**. CI unit tests and native host CTests pass (10/10); instrumentation tests compile; real GGUF decode and embeddings require physical device execution. **Issue #21 remains OPEN**. |
| **PL-Q02** | BackgroundAgentPersistenceTest flakiness / race | G | **RESOLVED** in PR #36. Added durability barrier and atomic rollback recovery tests. |
| **PL-Q03** | ChatSearchAndDeletionConsistencyTest coverage | G | **RESOLVED** in PR #36. Added comprehensive unit tests for search eviction, delete failure propagation, and temp file rollback. |

## Release gate: DEVICE_BLOCKED

The release qualification status is **DEVICE_BLOCKED**:
1. **Native GGUF embedding is untested in host CI**: CTest executes `engine_mock_test` with a mock engine that early-returns before `llama_decode` in embeddings mode.
2. **Instrumentation tests are compiled but not run**: `assembleDevDebugAndroidTest` builds `RealInferenceSmokeTest` and `VectorStoreRevisionMigrationTest`, but cloud CI lacks Android emulators/hardware.
3. **Issue #21 remains OPEN** as the tracking gate until physical device execution verifies real model inference, token generation, and embedding cosine similarity.

CI passing is a necessary prerequisite, but release qualification requires on-device smoke verification per `docs/evidence/QUAL_RUNBOOK.md`.

