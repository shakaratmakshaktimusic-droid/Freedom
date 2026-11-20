package com.example.domain

/**
 * Pure Kotlin zero-VPN real-time blocking decision engine supporting:
 *  - Multi-window, split-screen, floating/freeform, and Picture-in-Picture (PiP) inspection
 *  - Allowlist app blocking + strict hard-blocking of non-essential system apps & installers
 *  - Locked Mode 5-minute continuous emergency wait -> 3-minute temporary unlock for Android Settings ONLY
 *  - Highest-precedence Website Allowlist (domain & URL path prefix exceptions)
 *  - Zero-VPN Full Internet Browsing Block across browsers, Chrome Custom Tabs, and in-app WebViews
 *  - 16 hidden website filter categories via sub-millisecond 64-bit CompactDomainIndex
 */
object BlockDecisionEngine {

    sealed class Decision {
        data object Allow : Decision()

        data class BlockApp(
            val packageName: String,
            val isMultiWindowOrPip: Boolean = false,
            val isFloatingWindow: Boolean = false,
            val isPictureInPicture: Boolean = false,
            val isSystemTamperLock: Boolean = false
        ) : Decision()

        data class BlockBrowsing(
            val packageName: String,
            val isInAppWebView: Boolean = false,
            val isMultiWindowOrPip: Boolean = false
        ) : Decision()

        data class BlockWebsite(
            val packageName: String,
            val categoryTitle: String,
            val isMultiWindowOrPip: Boolean = false
        ) : Decision()

        data class RequireSettingsWait(
            val packageName: String,
            val matchedRoute: String,
            val isMultiWindowOrPip: Boolean = false
        ) : Decision()
    }

    data class ObservedWindow(
        val packageName: String,
        val windowClassName: String? = null,
        val extractedHost: String? = null,
        val extractedFullUrl: String? = null,
        val isKnownBrowser: Boolean = false,
        val isCustomTabOrWebView: Boolean = false,
        val isPictureInPicture: Boolean = false,
        val isFloatingWindow: Boolean = false,
        val isSplitScreenActive: Boolean = false
    ) {
        val isMultiWindowOrPip: Boolean
            get() = isPictureInPicture || isFloatingWindow || isSplitScreenActive
    }

    data class ActiveBlockSnapshot(
        val isActive: Boolean,
        val sessionId: Long,
        val blocklistId: Long = 1L,
        val label: String,
        val endWallTimeMs: Long,
        val endElapsedRealtimeMs: Long,
        val strictMode: Boolean,
        val fullInternetBrowsingBlock: Boolean = false,
        val settingsUnlockedUntilElapsedMs: Long = 0L,
        val allowedPackages: Set<String> = emptySet(),
        val allowedWebPatterns: Set<String> = emptySet(),
        val customBlockedDomains: Set<String> = emptySet(),
        val blockedWebCategories: Set<WebsiteCategory> = emptySet(),
        val autoDetectUnknownSites: Boolean = true,
        val enableDndDuringSession: Boolean = false,
        val hideBlockedNotifications: Boolean = false
    ) {
        fun isStillActive(nowWallMs: Long, nowElapsedRealtimeMs: Long): Boolean {
            if (!isActive) return false
            val wallRemaining = nowWallMs < endWallTimeMs
            val monotonicRemaining =
                endElapsedRealtimeMs > 0L && nowElapsedRealtimeMs < endElapsedRealtimeMs
            return wallRemaining || monotonicRemaining
        }

        fun remainingMillis(nowWallMs: Long, nowElapsedRealtimeMs: Long): Long {
            if (!isActive) return 0L
            val wallRem = (endWallTimeMs - nowWallMs).coerceAtLeast(0L)
            val monoRem = if (endElapsedRealtimeMs > 0L) {
                (endElapsedRealtimeMs - nowElapsedRealtimeMs).coerceAtLeast(0L)
            } else {
                0L
            }
            return maxOf(wallRem, monoRem)
        }
    }

    /**
     * ONLY native Android Settings packages may open the 5-minute Emergency Waiting Room
     * during Locked Mode (unlocking for 3 minutes upon completion).
     */
    val ANDROID_SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.samsung.android.settings"
    )

    /**
     * Non-essential / tamper-capable system apps that are HARD-BLOCKED during Locked Mode
     * with NO emergency waiting-room bypass (prevents uninstall, force-stop, or app-store workarounds).
     */
    val HARD_BLOCKED_SYSTEM_PACKAGES = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.samsung.android.lool",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.oplus.battery",
        "com.vivo.permissionmanager",
        "com.iqoo.secure",
        "com.huawei.systemmanager",
        "com.android.vending", // Google Play Store (can uninstall apps)
        "com.sec.android.app.samsungapps", // Galaxy Store
        "com.xiaomi.mipicks",
        "com.heytap.market",
        "com.google.android.apps.wellbeing"
    )

    /**
     * Core survival OS packages that must ALWAYS remain exempt so emergency calls,
     * phone calls, lock screen, System UI, permission dialogs, and keyboards keep working.
     */
    val CORE_EXEMPT_PACKAGES = setOf(
        "com.android.systemui",
        "android",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.google.android.dialer",
        "com.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.incallui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey"
    )

    /**
     * Evaluates ALL visible interactive windows simultaneously (covering fullscreen,
     * unfocused split-screen panes, floating/freeform pop-ups, and Picture-in-Picture).
     * Priority order:
     *   1. Locked Mode Settings 5-Min Emergency Wait
     *   2. Hard-Blocked System Tamper Apps / Blocked Apps (including PiP/Split-Screen)
     *   3. Full Internet Browsing Block (browsers & in-app links/WebViews)
     *   4. Blocked Website Category / Custom Domain
     *   5. Allow
     */
    fun evaluateWindows(
        windows: List<ObservedWindow>,
        snapshot: ActiveBlockSnapshot,
        domainIndex: CompactDomainIndex,
        ownPackageName: String,
        dynamicExemptPackages: Set<String>,
        nowWallMs: Long,
        nowElapsedRealtimeMs: Long
    ): Decision {
        if (!snapshot.isStillActive(nowWallMs, nowElapsedRealtimeMs)) {
            return Decision.Allow
        }

        var firstAppBlock: Decision.BlockApp? = null
        var firstBrowsingBlock: Decision.BlockBrowsing? = null
        var firstWebBlock: Decision.BlockWebsite? = null

        for (window in windows) {
            val decision = evaluateSingleWindow(
                window = window,
                snapshot = snapshot,
                domainIndex = domainIndex,
                ownPackageName = ownPackageName,
                dynamicExemptPackages = dynamicExemptPackages,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs
            )
            when (decision) {
                is Decision.RequireSettingsWait -> return decision
                is Decision.BlockApp -> {
                    if (firstAppBlock == null ||
                        decision.isSystemTamperLock ||
                        decision.isPictureInPicture ||
                        decision.isFloatingWindow
                    ) {
                        firstAppBlock = decision
                    }
                }
                is Decision.BlockBrowsing -> {
                    if (firstBrowsingBlock == null) firstBrowsingBlock = decision
                }
                is Decision.BlockWebsite -> {
                    if (firstWebBlock == null) firstWebBlock = decision
                }
                Decision.Allow -> Unit
            }
        }

        return firstAppBlock ?: firstBrowsingBlock ?: firstWebBlock ?: Decision.Allow
    }

    fun evaluate(
        foregroundPackage: String,
        windowClassName: String?,
        extractedHost: String?,
        extractedFullUrl: String? = extractedHost,
        isKnownBrowser: Boolean = false,
        isCustomTabOrWebView: Boolean = false,
        snapshot: ActiveBlockSnapshot,
        domainIndex: CompactDomainIndex,
        ownPackageName: String,
        dynamicExemptPackages: Set<String>,
        nowWallMs: Long,
        nowElapsedRealtimeMs: Long,
        isPictureInPicture: Boolean = false,
        isFloatingWindow: Boolean = false,
        isSplitScreenActive: Boolean = false
    ): Decision {
        return evaluateWindows(
            windows = listOf(
                ObservedWindow(
                    packageName = foregroundPackage,
                    windowClassName = windowClassName,
                    extractedHost = extractedHost,
                    extractedFullUrl = extractedFullUrl,
                    isKnownBrowser = isKnownBrowser,
                    isCustomTabOrWebView = isCustomTabOrWebView,
                    isPictureInPicture = isPictureInPicture,
                    isFloatingWindow = isFloatingWindow,
                    isSplitScreenActive = isSplitScreenActive
                )
            ),
            snapshot = snapshot,
            domainIndex = domainIndex,
            ownPackageName = ownPackageName,
            dynamicExemptPackages = dynamicExemptPackages,
            nowWallMs = nowWallMs,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs
        )
    }

    private fun evaluateSingleWindow(
        window: ObservedWindow,
        snapshot: ActiveBlockSnapshot,
        domainIndex: CompactDomainIndex,
        ownPackageName: String,
        dynamicExemptPackages: Set<String>,
        nowElapsedRealtimeMs: Long
    ): Decision {
        val pkg = window.packageName
        if (pkg.isBlank()) return Decision.Allow

        // 1. Always permit Freedom itself + core survival OS/Emergency/Dialer/Launcher/IME packages
        if (pkg == ownPackageName) return Decision.Allow
        if (pkg in CORE_EXEMPT_PACKAGES || pkg in dynamicExemptPackages) return Decision.Allow

        // 2. Locked Mode: Hard-block tamper-capable & non-essential system packages (NO wait door)
        if (snapshot.strictMode && pkg in HARD_BLOCKED_SYSTEM_PACKAGES) {
            return Decision.BlockApp(
                packageName = pkg,
                isMultiWindowOrPip = window.isMultiWindowOrPip,
                isFloatingWindow = window.isFloatingWindow,
                isPictureInPicture = window.isPictureInPicture,
                isSystemTamperLock = true
            )
        }

        // 3. Locked Mode: Native Android Settings -> 5-Min Continuous Emergency Wait -> 3-Min Unlock
        if (snapshot.strictMode && pkg in ANDROID_SETTINGS_PACKAGES) {
            val isUnlocked = SettingsWaitStateMachine.isTemporarilyUnlocked(
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                unlockedUntilElapsedRealtimeMs = snapshot.settingsUnlockedUntilElapsedMs
            )
            return if (!isUnlocked) {
                Decision.RequireSettingsWait(
                    packageName = pkg,
                    matchedRoute = describeSettingsRoute(window.windowClassName),
                    isMultiWindowOrPip = window.isMultiWindowOrPip
                )
            } else {
                Decision.Allow
            }
        }

        // 4. Highest-Precedence Website Allowlist check (before Full Browsing Block & Categories!)
        val hasExtractedHost = !window.extractedHost.isNullOrBlank()
        val isExplicitlyAllowedWebsite = hasExtractedHost &&
            DomainBlockMatcher.isAllowedByWebsiteAllowlist(
                candidateHost = window.extractedHost,
                candidateFullUrl = window.extractedFullUrl,
                allowedPatterns = snapshot.allowedWebPatterns
            )

        // 5. Full Internet Browsing Block (Zero-VPN: blocks all browsers & in-app links/WebViews)
        //    unless the user is visiting an explicitly allowlisted website URL inside an allowlisted app/browser.
        val isBrowsingSurface = window.isKnownBrowser || window.isCustomTabOrWebView
        if (snapshot.fullInternetBrowsingBlock && isBrowsingSurface) {
            if (!isExplicitlyAllowedWebsite) {
                // If user has NO website allowlist exceptions, block browser immediately even on new-tab page;
                // if user HAS website allowlist exceptions, allow typing in an allowlisted browser until a non-allowed URL loads,
                // while ALWAYS blocking unallowlisted in-app Custom Tabs / WebViews.
                val shouldAllowBrowserTyping =
                    window.isKnownBrowser &&
                        pkg in snapshot.allowedPackages &&
                        !hasExtractedHost &&
                        snapshot.allowedWebPatterns.isNotEmpty()
                if (!shouldAllowBrowserTyping) {
                    return Decision.BlockBrowsing(
                        packageName = pkg,
                        isInAppWebView = window.isCustomTabOrWebView && !window.isKnownBrowser,
                        isMultiWindowOrPip = window.isMultiWindowOrPip
                    )
                }
            }
        }

        // 6. App Allowlist Enforcement (blocks all unlisted user apps and non-essential system apps)
        if (pkg !in snapshot.allowedPackages) {
            return Decision.BlockApp(
                packageName = pkg,
                isMultiWindowOrPip = window.isMultiWindowOrPip,
                isFloatingWindow = window.isFloatingWindow,
                isPictureInPicture = window.isPictureInPicture
            )
        }

        // 7. Website Category & Custom Blocked Site Filtering (on allowlisted browsers, Custom Tabs & WebViews)
        if (hasExtractedHost && !isExplicitlyAllowedWebsite) {
            val customMatch = DomainBlockMatcher.findMatchingBlockedDomain(
                candidateHost = window.extractedHost,
                blockedDomains = snapshot.customBlockedDomains
            )
            if (customMatch != null) {
                return Decision.BlockWebsite(
                    packageName = pkg,
                    categoryTitle = "Custom Filter",
                    isMultiWindowOrPip = window.isMultiWindowOrPip
                )
            }

            val categoryMatch = domainIndex.containsDomain(
                candidateHost = window.extractedHost,
                activeCategories = snapshot.blockedWebCategories
            )
            if (categoryMatch != null) {
                return Decision.BlockWebsite(
                    packageName = pkg,
                    categoryTitle = categoryMatch.title,
                    isMultiWindowOrPip = window.isMultiWindowOrPip
                )
            }
        }

        return Decision.Allow
    }

    private fun describeSettingsRoute(windowClassName: String?): String {
        val cls = windowClassName?.lowercase().orEmpty()
        return when {
            cls.contains("notification") -> "Notification Settings"
            cls.contains("installedapp") || cls.contains("appinfo") -> "App Info Settings"
            cls.contains("accessibility") -> "Accessibility Settings"
            cls.contains("deviceadmin") -> "Device Admin Settings"
            cls.contains("datetime") -> "Date & Time Settings"
            else -> "Android Settings"
        }
    }
}
