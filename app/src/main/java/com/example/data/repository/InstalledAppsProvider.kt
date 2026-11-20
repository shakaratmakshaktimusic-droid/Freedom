package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val isAlwaysExempt: Boolean
)

/**
 * Discovers installed launchable apps via PackageManager <queries> without requiring
 * QUERY_ALL_PACKAGES, and identifies essential system components (launcher, dialer, IMEs)
 * that must always stay exempt from blocking to prevent locking out the phone.
 */
class InstalledAppsProvider(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    @Volatile
    private var cachedDynamicExempt: Set<String>? = null

    fun getDynamicExemptPackages(forceRefresh: Boolean = false): Set<String> {
        if (!forceRefresh) {
            cachedDynamicExempt?.let { return it }
        }
        val exempt = mutableSetOf<String>()
        exempt.add(context.packageName)

        // Default & installed Home launchers (so user is never trapped outside home screen)
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .forEach { exempt.add(it) }
            pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { exempt.add(it) }
        }

        // Default Phone dialer (emergency & voice calls must always work)
        runCatching {
            val dialIntent = Intent(Intent.ACTION_DIAL)
            pm.resolveActivity(dialIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { exempt.add(it) }
        }

        // Active / enabled Input Methods (keyboards)
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.forEach { imi ->
                exempt.add(imi.packageName)
            }
        }

        cachedDynamicExempt = exempt
        return exempt
    }

    suspend fun loadLaunchableApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val exempt = getDynamicExemptPackages(forceRefresh = true)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(launcherIntent, 0)

        val seen = LinkedHashMap<String, InstalledAppInfo>()
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg == context.packageName) continue
            if (seen.containsKey(pkg)) continue

            val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            val isSystem = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val label = runCatching { info.loadLabel(pm)?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: pkg

            seen[pkg] = InstalledAppInfo(
                packageName = pkg,
                label = label,
                isSystemApp = isSystem,
                isAlwaysExempt = pkg in exempt
            )
        }

        seen.values.sortedWith(
            compareBy<InstalledAppInfo> { it.isAlwaysExempt }
                .thenBy { it.label.lowercase() }
        )
    }

    fun getAppLabel(packageName: String): String {
        return runCatching {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrElse { packageName }
    }

    fun loadAppIcon(packageName: String): Drawable? {
        return runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
    }

    fun getLaunchIntent(packageName: String): Intent? {
        return pm.getLaunchIntentForPackage(packageName)
    }
}
