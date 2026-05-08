package com.example.llmhost

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class MemoryState(val level: Int) {
    NORMAL(0),
    WATCH(1),
    PRESSURE(2),
    CRITICAL(3),
}

class MemoryGovernor(private val context: Context) {
    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memoryInfo = ActivityManager.MemoryInfo()

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
            delay(pollIntervalMs)
        }
    }
}
