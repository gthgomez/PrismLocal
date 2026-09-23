// Contract test for the canonical native error-code values shared with Kotlin.

#include "runtime/NativeErrorCode.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>

using llmhost::NativeErrorCode;

namespace {

constexpr uint32_t value(NativeErrorCode code) {
    return static_cast<uint32_t>(code);
}

constexpr std::array<NativeErrorCode, 14> kDefinedCodes = {
    NativeErrorCode::OK,
    NativeErrorCode::DEBUG_HOOKS_REJECTED,
    NativeErrorCode::HANDLE_INVALID_OR_CLOSED,
    NativeErrorCode::DEBUG_GENERATION_REJECTED,
    NativeErrorCode::PROMPT_DOES_NOT_FIT,
    NativeErrorCode::TOKENIZE_SIZE_FAILED,
    NativeErrorCode::CONTEXT_WINDOW_TOO_SMALL,
    NativeErrorCode::TOKENIZE_FAILED,
    NativeErrorCode::SAMPLER_INIT_FAILED,
    NativeErrorCode::NULL_TOKEN_PRODUCED,
    NativeErrorCode::CONTEXT_SHIFT_FAILED,
    NativeErrorCode::GRAMMAR_COMPILE_FAILED,
    NativeErrorCode::NO_CONTINUATION_CONTEXT,
    NativeErrorCode::MODEL_LOAD_FAILED,
};

static_assert(value(NativeErrorCode::DEBUG_GENERATION_REJECTED) == 405);
static_assert(value(NativeErrorCode::MODEL_LOAD_FAILED) == 501);
static_assert(value(NativeErrorCode::DEBUG_GENERATION_REJECTED) !=
              value(NativeErrorCode::MODEL_LOAD_FAILED));
static_assert(value(NativeErrorCode::DEBUG_GENERATION_REJECTED) < 5000 ||
              value(NativeErrorCode::DEBUG_GENERATION_REJECTED) > 5999);

} // namespace

int main() {
    for (std::size_t i = 0; i < kDefinedCodes.size(); ++i) {
        for (std::size_t j = i + 1; j < kDefinedCodes.size(); ++j) {
            if (value(kDefinedCodes[i]) == value(kDefinedCodes[j])) {
                std::fprintf(
                    stderr,
                    "duplicate native error code: %u\n",
                    value(kDefinedCodes[i]));
                return 1;
            }
        }
        const uint32_t code = value(kDefinedCodes[i]);
        if (code >= 5000 && code <= 5999) {
            std::fprintf(
                stderr,
                "canonical native error code collides with decode range: %u\n",
                code);
            return 1;
        }
    }

    std::puts("native error-code contract passed");
    return 0;
}
