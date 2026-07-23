package com.prismai.llmhost.ui
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight event bus for UI messages, transient events, and panel
 * navigation requests that don't warrant a persistent [StateFlow].
 */
class UiEventBus {

    // ── Panel navigation requests (one-shot, no replay) ────────────────

    private val _panelRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val panelRequests: SharedFlow<String> = _panelRequests.asSharedFlow()

    // ── Sticky UI message (deduplicated by value) ──────────────────────

    internal val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    // ── Transient event firehose (replay = 1 for late collectors) ──────

    private val _uiEvents = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val uiEvents: SharedFlow<String> = _uiEvents.asSharedFlow()

    // ── Convenience ────────────────────────────────────────────────────

    /** Emit a transient UI event and update the sticky message. */
    fun publish(message: String) {
        _uiMessage.value = message
        _uiEvents.tryEmit(message)
    }

    /** Clear the sticky message (e.g. after it has been displayed). */
    fun clearMessage(message: String) {
        if (_uiMessage.value == message) {
            _uiMessage.value = null
        }
    }

    /** Request a panel navigation. */
    fun requestPanel(panel: String) {
        _panelRequests.tryEmit(panel)
    }
}
