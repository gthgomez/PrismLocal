#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace llmhost {

/**
 * HandleRegistry<T> provides thread-safe, leased handle acquisition and explicit
 * teardown synchronization for long-lived native objects.
 *
 * Lifecycle per entry:
 *   OPEN      - Active, leases granted via acquire().
 *   CLOSING   - close() has been initiated. New acquire() calls return null leases.
 *               The closing thread blocks until all in-flight active leases are released.
 *   DESTROYED - Object is deleted on the thread that invoked close(), preserving
 *               dedicated teardown thread affinity (e.g. InferenceService.onDestroy).
 *
 * Handle IDs are monotonically increasing 64-bit integers and are never reused.
 * 0 is reserved as invalid/null.
 */
template <typename T>
class HandleRegistry {
public:
    enum class State {
        Open,
        Closing,
        Destroyed,
    };

    class Lease {
    public:
        Lease() : registry_(nullptr), handle_(0), instance_(nullptr) {}

        Lease(HandleRegistry<T>* registry, int64_t handle, std::shared_ptr<T> instance)
            : registry_(registry), handle_(handle), instance_(std::move(instance)) {}

        ~Lease() {
            reset();
        }

        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;

        Lease(Lease&& other) noexcept
            : registry_(other.registry_), handle_(other.handle_), instance_(std::move(other.instance_)) {
            other.registry_ = nullptr;
            other.handle_ = 0;
        }

        Lease& operator=(Lease&& other) noexcept {
            if (this != &other) {
                reset();
                registry_ = other.registry_;
                handle_ = other.handle_;
                instance_ = std::move(other.instance_);
                other.registry_ = nullptr;
                other.handle_ = 0;
            }
            return *this;
        }

        T* get() const { return instance_.get(); }
        T* operator->() const { return instance_.get(); }
        T& operator*() const { return *instance_; }
        explicit operator bool() const { return instance_ != nullptr; }

        void reset() {
            // Drop our shared_ptr reference before notifying the registry, so that
            // when active_leases reaches 0, the registry entry holds the sole remaining reference.
            instance_.reset();
            if (registry_ != nullptr && handle_ != 0) {
                registry_->releaseLease(handle_);
                registry_ = nullptr;
                handle_ = 0;
            }
        }

    private:
        HandleRegistry<T>* registry_;
        int64_t handle_;
        std::shared_ptr<T> instance_;
    };

    static HandleRegistry<T>& instance() {
        static HandleRegistry<T> s_instance;
        return s_instance;
    }

    int64_t registerInstance(std::unique_ptr<T> obj) {
        if (!obj) return 0;
        std::lock_guard<std::mutex> lock(mu_);
        const int64_t handle = next_handle_++;
        auto entry = std::make_shared<Entry>();
        entry->instance = std::shared_ptr<T>(std::move(obj));
        entry->state = State::Open;
        entry->active_leases = 0;
        entries_[handle] = entry;
        return handle;
    }

    Lease acquire(int64_t handle) {
        if (handle <= 0) return Lease();
        std::lock_guard<std::mutex> lock(mu_);
        auto it = entries_.find(handle);
        if (it == entries_.end()) return Lease();
        auto& entry = it->second;
        if (entry->state != State::Open) return Lease();
        entry->active_leases++;
        return Lease(this, handle, entry->instance);
    }

    bool close(int64_t handle) {
        if (handle <= 0) return false;
        std::shared_ptr<Entry> entry;
        {
            std::unique_lock<std::mutex> lock(mu_);
            auto it = entries_.find(handle);
            if (it == entries_.end()) return false;
            entry = it->second;
            if (entry->state != State::Open) return false;
            entry->state = State::Closing;

            // Wait until all in-flight active leases are released
            cv_.wait(lock, [&entry]() {
                return entry->active_leases == 0;
            });

            entry->state = State::Destroyed;
            entries_.erase(it);
        }
        // Destroy the instance on this thread while holding no locks.
        entry->instance.reset();
        return true;
    }

    size_t size() const {
        std::lock_guard<std::mutex> lock(mu_);
        return entries_.size();
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mu_);
        entries_.clear();
    }

private:
    friend class Lease;

    void releaseLease(int64_t handle) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = entries_.find(handle);
        if (it != entries_.end()) {
            auto& entry = it->second;
            if (entry->active_leases > 0) {
                entry->active_leases--;
            }
            if (entry->state == State::Closing && entry->active_leases == 0) {
                cv_.notify_all();
            }
        }
    }

    struct Entry {
        std::shared_ptr<T> instance;
        State state{State::Open};
        int32_t active_leases{0};
    };

    mutable std::mutex mu_;
    std::condition_variable cv_;
    int64_t next_handle_{1};
    std::unordered_map<int64_t, std::shared_ptr<Entry>> entries_;
};

} // namespace llmhost
