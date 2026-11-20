package com.example.blocker

import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.FreedomApplication
import com.example.domain.BlockDecisionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Suppresses distracting notifications from non-allowlisted apps while a Freedom
 * focus session is active and "Disable Notifications" is enabled in Blocking Settings.
 *
 * Essential system/phone/emergency notifications and notifications from allowlisted apps
 * are never suppressed.
 */
class FreedomNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        instance = this
        sweepBlockedNotificationsIfActive()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isConnected.value = false
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (shouldSuppress(posted.packageName)) {
            runCatching { cancelNotification(posted.key) }
        }
    }

    fun sweepBlockedNotificationsIfActive() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        for (sbn in active) {
            if (shouldSuppress(sbn.packageName)) {
                runCatching { cancelNotification(sbn.key) }
            }
        }
    }

    private fun shouldSuppress(pkg: String): Boolean {
        val container = (applicationContext as? FreedomApplication)?.container ?: return false
        val snapshot = container.fastStore.readSnapshot()
        if (!snapshot.hideBlockedNotifications) return false
        if (!snapshot.isStillActive(System.currentTimeMillis(), SystemClock.elapsedRealtime())) return false
        if (pkg == packageName) return false
        if (pkg in BlockDecisionEngine.CORE_EXEMPT_PACKAGES) return false
        if (pkg in container.installedAppsProvider.getDynamicExemptPackages()) return false
        if (pkg in snapshot.allowedPackages) return false
        return true
    }

    companion object {
        @Volatile
        private var instance: FreedomNotificationListenerService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun sweepNow() {
            instance?.sweepBlockedNotificationsIfActive()
        }
    }
}
