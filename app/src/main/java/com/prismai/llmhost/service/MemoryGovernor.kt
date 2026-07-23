package com.prismai.llmhost.service

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class MemoryState(val level: Int) {
    NORMAL(0),
    WATCH(1),
    PRESSURE(2),
    CRITICAL(3);

    companion object {
        fun fromTrimLevel(trimLevel: Int): MemoryState = when (trimLevel) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> CRITICAL
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> PRESSURE
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> WATCH
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> PRESSURE
            else -> NORMAL
        }
    }
}

class MemoryGovernor(private val context: Context) : ComponentCallbacks2 {
    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memoryInfo = ActivityManager.MemoryInfo()
    private var pressureListener: ((MemoryState) -> Unit)? = null

    /** Register with the Application context for push-based ComponentCallbacks2 notifications. */
    fun register(listener: (MemoryState) -> Unit) {
        pressureListener = listener
        context.applicationContext.registerComponentCallbacks(this)
    }

    /** Unregister to avoid leaks. */
    fun unregister() {
        pressureListener = null
        context.applicationContext.unregisterComponentCallbacks(this)
    }

    // ComponentCallbacks2 — push-based, instant notification
    override fun onTrimMemory(level: Int) {
        val pushState = MemoryState.fromTrimLevel(level)
        if (pushState != MemoryState.NORMAL) {
            pressureListener?.invoke(pushState)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // No-op — we only care about memory
    }

    override fun onLowMemory() {
        pressureListener?.invoke(MemoryState.CRITICAL)
    }

    // Existing polling flow — keep as fallback for gradual pressure detection
    fun monitorMemory(pollIntervalMs: Long = 2000L): Flow<MemoryState> = flow {
        while (true) {
            activityManager.getMemoryInfo(memoryInfo)
            val availMb = memoryInfo.availMem / (1024 * 1024)
            val thresholdMb = memoryInfo.threshold / (1024 * 1024)
            val bufferMb = availMb - thresholdMb
            val state = when {
                memoryInfo.lowMemory || bufferMb < 100 -> MemoryState.CRITICAL
                bufferMb < 300 -> MemoryState.PRESSURE
                bufferMb < 600 -> MemoryState.WATCH
                else -> MemoryState.NORMAL
            }
            emit(state)
            val adaptiveDelay = when (state) {
                MemoryState.CRITICAL -> 1000L
                MemoryState.PRESSURE -> 2000L
                MemoryState.WATCH -> 2000L
                MemoryState.NORMAL -> 5000L
            }
            delay(adaptiveDelay)
        }
    }
}
