package com.example.blocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.FreedomApplication
import com.example.MainActivity
import com.example.R
import com.example.domain.DndMode
import com.example.domain.SettingsWaitStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that:
 *  - Displays a non-bypassable persistent countdown notification (zero Stop/Pause actions).
 *  - Automatically activates & restores Android Do Not Disturb (DND) during sessions when enabled.
 *  - Triggers Notification Shield sweeps to hide notifications from blocked apps.
 *  - Runs a 2-second watchdog for multi-window/PiP enforcement and 3-minute Settings auto-relock.
 */
class FreedomMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null
    private var previousDndFilter: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SYNC
        if (action == ACTION_STOP) {
            restoreDndIfApplied()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val container = (applicationContext as? FreedomApplication)?.container
        if (container == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val snapshot = container.fastStore.readSnapshot()
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        if (!snapshot.isStillActive(nowWall, nowElapsed)) {
            restoreDndIfApplied()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildActiveNotification(
            label = snapshot.label,
            strictMode = snapshot.strictMode,
            remainingMs = snapshot.remainingMillis(nowWall, nowElapsed),
            fullBrowsingBlock = snapshot.fullInternetBrowsingBlock,
            webCategoryCount = snapshot.blockedWebCategories.size,
            settingsUnlockRemainingMs = SettingsWaitStateMachine.remainingUnlockMs(
                nowElapsed,
                snapshot.settingsUnlockedUntilElapsedMs
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID_ACTIVE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID_ACTIVE, notification)
        }

        applyDndIfEnabled(container.fastStore.isDndEnabled(), container.fastStore.getDndMode())
        FreedomNotificationListenerService.sweepNow()
        startCountdownLoop()
        return START_STICKY
    }

    private fun startCountdownLoop() {
        tickJob?.cancel()
        tickJob = serviceScope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var tickCount = 0
            while (isActive) {
                val container = (applicationContext as? FreedomApplication)?.container ?: break
                val snap = container.fastStore.readSnapshot()
                val nowWall = System.currentTimeMillis()
                val nowElapsed = SystemClock.elapsedRealtime()

                if (!snap.isStillActive(nowWall, nowElapsed)) {
                    restoreDndIfApplied()
                    container.repository.reconcileSessionState()
                    postSessionEndedAlert()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }

                // Clear expired 3-minute Settings unlock & immediately eject if still in Settings
                val unlockUntil = snap.settingsUnlockedUntilElapsedMs
                val unlockRemMs = SettingsWaitStateMachine.remainingUnlockMs(nowElapsed, unlockUntil)
                if (unlockUntil > 0L && unlockRemMs == 0L) {
                    container.fastStore.clearTemporarySettingsUnlock()
                    FreedomAccessibilityService.triggerImmediateWindowCheck()
                }

                // Every 2 seconds: enforce multi-window / PiP / Settings auto-relock
                FreedomAccessibilityService.triggerImmediateWindowCheck()

                // Every 10 seconds: refresh countdown notification & sweep blocked notifications
                if (tickCount % 5 == 0) {
                    val remaining = snap.remainingMillis(nowWall, nowElapsed)
                    nm.notify(
                        NOTIF_ID_ACTIVE,
                        buildActiveNotification(
                            label = snap.label,
                            strictMode = snap.strictMode,
                            remainingMs = remaining,
                            fullBrowsingBlock = snap.fullInternetBrowsingBlock,
                            webCategoryCount = snap.blockedWebCategories.size,
                            settingsUnlockRemainingMs = unlockRemMs
                        )
                    )
                    if (snap.hideBlockedNotifications) {
                        FreedomNotificationListenerService.sweepNow()
                    }
                }
                tickCount++
                delay(2_000L)
            }
        }
    }

    private fun applyDndIfEnabled(enabled: Boolean, mode: DndMode) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        if (enabled) {
            if (previousDndFilter == null) {
                previousDndFilter = nm.currentInterruptionFilter
            }
            val targetFilter = when (mode) {
                DndMode.PRIORITY_ONLY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                DndMode.ALARMS_ONLY -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            }
            runCatching { nm.setInterruptionFilter(targetFilter) }
        } else {
            restoreDndIfApplied()
        }
    }

    private fun restoreDndIfApplied() {
        val prev = previousDndFilter ?: return
        previousDndFilter = null
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            runCatching { nm.setInterruptionFilter(prev) }
        }
    }

    private fun buildActiveNotification(
        label: String,
        strictMode: Boolean,
        remainingMs: Long,
        fullBrowsingBlock: Boolean,
        webCategoryCount: Int,
        settingsUnlockRemainingMs: Long
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (strictMode) {
            getString(R.string.notif_active_title_strict)
        } else {
            getString(R.string.notif_active_title_normal)
        }

        val shields = buildList {
            add("Allowlist")
            if (fullBrowsingBlock) add("No Browsing")
            else if (webCategoryCount > 0) add("$webCategoryCount Web Filters")
        }.joinToString(" · ")

        val contentText = buildString {
            append("$label · ${formatDuration(remainingMs)} left")
            if (settingsUnlockRemainingMs > 0L) {
                append(" · Settings open ${formatDuration(settingsUnlockRemainingMs)}")
            } else {
                append(" · $shields")
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ACTIVE_SESSION)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    private fun postSessionEndedAlert() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.notif_session_ended_title))
            .setContentText(getString(R.string.notif_session_ended_body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(NOTIF_ID_ENDED, notif) }
    }

    override fun onDestroy() {
        restoreDndIfApplied()
        tickJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ACTIVE_SESSION = "freedom_active_session"
        const val CHANNEL_ALERTS = "freedom_alerts"
        private const val NOTIF_ID_ACTIVE = 1001
        private const val NOTIF_ID_ENDED = 1002
        private const val ACTION_SYNC = "com.example.freedom.MONITOR_SYNC"
        private const val ACTION_STOP = "com.example.freedom.MONITOR_STOP"

        fun ensureNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val activeChannel = NotificationChannel(
                CHANNEL_ACTIVE_SESSION,
                context.getString(R.string.channel_active_session_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_active_session_desc)
                setShowBadge(false)
            }
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_alerts_desc)
            }
            nm.createNotificationChannel(activeChannel)
            nm.createNotificationChannel(alertsChannel)
        }

        fun sync(context: Context) {
            val intent = Intent(context, FreedomMonitorService::class.java).apply {
                action = ACTION_SYNC
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FreedomMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }

        fun formatDuration(ms: Long): String {
            val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }
}
