package com.example.blocker

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

data class GuardPermissionStatus(
    val accessibilityEnabled: Boolean,
    val accessibilityServiceConnected: Boolean,
    val deviceAdminEnabled: Boolean,
    val notificationListenerEnabled: Boolean,
    val dndPolicyAccessGranted: Boolean,
    val overlayGranted: Boolean,
    val exactAlarmGranted: Boolean,
    val notificationsGranted: Boolean,
    val batteryOptimizationIgnored: Boolean
) {
    val isCoreBlockingReady: Boolean
        get() = accessibilityEnabled || accessibilityServiceConnected

    val readinessScore: Int
        get() = listOf(
            isCoreBlockingReady,
            deviceAdminEnabled,
            notificationListenerEnabled,
            dndPolicyAccessGranted,
            exactAlarmGranted,
            notificationsGranted,
            batteryOptimizationIgnored
        ).count { it }

    val totalChecks: Int = 7
}

class PermissionStatusChecker(private val context: Context) {

    fun check(): GuardPermissionStatus {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, FreedomDeviceAdminReceiver::class.java)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val exactAlarmGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        val overlayGranted = Settings.canDrawOverlays(context)
        val batteryIgnored = powerManager.isIgnoringBatteryOptimizations(context.packageName)

        return GuardPermissionStatus(
            accessibilityEnabled = isAccessibilityServiceEnabledInSettings(),
            accessibilityServiceConnected = FreedomAccessibilityService.isRunning.value,
            deviceAdminEnabled = dpm.isAdminActive(adminComponent),
            notificationListenerEnabled = isNotificationListenerEnabledInSettings() ||
                FreedomNotificationListenerService.isConnected.value,
            dndPolicyAccessGranted = notificationManager.isNotificationPolicyAccessGranted,
            overlayGranted = overlayGranted,
            exactAlarmGranted = exactAlarmGranted,
            notificationsGranted = notificationsGranted,
            batteryOptimizationIgnored = batteryIgnored
        )
    }

    private fun isAccessibilityServiceEnabledInSettings(): Boolean {
        return runCatching {
            val expectedComponent = ComponentName(context, FreedomAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val flattened = expectedComponent.flattenToString()
            val shortFlattened = expectedComponent.flattenToShortString()
            enabledServices.split(':').any { entry ->
                entry.equals(flattened, ignoreCase = true) ||
                    entry.equals(shortFlattened, ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    private fun isNotificationListenerEnabledInSettings(): Boolean {
        return runCatching {
            val expectedComponent = ComponentName(context, FreedomNotificationListenerService::class.java)
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val flattened = expectedComponent.flattenToString()
            val shortFlattened = expectedComponent.flattenToShortString()
            enabledListeners.split(':').any { entry ->
                entry.equals(flattened, ignoreCase = true) ||
                    entry.equals(shortFlattened, ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    fun createAccessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun createDeviceAdminIntent(): Intent {
        val adminComponent = ComponentName(context, FreedomDeviceAdminReceiver::class.java)
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Prevents uninstalling Freedom while Locked Mode is active. Freedom requests zero wipe or lock policies."
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createNotificationListenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun createDndPolicySettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun createOverlaySettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun createExactAlarmSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            createAppDetailsIntent()
        }
    }

    fun createBatteryOptimizationIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun createAppDetailsIntent(): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
