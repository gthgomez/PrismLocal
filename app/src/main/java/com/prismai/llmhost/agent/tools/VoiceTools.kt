package com.prismai.llmhost.agent.tools
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import com.prismai.llmhost.ui.ServiceUiState
import kotlinx.coroutines.delay
import org.json.JSONObject

class VoiceTools(
    private val voiceIoManager: VoiceIoManager,
    private val uiState: ServiceUiState,
) {
    suspend fun voiceInput(call: AgentToolCall): AgentToolResult {
        val timeoutSeconds = call.arguments.optInt("timeout_seconds", 10).coerceIn(3, 30)
        if (!voiceIoManager.isSttAvailable()) {
            return toolFailure(call, AgentToolErrorCode.FAILED,
                "On-device speech recognition not available. Install a speech recognition language pack.")
        }
        uiState._voiceInputResult.value = null
        uiState._voiceState.value = uiState._voiceState.value.copy(isListening = true)
        if (!voiceIoManager.startListening()) {
            uiState._voiceState.value = uiState._voiceState.value.copy(isListening = false)
            return toolFailure(call, AgentToolErrorCode.FAILED, "Failed to start speech recognition.")
        }
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L)
        var result: String? = null
        while (System.currentTimeMillis() < deadline && result == null) {
            result = uiState._voiceInputResult.value
            if (result == null) delay(200)
        }
        voiceIoManager.stopListening()
        uiState._voiceState.value = uiState._voiceState.value.copy(isListening = false, partialTranscript = null)
        val finalResult = uiState._voiceInputResult.value
        uiState._voiceInputResult.value = null
        return if (finalResult != null) {
            toolSuccess(call, "Voice captured: ${finalResult.take(120)}",
                JSONObject().put("transcript", finalResult))
        } else {
            toolFailure(call, AgentToolErrorCode.FAILED, "No speech detected within $timeoutSeconds seconds.")
        }
    }

    suspend fun speakOutput(call: AgentToolCall): AgentToolResult {
        val text = call.arguments.optString("text").take(1000)
        if (text.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "Text to speak is empty")
        if (!voiceIoManager.isTtsAvailable()) {
            voiceIoManager.initTts()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 2000L && !voiceIoManager.isTtsAvailable()) {
                delay(100)
            }
        }
        if (!voiceIoManager.isTtsAvailable()) {
            return toolFailure(call, AgentToolErrorCode.FAILED, "TTS engine not available.")
        }
        voiceIoManager.speak(text)
        uiState._voiceState.value = uiState._voiceState.value.copy(isSpeaking = true)
        return toolSuccess(call, "Speaking: ${text.take(80)}", JSONObject().put("text_length", text.length))
    }

    suspend fun stopSpeaking(call: AgentToolCall): AgentToolResult {
        voiceIoManager.stopSpeaking()
        uiState._voiceState.value = uiState._voiceState.value.copy(isSpeaking = false)
        return toolSuccess(call, "Speech output stopped")
    }
}
