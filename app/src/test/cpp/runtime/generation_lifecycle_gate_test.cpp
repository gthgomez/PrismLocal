#include "runtime/GenerationLifecycleGate.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>

#define CHECK(value) do { if (!(value)) { \
    std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #value); \
    return 1; \
} } while (false)

int main() {
    llmhost::GenerationLifecycleGate gate;
    std::mutex state_mu;
    std::condition_variable state_cv;
    bool drain_inside = false;
    bool release_drain = false;
    std::atomic<bool> reset_entered{false};

    std::thread drain([&] {
        auto ownership = gate.enter(); // same gate used around production drain/decode
        {
            std::lock_guard<std::mutex> lock(state_mu);
            drain_inside = true;
        }
        state_cv.notify_all();
        std::unique_lock<std::mutex> lock(state_mu);
        state_cv.wait(lock, [&] { return release_drain; });
    });

    bool drain_started = false;
    {
        std::unique_lock<std::mutex> lock(state_mu);
        drain_started = state_cv.wait_for(lock, std::chrono::seconds(1), [&] { return drain_inside; });
    }
    if (!drain_started) {
        {
            std::lock_guard<std::mutex> lock(state_mu);
            release_drain = true;
        }
        state_cv.notify_all();
        drain.join();
        std::fprintf(stderr, "FAIL drain did not enter gate\n");
        return 1;
    }
    std::thread reset([&] {
        auto transition = gate.enter(); // reset/unload/new generation share the gate
        reset_entered.store(true, std::memory_order_release);
    });
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    const bool transition_was_blocked = !reset_entered.load(std::memory_order_acquire);

    {
        std::lock_guard<std::mutex> lock(state_mu);
        release_drain = true;
    }
    state_cv.notify_all();
    drain.join();
    reset.join();
    CHECK(transition_was_blocked);
    CHECK(reset_entered.load(std::memory_order_acquire));
    std::puts("generation lifecycle gate serializes drain and transition");
    return 0;
}
