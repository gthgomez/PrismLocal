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
import kotlinx.coroutines.cancelAndJoin
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
class BackgroundAgentManager(
    private val context: Context,
    private val executeTask: (suspend (BackgroundTask) -> String)? = null,
    private val cancelNativeGeneration: (suspend () -> Unit)? = null,
) {
    companion object {
        private const val TAG = "BackgroundAgentManager"
        private const val CHANNEL_BG_TASKS = "prism_bg_tasks"
        private const val CHANNEL_BG_PROGRESS = "prism_bg_progress"
        private const val NOTIFICATION_ID_BASE = 3000
        private const val MAX_QUEUED_TASKS = 5
        private const val LOW_BATTERY_THRESHOLD = 15
    }

    private val wakeLock: PowerManager.WakeLock?
    private val notificationManager: NotificationManager?
    private val _state = MutableStateFlow(BackgroundAgentState())
    val state: StateFlow<BackgroundAgentState> = _state.asStateFlow()
    private val bgScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var taskIdCounter = 0L
    private var activeTaskJob: kotlinx.coroutines.Job? = null
    @Volatile private var cancelInFlight = false

    private fun logD(tag: String, msg: String) { runCatching { Log.d(tag, msg) } }
    private fun logE(tag: String, msg: String, tr: Throwable? = null) { runCatching { Log.e(tag, msg, tr) } }
    private fun logW(tag: String, msg: String) { runCatching { Log.w(tag, msg) } }

    init {
        wakeLock = try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PrismLocal:BackgroundAgent"
            )?.apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            logE(TAG, "Failed to acquire wake lock", e)
            null
        }
        notificationManager = try {
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        } catch (e: Exception) {
            null
        }
        runCatching { createNotificationChannels() }
    }

    /**
     * Queue a task for background execution. Returns task ID.
     * Rejects if the queue is full (max [MAX_QUEUED_TASKS]).
     */
    fun enqueue(prompt: String): BackgroundTask? {
        val current = _state.value
        if (current.queuedTasks.size >= MAX_QUEUED_TASKS) {
            logW(TAG, "Task queue full, rejecting prompt: ${prompt.take(80)}")
            return null
        }
        val id = "bg_task_${++taskIdCounter}"
        val task = BackgroundTask(id = id, prompt = prompt)
        _state.value = current.copy(
            queuedTasks = current.queuedTasks + task
        )
        logD(TAG, "Enqueued task $id: ${prompt.take(80)}")
        processNextTask()
        return task
    }

    /** Start processing the queue. Acquires wake lock. */
    fun startBackgroundMode() {
        val current = _state.value
        if (current.isBackgroundMode) {
            processNextTask()
            return
        }

        wakeLock?.acquire(30_000L) // Initial timeout guards against dangling locks
        _state.value = current.copy(isBackgroundMode = true)
        showProgressNotification("Background agent active")
        logD(TAG, "Background mode started, wake lock acquired")
        processNextTask()
    }

    /** Process the next task in queue using [executeTask] */
    fun processNextTask() {
        if (_state.value.activeTask != null) return
        if (cancelInFlight) return
        if (!checkBudget()) {
            logW(TAG, "Resource budget constrained, holding background queue processing")
            return
        }
        val task = nextTask() ?: run {
            if (_state.value.isBackgroundMode && _state.value.queuedTasks.isEmpty()) {
                stopBackgroundMode()
            }
            return
        }

        if (!_state.value.isBackgroundMode) {
            startBackgroundMode()
        }

        activeTaskJob = bgScope.launch {
            try {
                wakeLock?.acquire(300_000L) // 5-minute wake lock per background task
                val summary = executeTask?.invoke(task) ?: "Background task executed"
                completeCurrentTask(summary)
            } catch (c: kotlinx.coroutines.CancellationException) {
                logD(TAG, "Background task ${task.id} cancelled")
                throw c
            } catch (e: Exception) {
                logE(TAG, "Background task ${task.id} failed", e)
                failCurrentTask(e.message ?: "Task execution error")
            } finally {
                releaseWakeLockSafely()
                activeTaskJob = null
                if (!cancelInFlight) {
                    processNextTask()
                }
            }
        }
    }

    /** Stop background mode. Releases wake lock. */
    fun stopBackgroundMode() {
        val current = _state.value
        if (!current.isBackgroundMode) return

        cancelInFlight = true
        bgScope.launch {
            try {
                activeTaskJob?.cancelAndJoin()
                activeTaskJob = null
                runCatching { cancelNativeGeneration?.invoke() }
                val latest = _state.value
                _state.value = latest.copy(isBackgroundMode = false, activeTask = null)
                releaseWakeLockSafely()
                cancelProgressNotification()
                logD(TAG, "Background mode stopped, wake lock released")
            } finally {
                cancelInFlight = false
            }
        }
    }

    /** Cancel a queued or running task */
    fun cancelTask(taskId: String): Boolean {
        val current = _state.value
        if (current.activeTask?.id == taskId) {
            val task = current.activeTask!!
            cancelInFlight = true
            bgScope.launch {
                try {
                    activeTaskJob?.cancelAndJoin()
                    activeTaskJob = null
                    runCatching { cancelNativeGeneration?.invoke() }
                    val latest = _state.value
                    _state.value = latest.copy(
                        activeTask = null,
                        queuedTasks = latest.queuedTasks.filterNot { it.id == taskId },
                        completedTasks = latest.completedTasks + task.copy(
                            status = BackgroundTaskStatus.CANCELLED
                        ),
                    )
                    releaseWakeLockSafely()
                } finally {
                    cancelInFlight = false
                    processNextTask()
                }
            }
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
        logD(TAG, "Task ${active.id} completed: ${summary.take(80)}")
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
        logD(TAG, "Task ${active.id} failed: ${error.take(80)}")
    }

    /** Check battery/thermal budget. Returns false if resources are too constrained. */
    fun checkBudget(): Boolean {
        val batteryOk = checkBattery()
        val thermalOk = checkThermal()
        _state.value = _state.value.copy(batteryOk = batteryOk, thermalOk = thermalOk)
        return batteryOk && thermalOk
    }

    /** Show notification for completed task */
    @android.annotation.SuppressLint("MissingPermission")
    fun notifyTaskComplete(task: BackgroundTask) {
        runCatching {
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
            notificationManager?.notify(notificationId, notification)
        }
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
            logE(TAG, "Error releasing wake lock", e)
        }
    }

    private fun checkBattery(): Boolean {
        return runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return true
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: return true
            if (level < 0 || scale <= 0) return true
            val percent = (level * 100.0 / scale).toInt()
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val isCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
            if (isCharging) true else percent >= LOW_BATTERY_THRESHOLD
        }.getOrDefault(true)
    }

    private fun checkThermal(): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            val status = powerManager.currentThermalStatus
            status < PowerManager.THERMAL_STATUS_SEVERE
        }.getOrDefault(true)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
        notificationManager?.createNotificationChannel(tasksChannel)
        notificationManager?.createNotificationChannel(progressChannel)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showProgressNotification(message: String) {
        runCatching {
            val notification = NotificationCompat.Builder(context, CHANNEL_BG_PROGRESS)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Agent Working")
                .setContentText(message)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            notificationManager?.notify(NOTIFICATION_ID_BASE, notification)
        }
    }

    private fun cancelProgressNotification() {
        runCatching {
            notificationManager?.cancel(NOTIFICATION_ID_BASE)
        }
    }
}
