package com.example.blocker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

/**
 * Clean, non-intrusive heads-up notification alert shown when Freedom evicts a
 * blocked app or website—matching the sleek notification banner style without
 * flashing a jarring full-screen overlay on every tap.
 */
object FreedomBlockNotifier {

    const val CHANNEL_ID = "freedom_block_alerts"
    private const val NOTIFICATION_ID = 4102

    @Volatile
    private var lastAlertElapsedMs: Long = 0L

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Blocked App & Website Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Shows a clean focus reminder banner when Freedom blocks a distracting app or website."
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun showBlockedAlert(
        context: Context,
        message: String = "Blocked by Freedom. Do what matters.",
        force: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val now = SystemClock.elapsedRealtime()
        if (!force && (now - lastAlertElapsedMs) < 900L) return
        lastAlertElapsedMs = now

        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Freedom")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setSilent(true)
            .setTimeoutAfter(4_500L)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            nm.notify(NOTIFICATION_ID, notification)
        }
    }
}
