#pragma once

// PIR-02 — portable stream / terminal protocol for the native token pipe.
//
// Header-only and dependency-light: no llama.cpp, no Android, no threads, no
// Gradle. The implementation deliberately includes only
// <cstdint>/<string>/<vector>/<algorithm> so that the accounting can be
// compiled and exercised on a plain host toolchain, without a model or a
// device.
//
// A single generation moves tokens through three counters:
//
//   produced  — tokens the model has generated.
//   committed — tokens handed to the output sink, therefore safe to expose.
//   drained   — tokens the consumer has actually read back out.
//
// The consumer runs at its own rate, so `committed` may legitimately run ahead
// of `drained`; once the generation reaches a terminal state that backlog is
// `pending`. The terminal may only be acknowledged when *all* produced output
// has been delivered (`drained == produced`) and nothing is pending. This is
// the G03 invariant: a terminal is never reported complete while output could
// still be lost.
//
// Every mutator is total. An invalid call is rejected (returns false and sets
// an error flag) and leaves the counters untouched, so a rejected call is never
// confused with a successful mutation. `validate()` re-checks the maintained
// invariants against the stored state: `produced_ >= committed_ >= 0` and
// `produced_ >= drained_ >= 0`. It deliberately does NOT require
// `committed_ >= drained_`: a token may legitimately be delivered to the
// consumer before its KV entry is committed, so delivery may precede commit.
// A rejected call is *not* corruption, so `validate()` stays true and
// `had_error()` surfaces the rejected call.

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

namespace llmhost {

// Bump when the meaning or layout of the protocol fields changes.
constexpr int kStreamProtocolVersion = 1;

// Why a generation ended. `None` means "not terminal yet".
enum class StreamTerminal : uint8_t {
    None = 0,
    Eof,
    Cancelled,
    Error,
    MaxTokens,
};

// Flat snapshot of the sequence accounting. Intended to be mirrored 1:1 across
// the JNI/FFI boundary, so it holds only fixed-width integers and booleans.
struct DrainResult {
    int schema_version = kStreamProtocolVersion;
    int32_t produced = 0;
    int32_t committed = 0;
    int32_t drained = 0;
    bool pending = false;
    bool terminal = false;
    StreamTerminal terminal_reason = StreamTerminal::None;
};

// Tracks the produced/committed/drained accounting for one generation.
class StreamSequenceTracker {
public:
    StreamSequenceTracker() = default;

    // ---- Producer side ----------------------------------------------------

    // Record `n` newly generated tokens. Rejected after a terminal (no output
    // may appear after the stream has ended), on negative input, or if the
    // counter would overflow.
    bool on_produce(int32_t n) {
        if (terminal_) return fail();
        if (n < 0) return fail();
        if (n > kMax - produced_) return fail();
        produced_ += n;
        return true;
    }

    // Pledge `n` of the already-produced tokens to the output sink. May not
    // exceed what is still uncommitted, and is rejected after a terminal.
    bool on_commit(int32_t n) {
        if (terminal_) return fail();
        if (n < 0) return fail();
        if (n > produced_ - committed_) return fail();
        committed_ += n;
        refreshPending();
        return true;
    }

    // Record `n` tokens read by the consumer. Allowed after a terminal (that is
    // how a backlog gets delivered) but may never exceed `produced`.
    bool on_drain(int32_t n) {
        if (n < 0) return fail();
        if (n > produced_ - drained_) return fail();
        drained_ += n;
        refreshPending();
        return true;
    }

    // ---- Terminal side ----------------------------------------------------

    // Enter the terminal state exactly once and derive `pending` from the
    // committed/drained backlog. Returns false if a terminal was already set
    // (the reason is left unchanged) or if `reason` is `StreamTerminal::None`
    // (which means "not terminal yet", not a valid terminal reason).
    bool mark_terminal(StreamTerminal reason) {
        if (terminal_) return false;
        if (reason == StreamTerminal::None) return fail();
        terminal_ = true;
        terminal_reason_ = reason;
        refreshPending();
        return true;
    }

    // A terminal may be acknowledged only when the whole stream has been
    // delivered: terminal set, nothing pending, and every produced token
    // drained. Also false once the acknowledgement has been consumed.
    bool can_acknowledge_terminal() const {
        return terminal_ && !acknowledged_ && !pending_ && drained_ == produced_;
    }

    // Consume the acknowledgement. Succeeds at most once.
    bool acknowledge_terminal() {
        if (!can_acknowledge_terminal()) return false;
        acknowledged_ = true;
        return true;
    }

    // ---- Observation ------------------------------------------------------

    // Re-checks every invariant the tracker maintains. Returns false if any
    // counter or the derived `pending` flag is inconsistent.
    bool validate() const {
        if (produced_ < 0 || committed_ < 0 || drained_ < 0) return false;
        if (committed_ > produced_) return false; // produced >= committed >= 0
        if (drained_ > produced_) return false;   // produced >= drained >= 0
        if (pending_ != (terminal_ && committed_ > drained_)) return false;
        return true;
    }

    DrainResult snapshot() const {
        DrainResult r;
        r.schema_version = kStreamProtocolVersion;
        r.produced = produced_;
        r.committed = committed_;
        r.drained = drained_;
        r.pending = pending_;
        r.terminal = terminal_;
        r.terminal_reason = terminal_reason_;
        return r;
    }

    int32_t produced() const { return produced_; }
    int32_t committed() const { return committed_; }
    int32_t drained() const { return drained_; }
    bool pending() const { return pending_; }
    bool terminal() const { return terminal_; }
    StreamTerminal terminal_reason() const { return terminal_reason_; }

    // True once any call has been rejected. Distinct from `validate()`: a
    // rejected call leaves the state valid, but it is still a caller bug worth
    // surfacing at the JNI/FFI boundary.
    bool had_error() const { return error_; }

private:
    static constexpr int32_t kMax = INT32_MAX;

    bool fail() {
        error_ = true;
        return false;
    }

    void refreshPending() { pending_ = terminal_ && committed_ > drained_; }

    int32_t produced_ = 0;
    int32_t committed_ = 0;
    int32_t drained_ = 0;
    bool pending_ = false;
    bool terminal_ = false;
    bool acknowledged_ = false;
    bool error_ = false;
    StreamTerminal terminal_reason_ = StreamTerminal::None;
};

// Bounded single-producer/single-consumer ring, modelling the native token
// ring. Values are never lost or duplicated while it is used correctly: push
// only when not full, pop only when not empty.
//
// This host-side helper carries no atomics — it captures the *semantics* of the
// native ring, and the engine supplies the memory ordering for the real
// cross-thread case.
template <std::size_t Capacity>
class SpscRing {
    static_assert(Capacity > 0, "SpscRing capacity must be positive");

public:
    bool push(int32_t value) {
        if (size_ == Capacity) return false;
        buffer_[tail_] = value;
        tail_ = (tail_ + 1) % Capacity;
        ++size_;
        return true;
    }

    bool pop(int32_t& out) {
        if (size_ == 0) return false;
        out = buffer_[head_];
        head_ = (head_ + 1) % Capacity;
        --size_;
        return true;
    }

    std::size_t size() const { return size_; }
    static constexpr std::size_t capacity() { return Capacity; }
    bool empty() const { return size_ == 0; }
    bool full() const { return size_ == Capacity; }

private:
    int32_t buffer_[Capacity]{};
    std::size_t head_ = 0;
    std::size_t tail_ = 0;
    std::size_t size_ = 0;
};

} // namespace llmhost
