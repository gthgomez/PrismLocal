package com.prismai.llmhost.util

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ThermalGovernorState(
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val recommendedThreads: Int = 4,
    val isThrottled: Boolean = false,
    val isEmergency: Boolean = false,
    val statusLabel: String = "NORMAL"
)

/**
 * Adaptive Hardware Thermal Governor for mobile LLM inference.
 * Monitors Android PowerManager thermal status and adjusts CPU thread targets dynamically.
 * Incorporates a 10-second hysteresis cooldown to avoid rapid thermal thread flapping.
 */
class AdaptiveThermalGovernor(
    private val context: Context,
    private val defaultThreadCount: Int = 4,
    private val throttledThreadCount: Int = 2,
    private val cooldownMs: Long = 10_000L,
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "AdaptiveThermalGovernor"
    }

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _governorState = MutableStateFlow(
        ThermalGovernorState(
            recommendedThreads = defaultThreadCount
        )
    )
    val governorState: StateFlow<ThermalGovernorState> = _governorState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var cooldownJob: Job? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        startMonitoring()
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        stopMonitoring()
    }

    fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            if (thermalListener == null) {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    handleThermalStatusChanged(status)
                }
                thermalListener = listener
                try {
                    powerManager.addThermalStatusListener(
                        ContextCompat.getMainExecutor(context),
                        listener
                    )
                    handleThermalStatusChanged(powerManager.currentThermalStatus)
                    Log.d(TAG, "Thermal listener registered successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register thermal status listener", e)
                }
            }
        }
    }

    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            thermalListener?.let { listener ->
                try {
                    powerManager.removeThermalStatusListener(listener)
                    Log.d(TAG, "Thermal listener unregistered")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove thermal status listener", e)
                }
                thermalListener = null
            }
        }
        cooldownJob?.cancel()
    }

    private fun handleThermalStatusChanged(status: Int) {
        val (isThrottled, isEmergency, threads, label) = when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> {
                Tuple4(isThrottled = true, isEmergency = true, threads = throttledThreadCount, label = "EMERGENCY")
            }
            status >= PowerManager.THERMAL_STATUS_SEVERE -> {
                Tuple4(isThrottled = true, isEmergency = false, threads = throttledThreadCount, label = "THROTTLED")
            }
            status >= PowerManager.THERMAL_STATUS_MODERATE -> {
                Tuple4(isThrottled = false, isEmergency = false, threads = defaultThreadCount, label = "MODERATE")
            }
            else -> {
                Tuple4(isThrottled = false, isEmergency = false, threads = defaultThreadCount, label = "NORMAL")
            }
        }

        Log.i(TAG, "Thermal status changed: status=$status label=$label threads=$threads")

        if (isThrottled) {
            // Immediately throttle down threads for thermal relief
            cooldownJob?.cancel()
            _governorState.value = ThermalGovernorState(
                thermalStatus = status,
                recommendedThreads = threads,
                isThrottled = true,
                isEmergency = isEmergency,
                statusLabel = label
            )
        } else {
            // Apply hysteresis cooldown before scaling threads back up
            if (_governorState.value.isThrottled) {
                Log.i(TAG, "Thermal relief detected; starting ${cooldownMs}ms hysteresis cooldown")
                cooldownJob?.cancel()
                cooldownJob = scope.launch {
                    delay(cooldownMs)
                    _governorState.value = ThermalGovernorState(
                        thermalStatus = status,
                        recommendedThreads = defaultThreadCount,
                        isThrottled = false,
                        isEmergency = false,
                        statusLabel = label
                    )
                    Log.i(TAG, "Hysteresis cooldown complete; restored threads to $defaultThreadCount")
                }
            } else {
                _governorState.value = ThermalGovernorState(
                    thermalStatus = status,
                    recommendedThreads = defaultThreadCount,
                    isThrottled = false,
                    isEmergency = false,
                    statusLabel = label
                )
            }
        }
    }

    private data class Tuple4(
        val isThrottled: Boolean,
        val isEmergency: Boolean,
        val threads: Int,
        val label: String
    )
}
