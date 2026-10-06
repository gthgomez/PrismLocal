// Engine mock-model qualification test (host).
//
// This test compiles the REAL production Engine.cpp — the same source Gradle
// compiles into libllmhost.so — against a host-built static llama.cpp, with
// LLMHOST_DEBUG_HOOKS=1. That activates the DEBUG_MOCK_MODEL / DEBUG_SIMULATE_RING
// paths, so the actual engine code (token ring, session lifecycle,
// cancellation, reset, memory-pressure handling, drain accounting) executes
// on a plain Linux host WITHOUT a model, a device, or Gradle.
//
// The mock path performs no llama decode: runDebugGeneration writes a
// deterministic token sequence (10000..10000+3071) into the real ring buffer
// with the real backpressure, cancellation and accounting logic. Everything
// asserted here is production code, not a simulation of it.
//
// StreamState values (Engine.hpp): Idle=0, Generating=1, CancelRequested=2,
// Eof=3, Cancelled=4, Error=5, Tombstoned=6, MaxTokens=7.

#include "Engine.hpp"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <memory>
#include <thread>
#include <vector>

#define CHECK(value) do { if (!(value)) { \
    std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #value); \
    return 1; \
} } while (false)

namespace {

constexpr int kMockTokenCount = 2048 + 1024; // kTokenCapacity + 1024, as in Engine.cpp
constexpr int kFirstMockToken = 10000;

// StreamState values mirrored from Engine.hpp for readable assertions.
constexpr int kStateEof = 3;
constexpr int kStateCancelled = 4;
constexpr int kStateTombstoned = 6;

llmhost::Engine* g_engine = nullptr;

// Poll until getState(genId) returns `expected` or the timeout elapses.
// Returns the last observed state.
int waitForState(int genId, int expected, int timeoutMs) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    int latest = g_engine->getState(genId);
    while (latest != expected && std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
        latest = g_engine->getState(genId);
    }
    return latest;
}

// Drain a whole mock generation to terminal. Returns the tokens handed out.
// The producer blocks once the ring is full, so draining must interleave with
// production — hence the poll loop rather than a single drain call.
std::vector<int32_t> drainWholeGeneration(int genId) {
    std::vector<int32_t> all;
    for (;;) {
        const int state = g_engine->getState(genId);
        if (state == kStateEof || state == kStateCancelled || state == 5 /* Error */) {
            // Final sweep for anything still buffered.
            auto tail = g_engine->drainTokens(genId, 4096);
            all.insert(all.end(), tail.begin(), tail.end());
            return all;
        }
        auto chunk = g_engine->drainTokens(genId, 256);
        all.insert(all.end(), chunk.begin(), chunk.end());
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

int testMockModelLoadRejectsWhenHooksDisabled() {
    llmhost::Engine engine(false); // debug_hooks_enabled = false
    CHECK(!engine.loadModel("DEBUG_MOCK_MODEL"));
    std::puts("  mock model load is rejected when debug hooks are disabled");
    return 0;
}

int testStartGenerationWithoutModelFails() {
    llmhost::Engine engine(true);
    CHECK(engine.startGeneration("DEBUG_SIMULATE_RING", 9001, llmhost::GenerationConfig{}) == -1);
    std::puts("  startGeneration without a loaded model returns -1");
    return 0;
}

int testFullMockGenerationProducesExactTokenSequence() {
    g_engine = new llmhost::Engine(true);
    CHECK(g_engine->loadModel("DEBUG_MOCK_MODEL"));

    const int genId = 9101;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", genId, llmhost::GenerationConfig{}) == genId);
    CHECK(waitForState(genId, 1 /* Generating */, 2000) == 1);

    auto tokens = drainWholeGeneration(genId);
    CHECK(g_engine->getState(genId) == kStateEof);
    CHECK(static_cast<int>(tokens.size()) == kMockTokenCount);
    bool sequenceOk = static_cast<int>(tokens.size()) == kMockTokenCount;
    for (int i = 0; sequenceOk && i < kMockTokenCount; ++i) {
        if (tokens[static_cast<size_t>(i)] != kFirstMockToken + i) {
            sequenceOk = false;
        }
    }
    CHECK(sequenceOk);

    // The drain accounting must agree with what was produced.
    auto result = g_engine->drainDecodeAndState(genId, 64);
    CHECK(result.produced == kMockTokenCount);
    CHECK(result.drained == kMockTokenCount);
    CHECK(!result.pending);
    CHECK(result.error_code == 0);

    delete g_engine;
    g_engine = nullptr;
    std::puts("  full mock generation produces the exact 3072-token sequence and drains cleanly to EOF");
    return 0;
}

int testCancelGenerationReachesCancelledAndClearsRing() {
    g_engine = new llmhost::Engine(true);
    CHECK(g_engine->loadModel("DEBUG_MOCK_MODEL"));

    const int genId = 9201;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", genId, llmhost::GenerationConfig{}) == genId);
    std::this_thread::sleep_for(std::chrono::milliseconds(20)); // let it produce some tokens

    // Real tokens were produced before the cancel.
    auto preCancel = g_engine->drainTokens(genId, 256);
    CHECK(!preCancel.empty());
    CHECK(preCancel.front() == kFirstMockToken);

    g_engine->cancelGeneration(genId); // joins the worker internally
    CHECK(g_engine->getState(genId) == kStateCancelled);

    // Explicit cancellation discards the unacknowledged batch.
    CHECK(g_engine->drainTokens(genId, 256).empty());

    auto result = g_engine->drainDecodeAndState(genId, 64);
    CHECK(result.tokens.empty());
    CHECK(result.produced == 0); // cancel cleared the ring and its accounting
    CHECK(result.drained == 0);
    CHECK(!result.pending);

    delete g_engine;
    g_engine = nullptr;
    std::puts("  cancelGeneration reaches CANCELLED and clears the unacknowledged ring batch");
    return 0;
}

int testResetConversationGivesNextGenerationNoStaleTokens() {
    g_engine = new llmhost::Engine(true);
    CHECK(g_engine->loadModel("DEBUG_MOCK_MODEL"));

    const int firstId = 9301;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", firstId, llmhost::GenerationConfig{}) == firstId);
    std::this_thread::sleep_for(std::chrono::milliseconds(15));
    g_engine->cancelGeneration(firstId);
    CHECK(g_engine->getState(firstId) == kStateCancelled);

    g_engine->resetConversation();

    const int secondId = 9302;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", secondId, llmhost::GenerationConfig{}) == secondId);
    auto tokens = drainWholeGeneration(secondId);
    CHECK(g_engine->getState(secondId) == kStateEof);
    CHECK(static_cast<int>(tokens.size()) == kMockTokenCount);
    CHECK(!tokens.empty());
    CHECK(tokens.front() == kFirstMockToken); // no stale tokens from the cancelled generation
    bool sequenceOk = static_cast<int>(tokens.size()) == kMockTokenCount;
    for (int i = 0; sequenceOk && i < kMockTokenCount; ++i) {
        if (tokens[static_cast<size_t>(i)] != kFirstMockToken + i) {
            sequenceOk = false;
        }
    }
    CHECK(sequenceOk);

    delete g_engine;
    g_engine = nullptr;
    std::puts("  resetConversation clears the ring; the next generation starts fresh at token 10000");
    return 0;
}

int testCriticalMemoryPressureCancelsActiveGeneration() {
    g_engine = new llmhost::Engine(true);
    CHECK(g_engine->loadModel("DEBUG_MOCK_MODEL"));

    g_engine->setMemoryPressure(3); // critical, before generation starts
    const int genId = 9401;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", genId, llmhost::GenerationConfig{}) == genId);
    CHECK(waitForState(genId, kStateCancelled, 3000) == kStateCancelled);

    delete g_engine;
    g_engine = nullptr;
    std::puts("  critical memory pressure (>=3) cancels an active generation");
    return 0;
}

int testUnloadModelDuringGenerationIsSafe() {
    g_engine = new llmhost::Engine(true);
    CHECK(g_engine->loadModel("DEBUG_MOCK_MODEL"));

    const int genId = 9501;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", genId, llmhost::GenerationConfig{}) == genId);
    std::this_thread::sleep_for(std::chrono::milliseconds(15));

    g_engine->unloadModel(); // cancels and joins the active session
    CHECK(g_engine->getState(genId) == 0 /* Idle */);

    // The engine must still be usable afterwards.
    CHECK(g_engine->loadModel("DEBUG_MOCK_MODEL"));
    const int nextId = 9502;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", nextId, llmhost::GenerationConfig{}) == nextId);
    auto tokens = drainWholeGeneration(nextId);
    CHECK(static_cast<int>(tokens.size()) == kMockTokenCount);

    delete g_engine;
    g_engine = nullptr;
    std::puts("  unloadModel during generation cancels safely and the engine remains usable");
    return 0;
}

int testEngineDestructionDuringGenerationCancelsAndJoins() {
    {
        auto engine = std::make_unique<llmhost::Engine>(true);
        CHECK(engine->loadModel("DEBUG_MOCK_MODEL"));
        const int genId = 9601;
        CHECK(engine->startGeneration("DEBUG_SIMULATE_RING", genId, llmhost::GenerationConfig{}) == genId);
        std::this_thread::sleep_for(std::chrono::milliseconds(15));
        // Engine destructor runs here with a generation in flight: it must
        // cancel and join the worker, not crash, leak, or hang.
    }
    std::puts("  engine destruction with an active generation cancels and joins cleanly");
    return 0;
}

int testStaleGenerationCannotReadReplacement() {
    g_engine = new llmhost::Engine(true);
    CHECK(g_engine->loadModel("DEBUG_MOCK_MODEL"));

    const int staleId = 9701;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", staleId, llmhost::GenerationConfig{}) == staleId);
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
    g_engine->cancelGeneration(staleId);
    g_engine->resetConversation();

    const int replacementId = 9702;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", replacementId, llmhost::GenerationConfig{}) == replacementId);
    std::this_thread::sleep_for(std::chrono::milliseconds(10));

    CHECK(!g_engine->drainTokens(replacementId, 32).empty());
    CHECK(g_engine->drainTokens(staleId, 32).empty());
    CHECK(g_engine->getState(staleId) == kStateTombstoned);

    auto stale = g_engine->drainDecodeAndState(staleId, 32);
    CHECK(stale.tokens.empty());
    CHECK(stale.state == kStateTombstoned);
    CHECK(stale.error_code == 404); // HANDLE_INVALID_OR_CLOSED
    CHECK(stale.produced == 0);
    CHECK(stale.drained == 0);
    CHECK(!stale.pending);

    delete g_engine;
    g_engine = nullptr;
    std::puts("  a stale generation id cannot read the replacement generation's tokens");
    return 0;
}

int testAckEofTombstonesFullyDrainedGeneration() {
    g_engine = new llmhost::Engine(true);
    CHECK(g_engine->loadModel("DEBUG_MOCK_MODEL"));

    const int genId = 9801;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", genId, llmhost::GenerationConfig{}) == genId);
    auto tokens = drainWholeGeneration(genId);
    CHECK(g_engine->getState(genId) == kStateEof);
    CHECK(static_cast<int>(tokens.size()) == kMockTokenCount);

    g_engine->ackEof(genId);
    CHECK(g_engine->getState(genId) == kStateTombstoned);

    delete g_engine;
    g_engine = nullptr;
    std::puts("  ackEof tombstones a fully drained generation");
    return 0;
}

int testAckEofBeforeTerminalDoesNotTombstone() {
    g_engine = new llmhost::Engine(true);
    CHECK(g_engine->loadModel("DEBUG_MOCK_MODEL"));

    const int genId = 9901;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", genId, llmhost::GenerationConfig{}) == genId);
    std::this_thread::sleep_for(std::chrono::milliseconds(10));

    g_engine->ackEof(genId); // not terminal yet
    CHECK(g_engine->getState(genId) != kStateTombstoned);

    g_engine->cancelGeneration(genId);
    CHECK(g_engine->getState(genId) == kStateCancelled);

    delete g_engine;
    g_engine = nullptr;
    std::puts("  ackEof before the terminal state does not tombstone");
    return 0;
}

int testDoubleStartCancelsPreviousSession() {
    g_engine = new llmhost::Engine(true);
    CHECK(g_engine->loadModel("DEBUG_MOCK_MODEL"));

    const int firstId = 10001;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", firstId, llmhost::GenerationConfig{}) == firstId);
    std::this_thread::sleep_for(std::chrono::milliseconds(10));

    const int secondId = 10002;
    CHECK(g_engine->startGeneration("DEBUG_SIMULATE_RING", secondId, llmhost::GenerationConfig{}) == secondId);
    // The first session was superseded: its id no longer resolves.
    CHECK(waitForState(firstId, kStateTombstoned, 2000) == kStateTombstoned);

    auto tokens = drainWholeGeneration(secondId);
    CHECK(static_cast<int>(tokens.size()) == kMockTokenCount);

    delete g_engine;
    g_engine = nullptr;
    std::puts("  starting a new generation supersedes (cancels) the previous session");
    return 0;
}

int testMockModelLoadTwiceAndBackendTelemetry() {
    g_engine = new llmhost::Engine(true);
    CHECK(g_engine->loadModel("DEBUG_MOCK_MODEL"));
    CHECK(g_engine->loadModel("DEBUG_MOCK_MODEL")); // reload is idempotent
    CHECK(g_engine->get_gpu_layers() == 0);
    CHECK(!g_engine->is_vulkan_enabled());
    CHECK(!g_engine->is_kleidiai_enabled());
    CHECK(g_engine->get_backend_name() == "CPU");
    delete g_engine;
    g_engine = nullptr;
    std::puts("  mock model reload is idempotent and telemetry reports the CPU baseline");
    return 0;
}

} // namespace

int main() {
    struct Case {
        const char* name;
        int (*fn)();
    };
    const Case cases[] = {
        {"mock_model_load_rejects_when_hooks_disabled", testMockModelLoadRejectsWhenHooksDisabled},
        {"start_generation_without_model_fails", testStartGenerationWithoutModelFails},
        {"full_mock_generation_exact_sequence", testFullMockGenerationProducesExactTokenSequence},
        {"cancel_generation_reaches_cancelled", testCancelGenerationReachesCancelledAndClearsRing},
        {"reset_conversation_no_stale_tokens", testResetConversationGivesNextGenerationNoStaleTokens},
        {"critical_memory_pressure_cancels", testCriticalMemoryPressureCancelsActiveGeneration},
        {"unload_model_during_generation_safe", testUnloadModelDuringGenerationIsSafe},
        {"engine_destruction_cancels_and_joins", testEngineDestructionDuringGenerationCancelsAndJoins},
        {"stale_generation_cannot_read_replacement", testStaleGenerationCannotReadReplacement},
        {"ack_eof_tombstones_drained_generation", testAckEofTombstonesFullyDrainedGeneration},
        {"ack_eof_before_terminal_no_tombstone", testAckEofBeforeTerminalDoesNotTombstone},
        {"double_start_cancels_previous_session", testDoubleStartCancelsPreviousSession},
        {"mock_model_reload_and_telemetry", testMockModelLoadTwiceAndBackendTelemetry},
    };

    int failed = 0;
    for (const auto& c : cases) {
        std::printf("[ RUN      ] %s\n", c.name);
        const int rc = c.fn();
        if (rc != 0) {
            std::printf("[  FAILED  ] %s\n", c.name);
            ++failed;
        } else {
            std::printf("[       OK ] %s\n", c.name);
        }
    }

    if (failed > 0) {
        std::printf("engine_mock_test: %d/%d cases FAILED\n", failed, static_cast<int>(sizeof(cases) / sizeof(cases[0])));
        return 1;
    }
    std::printf("engine_mock_test: all %d cases passed\n", static_cast<int>(sizeof(cases) / sizeof(cases[0])));
    return 0;
}
