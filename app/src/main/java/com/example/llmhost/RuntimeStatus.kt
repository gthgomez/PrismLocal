package com.example.llmhost

enum class RuntimeStatus {
    IDLE,
    LOADING_MODEL,
    IMPORTING,
    GENERATING,
    CANCELLING,
    ERROR,
}
