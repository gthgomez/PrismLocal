// PIR-02 — host unit tests for the portable stream/terminal protocol.
//
// Exercises the REAL production header (`runtime/StreamProtocol.hpp`) with a
// tiny assert-style CHECK macro. No gtest, no llama.cpp, no Android, no Gradle.
// A fixed RNG seed makes every run byte-for-byte deterministic. Exits non-zero
// if any check fails.

#include "runtime/StreamProtocol.hpp"

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
using llmhost::kStreamProtocolVersion;
using llmhost::SpscRing;
using llmhost::StreamSequenceTracker;
using llmhost::StreamTerminal;

namespace {

// Fixed seed -> the whole suite is reproducible across runs/toolchains.
constexpr uint32_t kSeed = 0xC0FFEEu;

// Capacity small enough to force the ring head/tail to wrap many times.
constexpr std::size_t kRingCapacity = 8;

// >= 1000 iterations per stress scenario, as required.
constexpr std::size_t kStressIterations = 2000;
constexpr std::size_t kWrapRounds = 1000;

// ---------------------------------------------------------------------------
// 1. Random produce/commit/drain interleaving with a slow consumer.
//
// The producer generates variable-size batches into a staging FIFO; a commit
// step moves staging into the bounded ring as room allows; the consumer drains
// 0..2 tokens per iteration. Because the consumer is slower than the producer
// the ring and the staging FIFO both stay busy and the ring wraps repeatedly.
// The popped value sequence must equal the produced sequence exactly, and at
// the end produced == committed == drained.
// ---------------------------------------------------------------------------
void testInterleavedProduceDrain() {
    std::mt19937 rng(kSeed);

    SpscRing<kRingCapacity> ring;
    StreamSequenceTracker tracker;

    std::vector<int32_t> produced_seq;
    std::vector<int32_t> popped_seq;
    std::vector<int32_t> staging; // produced but not yet committed
    std::size_t staging_head = 0;
    int32_t next_value = 1;

    for (std::size_t it = 0; it < kStressIterations; ++it) {
        // Producer: 1..3 new tokens.
        const int batch = 1 + static_cast<int>(rng() % 3);
        for (int b = 0; b < batch; ++b) {
            CHECK(tracker.on_produce(1));
            produced_seq.push_back(next_value);
            staging.push_back(next_value);
            ++next_value;
        }

        // Commit as much staging as the ring has room for, in FIFO order.
        while (staging_head < staging.size() && !ring.full()) {
            CHECK(ring.push(staging[staging_head]));
            CHECK(tracker.on_commit(1));
            ++staging_head;
        }

        // Slow consumer: 0..2 tokens per iteration.
        const int drains = static_cast<int>(rng() % 3);
        for (int d = 0; d < drains; ++d) {
            int32_t value = 0;
            if (ring.pop(value)) {
                popped_seq.push_back(value);
                CHECK(tracker.on_drain(1));
            }
        }

        CHECK(tracker.validate());
        CHECK(tracker.drained() == static_cast<int32_t>(popped_seq.size()));
    }

    // Flush: no producer left. Commit into the ring only as room frees up, and
    // drain the ring in between, until both the staging FIFO and ring are empty.
    while (staging_head < staging.size() || !ring.empty()) {
        while (staging_head < staging.size() && !ring.full()) {
            CHECK(ring.push(staging[staging_head]));
            CHECK(tracker.on_commit(1));
            ++staging_head;
        }
        int32_t value = 0;
        while (ring.pop(value)) {
            popped_seq.push_back(value);
            CHECK(tracker.on_drain(1));
        }
    }

    CHECK(tracker.validate());
    CHECK(tracker.produced() == static_cast<int32_t>(produced_seq.size()));
    CHECK(tracker.committed() == tracker.produced());
    CHECK(tracker.drained() == tracker.produced());
    CHECK(tracker.pending() == false);
    CHECK(tracker.terminal() == false);

    // The core no-loss / no-duplication guarantee, in order.
    CHECK(popped_seq.size() == produced_seq.size());
    CHECK(popped_seq == produced_seq);

    const DrainResult snap = tracker.snapshot();
    CHECK(snap.schema_version == kStreamProtocolVersion);
    CHECK(snap.produced == tracker.produced());
    CHECK(snap.committed == tracker.committed());
    CHECK(snap.drained == tracker.drained());
    CHECK(snap.pending == false);
    CHECK(snap.terminal == false);
    CHECK(snap.terminal_reason == StreamTerminal::None);

    g_iterations += static_cast<long long>(kStressIterations);
}

// ---------------------------------------------------------------------------
// 2. A large committed backlog (>128 tokens) followed by a terminal.
//    `can_acknowledge_terminal()` must stay false until everything is drained,
//    then be true exactly once.
// ---------------------------------------------------------------------------
void testTerminalBackpressure() {
    StreamSequenceTracker tracker;
    const int32_t total = 200; // > 128

    for (int32_t i = 0; i < total; ++i) {
        CHECK(tracker.on_produce(1));
        CHECK(tracker.on_commit(1));
    }
    CHECK(tracker.produced() == total);
    CHECK(tracker.committed() == total);
    // Not terminal yet -> nothing is pending even with a full backlog.
    CHECK(tracker.pending() == false);
    CHECK(tracker.can_acknowledge_terminal() == false);

    CHECK(tracker.mark_terminal(StreamTerminal::Eof) == true);
    CHECK(tracker.terminal() == true);
    CHECK(tracker.terminal_reason() == StreamTerminal::Eof);
    CHECK(tracker.pending() == true);
    CHECK(tracker.can_acknowledge_terminal() == false);

    // Slow consumer drains all but the final token.
    for (int32_t i = 0; i < total - 1; ++i) {
        CHECK(tracker.on_drain(1));
        CHECK(tracker.can_acknowledge_terminal() == false);
    }
    CHECK(tracker.pending() == true);
    CHECK(tracker.can_acknowledge_terminal() == false);

    // Final token delivered -> the terminal becomes acknowledgeable.
    CHECK(tracker.on_drain(1));
    CHECK(tracker.pending() == false);
    CHECK(tracker.can_acknowledge_terminal() == true);
    CHECK(tracker.acknowledge_terminal() == true);
    CHECK(tracker.can_acknowledge_terminal() == false);
    CHECK(tracker.acknowledge_terminal() == false); // exactly once
    CHECK(tracker.validate());

    // A second terminal is refused and does not rewrite the reason.
    CHECK(tracker.mark_terminal(StreamTerminal::Error) == false);
    CHECK(tracker.terminal_reason() == StreamTerminal::Eof);

    const DrainResult snap = tracker.snapshot();
    CHECK(snap.terminal == true);
    CHECK(snap.terminal_reason == StreamTerminal::Eof);
    CHECK(snap.pending == false);
    CHECK(snap.produced == total);
    CHECK(snap.drained == total);
}

// ---------------------------------------------------------------------------
// 3. Ring wrap: fill/drain repeatedly so head and tail wrap many times, and
//    verify strict FIFO ordering plus full/empty rejection.
// ---------------------------------------------------------------------------
void testRingWrapOrdering() {
    SpscRing<kRingCapacity> ring;
    int32_t next = 0;
    int32_t expected = 0;

    for (std::size_t round = 0; round < kWrapRounds; ++round) {
        while (!ring.full()) {
            CHECK(ring.push(next));
            ++next;
        }
        CHECK(ring.full());
        CHECK(ring.push(next) == false); // full push rejected, no corruption

        int32_t value = 0;
        while (ring.pop(value)) {
            CHECK(value == expected);
            ++expected;
        }
        CHECK(ring.empty());
        CHECK(ring.pop(value) == false); // empty pop rejected
    }

    CHECK(next == expected);
    CHECK(next == static_cast<int32_t>(kWrapRounds * kRingCapacity));
    CHECK(ring.size() == 0);
    CHECK(ring.capacity() == kRingCapacity);
    CHECK(ring.empty());

    g_iterations += static_cast<long long>(kWrapRounds * kRingCapacity);
}

// ---------------------------------------------------------------------------
// 4. Cancellation mid-drain: the terminal must not be acknowledged while
//    output remains, and no further produce is accepted after the terminal.
// ---------------------------------------------------------------------------
void testCancellationMidDrain() {
    StreamSequenceTracker tracker;

    for (int i = 0; i < 10; ++i) {
        CHECK(tracker.on_produce(1));
        CHECK(tracker.on_commit(1));
    }
    for (int i = 0; i < 4; ++i) {
        CHECK(tracker.on_drain(1));
    }

    CHECK(tracker.mark_terminal(StreamTerminal::Cancelled) == true);
    CHECK(tracker.pending() == true);
    CHECK(tracker.can_acknowledge_terminal() == false);

    // No further produce/commit after the terminal; state stays uncorrupted.
    CHECK(tracker.on_produce(1) == false);
    CHECK(tracker.on_commit(1) == false);
    CHECK(tracker.produced() == 10);
    CHECK(tracker.committed() == 10);
    CHECK(tracker.drained() == 4);
    CHECK(tracker.validate());
    CHECK(tracker.had_error() == true); // rejected calls were recorded

    // Deliver the remaining output; the terminal is not acknowledged early.
    for (int i = 0; i < 5; ++i) {
        CHECK(tracker.on_drain(1));
        CHECK(tracker.can_acknowledge_terminal() == false);
    }
    CHECK(tracker.pending() == true);
    CHECK(tracker.can_acknowledge_terminal() == false);

    CHECK(tracker.on_drain(1));
    CHECK(tracker.pending() == false);
    CHECK(tracker.can_acknowledge_terminal() == true);
    CHECK(tracker.acknowledge_terminal() == true);
    CHECK(tracker.acknowledge_terminal() == false);
    CHECK(tracker.terminal_reason() == StreamTerminal::Cancelled);
    CHECK(tracker.validate());

    // Still no produce after the terminal has been acknowledged.
    CHECK(tracker.on_produce(1) == false);
    CHECK(tracker.produced() == 10);
    CHECK(tracker.validate());
}

// ---------------------------------------------------------------------------
// 5. Invariant guards: `validate()` holds throughout, and deliberately invalid
//    calls are rejected without corrupting state.
// ---------------------------------------------------------------------------
void testInvariantGuards() {
    StreamSequenceTracker t;
    CHECK(t.validate());
    CHECK(t.produced() == 0);
    CHECK(t.committed() == 0);
    CHECK(t.drained() == 0);
    CHECK(t.terminal() == false);
    CHECK(t.pending() == false);
    CHECK(t.had_error() == false);

    // No-op calls are accepted.
    CHECK(t.on_produce(0));
    CHECK(t.on_commit(0));
    CHECK(t.on_drain(0));
    CHECK(t.validate());

    CHECK(t.on_produce(5));
    CHECK(t.on_commit(3));
    CHECK(t.drained() == 0);
    CHECK(t.validate());
    // committed > drained but not terminal -> pending must be false.
    CHECK(t.pending() == false);

    // Commit beyond produced is rejected and leaves `committed` untouched.
    CHECK(t.on_commit(t.produced() + 1) == false);
    CHECK(t.had_error() == true);
    CHECK(t.committed() == 3);
    CHECK(t.validate());

    // Drain beyond produced is rejected.
    CHECK(t.on_drain(t.produced() + 1) == false);
    CHECK(t.drained() == 0);
    CHECK(t.validate());

    // Negative counts are rejected everywhere.
    CHECK(t.on_produce(-1) == false);
    CHECK(t.produced() == 5);
    CHECK(t.on_commit(-1) == false);
    CHECK(t.committed() == 3);
    CHECK(t.on_drain(-1) == false);
    CHECK(t.drained() == 0);
    CHECK(t.validate());

    // Exactly filling the remaining budget succeeds; one more would fail.
    CHECK(t.on_commit(2));
    CHECK(t.on_commit(1) == false);
    CHECK(t.committed() == 5);
    CHECK(t.validate());

    CHECK(t.on_drain(5));
    CHECK(t.committed() == 5);
    CHECK(t.drained() == 5);
    CHECK(t.validate());

    // Terminal exactly when everything is delivered -> immediately ack-able.
    CHECK(t.mark_terminal(StreamTerminal::MaxTokens));
    CHECK(t.pending() == false);
    CHECK(t.can_acknowledge_terminal() == true);
    CHECK(t.acknowledge_terminal() == true);
    CHECK(t.validate());

    // Terminal with an undelivered backlog is never acknowledgeable.
    StreamSequenceTracker u;
    CHECK(u.on_produce(4));
    CHECK(u.on_commit(4));
    CHECK(u.mark_terminal(StreamTerminal::Error));
    CHECK(u.pending() == true);
    CHECK(u.can_acknowledge_terminal() == false);
    CHECK(u.on_drain(3));
    CHECK(u.can_acknowledge_terminal() == false);
    CHECK(u.on_drain(1));
    CHECK(u.can_acknowledge_terminal() == true);
    CHECK(u.acknowledge_terminal() == true);
    CHECK(u.validate());

    // Default DrainResult is versioned and empty.
    const DrainResult fresh;
    CHECK(fresh.schema_version == kStreamProtocolVersion);
    CHECK(fresh.produced == 0);
    CHECK(fresh.committed == 0);
    CHECK(fresh.drained == 0);
    CHECK(fresh.pending == false);
    CHECK(fresh.terminal == false);
    CHECK(fresh.terminal_reason == StreamTerminal::None);
}

} // namespace

int main() {
    std::printf("stream_protocol_test: exercising runtime/StreamProtocol.hpp\n");

    testInterleavedProduceDrain();
    testTerminalBackpressure();
    testRingWrapOrdering();
    testCancellationMidDrain();
    testInvariantGuards();

    std::printf("iterations: %lld, checks: %d, failures: %d\n",
                g_iterations, g_checks, g_failures);
    if (g_failures != 0) {
        std::fprintf(stderr, "stream_protocol_test: FAILED (%d/%d checks)\n",
                     g_failures, g_checks);
        return 1;
    }
    std::printf("stream_protocol_test: OK\n");
    return 0;
}
