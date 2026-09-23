#include "runtime/HandleRegistry.hpp"

#include <atomic>
#include <cassert>
#include <chrono>
#include <iostream>
#include <thread>
#include <vector>

namespace {

struct FakeEngine {
    static inline std::atomic<int> s_alive_count{0};
    static inline std::atomic<int> s_destructor_count{0};
    static inline std::thread::id s_last_destructor_thread_id{};

    int id;

    explicit FakeEngine(int id) : id(id) {
        s_alive_count.fetch_add(1, std::memory_order_relaxed);
    }

    ~FakeEngine() {
        s_last_destructor_thread_id = std::this_thread::get_id();
        s_destructor_count.fetch_add(1, std::memory_order_relaxed);
        s_alive_count.fetch_sub(1, std::memory_order_relaxed);
    }
};

using FakeRegistry = llmhost::HandleRegistry<FakeEngine>;

void test_register_and_acquire() {
    FakeRegistry registry;
    registry.clear();

    auto engine = std::make_unique<FakeEngine>(42);
    int64_t handle = registry.registerInstance(std::move(engine));
    assert(handle > 0);
    assert(FakeEngine::s_alive_count.load() == 1);

    {
        auto lease = registry.acquire(handle);
        assert(lease);
        assert(lease->id == 42);
        assert(lease.get() != nullptr);
    }

    bool closed = registry.close(handle);
    assert(closed);
    assert(FakeEngine::s_alive_count.load() == 0);
    std::cout << "test_register_and_acquire passed\n";
}

void test_invalid_and_stale_handles() {
    FakeRegistry registry;
    registry.clear();

    assert(!registry.acquire(0));
    assert(!registry.acquire(-1));
    assert(!registry.acquire(99999));
    assert(!registry.close(0));
    assert(!registry.close(99999));

    auto engine = std::make_unique<FakeEngine>(100);
    int64_t handle = registry.registerInstance(std::move(engine));
    assert(registry.close(handle));

    // Stale handle should fail to acquire and fail to close again
    assert(!registry.acquire(handle));
    assert(!registry.close(handle));
    std::cout << "test_invalid_and_stale_handles passed\n";
}

void test_monotonic_non_reused_handles() {
    FakeRegistry registry;
    registry.clear();

    int64_t h1 = registry.registerInstance(std::make_unique<FakeEngine>(1));
    int64_t h2 = registry.registerInstance(std::make_unique<FakeEngine>(2));
    assert(h2 > h1);

    registry.close(h1);
    int64_t h3 = registry.registerInstance(std::make_unique<FakeEngine>(3));
    assert(h3 > h2);
    assert(h3 != h1);

    registry.close(h2);
    registry.close(h3);
    std::cout << "test_monotonic_non_reused_handles passed\n";
}

void test_close_waits_for_leases_and_destroys_on_close_thread() {
    FakeRegistry registry;
    registry.clear();

    auto engine = std::make_unique<FakeEngine>(77);
    int64_t handle = registry.registerInstance(std::move(engine));

    auto lease = registry.acquire(handle);
    assert(lease);

    std::atomic<bool> close_started{false};
    std::atomic<bool> close_finished{false};
    std::thread::id close_thread_id;

    std::thread close_thread([&]() {
        close_thread_id = std::this_thread::get_id();
        close_started.store(true);
        bool ok = registry.close(handle);
        assert(ok);
        close_finished.store(true);
    });

    while (!close_started.load()) {
        std::this_thread::yield();
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    // While lease is held, close must not have finished, and engine must still be alive
    assert(!close_finished.load());
    assert(FakeEngine::s_alive_count.load() == 1);

    // Any new acquire during closing must be rejected
    auto lease2 = registry.acquire(handle);
    assert(!lease2);

    // Release lease
    lease.reset();

    close_thread.join();
    assert(close_finished.load());
    assert(FakeEngine::s_alive_count.load() == 0);
    // Destructor must have run on the close thread!
    assert(FakeEngine::s_last_destructor_thread_id == close_thread_id);
    std::cout << "test_close_waits_for_leases_and_destroys_on_close_thread passed\n";
}

void test_concurrent_stress() {
    FakeRegistry registry;
    registry.clear();

    const int kIterations = 500;
    for (int i = 0; i < kIterations; ++i) {
        int64_t handle = registry.registerInstance(std::make_unique<FakeEngine>(i));
        std::atomic<bool> stop{false};

        std::thread reader([&]() {
            while (!stop.load()) {
                auto lease = registry.acquire(handle);
                if (lease) {
                    assert(lease->id == i);
                    std::this_thread::yield();
                }
            }
        });

        std::this_thread::sleep_for(std::chrono::microseconds(100));
        stop.store(true);
        bool closed = registry.close(handle);
        assert(closed);
        reader.join();
    }
    assert(FakeEngine::s_alive_count.load() == 0);
    std::cout << "test_concurrent_stress passed\n";
}

} // namespace

int main() {
    test_register_and_acquire();
    test_invalid_and_stale_handles();
    test_monotonic_non_reused_handles();
    test_close_waits_for_leases_and_destroys_on_close_thread();
    test_concurrent_stress();
    std::cout << "ALL HANDLE REGISTRY TESTS PASSED\n";
    return 0;
}
