package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BackgroundTask(
    val id: String,
    val prompt: String,
    val createdAt: Long = System.currentTimeMillis(),
    val status: BackgroundTaskStatus = BackgroundTaskStatus.QUEUED,
    val resultSummary: String? = null,
)

enum class BackgroundTaskStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

data class BackgroundAgentState(
    val isBackgroundMode: Boolean = false,
    val activeTask: BackgroundTask? = null,
    val queuedTasks: List<BackgroundTask> = emptyList(),
    val completedTasks: List<BackgroundTask> = emptyList(),
    val batteryOk: Boolean = true,
    val thermalOk: Boolean = true,
)

/**
 * Manages background agent execution — task queue, wake locks, notifications.
 * The agent can continue working after the app is backgrounded or screen locked.
 *
 * Acquires a PARTIAL_WAKE_LOCK to keep the CPU running while the screen sleeps
 * during background task execution. Does NOT require a manifest permission.
 *
 * Battery check is best-effort — never blocks foreground work on battery state.
 * Max 5 queued tasks to prevent resource exhaustion.
 */
class BackgroundAgentManager(private val context: Context) {
    companion object {
        private const val TAG = "BackgroundAgentManager"
        private const val CHANNEL_BG_TASKS = "prism_bg_tasks"
        private const val CHANNEL_BG_PROGRESS = "prism_bg_progress"
        private const val NOTIFICATION_ID_BASE = 3000
        private const val MAX_QUEUED_TASKS = 5
        private const val LOW_BATTERY_THRESHOLD = 15
    }

    private val wakeLock: PowerManager.WakeLock?
    private val notificationManager: NotificationManager
    private val _state = MutableStateFlow(BackgroundAgentState())
    val state: StateFlow<BackgroundAgentState> = _state.asStateFlow()
    private val bgScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var taskIdCounter = 0L

    init {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = try {
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PrismLocal:BackgroundAgent"
            ).apply {
                setReferenceCounted(false)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to acquire wake lock", e)
            null
        }
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
    }

    /**
     * Queue a task for background execution. Returns task ID.
     * Rejects if the queue is full (max [MAX_QUEUED_TASKS]).
     */
    fun enqueue(prompt: String): BackgroundTask? {
        val current = _state.value
        if (current.queuedTasks.size >= MAX_QUEUED_TASKS) {
            Log.w(TAG, "Task queue full, rejecting prompt: ${prompt.take(80)}")
            return null
        }
        val id = "bg_task_${++taskIdCounter}"
        val task = BackgroundTask(id = id, prompt = prompt)
        _state.value = current.copy(
            queuedTasks = current.queuedTasks + task
        )
        Log.d(TAG, "Enqueued task $id: ${prompt.take(80)}")
        return task
    }

    /** Start processing the queue. Acquires wake lock. */
    fun startBackgroundMode() {
        val current = _state.value
        if (current.isBackgroundMode) return

        wakeLock?.acquire(30_000L) // 30-second timeout guards against dangling locks
        _state.value = current.copy(isBackgroundMode = true)
        showProgressNotification("Background agent active")
        Log.d(TAG, "Background mode started, wake lock acquired")
    }

    /** Stop background mode. Releases wake lock. */
    fun stopBackgroundMode() {
        val current = _state.value
        if (!current.isBackgroundMode) return

        _state.value = current.copy(isBackgroundMode = false, activeTask = null)
        releaseWakeLockSafely()
        cancelProgressNotification()
        Log.d(TAG, "Background mode stopped, wake lock released")
    }

    /** Cancel a queued or running task */
    fun cancelTask(taskId: String): Boolean {
        val current = _state.value
        if (current.activeTask?.id == taskId) {
            _state.value = current.copy(
                activeTask = null,
                queuedTasks = current.queuedTasks.filterNot { it.id == taskId },
                completedTasks = current.completedTasks + current.activeTask!!.copy(
                    status = BackgroundTaskStatus.CANCELLED
                ),
            )
            return true
        }
        val wasInQueue = current.queuedTasks.any { it.id == taskId }
        if (wasInQueue) {
            val task = current.queuedTasks.first { it.id == taskId }
            _state.value = current.copy(
                queuedTasks = current.queuedTasks.filterNot { it.id == taskId },
                completedTasks = current.completedTasks + task.copy(
                    status = BackgroundTaskStatus.CANCELLED
                ),
            )
            return true
        }
        return false
    }

    /** Mark the active task as complete with summary */
    fun completeCurrentTask(summary: String) {
        val current = _state.value
        val active = current.activeTask ?: return
        val completed = active.copy(
            status = BackgroundTaskStatus.COMPLETED,
            resultSummary = summary,
        )
        _state.value = current.copy(
            activeTask = null,
            completedTasks = current.completedTasks + completed,
        )
        notifyTaskComplete(completed)
        Log.d(TAG, "Task ${active.id} completed: ${summary.take(80)}")
    }

    /** Mark the active task as failed */
    fun failCurrentTask(error: String) {
        val current = _state.value
        val active = current.activeTask ?: return
        val failed = active.copy(
            status = BackgroundTaskStatus.FAILED,
            resultSummary = error,
        )
        _state.value = current.copy(
            activeTask = null,
            completedTasks = current.completedTasks + failed,
        )
        notifyTaskComplete(failed)
        Log.d(TAG, "Task ${active.id} failed: ${error.take(80)}")
    }

    /** Check battery/thermal budget. Returns false if resources are too constrained. */
    fun checkBudget(): Boolean {
        val batteryOk = checkBattery()
        val thermalOk = checkThermal()
        _state.value = _state.value.copy(batteryOk = batteryOk, thermalOk = thermalOk)
        return batteryOk && thermalOk
    }

    /** Show notification for completed task */
    fun notifyTaskComplete(task: BackgroundTask) {
        val title = when (task.status) {
            BackgroundTaskStatus.COMPLETED -> "Agent task complete"
            BackgroundTaskStatus.FAILED -> "Agent task failed"
            BackgroundTaskStatus.CANCELLED -> "Agent task cancelled"
            else -> "Agent task"
        }
        val body = task.resultSummary?.take(100) ?: "No summary available"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_BG_TASKS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationId = NOTIFICATION_ID_BASE + (task.id.hashCode() % 1000).coerceAtLeast(0)
        notificationManager.notify(notificationId, notification)
    }

    /** Get the next queued task, or null if the queue is empty */
    fun nextTask(): BackgroundTask? {
        val current = _state.value
        if (current.queuedTasks.isEmpty() || current.activeTask != null) return null
        val next = current.queuedTasks.first()
        _state.value = current.copy(
            activeTask = next,
            queuedTasks = current.queuedTasks.drop(1),
        )
        return next
    }

    /** Release resources */
    fun shutdown() {
        stopBackgroundMode()
        bgScope.cancel()
    }

    private fun releaseWakeLockSafely() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock!!.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }

    private fun checkBattery(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return true
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: return true
        if (level < 0 || scale <= 0) return true
        val percent = (level * 100.0 / scale).toInt()
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val isCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        // Allow if charging, only pause if battery is critically low and not charging
        return if (isCharging) true else percent >= LOW_BATTERY_THRESHOLD
    }

    private fun checkThermal(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val status = powerManager.currentThermalStatus
        // Pause on severe+ thermal states (MODERATE is acceptable for background processing)
        return status < PowerManager.THERMAL_STATUS_SEVERE
    }

    private fun createNotificationChannels() {
        val tasksChannel = NotificationChannel(
            CHANNEL_BG_TASKS,
            "Background Tasks",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifications for completed and failed background agent tasks"
        }
        val progressChannel = NotificationChannel(
            CHANNEL_BG_PROGRESS,
            "Agent Working",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing background agent work indicator"
        }
        notificationManager.createNotificationChannel(tasksChannel)
        notificationManager.createNotificationChannel(progressChannel)
    }

    private fun showProgressNotification(message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_BG_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Agent Working")
            .setContentText(message)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notificationManager.notify(NOTIFICATION_ID_BASE, notification)
    }

    private fun cancelProgressNotification() {
        notificationManager.cancel(NOTIFICATION_ID_BASE)
    }
}
