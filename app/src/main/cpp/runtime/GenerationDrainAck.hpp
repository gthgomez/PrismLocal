#pragma once

#include <cstdint>

namespace llmhost {

// Validation shared by the native consumer commit path and host tests. A
// batch acknowledgement must name the exact tail it observed and stay within
// the currently available ring contents.
inline bool validDrainAcknowledgement(
        int expected_tail,
        uint32_t current_tail,
        int token_count,
        uint32_t available) {
    return expected_tail >= 0 &&
        static_cast<uint32_t>(expected_tail) == current_tail &&
        token_count > 0 &&
        static_cast<uint32_t>(token_count) <= available;
}

} // namespace llmhost
