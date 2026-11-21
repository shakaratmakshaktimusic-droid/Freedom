package com.example.blocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Browser
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.FreedomApplication
import com.example.domain.BlockDecisionEngine
import com.example.domain.ContentProtectionConfig
import com.example.domain.DomainBlockMatcher
import com.example.domain.InAppAdultFilterEngine
import com.example.domain.SafeSearchEngine
import com.example.domain.UnknownSiteClassifier
import com.example.domain.YouTubeClientRegistry
import com.example.domain.YouTubeStudyEngine
import java.util.concurrent.ConcurrentHashMap
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
 *  1. Non-intrusive Home eviction + multi-pass ActivityManager.killBackgroundProcesses() +
 *     clean Freedom heads-up notification ("Blocked by Freedom. Do what matters.") for
 *     normal blocked apps & websites.
 *  2. Reliable Vivo Small Window & Mini Window / OEM floating-window & PiP teardown:
 *     - First tries Accessibility Close node click across the app window & overlapping OEM caption windows.
 *     - When the OEM draws the Small Window caption bar natively without Accessibility labels (e.g., Vivo),
 *       dispatches a real tap gesture directly on the top-right Close ("×") control — closing the window
 *       completely instead of shrinking it to a Mini Window.
 *     - For collapsed Mini Windows, taps to expand (or swipes to the bottom close zone) so it can be
 *       immediately closed and killed with killBackgroundProcesses().
 *     - Never flickers a popup overlay; only shows a rock-solid, in-place fallback shield if an unknown
 *       OEM still refuses all teardown passes after 4 attempts.
 *  3. Locked Mode 5-minute continuous Emergency Settings Waiting Room -> 3-minute unlock.
 *  4. Highest-precedence Website Allowlist, Full Internet Browsing Block across browsers &
 *     in-app WebViews, 16 hidden filter categories (<0.01ms CompactDomainIndex), and
 *     lightweight (<2ms) UnknownSiteClassifier auto-learning.
 */
class FreedomAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var overlayManager: FreedomOverlayManager

    @Volatile
    private var lastEnforcementKey: String = ""

    @Volatile
    private var lastEnforcementTimeMs: Long = 0L

    @Volatile
    private var lastContentScanMs: Long = 0L

    @Volatile
    private var lastClassifiedUrlKey: String = ""

    @Volatile
    private var lastSafeSearchRedirectKey: String = ""

    @Volatile
    private var lastSafeSearchRedirectTimeMs: Long = 0L

    @Volatile
    private var lastApprovedYouTubePkg: String = ""

    @Volatile
    private var lastApprovedYouTubeTimeMs: Long = 0L

    private val inFlightVideoLookups = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var persistentFloatingShieldActive: Boolean = false

    @Volatile
    private var consecutiveFloatingHits: Int = 0

    @Volatile
    private var lastFloatingPkg: String = ""

    @Volatile
    private var lastNotifiedFloatingPkg: String = ""

    private val floatingWatchdogRunnable = Runnable {
        recheckActiveWindowsNow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager = FreedomOverlayManager(this)
        FreedomBlockNotifier.ensureChannel(this)
        _isRunning.value = true
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType

        // Throttle rapid scroll/content events so CPU usage stays near zero (260ms for responsive card masks)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastContentScanMs < 260L) return
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
        val contentConfig = container.fastStore.readContentProtectionConfig()
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        val isSessionActive = snapshot.isStillActive(nowWall, nowElapsed)
        val isStrictActive = isSessionActive && snapshot.strictMode

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val allWindows = runCatching { windows }.getOrNull().orEmpty()

        // If NO timed focus session is active, run silent always-on SafeSearch,
        // Multilingual Adult/Mature-Media Protection, and (when enabled) YouTube Study Mode.
        if (!isSessionActive) {
            clearFloatingShieldAndCounters()
            evaluateAlwaysOnContentProtection(
                allWindows = allWindows,
                triggerPackage = triggerPackage,
                triggerClassName = triggerClassName,
                contentConfig = contentConfig,
                strictMode = false,
                sessionId = -1L,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
            return
        }

        val hasSplitDivider = allWindows.any {
            it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
        }

        val observedWindows = mutableListOf<BlockDecisionEngine.ObservedWindow>()
        val windowNodesByPkg = HashMap<String, Pair<AccessibilityNodeInfo?, Rect>>()
        var webRootForClassifier: Pair<AccessibilityNodeInfo, BrowserUrlExtractor.WebInspectionResult>? = null

        val shouldInspectWebContent =
            snapshot.fullInternetBrowsingBlock ||
                snapshot.blockedWebCategories.isNotEmpty() ||
                snapshot.customBlockedDomains.isNotEmpty()

        for (win in allWindows) {
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { win.root }.getOrNull() ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (pkg.isBlank()) continue

            val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                win.isInPictureInPictureMode
            } else {
                false
            }

            val bounds = Rect()
            win.getBoundsInScreen(bounds)
            // Detect Vivo Small Window, Vivo Mini Window, ColorOS Flexible Window, MIUI Floating Window
            val isFloating = !isPip && !hasSplitDivider &&
                bounds.width() > 0 && bounds.height() > 0 &&
                (bounds.width() < (screenWidth * 0.94f) || bounds.height() < (screenHeight * 0.86f))

            if (isFloating || isPip) {
                windowNodesByPkg[pkg] = root to Rect(bounds)
            }

            val classForWindow = if (pkg == triggerPackage) triggerClassName else null
            val webInspection = if (shouldInspectWebContent &&
                (pkg in snapshot.allowedPackages || BrowserUrlExtractor.isBrowserPackage(pkg))
            ) {
                BrowserUrlExtractor.inspectWindow(root, pkg, classForWindow)
            } else {
                BrowserUrlExtractor.WebInspectionResult(isKnownBrowser = BrowserUrlExtractor.isBrowserPackage(pkg))
            }

            if (webRootForClassifier == null && !webInspection.extractedHost.isNullOrBlank()) {
                webRootForClassifier = root to webInspection
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
                clearFloatingShieldAndCounters()
                evaluateAlwaysOnContentProtection(
                    allWindows = allWindows,
                    triggerPackage = triggerPackage,
                    triggerClassName = triggerClassName,
                    contentConfig = contentConfig,
                    strictMode = isStrictActive,
                    sessionId = snapshot.sessionId,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                )
            }

            is BlockDecisionEngine.Decision.BlockApp -> {
                if (decision.isFloatingWindow || decision.isPictureInPicture) {
                    val pair = windowNodesByPkg[decision.packageName]
                    enforceFloatingOrPipTeardown(
                        blockedPackage = decision.packageName,
                        targetRoot = pair?.first,
                        targetBounds = pair?.second,
                        allWindows = allWindows,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        isPip = decision.isPictureInPicture,
                        snapshot = snapshot
                    )
                } else {
                    clearFloatingShieldAndCounters()
                    enforceQuietAppBlock(
                        blockedPackage = decision.packageName,
                        reason = if (decision.isSystemTamperLock) {
                            "System app locked during Locked Mode"
                        } else if (decision.isMultiWindowOrPip) {
                            "Blocked split-screen app"
                        } else {
                            "Blocked by Allowlist"
                        },
                        notificationText = if (decision.isSystemTamperLock) {
                            "Locked by Freedom during Locked Mode. Stay focused."
                        } else {
                            "Blocked by Freedom. Do what matters."
                        },
                        snapshot = snapshot
                    )
                }
            }

            is BlockDecisionEngine.Decision.BlockBrowsing -> {
                clearFloatingShieldAndCounters()
                enforceQuietWebBlock(
                    blockedPackage = decision.packageName,
                    isInAppWebView = decision.isInAppWebView,
                    killBrowserProcess = !decision.isInAppWebView,
                    reason = if (decision.isInAppWebView) {
                        "In-app web link blocked"
                    } else {
                        "Full internet browsing block active"
                    },
                    notificationText = "Browsing blocked by Freedom. Do what matters.",
                    snapshot = snapshot
                )
            }

            is BlockDecisionEngine.Decision.BlockWebsite -> {
                clearFloatingShieldAndCounters()
                enforceQuietWebBlock(
                    blockedPackage = decision.packageName,
                    isInAppWebView = true,
                    killBrowserProcess = false,
                    reason = "Website blocked (${decision.categoryTitle})",
                    notificationText = "Website blocked by Freedom. Do what matters.",
                    snapshot = snapshot
                )
            }

            is BlockDecisionEngine.Decision.RequireSettingsWait -> {
                clearFloatingShieldAndCounters()
                enforceSettingsWaitingRoom(
                    blockedPackage = decision.packageName,
                    matchedRoute = decision.matchedRoute,
                    snapshot = snapshot
                )
            }
        }
    }

    /**
     * Quietly evicts a blocked fullscreen/split-screen app to Home, terminates its
     * background processes across multiple passes, and shows a clean heads-up notification.
     */
    private fun enforceQuietAppBlock(
        blockedPackage: String,
        reason: String,
        notificationText: String,
        snapshot: BlockDecisionEngine.ActiveBlockSnapshot
    ) {
        val key = "app:$blockedPackage"
        if (shouldThrottle(key, 650L)) return

        val container = (applicationContext as? FreedomApplication)?.container ?: return
        val appLabel = container.installedAppsProvider.getAppLabel(blockedPackage)

        // 1. Immediately evict to Home screen
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Kill background processes right after leaving foreground
        scheduleBackgroundProcessKill(blockedPackage)

        // 3. Clean heads-up focus notification
        FreedomBlockNotifier.showBlockedAlert(
            context = this,
            message = notificationText
        )

        // 4. Log attempt asynchronously
        serviceScope.launch {
            container.repository.recordBlockEvent(
                sessionId = snapshot.sessionId,
                packageName = blockedPackage,
                appLabel = appLabel,
                reason = reason
            )
        }
    }

    /**
     * Reliable, flicker-free teardown for Vivo Small Window & Mini Window, ColorOS Flexible Window,
     * MIUI Floating Window, and Android Picture-in-Picture:
     *  - Never flashes or recreates a full-screen overlay while closing.
     *  - Uses Accessibility Close node clicks first, then real dispatchGesture() taps on the Small
     *    Window's top-right "×" button (and expand/drag-to-close gestures for Mini Windows).
     *  - Kills background processes AFTER the floating window leaves foreground so Android actually
     *    terminates the process.
     */
    private fun enforceFloatingOrPipTeardown(
        blockedPackage: String,
        targetRoot: AccessibilityNodeInfo?,
        targetBounds: Rect?,
        allWindows: List<AccessibilityWindowInfo>,
        screenWidth: Int,
        screenHeight: Int,
        isPip: Boolean,
        snapshot: BlockDecisionEngine.ActiveBlockSnapshot
    ) {
        // If fallback shield is already up and steady for this same blocked package, do NOT re-add or flicker!
        if (persistentFloatingShieldActive && lastFloatingPkg == blockedPackage && overlayManager.isVisible) {
            return
        }

        val key = "float:$blockedPackage:$isPip"
        if (shouldThrottle(key, 380L)) return

        if (lastFloatingPkg == blockedPackage) {
            consecutiveFloatingHits++
        } else {
            lastFloatingPkg = blockedPackage
            consecutiveFloatingHits = 1
        }

        val container = (applicationContext as? FreedomApplication)?.container ?: return
        val appLabel = container.installedAppsProvider.getAppLabel(blockedPackage)

        val isMiniWindow = !isPip && targetBounds != null &&
            (targetBounds.width() < (screenWidth * 0.45f) || targetBounds.height() < (screenHeight * 0.36f))

        // Step 1: Attempt Accessibility Close ("×") node click across target window + ALL overlapping caption windows
        val clickedClose = attemptCloseFloatingOrPipWindow(
            targetRoot = targetRoot,
            targetBounds = targetBounds,
            allWindows = allWindows
        )

        // Step 2: If no Accessibility close node was exposed (common on Vivo smartmultiwindow where the
        // caption bar "×" is compositor-drawn), use real dispatchGesture() directly on the window controls!
        if (!clickedClose && targetBounds != null) {
            val density = resources.displayMetrics.density
            if (isMiniWindow) {
                when (consecutiveFloatingHits) {
                    1 -> {
                        // Tap center of Vivo Mini Window to expand it into Small Window / Fullscreen
                        // where either × or GLOBAL_ACTION_HOME immediately closes & kills it!
                        dispatchTapGesture(
                            x = targetBounds.exactCenterX().coerceIn(8f, screenWidth - 8f),
                            y = targetBounds.exactCenterY().coerceIn(8f, screenHeight - 8f)
                        )
                    }
                    else -> {
                        // Drag Mini Window smoothly down to Vivo's bottom-center Close drop zone
                        dispatchDragGesture(
                            startX = targetBounds.exactCenterX().coerceIn(8f, screenWidth - 8f),
                            startY = targetBounds.exactCenterY().coerceIn(8f, screenHeight - 8f),
                            endX = screenWidth * 0.5f,
                            endY = screenHeight * 0.90f,
                            durationMs = 260L
                        )
                    }
                }
            } else {
                // Vivo Small Window / OEM Freeform Window / PiP: tap top-right Close ("×") icon!
                // Vary vertical offset cleanly across attempts to cover both inside-bounds caption bars
                // (standard Vivo Funtouch/OriginOS) and above-bounds caption decorations.
                val tapX = (targetBounds.right - 22f * density).coerceIn(16f, screenWidth - 16f)
                val tapY = when (consecutiveFloatingHits) {
                    1 -> targetBounds.top + 20f * density
                    2 -> targetBounds.top + 30f * density
                    3 -> targetBounds.top - 16f * density
                    else -> targetBounds.top + 24f * density
                }.coerceIn(16f, screenHeight - 16f)

                dispatchTapGesture(x = tapX, y = tapY)
            }
        } else if (!clickedClose) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }

        // Step 3: Schedule staggered background process termination AFTER the window closes
        scheduleBackgroundProcessKill(blockedPackage)

        // Step 4: Post clean notification ONCE per floating block incident (no spam on retries)
        if (lastNotifiedFloatingPkg != blockedPackage) {
            lastNotifiedFloatingPkg = blockedPackage
            FreedomBlockNotifier.showBlockedAlert(
                context = this,
                message = "Blocked by Freedom. Do what matters."
            )
        }

        // Step 5: Only if the OEM STILL refuses all 4 real close/gesture attempts, show ONE steady,
        // non-flickering fallback shield and stop the watchdog loop while the shield is up.
        if (consecutiveFloatingHits >= 5) {
            mainHandler.removeCallbacks(floatingWatchdogRunnable)
            persistentFloatingShieldActive = true
            overlayManager.showShield(
                title = "$appLabel Blocked",
                subtitle = if (isPip) {
                    "Picture-in-Picture is blocked during your focus session."
                } else {
                    "Floating & Mini Windows are blocked during your focus session."
                },
                persistent = true,
                onDismissAction = {
                    persistentFloatingShieldActive = false
                    consecutiveFloatingHits = 0
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    scheduleBackgroundProcessKill(blockedPackage)
                    mainHandler.postDelayed({ recheckActiveWindowsNow() }, 480L)
                }
            )
            return
        }

        // Follow-up check after gesture/close settles to confirm the window disappeared and kill process
        mainHandler.removeCallbacks(floatingWatchdogRunnable)
        mainHandler.postDelayed(floatingWatchdogRunnable, 460L)

        // Record only on initial hit so retries don't inflate stats
        if (consecutiveFloatingHits == 1) {
            serviceScope.launch {
                container.repository.recordBlockEvent(
                    sessionId = snapshot.sessionId,
                    packageName = blockedPackage,
                    appLabel = appLabel,
                    reason = if (isPip) "Blocked Picture-in-Picture window" else "Blocked floating / mini window"
                )
            }
        }
    }

    /**
     * Dispatches a fast, real single tap at (x, y) via AccessibilityService.dispatchGesture().
     */
    private fun dispatchTapGesture(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.5f, y + 0.5f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 40L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        runCatching { dispatchGesture(gesture, null, null) }
    }

    /**
     * Dispatches a smooth drag gesture (used to drag stubborn OEM Mini Windows into the bottom close target).
     */
    private fun dispatchDragGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        runCatching { dispatchGesture(gesture, null, null) }
    }

    /**
     * Quietly closes a blocked website or in-app WebView/Custom Tab and notifies cleanly.
     */
    private fun enforceQuietWebBlock(
        blockedPackage: String,
        isInAppWebView: Boolean,
        killBrowserProcess: Boolean,
        reason: String,
        notificationText: String,
        snapshot: BlockDecisionEngine.ActiveBlockSnapshot
    ) {
        val key = "web:$blockedPackage:$reason"
        if (shouldThrottle(key, 700L)) return

        val container = (applicationContext as? FreedomApplication)?.container ?: return
        val appLabel = container.installedAppsProvider.getAppLabel(blockedPackage)

        if (isInAppWebView) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        if (killBrowserProcess) {
            scheduleBackgroundProcessKill(blockedPackage)
        }

        FreedomBlockNotifier.showBlockedAlert(
            context = this,
            message = notificationText
        )

        serviceScope.launch {
            container.repository.recordBlockEvent(
                sessionId = snapshot.sessionId,
                packageName = blockedPackage,
                appLabel = appLabel,
                reason = reason
            )
        }
    }

    /**
     * Dedicated full-screen 5-minute continuous Emergency Settings Waiting Room
     * (ONLY shown when user opens Android Settings during Locked Mode).
     */
    private fun enforceSettingsWaitingRoom(
        blockedPackage: String,
        matchedRoute: String,
        snapshot: BlockDecisionEngine.ActiveBlockSnapshot
    ) {
        val key = "settings_wait:$blockedPackage"
        if (shouldThrottle(key, 700L)) return

        val container = (applicationContext as? FreedomApplication)?.container ?: return
        val appLabel = container.installedAppsProvider.getAppLabel(blockedPackage)

        val overlayIntent = Intent(this, BlockOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra(BlockOverlayActivity.EXTRA_MODE, BlockOverlayActivity.MODE_SETTINGS_WAITING_ROOM)
            putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PACKAGE, blockedPackage)
            putExtra(BlockOverlayActivity.EXTRA_BLOCKED_LABEL, appLabel)
            putExtra(BlockOverlayActivity.EXTRA_REASON_SUBTITLE, matchedRoute)
            putExtra(BlockOverlayActivity.EXTRA_SESSION_LABEL, snapshot.label)
            putExtra(BlockOverlayActivity.EXTRA_STRICT_MODE, snapshot.strictMode)
        }
        runCatching { startActivity(overlayIntent) }

        serviceScope.launch {
            container.repository.recordBlockEvent(
                sessionId = snapshot.sessionId,
                packageName = blockedPackage,
                appLabel = appLabel,
                reason = "Emergency Settings wait ($matchedRoute)"
            )
        }
    }

    /**
     * Calls ActivityManager.killBackgroundProcesses across staggered delays so that once
     * the blocked app exits foreground (after Home or floating-window close), Android
     * immediately terminates its background process.
     */
    private fun scheduleBackgroundProcessKill(targetPackage: String) {
        if (targetPackage.isBlank() || targetPackage == packageName) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val killNow = {
            runCatching { am.killBackgroundProcesses(targetPackage) }
        }
        mainHandler.postDelayed({ killNow() }, 180L)
        mainHandler.postDelayed({ killNow() }, 550L)
        mainHandler.postDelayed({ killNow() }, 1100L)
    }

    /**
     * Searches BOTH the floating/PiP app window AND any overlapping OEM window-manager
     * caption window (Vivo smartmultiwindow, ColorOS, MIUI, SystemUI) for Close controls,
     * including unlabeled clickable buttons located in the top-right caption corner.
     */
    private fun attemptCloseFloatingOrPipWindow(
        targetRoot: AccessibilityNodeInfo?,
        targetBounds: Rect?,
        allWindows: List<AccessibilityWindowInfo>
    ): Boolean {
        val density = resources.displayMetrics.density
        if (targetRoot != null && findAndClickCloseNode(targetRoot, targetBounds, density)) {
            return true
        }
        if (targetBounds == null) return false

        // Expand slightly upward to catch OEM caption bars docked right above the Small/Mini Window
        val expanded = Rect(
            targetBounds.left - (48 * density).toInt(),
            (targetBounds.top - (72 * density).toInt()).coerceAtLeast(0),
            targetBounds.right + (48 * density).toInt(),
            targetBounds.top + (64 * density).toInt()
        )

        for (win in allWindows) {
            val winRoot = runCatching { win.root }.getOrNull() ?: continue
            val winPkg = winRoot.packageName?.toString().orEmpty()
            if (winPkg == packageName) continue
            val winBounds = Rect()
            win.getBoundsInScreen(winBounds)
            if (Rect.intersects(expanded, winBounds)) {
                if (findAndClickCloseNode(winRoot, targetBounds, density)) {
                    return true
                }
            }
        }
        return false
    }

    private fun findAndClickCloseNode(
        root: AccessibilityNodeInfo,
        targetBounds: Rect?,
        density: Float
    ): Boolean {
        val closeTokens = listOf(
            "close", "dismiss", "exit picture-in-picture", "exit freeform",
            "close window", "close mini", "关闭", "退出"
        )
        val closeIdTokens = listOf(
            "close", "btn_close", "close_btn", "freeform_close",
            "mini_close", "vivo_freeform_close", "exit_freeform", "dismiss"
        )

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val nodeBounds = Rect()
        val cornerToleranceX = (60f * density).toInt()
        val cornerToleranceY = (56f * density).toInt()

        while (queue.isNotEmpty() && visited < 80) {
            val node = queue.removeFirst()
            visited++
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()
            val resId = node.viewIdResourceName?.lowercase().orEmpty()

            val matchesLabel = closeTokens.any { desc.contains(it) || text == it } ||
                closeIdTokens.any { resId.contains(it) }

            val matchesTopRightCorner = if (!matchesLabel && targetBounds != null && node.isClickable) {
                node.getBoundsInScreen(nodeBounds)
                val isSmallIcon = nodeBounds.width() in 1..(64 * density).toInt() &&
                    nodeBounds.height() in 1..(64 * density).toInt()
                val nearRight = nodeBounds.right >= (targetBounds.right - cornerToleranceX) &&
                    nodeBounds.right <= (targetBounds.right + cornerToleranceX / 2)
                val nearTop = nodeBounds.top >= (targetBounds.top - cornerToleranceY) &&
                    nodeBounds.top <= (targetBounds.top + cornerToleranceY)
                isSmallIcon && nearRight && nearTop
            } else {
                false
            }

            if (matchesLabel || matchesTopRightCorner) {
                var clickable: AccessibilityNodeInfo? = node
                var hops = 0
                while (clickable != null && !clickable.isClickable && hops < 3) {
                    clickable = clickable.parent
                    hops++
                }
                val actionTarget = clickable ?: node
                val clicked = runCatching {
                    actionTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (clicked) return true
            }

            for (i in 0 until node.childCount.coerceAtMost(14)) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun clearFloatingShieldAndCounters() {
        mainHandler.removeCallbacks(floatingWatchdogRunnable)
        consecutiveFloatingHits = 0
        lastFloatingPkg = ""
        lastNotifiedFloatingPkg = ""
        if (persistentFloatingShieldActive) {
            overlayManager.dismiss()
            persistentFloatingShieldActive = false
        }
    }

    private fun shouldThrottle(key: String, windowMs: Long): Boolean {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (key == lastEnforcementKey && (nowElapsed - lastEnforcementTimeMs) < windowMs) {
            return true
        }
        lastEnforcementKey = key
        lastEnforcementTimeMs = nowElapsed
        return false
    }

    // =========================================================================
    // Silent Always-On Backend Content Protection (ZERO Floating Card Masks!):
    //  1. Regain-style Multi-Client YouTube Study Mode (Official YT, Morphe YT, ReVanced, 3P)
    //     — Cuts unapproved videos INSIDE YouTube (Watch -> Back + Miniplayer ×, Shorts -> Home,
    //       and PiP safety close) so YouTube never minimizes into PiP and keeps playing.
    //  2. Silent Always-On Universal SafeSearch Enforcement (Google, Bing, DDG, Yahoo, Brave...)
    //  3. Silent Always-On Multilingual & Mature-Media Adult Filter (Direct Back Cut, No Overlays)
    // =========================================================================

    private data class ObservedAppWindow(
        val packageName: String,
        val root: AccessibilityNodeInfo,
        val isPip: Boolean,
        val bounds: Rect
    )

    private fun isRecentApprovedYouTubeWatch(pkg: String): Boolean {
        if (pkg != lastApprovedYouTubePkg) return false
        return (SystemClock.elapsedRealtime() - lastApprovedYouTubeTimeMs) < 90_000L
    }

    private fun evaluateAlwaysOnContentProtection(
        allWindows: List<AccessibilityWindowInfo>,
        triggerPackage: String?,
        triggerClassName: String?,
        contentConfig: ContentProtectionConfig,
        strictMode: Boolean,
        sessionId: Long,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val container = (applicationContext as? FreedomApplication)?.container ?: return
        val observedApps = ArrayList<ObservedAppWindow>(3)

        for (win in allWindows) {
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { win.root }.getOrNull() ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank() || pkg == packageName) continue
            val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                win.isInPictureInPictureMode
            } else {
                false
            }
            val bounds = Rect()
            win.getBoundsInScreen(bounds)
            observedApps.add(ObservedAppWindow(pkg, root, isPip, bounds))
        }
        if (observedApps.isEmpty() && !triggerPackage.isNullOrBlank() && triggerPackage != packageName) {
            runCatching { rootInActiveWindow }.getOrNull()?.let { r ->
                observedApps.add(ObservedAppWindow(triggerPackage, r, false, Rect(0, 0, screenWidth, screenHeight)))
            }
        }

        // Fast exit on Launcher / SystemUI / Freedom itself — zero overhead!
        if (observedApps.isEmpty()) return

        val dynamicExempt = container.installedAppsProvider.getDynamicExemptPackages()

        for (win in observedApps) {
            val pkg = win.packageName
            val root = win.root
            if (pkg in dynamicExempt || pkg == "com.android.systemui") continue

            val appLabel = container.installedAppsProvider.getAppLabel(pkg)

            // -----------------------------------------------------------------
            // A. Multi-Client YouTube Study Mode + In-App Adult/Mature Filter
            //    (Official YouTube, Morphe YT, ReVanced, Third-Party Clients)
            // -----------------------------------------------------------------
            val ytClient = YouTubeClientRegistry.identifyClient(pkg, appLabel)
            if (ytClient != null) {
                // A0. PiP Leak Safety Guard: if a YouTube client is floating in PiP while Study Mode
                // is ON and the current video was NOT verified as an approved channel, close PiP!
                if (win.isPip && contentConfig.youtubeStudyModeEnabled && !isRecentApprovedYouTubeWatch(pkg)) {
                    closeYouTubePipWindow(
                        pkg = pkg,
                        targetRoot = root,
                        targetBounds = win.bounds,
                        allWindows = allWindows,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        sessionId = sessionId
                    )
                    return
                }

                val ytSnapshot = YouTubeAccessibilityInspector.inspect(
                    root = root,
                    client = ytClient,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                )

                var isApprovedYouTubeChannel = false

                if (contentConfig.youtubeStudyModeEnabled) {
                    val clientDecision = YouTubeStudyEngine.evaluateClientCompatibility(ytClient, strictMode)
                    if (clientDecision is YouTubeStudyEngine.StudyDecision.BlockPlayback) {
                        enforceYouTubeInAppVideoCut(
                            blockedPackage = pkg,
                            root = root,
                            cutAction = clientDecision.cutAction,
                            reason = clientDecision.reason,
                            notificationText = clientDecision.notificationText,
                            sessionId = sessionId,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight
                        )
                        return
                    }

                    val approved = container.fastStore.readApprovedChannelsSnapshot()
                    val playbackDecision = YouTubeStudyEngine.evaluateActivePlayback(
                        screenMode = ytSnapshot.screenMode,
                        detectedChannelId = null,
                        detectedHandle = ytSnapshot.activeChannelHandle,
                        detectedChannelTitle = ytSnapshot.activeChannelTitle,
                        approved = approved,
                        config = contentConfig,
                        strictMode = strictMode,
                        lastApprovedWatchActive = isRecentApprovedYouTubeWatch(pkg)
                    )

                    if (playbackDecision is YouTubeStudyEngine.StudyDecision.BlockPlayback) {
                        if (lastApprovedYouTubePkg == pkg) {
                            lastApprovedYouTubePkg = ""
                        }
                        enforceYouTubeInAppVideoCut(
                            blockedPackage = pkg,
                            root = root,
                            cutAction = playbackDecision.cutAction,
                            reason = playbackDecision.reason,
                            notificationText = playbackDecision.notificationText,
                            sessionId = sessionId,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight
                        )
                        return
                    }

                    // Mark approved channel playback so miniplayer/PiP & spiritual recovery talks stay smooth
                    if (ytSnapshot.screenMode == YouTubeStudyEngine.ScreenMode.WATCH_PLAYER ||
                        ytSnapshot.screenMode == YouTubeStudyEngine.ScreenMode.LIVE_PLAYER ||
                        ytSnapshot.screenMode == YouTubeStudyEngine.ScreenMode.SHORTS_PLAYER
                    ) {
                        if (approved.matchesAttribution(
                                channelId = null,
                                handle = ytSnapshot.activeChannelHandle,
                                channelTitle = ytSnapshot.activeChannelTitle
                            )
                        ) {
                            isApprovedYouTubeChannel = true
                            lastApprovedYouTubePkg = pkg
                            lastApprovedYouTubeTimeMs = SystemClock.elapsedRealtime()
                        }
                    }
                }

                // Silent Always-On Adult / Mature-Media Protection inside YouTube & Morphe YT
                // (Skipped for channels the user explicitly verified & approved in Study Mode)
                val adultActive = InAppAdultFilterEngine.evaluateActiveContent(
                    urlOrQuery = ytSnapshot.activeVideoTitle,
                    visibleSnippets = ytSnapshot.allVisibleSnippets,
                    isApprovedYouTubeChannel = isApprovedYouTubeChannel
                )
                if (adultActive.isFlagged) {
                    val cutAction = when (ytSnapshot.screenMode) {
                        YouTubeStudyEngine.ScreenMode.SHORTS_PLAYER ->
                            YouTubeStudyEngine.PlaybackCutAction.EXIT_SHORTS_TO_HOME
                        YouTubeStudyEngine.ScreenMode.MINI_PLAYER ->
                            YouTubeStudyEngine.PlaybackCutAction.CLOSE_MINIPLAYER_ONLY
                        YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
                        YouTubeStudyEngine.ScreenMode.LIVE_PLAYER ->
                            YouTubeStudyEngine.PlaybackCutAction.CLOSE_WATCH_AND_MINIPLAYER
                        else ->
                            YouTubeStudyEngine.PlaybackCutAction.EXIT_SHORTS_TO_HOME
                    }
                    enforceYouTubeInAppVideoCut(
                        blockedPackage = pkg,
                        root = root,
                        cutAction = cutAction,
                        reason = "Inappropriate or mature content filtered in ${ytClient.displayName}",
                        notificationText = "Inappropriate content filtered by Freedom.",
                        sessionId = sessionId,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight
                    )
                    return
                }
                continue
            }

            // -----------------------------------------------------------------
            // B. Browsers, Embedded WebViews, Google Search & Google Images
            //    (Silent Always-On SafeSearch + Direct Adult/Mature Cut — ZERO Floating Masks!)
            // -----------------------------------------------------------------
            val isBrowserOrWeb = BrowserUrlExtractor.isBrowserPackage(pkg) ||
                pkg == "com.google.android.googlequicksearchbox"
            if (isBrowserOrWeb) {
                // Automatic Strict Mode safeguard for Google App's native unfilterable views
                if (pkg == "com.google.android.googlequicksearchbox" && strictMode) {
                    enforceContentProtectionEviction(
                        blockedPackage = pkg,
                        navigateHome = true,
                        reason = "Unfilterable search app blocked during Strict Mode",
                        notificationText = "Google App blocked in Strict Mode — use a browser with SafeSearch.",
                        sessionId = sessionId
                    )
                    return
                }

                val classForWindow = if (pkg == triggerPackage) triggerClassName else null
                val webInfo = BrowserUrlExtractor.inspectWindow(root, pkg, classForWindow)
                val rawUrl = webInfo.rawUrlWithQuery ?: webInfo.extractedFullUrl

                // B1. Direct YouTube Links in Browsers / Custom Tabs (when Study Mode is ON)
                if (contentConfig.youtubeStudyModeEnabled && !rawUrl.isNullOrBlank()) {
                    val approved = container.fastStore.readApprovedChannelsSnapshot()
                    when (val ytUrlDecision = YouTubeStudyEngine.evaluateDirectYouTubeUrl(
                        rawUrl = rawUrl,
                        approved = approved,
                        config = contentConfig,
                        strictMode = strictMode
                    )) {
                        is YouTubeStudyEngine.StudyDecision.BlockPlayback -> {
                            enforceContentProtectionEviction(
                                blockedPackage = pkg,
                                navigateHome = false,
                                reason = ytUrlDecision.reason,
                                notificationText = ytUrlDecision.notificationText,
                                sessionId = sessionId
                            )
                            return
                        }

                        is YouTubeStudyEngine.StudyDecision.NeedsVideoChannelVerification -> {
                            val vid = ytUrlDecision.videoId
                            if (strictMode) {
                                enforceContentProtectionEviction(
                                    blockedPackage = pkg,
                                    navigateHome = false,
                                    reason = "Verifying YouTube video channel (fail-closed in Strict Mode)",
                                    notificationText = "Verifying channel identity — retry once verified.",
                                    sessionId = sessionId
                                )
                            }
                            if (inFlightVideoLookups.add(vid)) {
                                serviceScope.launch {
                                    try {
                                        val cid = container.repository.verifyAndCacheDirectVideoChannel(vid)
                                        if (cid != null && !container.fastStore.readApprovedChannelsSnapshot().isChannelIdApproved(cid)) {
                                            mainHandler.post { recheckActiveWindowsNow() }
                                        }
                                    } finally {
                                        inFlightVideoLookups.remove(vid)
                                    }
                                }
                            }
                            if (strictMode) return
                        }

                        YouTubeStudyEngine.StudyDecision.Allow -> Unit
                    }
                }

                // B2. Silent Always-On Universal SafeSearch Engine
                var activeSearchQuery: String? = null
                if (!rawUrl.isNullOrBlank()) {
                    val safeDecision = SafeSearchEngine.evaluateUrl(
                        rawUrl = rawUrl,
                        config = contentConfig,
                        strictMode = strictMode,
                        rules = container.fastStore.getSafeSearchRules()
                    )
                    when (safeDecision) {
                        is SafeSearchEngine.Decision.EnforceRedirect -> {
                            activeSearchQuery = safeDecision.searchQuery
                            enforceSafeSearchUrlRedirect(
                                browserPackage = pkg,
                                providerName = safeDecision.providerName,
                                enforcedUrl = safeDecision.enforcedUrl,
                                sessionId = sessionId
                            )
                            return
                        }

                        is SafeSearchEngine.Decision.BlockSettingsTamper -> {
                            enforceContentProtectionEviction(
                                blockedPackage = pkg,
                                navigateHome = false,
                                reason = safeDecision.reason,
                                notificationText = "${safeDecision.providerName} settings are locked during Strict Mode.",
                                sessionId = sessionId
                            )
                            return
                        }

                        is SafeSearchEngine.Decision.BlockImageSearch -> {
                            enforceContentProtectionEviction(
                                blockedPackage = pkg,
                                navigateHome = false,
                                reason = "Image Search blocked in Strict Mode (${safeDecision.providerName})",
                                notificationText = "Image Search is blocked during Strict Mode.",
                                sessionId = sessionId
                            )
                            return
                        }

                        is SafeSearchEngine.Decision.BlockUnverifiedSearchEngine -> {
                            enforceContentProtectionEviction(
                                blockedPackage = pkg,
                                navigateHome = false,
                                reason = "Unverified search engine blocked during Strict Mode",
                                notificationText = "Unverified search engines are blocked during Strict Mode.",
                                sessionId = sessionId
                            )
                            return
                        }

                        is SafeSearchEngine.Decision.Compliant -> {
                            activeSearchQuery = safeDecision.searchQuery
                        }

                        SafeSearchEngine.Decision.NotASearchUrl -> Unit
                    }

                    // Detect visible SafeSearch toggle tampering in settings UI during Strict Mode
                    if (strictMode && !webInfo.extractedHost.isNullOrBlank()) {
                        val headerSnippets = BrowserUrlExtractor.collectBoundedPageSnippets(root, maxSnippets = 24)
                        if (SafeSearchEngine.detectSafeSearchSettingsTamperText(
                                host = webInfo.extractedHost,
                                visibleSnippets = headerSnippets,
                                strictMode = true
                            )
                        ) {
                            enforceContentProtectionEviction(
                                blockedPackage = pkg,
                                navigateHome = false,
                                reason = "Attempt to disable SafeSearch blocked in Strict Mode",
                                notificationText = "SafeSearch cannot be weakened during Strict Mode.",
                                sessionId = sessionId
                            )
                            return
                        }
                    }
                }

                // B3. Silent Always-On Multilingual & Mature-Media Adult Filter (Direct Back Cut — Zero Overlays!)
                val queryCheck = InAppAdultFilterEngine.evaluateQueryOrUrl(activeSearchQuery ?: rawUrl)
                if (queryCheck.isFlagged) {
                    enforceContentProtectionEviction(
                        blockedPackage = pkg,
                        navigateHome = false,
                        reason = "Explicit search query or URL filtered",
                        notificationText = "Inappropriate search filtered by Freedom.",
                        sessionId = sessionId
                    )
                    return
                }

                // Inspect visible page / search-result snippets directly (NO floating overlay masks!)
                val pageSnippets = BrowserUrlExtractor.collectBoundedPageSnippets(root, maxSnippets = 28)
                val pageCheck = InAppAdultFilterEngine.evaluateActiveContent(
                    urlOrQuery = activeSearchQuery ?: rawUrl,
                    visibleSnippets = pageSnippets
                )
                if (pageCheck.isFlagged) {
                    enforceContentProtectionEviction(
                        blockedPackage = pkg,
                        navigateHome = false,
                        reason = "Inappropriate search results or page filtered",
                        notificationText = "Inappropriate content filtered by Freedom.",
                        sessionId = sessionId
                    )
                    return
                }
            } else {
                // C. Silent Always-On Filtering for Other Inspectable Third-Party Apps
                val snippets = BrowserUrlExtractor.collectBoundedPageSnippets(root, maxSnippets = 24)
                val res = InAppAdultFilterEngine.evaluateActiveContent(
                    urlOrQuery = null,
                    visibleSnippets = snippets
                )
                if (res.isFlagged) {
                    enforceContentProtectionEviction(
                        blockedPackage = pkg,
                        navigateHome = false,
                        reason = "Inappropriate in-app content filtered",
                        notificationText = "Inappropriate content filtered by Freedom.",
                        sessionId = sessionId
                    )
                    return
                }
            }
        }
    }

    /**
     * Cuts playback INSIDE YouTube / Morphe YT / ReVanced without exiting YouTube into Android PiP:
     *  - Watch/Live: Pause -> Back (collapses to miniplayer) -> multi-pass Miniplayer Close (×).
     *  - Miniplayer: Immediately clicks Miniplayer Close (×).
     *  - Shorts: Clicks bottom Home tab (or Back if unavailable) while keeping YouTube open.
     *  - Unverifiable 3P Client (Strict Mode): Evicts app to Home.
     */
    private fun enforceYouTubeInAppVideoCut(
        blockedPackage: String,
        root: AccessibilityNodeInfo?,
        cutAction: YouTubeStudyEngine.PlaybackCutAction,
        reason: String,
        notificationText: String,
        sessionId: Long,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val key = "ytcut:$blockedPackage:${cutAction.name}:$reason"
        if (shouldThrottle(key, 650L)) return

        when (cutAction) {
            YouTubeStudyEngine.PlaybackCutAction.CLOSE_WATCH_AND_MINIPLAYER -> {
                // 1. Pause immediately if Pause control is accessible
                YouTubeAccessibilityInspector.tryPauseActiveVideo(root)
                // 2. Collapse watch player into YouTube's bottom miniplayer (keeps YouTube open -> NO PiP!)
                performGlobalAction(GLOBAL_ACTION_BACK)
                // 3. Follow-up passes to immediately close the collapsed bottom miniplayer (×)
                scheduleMiniPlayerClosePasses(blockedPackage, screenWidth, screenHeight)
            }

            YouTubeStudyEngine.PlaybackCutAction.CLOSE_MINIPLAYER_ONLY -> {
                val closed = YouTubeAccessibilityInspector.tryCloseMiniPlayer(root, screenWidth, screenHeight)
                if (!closed) {
                    YouTubeAccessibilityInspector.tryPauseActiveVideo(root)
                    scheduleMiniPlayerClosePasses(blockedPackage, screenWidth, screenHeight)
                }
            }

            YouTubeStudyEngine.PlaybackCutAction.EXIT_SHORTS_TO_HOME -> {
                val navigatedHomeTab = YouTubeAccessibilityInspector.tryNavigateShortsToHome(root, screenHeight)
                if (!navigatedHomeTab) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }

            YouTubeStudyEngine.PlaybackCutAction.CLOSE_PIP_WINDOW -> {
                // Handled directly by closeYouTubePipWindow
                performGlobalAction(GLOBAL_ACTION_BACK)
            }

            YouTubeStudyEngine.PlaybackCutAction.EVICT_CLIENT_HOME -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                scheduleBackgroundProcessKill(blockedPackage)
            }
        }

        FreedomBlockNotifier.showBlockedAlert(
            context = this,
            message = notificationText
        )

        val container = (applicationContext as? FreedomApplication)?.container ?: return
        val appLabel = container.installedAppsProvider.getAppLabel(blockedPackage)
        serviceScope.launch {
            container.repository.recordBlockEvent(
                sessionId = sessionId,
                packageName = blockedPackage,
                appLabel = appLabel,
                reason = reason
            )
        }
    }

    /**
     * Rapidly clicks YouTube's bottom miniplayer Close ("×") button right after collapsing
     * an unapproved Watch player so video & audio stop completely inside YouTube.
     */
    private fun scheduleMiniPlayerClosePasses(
        ytPackage: String,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val delays = longArrayOf(180L, 420L, 760L)
        for (delayMs in delays) {
            mainHandler.postDelayed({
                val activeRoot = runCatching { rootInActiveWindow }.getOrNull() ?: return@postDelayed
                if (activeRoot.packageName?.toString() != ytPackage) return@postDelayed
                val closed = YouTubeAccessibilityInspector.tryCloseMiniPlayer(activeRoot, screenWidth, screenHeight)
                if (!closed) {
                    YouTubeAccessibilityInspector.tryPauseActiveVideo(activeRoot)
                }
            }, delayMs)
        }
    }

    /**
     * Safety net: if a YouTube client enters Picture-in-Picture with an unapproved video,
     * immediately dismisses/closes the PiP window so playback cannot continue in the corner.
     */
    private fun closeYouTubePipWindow(
        pkg: String,
        targetRoot: AccessibilityNodeInfo?,
        targetBounds: Rect,
        allWindows: List<AccessibilityWindowInfo>,
        screenWidth: Int,
        screenHeight: Int,
        sessionId: Long
    ) {
        val key = "ytpip:$pkg"
        if (shouldThrottle(key, 700L)) return

        val clickedClose = attemptCloseFloatingOrPipWindow(
            targetRoot = targetRoot,
            targetBounds = targetBounds,
            allWindows = allWindows
        )
        if (!clickedClose && targetBounds.width() > 0 && targetBounds.height() > 0) {
            // Drag PiP window cleanly down to Android's bottom-center Dismiss ("×") target
            dispatchDragGesture(
                startX = targetBounds.exactCenterX().coerceIn(8f, screenWidth - 8f),
                startY = targetBounds.exactCenterY().coerceIn(8f, screenHeight - 8f),
                endX = screenWidth * 0.5f,
                endY = screenHeight * 0.92f,
                durationMs = 260L
            )
        }

        FreedomBlockNotifier.showBlockedAlert(
            context = this,
            message = "Unapproved YouTube PiP playback stopped by Freedom."
        )

        val container = (applicationContext as? FreedomApplication)?.container ?: return
        val appLabel = container.installedAppsProvider.getAppLabel(pkg)
        serviceScope.launch {
            container.repository.recordBlockEvent(
                sessionId = sessionId,
                packageName = pkg,
                appLabel = appLabel,
                reason = "Unapproved YouTube PiP stopped"
            )
        }
    }

    /**
     * Loop-protected SafeSearch URL enforcement: opens the strict SafeSearch URL inside the
     * active browser tab without storing any browsing history.
     */
    private fun enforceSafeSearchUrlRedirect(
        browserPackage: String,
        providerName: String,
        enforcedUrl: String,
        sessionId: Long
    ) {
        val now = SystemClock.elapsedRealtime()
        val key = "$browserPackage:${enforcedUrl.hashCode()}"
        if (key == lastSafeSearchRedirectKey && (now - lastSafeSearchRedirectTimeMs) < 2_500L) {
            return
        }
        lastSafeSearchRedirectKey = key
        lastSafeSearchRedirectTimeMs = now

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(enforcedUrl)).apply {
            setPackage(browserPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(Browser.EXTRA_APPLICATION_ID, browserPackage)
        }
        val launched = runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)

        if (!launched) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }

        FreedomBlockNotifier.showBlockedAlert(
            context = this,
            message = "$providerName enforced by Freedom."
        )

        val container = (applicationContext as? FreedomApplication)?.container ?: return
        val appLabel = container.installedAppsProvider.getAppLabel(browserPackage)
        serviceScope.launch {
            container.repository.recordBlockEvent(
                sessionId = sessionId,
                packageName = browserPackage,
                appLabel = appLabel,
                reason = "$providerName enforced"
            )
        }
    }

    /**
     * Clean eviction for SafeSearch tampering, browser YouTube links, and In-App Adult Protection.
     * Never logs URLs, search queries, or explicit titles, and never attaches a floating mask overlay.
     */
    private fun enforceContentProtectionEviction(
        blockedPackage: String,
        navigateHome: Boolean,
        reason: String,
        notificationText: String,
        sessionId: Long
    ) {
        val key = "cp:$blockedPackage:$reason"
        if (shouldThrottle(key, 750L)) return

        if (navigateHome) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            scheduleBackgroundProcessKill(blockedPackage)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }

        FreedomBlockNotifier.showBlockedAlert(
            context = this,
            message = notificationText
        )

        val container = (applicationContext as? FreedomApplication)?.container ?: return
        val appLabel = container.installedAppsProvider.getAppLabel(blockedPackage)
        serviceScope.launch {
            container.repository.recordBlockEvent(
                sessionId = sessionId,
                packageName = blockedPackage,
                appLabel = appLabel,
                reason = reason
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
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
