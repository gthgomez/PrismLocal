# Initial inference qualification fixtures

Fixture set `prism-inference-v1` is declared before the next qualification
campaign. Prompts are synthetic and may be used in repository CI. This file is
fixture definition only, not a run result.

## A. Deterministic lifecycle and contract

| ID | Exercise | Pass criteria |
| --- | --- | --- |
| L01 | Supported short answer | At least one nonterminal token chunk, exactly one terminal, terminal reason recorded, no JNI error. |
| L02 | Unicode split across drain batches | Reassembled text equals the expected UTF-8 fixture; no replacement character introduced by batch boundaries. |
| L03 | Slow consumer beyond native ring capacity | Deliver token IDs in order exactly once, then one EOF; no ring overwrite or early terminal. Uses debug deterministic native tokens. |
| L04 | Cancel while tokens are pending, reset, then generate | Cancelled generation cannot publish into the following generation; new generation starts with its own first fixture token. |
| L05 | Decode/delivery/ack failure | Decode error produces an explicit ERROR for its owner; closed downstream cancels and drops only its own unacknowledged batch; stale tail acknowledgement is rejected. |
| L06 | Chat A task, switch to B, background/resume | Task continues under source chat A and persists only to A; switching/backgrounding does not cancel or retarget it. Explicit cancel and deletion invalidate A's queued persistence. |
| L07 | Confirmation revoked before dispatch | Revoked/disabled capability prevents dispatch; stale token reuse fails; revocation after dispatch admission is recorded as an in-flight operation. |

Lifecycle fixtures use exact state, token-order, owner-ID and terminal-reason
assertions. They may hash output only after determinism is demonstrated for the
same app/native revision, model bytes, prompt and settings.

## B. Semantic generation quality

| ID | Fixture | Correctness rubric |
| --- | --- | --- |
| Q01 | Basic factual answer | Human-scored factuality and directness against a supplied answer key. |
| Q02 | Unicode response | Preserve requested non-ASCII names and punctuation. |
| Q03 | Long context | Answer a question using a fact placed near the beginning and one near the end. |
| Q04 | Continuation | Continue from the prior turn without contradicting an explicit retained fact. |
| Q05 | RAG | Use the supplied retrieved passage; do not invent unsupported source facts. |
| Q06 | Tool JSON | Produce parseable JSON matching the tool schema; record dispatch, validation and result ownership separately. |

Quality outputs are scored against the rubric and are not compared by hashes
unless the exact model/runtime pair has demonstrated deterministic decoding.

## C. Device performance and endurance

For each run record app SHA, native SHA/gitlink, model ID and SHA-256,
quantization, chat-template identity, prompt/settings, device/SoC/RAM class,
Android/API, backend requested/applied, and start/end UTC. Capture TTFT,
decode throughput, peak available memory, terminal reason and cancellation
latency where measurable. Capture thermal state before/after and throughout
endurance runs; unavailable counters remain `null` with a reason. Keep emulator
contract runs separate from physical-device performance/thermal runs. One
device never establishes broad device support.

The intended first semantic/device fixture is the small TinyStories model, but
its test asset is absent from this checkout and must be provenance-reviewed
before it is staged. Larger models require separate rows and qualification;
synthetic debug-token tests are never performance evidence.
