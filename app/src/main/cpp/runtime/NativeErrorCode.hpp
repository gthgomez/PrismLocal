#pragma once

#include <cstdint>

namespace llmhost {

/**
 * Canonical typed native error codes for inference engine operations.
 * Mirrored in Kotlin at GenerationOrchestrator.mapErrorCodeToUserMessage.
 */
enum class NativeErrorCode : uint32_t {
    OK = 0,
    DEBUG_HOOKS_REJECTED = 403,
    HANDLE_INVALID_OR_CLOSED = 404,
    PROMPT_DOES_NOT_FIT = 420,
    TOKENIZE_SIZE_FAILED = 421,
    CONTEXT_WINDOW_TOO_SMALL = 422,
    TOKENIZE_FAILED = 423,
    SAMPLER_INIT_FAILED = 424,
    NULL_TOKEN_PRODUCED = 425,
    CONTEXT_SHIFT_FAILED = 426,
    GRAMMAR_COMPILE_FAILED = 427,
    NO_CONTINUATION_CONTEXT = 428,
    MODEL_LOAD_FAILED = 501,
};

} // namespace llmhost
