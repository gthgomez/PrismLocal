package com.prismai.llmhost.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThermalProtectionState {
    NORMAL,
    WARNING_THERMAL,
    WARNING_BATTERY,
    PAUSED_CRITICAL,
}

class ThermalBatteryGovernor(private val context: Context) {

    private val _protectionState = MutableStateFlow(ThermalProtectionState.NORMAL)
    val protectionState: StateFlow<ThermalProtectionState> = _protectionState.asStateFlow()

    private var isRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(cntx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 100

                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val tempCelsius = tempTenths / 10.0f

                evaluateSafety(batteryPct, tempCelsius)
            }
        }
    }

    fun startMonitoring() {
        if (isRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.addThermalStatusListener { status ->
                if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                    _protectionState.value = ThermalProtectionState.PAUSED_CRITICAL
                }
            }
        }
        isRegistered = true
    }

    fun stopMonitoring() {
        if (!isRegistered) return
        runCatching { context.unregisterReceiver(batteryReceiver) }
        isRegistered = false
    }

    fun shouldPauseInference(): Boolean {
        return _protectionState.value == ThermalProtectionState.PAUSED_CRITICAL
    }

    private fun evaluateSafety(batteryPct: Int, tempCelsius: Float) {
        when {
            tempCelsius >= 42.0f || batteryPct < 10 -> {
                _protectionState.value = ThermalProtectionState.PAUSED_CRITICAL
            }
            tempCelsius >= 38.0f -> {
                _protectionState.value = ThermalProtectionState.WARNING_THERMAL
            }
            batteryPct < 15 -> {
                _protectionState.value = ThermalProtectionState.WARNING_BATTERY
            }
            else -> {
                _protectionState.value = ThermalProtectionState.NORMAL
            }
        }
    }
}
