package com.prismai.llmhost.generation

/**
 * Kotlin mirror of runtime/NativeErrorCode.hpp.
 * Keep values and enum members aligned with the native contract tests.
 */
internal enum class NativeErrorCode(val value: Int) {
    OK(0),
    DEBUG_HOOKS_REJECTED(403),
    HANDLE_INVALID_OR_CLOSED(404),
    DEBUG_GENERATION_REJECTED(405),
    PROMPT_DOES_NOT_FIT(420),
    TOKENIZE_SIZE_FAILED(421),
    CONTEXT_WINDOW_TOO_SMALL(422),
    TOKENIZE_FAILED(423),
    SAMPLER_INIT_FAILED(424),
    NULL_TOKEN_PRODUCED(425),
    CONTEXT_SHIFT_FAILED(426),
    GRAMMAR_COMPILE_FAILED(427),
    NO_CONTINUATION_CONTEXT(428),
    NATIVE_EXCEPTION(500),
    MODEL_LOAD_FAILED(501),
    ;

    companion object {
        fun fromValue(value: Int): NativeErrorCode? = entries.firstOrNull { it.value == value }
    }
}
