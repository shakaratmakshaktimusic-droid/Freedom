package com.example.blocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Schedules exact wakeups via AlarmManager for manual session endings and
 * recurring schedule start/end transitions so sessions activate and deactivate
 * automatically even when the app is closed or the device is in Doze mode.
 */
class SessionAlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleSessionEnd(triggerAtWallMs: Long) {
        if (triggerAtWallMs <= System.currentTimeMillis()) return
        scheduleExactOrFallback(
            requestCode = REQ_SESSION_END,
            action = FreedomSystemReceiver.ACTION_EVALUATE_SESSIONS,
            triggerAtWallMs = triggerAtWallMs
        )
    }

    fun cancelSessionEnd() {
        alarmManager.cancel(createPendingIntent(REQ_SESSION_END, FreedomSystemReceiver.ACTION_EVALUATE_SESSIONS))
    }

    fun scheduleNextScheduleTransition(triggerAtWallMs: Long?) {
        val pi = createPendingIntent(REQ_SCHEDULE_TRANSITION, FreedomSystemReceiver.ACTION_EVALUATE_SESSIONS)
        if (triggerAtWallMs == null) {
            alarmManager.cancel(pi)
            return
        }
        val safeTrigger = maxOf(triggerAtWallMs, System.currentTimeMillis() + 1_000L)
        scheduleExactOrFallback(
            requestCode = REQ_SCHEDULE_TRANSITION,
            action = FreedomSystemReceiver.ACTION_EVALUATE_SESSIONS,
            triggerAtWallMs = safeTrigger
        )
    }

    private fun scheduleExactOrFallback(
        requestCode: Int,
        action: String,
        triggerAtWallMs: Long
    ) {
        val pi = createPendingIntent(requestCode, action)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        runCatching {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtWallMs, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtWallMs, pi)
            }
        }.onFailure {
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtWallMs, pi)
            }
        }
    }

    private fun createPendingIntent(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, FreedomSystemReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val REQ_SESSION_END = 4001
        private const val REQ_SCHEDULE_TRANSITION = 4002
    }
}
