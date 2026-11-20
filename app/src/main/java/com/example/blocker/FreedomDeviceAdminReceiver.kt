package com.example.blocker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.example.FreedomApplication

/**
 * Minimal Device Administrator receiver solely to disable the OS "Uninstall"
 * button while enabled. Requests zero wipe, lock, or password policies.
 */
class FreedomDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val store = (context.applicationContext as? FreedomApplication)?.container?.fastStore
        val snapshot = store?.readSnapshot()
        return if (snapshot != null && snapshot.strictMode && snapshot.isActive) {
            "Freedom Strict Mode is currently active. Are you sure you want to remove uninstall protection?"
        } else {
            "Disabling Freedom Uninstall Protection allows uninstalling the app normally."
        }
    }
}
