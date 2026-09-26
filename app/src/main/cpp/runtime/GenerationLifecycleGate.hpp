#pragma once

#include <mutex>

namespace llmhost {

// Shared transition barrier for the production Engine. A recursive lock lets
// the JNI drain/decode/state composition call the same guarded primitives
// without exposing an unlocked interval to reset, unload, or replacement.
class GenerationLifecycleGate {
public:
    using Guard = std::unique_lock<std::recursive_mutex>;

    Guard enter() const { return Guard(mutex_); }

private:
    mutable std::recursive_mutex mutex_;
};

} // namespace llmhost
