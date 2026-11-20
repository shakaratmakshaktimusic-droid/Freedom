package com.example.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.FreedomApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles exact AlarmManager triggers for automatic schedule activation/deactivation,
 * reboot recovery (BOOT_COMPLETED / LOCKED_BOOT_COMPLETED), app upgrades, and
 * system clock/timezone changes.
 */
class FreedomSystemReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? FreedomApplication ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                        app.container.repository.onDeviceBooted()
                    }
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    ACTION_EVALUATE_SESSIONS -> {
                        app.container.repository.reconcileSessionState()
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_EVALUATE_SESSIONS = "com.example.freedom.ACTION_EVALUATE_SESSIONS"
    }
}
