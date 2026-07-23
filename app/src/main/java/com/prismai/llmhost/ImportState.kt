package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

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
