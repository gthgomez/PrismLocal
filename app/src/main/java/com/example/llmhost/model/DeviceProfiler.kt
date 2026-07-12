package com.example.llmhost.model

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.example.llmhost.DeviceCapabilityProfile

/**
 * Captures device capability profiles: RAM, CPU, storage, battery, thermal.
 */
class DeviceProfiler(private val context: Context) {

    private var lastProfileCaptureTime = 0L
    private var cachedProfile: DeviceCapabilityProfile? = null

    fun deviceMemorySnapshot(): DeviceMemorySnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return DeviceMemorySnapshot(
            totalBytes = memoryInfo.totalMem,
            availableBytes = memoryInfo.availMem,
            lowMemory = memoryInfo.lowMemory,
        )
    }

    fun captureProfile(): DeviceCapabilityProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPercent = runCatching {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        }.getOrNull()
        val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            batteryManager.isCharging
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                val plugged = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                    plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                    plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
            }.getOrDefault(false)
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val stat = StatFs(context.filesDir.absolutePath)
        return DeviceCapabilityProfile(
            totalRamBytes = memoryInfo.totalMem,
            availableRamBytes = memoryInfo.availMem,
            lowMemory = memoryInfo.lowMemory,
            cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            androidSdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            storageFreeBytes = stat.availableBytes,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalStatusLabel(powerManager.currentThermalStatus)
            } else {
                null
            },
            memoryClassMb = activityManager.memoryClass,
            largeMemoryClassMb = activityManager.largeMemoryClass,
            appHeapMaxBytes = Runtime.getRuntime().maxMemory(),
        )
    }

    fun getCachedProfile(maxAgeMs: Long = 90000L): DeviceCapabilityProfile {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedProfile
        return if (cached != null && now - lastProfileCaptureTime < maxAgeMs) {
            cached
        } else {
            val fresh = captureProfile()
            cachedProfile = fresh
            lastProfileCaptureTime = now
            fresh
        }
    }

    companion object {
        private const val TAG = "DeviceProfiler"

        fun thermalStatusLabel(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }
}

data class DeviceMemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val lowMemory: Boolean,
) {
    val availableMb: Long = availableBytes / (1024L * 1024L)
}
