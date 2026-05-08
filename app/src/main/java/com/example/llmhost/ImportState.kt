package com.example.llmhost

sealed class ImportState {
    data object Idle : ImportState()
    data class Running(
        val fileName: String,
        val bytesCopied: Long,
        val totalBytes: Long?,
    ) : ImportState()
    data class Success(
        val modelId: String,
        val bytes: Long,
        val sha256: String,
    ) : ImportState()
    data class Failure(
        val message: String,
        val code: String,
    ) : ImportState()
    data object Cancelled : ImportState()
}
