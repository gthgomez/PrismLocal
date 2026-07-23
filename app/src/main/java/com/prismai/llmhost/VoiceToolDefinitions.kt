package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

/**
 * Tool definitions for voice I/O. Separated to avoid merge conflicts during integration.
 *
 * These are registered alongside [AgentToolRegistry.definitions] but kept in a separate
 * file so the voice pipeline can be added or removed cleanly without touching the main
 * tool registry.
 */
object VoiceToolDefinitions {
    val VOICE_INPUT = AgentToolDefinition(
        name = "voice_input",
        description = "Listen for voice input from the user's microphone. Returns transcribed text. Requires on-device speech recognition to be available.",
        risk = AgentToolRisk.SAFE,
        argumentSchema = """{"type":"object","properties":{"timeout_seconds":{"type":"integer","description":"Max listening duration in seconds (default 10, max 30)"}},"required":[]}""",
        intRanges = mapOf("timeout_seconds" to AgentToolIntRange(3, 30)),
    )

    val SPEAK_OUTPUT = AgentToolDefinition(
        name = "speak_output",
        description = "Speak text aloud using the device's text-to-speech engine. Useful for voice responses.",
        risk = AgentToolRisk.SAFE,
        argumentSchema = """{"type":"object","properties":{"text":{"type":"string","description":"Text to speak aloud (max 1000 characters)"}},"required":["text"]}""",
        maxStringLengths = mapOf("text" to 1000),
    )

    val STOP_SPEAKING = AgentToolDefinition(
        name = "stop_speaking",
        description = "Stop any currently playing text-to-speech output.",
        risk = AgentToolRisk.SAFE,
        argumentSchema = """{"type":"object","properties":{}}""",
    )

    val ALL = listOf(VOICE_INPUT, SPEAK_OUTPUT, STOP_SPEAKING)
}
