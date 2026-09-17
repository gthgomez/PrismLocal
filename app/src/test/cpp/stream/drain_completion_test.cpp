// PIR-02 / PIR-05 — host test for the drain-completion contract of the native
// token pipe.
//
// This is a *contract* test for the G03 invariant, written against the REAL
// production header (`runtime/StreamProtocol.hpp`). It deliberately mirrors the
// shape of the Android bridge:
//
//   * a producer streams N tokens through a bounded `SpscRing<256>`, calling
//     `on_produce(1)` per generated token and `on_commit(1)` when the token is
//     handed to the output sink;
//   * the producer is faster than the consumer, so when generation ends a
//     backlog is still committed-but-undrained;
//   * the consumer reads the ring back in the bridge's fixed 128-token batches
//     and reports each batch with a single `on_drain(batch)` call.
//
// The bug this test pins down: the old bridge stopped draining the moment the
// tracker reported a terminal, silently dropping the last committed batches.
// The fix is the drain-completion contract — a terminal is only acknowledgeable
// once every produced token has actually been drained (`drained == produced`)
// and nothing is pending.
//
// No gtest, no llama.cpp, no Android, no Gradle. The only randomness uses a
// fixed seed so every run is reproducible. Exits non-zero if any check fails.

#include "runtime/StreamProtocol.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <random>
#include <vector>

namespace {

int g_checks = 0;
int g_failures = 0;
long long g_iterations = 0;

void reportFailure(const char* file, int line, const char* expr) {
    ++g_failures;
    std::fprintf(stderr, "CHECK FAILED %s:%d: %s\n", file, line, expr);
}

} // namespace

// Each CHECK bumps the counter and records a failure (with location) on
// mismatch. The macro never aborts, so one run reports every broken expectation.
#define CHECK(cond)                                                            \
    do {                                                                       \
        ++g_checks;                                                            \
        if (!(cond)) {                                                         \
            reportFailure(__FILE__, __LINE__, #cond);                          \
        }                                                                      \
    } while (0)

using llmhost::DrainResult;
using llmhost::SpscRing;
using llmhost::StreamSequenceTracker;
using llmhost::StreamTerminal;

namespace {

// Bridge constants. kRingCapacity matches the native token ring the Android
// bridge uses; kBridgeBatch matches the fixed JNI drain batch size.
constexpr std::size_t kRingCapacity = 256;
constexpr int32_t kBridgeBatch = 128;
constexpr int32_t kTotalTokens = 1000;

// Fixed seed -> deterministic even for the randomized producer-burst scenario.
constexpr uint32_t kSeed = 0xD2A1Fu;

using Ring = SpscRing<kRingCapacity>;

// Drain up to `max_batch` values from `ring`, record them in order, and account
// for the *whole batch* with one `on_drain(n)` call — exactly how the bridge
// reports a batch back across the JNI boundary. Returns the number popped.
int32_t drainBatch(Ring& ring,
                   StreamSequenceTracker& tracker,
                   std::vector<int32_t>& popped,
                   int32_t max_batch) {
    int32_t n = 0;
    int32_t value = 0;
    while (n < max_batch && ring.pop(value)) {
        popped.push_back(value);
        ++n;
    }
    if (n > 0) {
        CHECK(tracker.on_drain(n));
    }
    return n;
}

// True when the drained prefix still matches the produced prefix exactly, i.e.
// no loss, no reordering, no duplication so far.
bool poppedPrefixMatches(const std::vector<int32_t>& popped,
                         const std::vector<int32_t>& produced) {
    return popped.size() <= produced.size() &&
           std::equal(popped.begin(), popped.end(), produced.begin());
}

// ---------------------------------------------------------------------------
// 1..4. Bridge-sized batching: FIFO ordering, terminal backpressure, a rejected
//      premature acknowledgement, and the old-bridge lossy-stop failure mode.
// ---------------------------------------------------------------------------
void testBridgeBatchDrainCompletion() {
    Ring ring;
    StreamSequenceTracker tracker;
    std::vector<int32_t> produced_seq;
    std::vector<int32_t> popped_seq;

    int32_t next_value = 0;
    int32_t produced = 0;

    // Cooperative producer/consumer pump. The producer is faster than the
    // consumer, so the bounded ring fills and the consumer drains fixed
    // 128-token batches whenever it does. The producer deliberately does NOT
    // drain after its final fill: generation ends with a committed backlog
    // still buffered, which is exactly the shape that triggered the bug.
    while (produced < kTotalTokens) {
        while (produced < kTotalTokens && !ring.full()) {
            CHECK(tracker.on_produce(1)); // token generated
            CHECK(tracker.on_commit(1));  // handed to the output sink
            CHECK(ring.push(next_value));
            produced_seq.push_back(next_value);
            ++next_value;
            ++produced;
        }
        if (produced == kTotalTokens) {
            break; // leave the live backlog in the ring
        }

        // The ring is full here, so the bridge consumer takes a full batch.
        const int32_t popped = drainBatch(ring, tracker, popped_seq, kBridgeBatch);
        CHECK(popped == kBridgeBatch);
        CHECK(tracker.validate());
        CHECK(poppedPrefixMatches(popped_seq, produced_seq));
    }

    // Producer finished; the tracker has not been told yet.
    CHECK(produced == kTotalTokens);
    CHECK(tracker.produced() == kTotalTokens);
    CHECK(tracker.committed() == kTotalTokens);
    CHECK(tracker.terminal() == false);
    CHECK(tracker.pending() == false); // not terminal -> nothing is "pending"
    CHECK(ring.size() > static_cast<std::size_t>(kBridgeBatch)); // >1 batch left
    CHECK(static_cast<int32_t>(ring.size()) == kTotalTokens - tracker.drained());

    // The producer reaches terminal with output still in the ring.
    CHECK(tracker.mark_terminal(StreamTerminal::Eof));
    CHECK(tracker.terminal());
    CHECK(tracker.terminal_reason() == StreamTerminal::Eof);
    CHECK(tracker.pending());                          // committed > drained
    CHECK(tracker.can_acknowledge_terminal() == false); // G03: backlog remains

    // (3) A premature acknowledgement while pending must fail and must not
    // corrupt the accounting.
    const DrainResult before_premature = tracker.snapshot();
    CHECK(tracker.acknowledge_terminal() == false);
    CHECK(tracker.validate());
    const DrainResult after_premature = tracker.snapshot();
    CHECK(after_premature.schema_version == before_premature.schema_version);
    CHECK(after_premature.produced == before_premature.produced);
    CHECK(after_premature.committed == before_premature.committed);
    CHECK(after_premature.drained == before_premature.drained);
    CHECK(after_premature.pending == before_premature.pending);
    CHECK(after_premature.terminal == before_premature.terminal);
    CHECK(after_premature.terminal_reason == before_premature.terminal_reason);
    CHECK(tracker.can_acknowledge_terminal() == false);

    // (4) The old bridge failure mode: a consumer that stops as soon as the
    // tracker reports terminal, without draining the remaining batches, leaves
    // `drained < produced`. The terminal is NOT acknowledgeable at that point —
    // this is why the fix (drain to completion before acknowledging) exists.
    const int32_t drained_when_old_bridge_stopped = tracker.drained();
    CHECK(tracker.terminal()); // the condition the old loop tested
    CHECK(drained_when_old_bridge_stopped < kTotalTokens);
    CHECK(tracker.committed() > tracker.drained());
    CHECK(static_cast<int32_t>(popped_seq.size()) == drained_when_old_bridge_stopped);
    CHECK(tracker.can_acknowledge_terminal() == false);
    CHECK(tracker.validate());

    // The fixed bridge keeps draining in fixed 128-token batches until the ring
    // is empty. `can_acknowledge_terminal()` stays false the whole way.
    int32_t drain_batches = 0;
    while (!ring.empty()) {
        const int32_t popped = drainBatch(ring, tracker, popped_seq, kBridgeBatch);
        CHECK(popped > 0);
        ++drain_batches;
        CHECK(tracker.validate());
        if (!ring.empty()) {
            CHECK(tracker.drained() < kTotalTokens);
            CHECK(tracker.can_acknowledge_terminal() == false);
        }
    }
    CHECK(drain_batches >= 2); // the old bridge skipped at least one batch

    // (2) Only now — ring fully drained, produced == committed == drained and
    // nothing pending — is the terminal acknowledgeable, exactly once.
    CHECK(ring.empty());
    CHECK(tracker.produced() == kTotalTokens);
    CHECK(tracker.committed() == kTotalTokens);
    CHECK(tracker.drained() == kTotalTokens);
    CHECK(tracker.pending() == false);
    CHECK(tracker.can_acknowledge_terminal() == true);
    CHECK(tracker.acknowledge_terminal() == true);
    CHECK(tracker.can_acknowledge_terminal() == false);
    CHECK(tracker.acknowledge_terminal() == false); // acknowledged at most once
    CHECK(tracker.validate());

    // (1) Exact FIFO order, no loss, no duplication.
    CHECK(static_cast<int32_t>(popped_seq.size()) == kTotalTokens);
    CHECK(poppedPrefixMatches(popped_seq, produced_seq));
    CHECK(popped_seq == produced_seq);
    CHECK(std::is_sorted(popped_seq.begin(), popped_seq.end()));
    CHECK(std::adjacent_find(popped_seq.begin(), popped_seq.end()) == popped_seq.end());

    std::printf("  bridge-batch: produced=%d committed=%d drained=%d batches=%d\n",
                tracker.produced(), tracker.committed(), tracker.drained(), drain_batches);

    g_iterations += kTotalTokens;
}

// ---------------------------------------------------------------------------
// 5. Cancellation mid-generation. The terminal is not acknowledgeable until the
//    already-produced output has been drained; committing the uncommitted
//    remainder is refused (the terminal closed the producer side), so the only
//    honest reconciliation is to drain everything already produced. No value is
//    duplicated.
// ---------------------------------------------------------------------------
void testCancellationMidDrain() {
    Ring ring;
    StreamSequenceTracker tracker;
    std::vector<int32_t> produced_seq;
    std::vector<int32_t> popped_seq;

    int32_t next_value = 0;
    int32_t produced = 0;
    int32_t committed = 0;

    // Cancel part-way through the same 1000-token generation, leaving output
    // both buffered in the ring and uncommitted on the producer side.
    constexpr int32_t kCancelAt = 500;
    constexpr int32_t kCommitLimit = 300; // 200 produced-but-uncommitted

    while (produced < kCancelAt) {
        while (produced < kCancelAt && !ring.full()) {
            CHECK(tracker.on_produce(1));
            CHECK(ring.push(next_value));
            produced_seq.push_back(next_value);
            ++next_value;
            ++produced;
            if (committed < kCommitLimit) {
                CHECK(tracker.on_commit(1));
                ++committed;
            }
        }
        if (produced == kCancelAt) {
            break;
        }
        const int32_t popped = drainBatch(ring, tracker, popped_seq, kBridgeBatch);
        CHECK(popped == kBridgeBatch);
        CHECK(tracker.validate());
        CHECK(poppedPrefixMatches(popped_seq, produced_seq));
    }

    CHECK(produced == kCancelAt);
    CHECK(tracker.produced() == kCancelAt);
    CHECK(tracker.committed() == kCommitLimit); // uncommitted remainder left
    CHECK(tracker.drained() < kCancelAt);

    // The generation is cancelled while output is still outstanding.
    CHECK(tracker.mark_terminal(StreamTerminal::Cancelled));
    CHECK(tracker.terminal());
    CHECK(tracker.terminal_reason() == StreamTerminal::Cancelled);
    CHECK(tracker.pending());                          // committed > drained
    CHECK(tracker.can_acknowledge_terminal() == false);

    // Premature acknowledgement is rejected and leaves the state intact.
    const DrainResult before_premature = tracker.snapshot();
    CHECK(tracker.acknowledge_terminal() == false);
    CHECK(tracker.validate());
    const DrainResult after_premature = tracker.snapshot();
    CHECK(after_premature.produced == before_premature.produced);
    CHECK(after_premature.committed == before_premature.committed);
    CHECK(after_premature.drained == before_premature.drained);
    CHECK(after_premature.pending == before_premature.pending);
    CHECK(tracker.can_acknowledge_terminal() == false);

    // Reconciliation by committing the remainder is impossible: a terminal
    // rejects further produce/commit. The counters stay valid; the rejected
    // calls are surfaced via had_error().
    CHECK(tracker.on_commit(kCancelAt - kCommitLimit) == false);
    CHECK(tracker.committed() == kCommitLimit);
    CHECK(tracker.on_produce(1) == false);
    CHECK(tracker.produced() == kCancelAt);
    CHECK(tracker.had_error() == true);
    CHECK(tracker.validate());

    // Draining the already-produced output. The pending flag clears once the
    // committed portion is delivered, but the terminal is still not
    // acknowledgeable because not every produced token has been drained yet.
    int32_t drain_batches = 0;
    bool observed_uncommitted_backlog = false;
    while (!ring.empty()) {
        const int32_t popped = drainBatch(ring, tracker, popped_seq, kBridgeBatch);
        CHECK(popped > 0);
        ++drain_batches;
        CHECK(tracker.validate());
        CHECK(poppedPrefixMatches(popped_seq, produced_seq));
        if (!ring.empty()) {
            CHECK(tracker.can_acknowledge_terminal() == false);
            if (!tracker.pending()) {
                // All committed output is out; the drawn-out remainder is the
                // produced-but-uncommitted tail. Still no acknowledgement.
                observed_uncommitted_backlog = true;
                CHECK(tracker.drained() < kCancelAt);
            }
        }
    }
    CHECK(observed_uncommitted_backlog);

    // Only after every produced token is drained is the cancelled terminal
    // acknowledgeable. Note `committed` intentionally lags `produced`: the
    // header documents that delivery may precede commit.
    CHECK(ring.empty());
    CHECK(tracker.produced() == kCancelAt);
    CHECK(tracker.committed() == kCommitLimit);
    CHECK(tracker.drained() == kCancelAt);
    CHECK(tracker.pending() == false);
    CHECK(tracker.can_acknowledge_terminal() == true);
    CHECK(tracker.acknowledge_terminal() == true);
    CHECK(tracker.can_acknowledge_terminal() == false);
    CHECK(tracker.acknowledge_terminal() == false);
    CHECK(tracker.validate());

    // No loss and, critically, no duplication of the already-produced values.
    CHECK(static_cast<int32_t>(popped_seq.size()) == kCancelAt);
    CHECK(poppedPrefixMatches(popped_seq, produced_seq));
    CHECK(popped_seq == produced_seq);
    CHECK(std::adjacent_find(popped_seq.begin(), popped_seq.end()) == popped_seq.end());

    std::printf("  cancellation: produced=%d committed=%d drained=%d batches=%d\n",
                tracker.produced(), tracker.committed(), tracker.drained(), drain_batches);

    g_iterations += kCancelAt;
}

// ---------------------------------------------------------------------------
// Supplementary: a fixed-seed randomized producer (1..7 tokens per burst) with
// the same fixed 128-token consumer batches. Confirms the ordering and
// completion invariants are not an artifact of a regular produce cadence.
// ---------------------------------------------------------------------------
void testRandomizedProducerBursts() {
    std::mt19937 rng(kSeed);

    Ring ring;
    StreamSequenceTracker tracker;
    std::vector<int32_t> produced_seq;
    std::vector<int32_t> popped_seq;

    int32_t next_value = 0;
    int32_t produced = 0;

    while (produced < kTotalTokens) {
        const int32_t burst = 1 + static_cast<int32_t>(rng() % 7);
        for (int32_t b = 0; b < burst && produced < kTotalTokens && !ring.full(); ++b) {
            CHECK(tracker.on_produce(1));
            CHECK(tracker.on_commit(1));
            CHECK(ring.push(next_value));
            produced_seq.push_back(next_value);
            ++next_value;
            ++produced;
        }
        if (produced == kTotalTokens) {
            break;
        }
        if (ring.full()) {
            const int32_t popped = drainBatch(ring, tracker, popped_seq, kBridgeBatch);
            CHECK(popped == kBridgeBatch);
        }
        CHECK(tracker.validate());
    }

    CHECK(produced == kTotalTokens);
    CHECK(tracker.mark_terminal(StreamTerminal::MaxTokens));
    CHECK(tracker.pending());

    int32_t drain_batches = 0;
    while (!ring.empty()) {
        const int32_t popped = drainBatch(ring, tracker, popped_seq, kBridgeBatch);
        CHECK(popped > 0);
        ++drain_batches;
        if (!ring.empty()) {
            CHECK(tracker.can_acknowledge_terminal() == false);
        }
    }

    CHECK(tracker.drained() == kTotalTokens);
    CHECK(tracker.committed() == kTotalTokens);
    CHECK(tracker.produced() == kTotalTokens);
    CHECK(tracker.pending() == false);
    CHECK(tracker.can_acknowledge_terminal() == true);
    CHECK(tracker.acknowledge_terminal() == true);
    CHECK(popped_seq == produced_seq);
    CHECK(std::adjacent_find(popped_seq.begin(), popped_seq.end()) == popped_seq.end());
    CHECK(tracker.validate());

    std::printf("  randomized (seed=0x%X): produced=%d drained=%d batches=%d\n",
                kSeed, tracker.produced(), tracker.drained(), drain_batches);

    g_iterations += kTotalTokens;
}

} // namespace

int main() {
    std::printf("drain_completion_test: exercising runtime/StreamProtocol.hpp\n");
    std::printf("  ring capacity=%zu, bridge batch=%d, N=%d\n",
                kRingCapacity, kBridgeBatch, kTotalTokens);

    testBridgeBatchDrainCompletion();
    testCancellationMidDrain();
    testRandomizedProducerBursts();

    std::printf("iterations: %lld, checks: %d, failures: %d\n",
                g_iterations, g_checks, g_failures);
    if (g_failures != 0) {
        std::fprintf(stderr, "drain_completion_test: FAILED (%d/%d checks)\n",
                     g_failures, g_checks);
        return 1;
    }
    std::printf("drain_completion_test: OK\n");
    return 0;
}
