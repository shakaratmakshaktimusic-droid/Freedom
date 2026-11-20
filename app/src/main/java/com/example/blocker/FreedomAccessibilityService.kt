package com.example.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.FreedomApplication
import com.example.domain.BlockDecisionEngine
import com.example.domain.DomainBlockMatcher
import com.example.domain.UnknownSiteClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pure zero-VPN real-time blocking engine powered by Android AccessibilityService.
 *
 * Enforces:
 *  1. All-window multi-window / split-screen / floating pop-up / Picture-in-Picture scanning.
 *  2. Allowlist app blocking + Locked Mode hard-blocking of non-essential system apps & installers.
 *  3. Locked Mode 5-minute continuous emergency wait -> 3-minute auto-relock for Android Settings ONLY.
 *  4. Highest-precedence Website Allowlist (domain & URL path prefix exceptions).
 *  5. Zero-VPN Full Internet Browsing Block across browsers, Chrome Custom Tabs, & in-app WebViews.
 *  6. 16 hidden website categories via <0.01ms 64-bit CompactDomainIndex.
 *  7. Lightweight (<2ms) bounded UnknownSiteClassifier running once per newly navigated unknown URL,
 *     silently auto-learning newly discovered sites into hidden 64-bit hashes.
 */
class FreedomAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var overlayManager: FreedomOverlayManager

    @Volatile
    private var lastEnforcementKey: String = ""

    @Volatile
    private var lastEnforcementTimeMs: Long = 0L

    @Volatile
    private var lastContentScanMs: Long = 0L

    @Volatile
    private var lastClassifiedUrlKey: String = ""

    private var pipShieldActive: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager = FreedomOverlayManager(this)
        _isRunning.value = true
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType

        // Throttle rapid scroll/content events so CPU usage stays near zero
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastContentScanMs < 420L) return
            lastContentScanMs = now
        }

        val eventPkg = event.packageName?.toString()
        val eventClass = event.className?.toString()
        scanAllWindowsAndEnforce(eventPkg, eventClass)
    }

    fun recheckActiveWindowsNow() {
        scanAllWindowsAndEnforce(null, null)
    }

    private fun scanAllWindowsAndEnforce(
        triggerPackage: String?,
        triggerClassName: String?
    ) {
        val container = (applicationContext as? FreedomApplication)?.container ?: return
        val snapshot = container.fastStore.readSnapshot()
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        if (!snapshot.isStillActive(nowWall, nowElapsed)) {
            if (pipShieldActive) {
                overlayManager.dismiss()
                pipShieldActive = false
            }
            return
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val allWindows = runCatching { windows }.getOrNull().orEmpty()
        val hasSplitDivider = allWindows.any {
            it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
        }

        val observedWindows = mutableListOf<BlockDecisionEngine.ObservedWindow>()
        var pipNodeForDismiss: AccessibilityNodeInfo? = null
        var webRootForClassifier: Pair<AccessibilityNodeInfo, BrowserUrlExtractor.WebInspectionResult>? = null

        val shouldInspectWebContent =
            snapshot.fullInternetBrowsingBlock || snapshot.blockedWebCategories.isNotEmpty() || snapshot.customBlockedDomains.isNotEmpty()

        for (win in allWindows) {
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { win.root }.getOrNull()
            val pkg = root?.packageName?.toString() ?: continue
            if (pkg.isBlank()) continue

            val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                win.isInPictureInPictureMode
            } else {
                false
            }

            val bounds = Rect()
            win.getBoundsInScreen(bounds)
            val isFloating = !isPip && !hasSplitDivider &&
                bounds.width() > 0 && bounds.height() > 0 &&
                (bounds.width() < (screenWidth * 0.88f) && bounds.height() < (screenHeight * 0.78f))

            val classForWindow = if (pkg == triggerPackage) triggerClassName else null
            val webInspection = if (shouldInspectWebContent && (pkg in snapshot.allowedPackages || BrowserUrlExtractor.isBrowserPackage(pkg))) {
                BrowserUrlExtractor.inspectWindow(root, pkg, classForWindow)
            } else {
                BrowserUrlExtractor.WebInspectionResult(isKnownBrowser = BrowserUrlExtractor.isBrowserPackage(pkg))
            }

            if (webRootForClassifier == null && root != null && !webInspection.extractedHost.isNullOrBlank()) {
                webRootForClassifier = root to webInspection
            }

            if (isPip && pkg !in snapshot.allowedPackages && pkg != packageName) {
                pipNodeForDismiss = root
            }

            observedWindows.add(
                BlockDecisionEngine.ObservedWindow(
                    packageName = pkg,
                    windowClassName = classForWindow,
                    extractedHost = webInspection.extractedHost,
                    extractedFullUrl = webInspection.extractedFullUrl,
                    isKnownBrowser = webInspection.isKnownBrowser,
                    isCustomTabOrWebView = webInspection.isCustomTabOrWebView,
                    isPictureInPicture = isPip,
                    isFloatingWindow = isFloating,
                    isSplitScreenActive = hasSplitDivider
                )
            )
        }

        // Fallback if window enumeration returned empty
        if (observedWindows.isEmpty() && !triggerPackage.isNullOrBlank()) {
            val root = runCatching { rootInActiveWindow }.getOrNull()
            val webInspection = if (shouldInspectWebContent) {
                BrowserUrlExtractor.inspectWindow(root, triggerPackage, triggerClassName)
            } else {
                BrowserUrlExtractor.WebInspectionResult(isKnownBrowser = BrowserUrlExtractor.isBrowserPackage(triggerPackage))
            }
            if (root != null && !webInspection.extractedHost.isNullOrBlank()) {
                webRootForClassifier = root to webInspection
            }
            observedWindows.add(
                BlockDecisionEngine.ObservedWindow(
                    packageName = triggerPackage,
                    windowClassName = triggerClassName,
                    extractedHost = webInspection.extractedHost,
                    extractedFullUrl = webInspection.extractedFullUrl,
                    isKnownBrowser = webInspection.isKnownBrowser,
                    isCustomTabOrWebView = webInspection.isCustomTabOrWebView
                )
            )
        }

        if (observedWindows.isEmpty()) return

        val dynamicExempt = container.installedAppsProvider.getDynamicExemptPackages()
        var decision = BlockDecisionEngine.evaluateWindows(
            windows = observedWindows,
            snapshot = snapshot,
            domainIndex = container.domainIndex,
            ownPackageName = packageName,
            dynamicExemptPackages = dynamicExempt,
            nowWallMs = nowWall,
            nowElapsedRealtimeMs = nowElapsed
        )

        // Stage 2: Lightweight (<2ms) UnknownSiteClassifier — runs ONLY once per newly navigated unknown URL
        if (decision is BlockDecisionEngine.Decision.Allow &&
            snapshot.autoDetectUnknownSites &&
            snapshot.blockedWebCategories.isNotEmpty()
        ) {
            val pair = webRootForClassifier
            if (pair != null) {
                val (root, webInfo) = pair
                val host = webInfo.extractedHost
                val fullUrl = webInfo.extractedFullUrl ?: host
                if (!host.isNullOrBlank() &&
                    !DomainBlockMatcher.isAllowedByWebsiteAllowlist(host, fullUrl, snapshot.allowedWebPatterns)
                ) {
                    val urlKey = "${host}:${fullUrl?.hashCode()}"
                    if (urlKey != lastClassifiedUrlKey) {
                        lastClassifiedUrlKey = urlKey
                        val snippets = BrowserUrlExtractor.collectBoundedPageSnippets(root, maxSnippets = 40)
                        val match = UnknownSiteClassifier.classify(
                            host = host,
                            fullUrl = fullUrl ?: host,
                            visibleTextSnippets = snippets,
                            activeCategories = snapshot.blockedWebCategories
                        )
                        if (match != null) {
                            val hash64 = DomainBlockMatcher.fnv1a64(match.normalizedHost)
                            container.domainIndex.addAutoLearnedHash(match.category, hash64)
                            serviceScope.launch {
                                container.repository.recordAutoLearnedDomainHash(hash64, match.category)
                            }
                            val pkg = root.packageName?.toString() ?: triggerPackage.orEmpty()
                            decision = BlockDecisionEngine.Decision.BlockWebsite(
                                packageName = pkg,
                                categoryTitle = "${match.category.title} (Auto-Detected)",
                                isMultiWindowOrPip = hasSplitDivider
                            )
                        }
                    }
                }
            }
        }

        when (decision) {
            is BlockDecisionEngine.Decision.Allow -> {
                if (pipShieldActive) {
                    overlayManager.dismiss()
                    pipShieldActive = false
                }
            }

            is BlockDecisionEngine.Decision.BlockApp -> {
                if (decision.isPictureInPicture) {
                    attemptDismissPipWindow(pipNodeForDismiss)
                }
                enforceBlock(
                    enforcementKey = "app:${decision.packageName}:${decision.isPictureInPicture}",
                    mode = BlockOverlayActivity.MODE_APP_BLOCKED,
                    blockedPackage = decision.packageName,
                    matchedReason = when {
                        decision.isSystemTamperLock -> "System app locked during Locked Mode"
                        decision.isPictureInPicture -> "Blocked Picture-in-Picture window"
                        decision.isMultiWindowOrPip -> "Blocked split-screen or floating window"
                        else -> "Blocked by Allowlist"
                    },
                    subtitleOverride = if (decision.isSystemTamperLock) {
                        "Non-essential system apps and installers stay locked until your Locked Mode session finishes."
                    } else null,
                    snapshot = snapshot,
                    usePersistentOverlay = decision.isPictureInPicture,
                    closeViaBackOnly = false
                )
            }

            is BlockDecisionEngine.Decision.BlockBrowsing -> {
                enforceBlock(
                    enforcementKey = "browse:${decision.packageName}:${decision.isInAppWebView}",
                    mode = BlockOverlayActivity.MODE_WEBSITE_BLOCKED,
                    blockedPackage = decision.packageName,
                    matchedReason = if (decision.isInAppWebView) {
                        "In-app web link blocked"
                    } else {
                        "Full internet browsing block active"
                    },
                    subtitleOverride = "Full internet browsing is blocked during this focus session. Other allowlisted apps still have internet access.",
                    snapshot = snapshot,
                    usePersistentOverlay = false,
                    closeViaBackOnly = decision.isInAppWebView
                )
            }

            is BlockDecisionEngine.Decision.BlockWebsite -> {
                enforceBlock(
                    enforcementKey = "web:${decision.packageName}:${decision.categoryTitle}",
                    mode = BlockOverlayActivity.MODE_WEBSITE_BLOCKED,
                    blockedPackage = decision.packageName,
                    matchedReason = "Website blocked (${decision.categoryTitle})",
                    subtitleOverride = "Blocked by your active ${decision.categoryTitle} filter. Add a specific URL to Allowed Websites outside Locked Mode if needed.",
                    snapshot = snapshot,
                    usePersistentOverlay = false,
                    closeViaBackOnly = true
                )
            }

            is BlockDecisionEngine.Decision.RequireSettingsWait -> {
                enforceBlock(
                    enforcementKey = "settings_wait:${decision.packageName}",
                    mode = BlockOverlayActivity.MODE_SETTINGS_WAITING_ROOM,
                    blockedPackage = decision.packageName,
                    matchedReason = "Emergency Settings wait (${decision.matchedRoute})",
                    subtitleOverride = decision.matchedRoute,
                    snapshot = snapshot,
                    usePersistentOverlay = false,
                    closeViaBackOnly = false
                )
            }
        }
    }

    private fun attemptDismissPipWindow(pipRoot: AccessibilityNodeInfo?) {
        if (pipRoot == null) return
        val closeLabels = listOf("close", "dismiss", "exit picture-in-picture")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(pipRoot)
        var visited = 0
        while (queue.isNotEmpty() && visited < 24) {
            val node = queue.removeFirst()
            visited++
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()
            if (node.isClickable && closeLabels.any { desc.contains(it) || text.contains(it) }) {
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                return
            }
            for (i in 0 until node.childCount.coerceAtMost(8)) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    private fun enforceBlock(
        enforcementKey: String,
        mode: String,
        blockedPackage: String,
        matchedReason: String,
        subtitleOverride: String?,
        snapshot: BlockDecisionEngine.ActiveBlockSnapshot,
        usePersistentOverlay: Boolean,
        closeViaBackOnly: Boolean
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (enforcementKey == lastEnforcementKey && (nowElapsed - lastEnforcementTimeMs) < 700L) {
            return
        }
        lastEnforcementKey = enforcementKey
        lastEnforcementTimeMs = nowElapsed

        val container = (applicationContext as? FreedomApplication)?.container ?: return
        val appLabel = container.installedAppsProvider.getAppLabel(blockedPackage)

        // 1. Instant zero-extra-permission Accessibility overlay shield
        val title = when (mode) {
            BlockOverlayActivity.MODE_SETTINGS_WAITING_ROOM -> "Emergency Settings Wait"
            BlockOverlayActivity.MODE_WEBSITE_BLOCKED -> "Browsing Blocked"
            else -> "$appLabel Blocked"
        }
        overlayManager.showShield(
            title = title,
            subtitle = subtitleOverride ?: matchedReason,
            persistent = usePersistentOverlay
        )
        pipShieldActive = usePersistentOverlay

        // 2. Immediately dismiss in-app WebView/Custom Tab/tab or push blocked app to background
        if (closeViaBackOnly) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } else if (mode != BlockOverlayActivity.MODE_SETTINGS_WAITING_ROOM) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        // 3. Launch non-resizable BlockOverlayActivity (exits split-screen cleanly & hosts 5-min Settings Waiting Room)
        val overlayIntent = Intent(this, BlockOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra(BlockOverlayActivity.EXTRA_MODE, mode)
            putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PACKAGE, blockedPackage)
            putExtra(BlockOverlayActivity.EXTRA_BLOCKED_LABEL, appLabel)
            putExtra(BlockOverlayActivity.EXTRA_REASON_SUBTITLE, subtitleOverride ?: matchedReason)
            putExtra(BlockOverlayActivity.EXTRA_SESSION_LABEL, snapshot.label)
            putExtra(BlockOverlayActivity.EXTRA_STRICT_MODE, snapshot.strictMode)
        }
        runCatching { startActivity(overlayIntent) }

        // 4. Record blocked attempt asynchronously (never stores plain-text hidden domains!)
        serviceScope.launch {
            container.repository.recordBlockEvent(
                sessionId = snapshot.sessionId,
                packageName = blockedPackage,
                appLabel = appLabel,
                reason = matchedReason
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayManager.isInitialized) {
            overlayManager.dismiss()
        }
        _isRunning.value = false
        if (instance === this) instance = null
        serviceScope.cancel()
    }

    companion object {
        @Volatile
        private var instance: FreedomAccessibilityService? = null

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun triggerImmediateWindowCheck() {
            instance?.recheckActiveWindowsNow()
        }
    }
}
