package com.example.blocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Browser
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.FreedomApplication
import com.example.domain.BlockDecisionEngine
import com.example.domain.ContentProtectionConfig
import com.example.domain.DomainBlockMatcher
import com.example.domain.InAppAdultFilterEngine
import com.example.domain.MatureMediaCatalog
import com.example.domain.SafeSearchEngine
import com.example.domain.UnknownSiteClassifier
import com.example.domain.WebsiteCategory
import com.example.domain.YouTubeChannelVerificationTracker
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
import kotlinx.coroutines.withTimeoutOrNull

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
    private var lastClassifiedEvidenceCount: Int = 0

    @Volatile
    private var lastClassifiedAttempts: Int = 0

    @Volatile
    private var lastMiniRecheckScheduledAtMs: Long = 0L

    // Per-(browserPkg, providerId, normalizedQuery) 10-minute dedup map so SafeSearch NEVER
    // reload-loops or spams new tabs even when Bing/Brave/Google strip URL params after load.
    private val recentEnforcedSafeSearchQueries = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastApprovedYouTubePkg: String = ""

    @Volatile
    private var lastApprovedYouTubeSessionKey: String = ""

    @Volatile
    private var lastApprovedYouTubeWasLive: Boolean = false

    @Volatile
    private var lastApprovedYouTubeTimeMs: Long = 0L

    @Volatile
    private var lastUnverifiedYouTubePkg: String = ""

    @Volatile
    private var lastUnverifiedYouTubeTimeMs: Long = 0L

    // 1.5s Watch-Collapse Back Lockout: once a single Back collapses Watch -> Miniplayer,
    // NEVER fire a second GLOBAL_ACTION_BACK while miniplayer close passes run (prevents
    // accidental exit to Android Home / Vivo floating-window bounce!).
    @Volatile
    private var lastWatchCollapseCutPkg: String = ""

    @Volatile
    private var lastWatchCollapseCutTimeMs: Long = 0L

    // Bounded single-hop recheck tracking for browser / third-party app evictions
    @Volatile
    private var lastEvictionRecheckPkg: String = ""

    @Volatile
    private var lastEvictionRecheckCount: Int = 0

    @Volatile
    private var lastEvictionTimeMs: Long = 0L

    private val ytVerificationTracker = YouTubeChannelVerificationTracker()

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
        val eventPkg = event.packageName?.toString()
        val eventClass = event.className?.toString()

        // Tighter 120ms throttle inside YouTube clients so fast click + swipe-down to miniplayer
        // is caught before playback buffers; 240ms elsewhere to keep CPU near zero.
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            val now = SystemClock.elapsedRealtime()
            val isYtEvent = !eventPkg.isNullOrBlank() &&
                (eventPkg.contains("youtube", ignoreCase = true) || eventPkg.contains("morphe", ignoreCase = true))
            val minInterval = if (isYtEvent) 120L else 240L
            if (now - lastContentScanMs < minInterval) return
            lastContentScanMs = now
        }

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

        val (screenWidth, screenHeight) = getActualDisplayDimensions()

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
                allowedWebPatterns = snapshot.allowedWebPatterns,
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
                    val isNewUrl = urlKey != lastClassifiedUrlKey
                    val shouldCheckDelayedDom = !isNewUrl &&
                        lastClassifiedAttempts < 2 &&
                        lastClassifiedEvidenceCount < 6
                    if (isNewUrl || shouldCheckDelayedDom) {
                        val evidence = BrowserUrlExtractor.collectCombinedContentEvidence(
                            root = root,
                            displayWidth = screenWidth,
                            displayHeight = screenHeight,
                            maxPageSnippets = 40,
                            maxCards = 14,
                            maxTotalEvidence = 56
                        ).mergedEvidence
                        if (shouldEvaluateUnknownSiteClassification(
                                isNewUrl = isNewUrl,
                                lastEvidenceCount = lastClassifiedEvidenceCount,
                                lastAttempts = lastClassifiedAttempts,
                                currentEvidenceCount = evidence.size
                            )
                        ) {
                            lastClassifiedUrlKey = urlKey
                            lastClassifiedEvidenceCount = evidence.size
                            lastClassifiedAttempts = if (isNewUrl) 1 else (lastClassifiedAttempts + 1)
                            val match = UnknownSiteClassifier.classify(
                                host = host,
                                fullUrl = fullUrl ?: host,
                                visibleTextSnippets = evidence,
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
        }

        when (decision) {
            is BlockDecisionEngine.Decision.Allow -> {
                clearFloatingShieldAndCounters()
                evaluateAlwaysOnContentProtection(
                    allWindows = allWindows,
                    triggerPackage = triggerPackage,
                    triggerClassName = triggerClassName,
                    contentConfig = contentConfig,
                    allowedWebPatterns = snapshot.allowedWebPatterns,
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

    private fun isWithinWatchCollapseBackLockout(pkg: String): Boolean {
        if (pkg != lastWatchCollapseCutPkg) return false
        return (SystemClock.elapsedRealtime() - lastWatchCollapseCutTimeMs) < 1_500L
    }

    private fun evaluateAlwaysOnContentProtection(
        allWindows: List<AccessibilityWindowInfo>,
        triggerPackage: String?,
        triggerClassName: String?,
        contentConfig: ContentProtectionConfig,
        allowedWebPatterns: Set<String>,
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

        // Check if YouTube was left for Home / another app while an unverified/unapproved video
        // had just been tapped (catches fast click -> Home background-audio exploit in Morphe YT / ReVanced!)
        val ytForegroundVisible = observedApps.any { win ->
            val lbl = container.installedAppsProvider.getAppLabel(win.packageName)
            YouTubeClientRegistry.identifyClient(win.packageName, lbl) != null
        }
        if (contentConfig.youtubeStudyModeEnabled && !ytForegroundVisible) {
            val pendingPkg = lastUnverifiedYouTubePkg
            val elapsedSincePending = SystemClock.elapsedRealtime() - lastUnverifiedYouTubeTimeMs
            if (pendingPkg.isNotBlank() && elapsedSincePending in 0L..25_000L && !isRecentApprovedYouTubeWatch(pendingPkg)) {
                lastUnverifiedYouTubePkg = ""
                stopBackgroundYouTubePlayback(pendingPkg, sessionId)
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
                // A0. Android System PiP Mode (strictly distinct from YouTube's in-app bottom miniplayer!):
                //  - Android PiP (`win.isPip`) floats outside YouTube over Home/other apps and exposes
                //    NO channel header nodes.
                //  - Unapproved / unknown PiP (or Live PiP when user explicitly disabled Live) -> close PiP & pause!
                //  - Verified approved VOD or Live stream in Android PiP -> refresh session timestamp and
                //    continue WITHOUT running full in-app Watch inspection against the headerless PiP window!
                if (win.isPip) {
                    if (contentConfig.youtubeStudyModeEnabled) {
                        val pipLiveDisallowed = lastApprovedYouTubeWasLive && !contentConfig.youtubeAllowLiveFromApproved
                        if (!isRecentApprovedYouTubeWatch(pkg) || pipLiveDisallowed) {
                            stopBackgroundYouTubePlayback(pkg, sessionId, notify = false)
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
                        lastApprovedYouTubeTimeMs = SystemClock.elapsedRealtime()
                    }
                    continue
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
                            screenHeight = screenHeight,
                            miniPlayerBounds = ytSnapshot.miniPlayerBounds
                        )
                        return
                    }

                    val approved = container.fastStore.readApprovedChannelsSnapshot()
                    val isPlayerScreen = ytSnapshot.screenMode == YouTubeStudyEngine.ScreenMode.WATCH_PLAYER ||
                        ytSnapshot.screenMode == YouTubeStudyEngine.ScreenMode.LIVE_PLAYER ||
                        ytSnapshot.screenMode == YouTubeStudyEngine.ScreenMode.SHORTS_PLAYER ||
                        ytSnapshot.screenMode == YouTubeStudyEngine.ScreenMode.MINI_PLAYER

                    val nowElapsedMs = SystemClock.elapsedRealtime()
                    val resolution = if (isPlayerScreen) {
                        ytVerificationTracker.evaluateCurrentPlayback(
                            activeVideoId = ytSnapshot.activeVideoId,
                            extractedChannelId = ytSnapshot.activeChannelId,
                            observedHandle = ytSnapshot.activeChannelHandle,
                            observedDisplayTitle = ytSnapshot.activeChannelTitle,
                            observedVideoTitle = ytSnapshot.activeVideoTitle,
                            persistedVideoToChannelMap = approved.videoToChannelIdCache,
                            nowMs = nowElapsedMs
                        )
                    } else {
                        ytVerificationTracker.clearActiveSession()
                        null
                    }

                    // If the user switched to a different video, IMMEDIATELY invalidate prior approval
                    // so the previous video's identity NEVER carries over to the new video!
                    if (resolution != null && resolution.videoSwitched &&
                        ytSnapshot.screenMode != YouTubeStudyEngine.ScreenMode.MINI_PLAYER &&
                        lastApprovedYouTubePkg == pkg
                    ) {
                        lastApprovedYouTubePkg = ""
                        lastApprovedYouTubeSessionKey = ""
                        lastApprovedYouTubeWasLive = false
                    }

                    // Kick off at most ONE non-blocking IO lookup if a canonical videoId needs verification
                    resolution?.shouldStartAsyncLookupForVideoId?.let { vidToVerify ->
                        launchVideoChannelVerification(vidToVerify)
                    }

                    val currentAttribution = resolution?.attribution
                    val sameApprovedSession = isRecentApprovedYouTubeWatch(pkg) &&
                        (resolution?.sessionKey == null ||
                            lastApprovedYouTubeSessionKey.isEmpty() ||
                            resolution.sessionKey == lastApprovedYouTubeSessionKey)

                    val playbackDecision = YouTubeStudyEngine.evaluateActivePlayback(
                        screenMode = ytSnapshot.screenMode,
                        detectedChannelId = currentAttribution?.verifiedChannelId,
                        detectedHandle = ytSnapshot.activeChannelHandle,
                        detectedChannelTitle = ytSnapshot.activeChannelTitle,
                        approved = approved,
                        config = contentConfig,
                        strictMode = strictMode,
                        lastApprovedWatchActive = sameApprovedSession,
                        attribution = currentAttribution
                    )

                    // Update approved vs unverified tracking strictly from verified/honest decision
                    if (isPlayerScreen) {
                        val confirmedApproved = playbackDecision is YouTubeStudyEngine.StudyDecision.Allow &&
                            (currentAttribution?.status == YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED ||
                                (currentAttribution?.videoId == null && approved.matchesAttribution(
                                    channelId = currentAttribution?.verifiedChannelId,
                                    handle = ytSnapshot.activeChannelHandle,
                                    channelTitle = ytSnapshot.activeChannelTitle
                                )))
                        val continuedApprovedSession = playbackDecision is YouTubeStudyEngine.StudyDecision.Allow &&
                            sameApprovedSession &&
                            (ytSnapshot.screenMode == YouTubeStudyEngine.ScreenMode.MINI_PLAYER ||
                                ytSnapshot.screenMode == YouTubeStudyEngine.ScreenMode.LIVE_PLAYER)
                        if (confirmedApproved) {
                            isApprovedYouTubeChannel = true
                            lastApprovedYouTubePkg = pkg
                            lastApprovedYouTubeSessionKey = resolution?.sessionKey.orEmpty()
                            lastApprovedYouTubeWasLive = ytSnapshot.screenMode == YouTubeStudyEngine.ScreenMode.LIVE_PLAYER
                            lastApprovedYouTubeTimeMs = nowElapsedMs
                            if (lastUnverifiedYouTubePkg == pkg) lastUnverifiedYouTubePkg = ""
                        } else if (continuedApprovedSession) {
                            isApprovedYouTubeChannel = true
                            lastApprovedYouTubeTimeMs = nowElapsedMs
                            if (lastUnverifiedYouTubePkg == pkg) lastUnverifiedYouTubePkg = ""
                        } else {
                            if (ytSnapshot.screenMode != YouTubeStudyEngine.ScreenMode.MINI_PLAYER && lastApprovedYouTubePkg == pkg) {
                                lastApprovedYouTubePkg = ""
                                lastApprovedYouTubeSessionKey = ""
                                lastApprovedYouTubeWasLive = false
                            }
                            lastUnverifiedYouTubePkg = pkg
                            lastUnverifiedYouTubeTimeMs = nowElapsedMs
                        }
                    }

                    when (playbackDecision) {
                        is YouTubeStudyEngine.StudyDecision.BlockPlayback -> {
                            if (lastApprovedYouTubePkg == pkg) {
                                lastApprovedYouTubePkg = ""
                                lastApprovedYouTubeSessionKey = ""
                                lastApprovedYouTubeWasLive = false
                            }
                            enforceYouTubeInAppVideoCut(
                                blockedPackage = pkg,
                                root = root,
                                cutAction = playbackDecision.cutAction,
                                reason = playbackDecision.reason,
                                notificationText = playbackDecision.notificationText,
                                sessionId = sessionId,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight,
                                miniPlayerBounds = ytSnapshot.miniPlayerBounds
                            )
                            return
                        }

                        is YouTubeStudyEngine.StudyDecision.NeedsVideoChannelVerification -> {
                            launchVideoChannelVerification(playbackDecision.videoId)
                            lastUnverifiedYouTubePkg = pkg
                            lastUnverifiedYouTubeTimeMs = nowElapsedMs
                        }

                        YouTubeStudyEngine.StudyDecision.Allow -> Unit
                    }

                    // Fast swipe-down BEFORE buffering finishes guard:
                    // If user tapped an unverified video and immediately swiped down into the bottom
                    // miniplayer before its controls finished rendering, check live miniplayer bounds
                    // right away or run ONE bounded 360ms follow-up re-scan once controls settle.
                    if (ytSnapshot.screenMode == YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH &&
                        lastUnverifiedYouTubePkg == pkg &&
                        !isRecentApprovedYouTubeWatch(pkg)
                    ) {
                        val elapsed = SystemClock.elapsedRealtime() - lastUnverifiedYouTubeTimeMs
                        if (elapsed in 0L..4_500L) {
                            val liveMiniBounds = YouTubeAccessibilityInspector.findLiveMiniPlayerBounds(
                                root = root,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight
                            )
                            if (liveMiniBounds != null) {
                                enforceYouTubeInAppVideoCut(
                                    blockedPackage = pkg,
                                    root = root,
                                    cutAction = YouTubeStudyEngine.PlaybackCutAction.CLOSE_MINIPLAYER_ONLY,
                                    reason = "Fast swipe-down unverified miniplayer stopped",
                                    notificationText = "Video stopped — channel not in your Study Mode allowlist.",
                                    sessionId = sessionId,
                                    screenWidth = screenWidth,
                                    screenHeight = screenHeight,
                                    miniPlayerBounds = liveMiniBounds
                                )
                                return
                            }
                            val nowMs = SystemClock.elapsedRealtime()
                            if (nowMs - lastMiniRecheckScheduledAtMs > 500L) {
                                lastMiniRecheckScheduledAtMs = nowMs
                                mainHandler.postDelayed({ recheckActiveWindowsNow() }, 360L)
                            }
                        }
                    }
                }

                // Silent Always-On Adult / Mature-Media Protection inside YouTube & Morphe YT
                // (Evaluates active playback, active Search Results + visible result cards/playlists,
                //  open Playlists, and non-History recommendations — NEVER fires Back on Watch History!)
                val adultCutAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
                    screenMode = ytSnapshot.screenMode,
                    activeVideoTitle = ytSnapshot.activeVideoTitle,
                    activeSearchQuery = ytSnapshot.activeSearchQuery,
                    isSearchResultsScreen = ytSnapshot.isSearchResultsScreen,
                    visibleSnippets = ytSnapshot.allVisibleSnippets,
                    isApprovedYouTubeChannel = isApprovedYouTubeChannel,
                    isPlaylistScreen = ytSnapshot.isPlaylistScreen,
                    isWatchHistoryOrProfileScreen = ytSnapshot.isWatchHistoryOrProfileScreen,
                    visibleCardSnippets = ytSnapshot.visibleCardSnippets
                )
                if (adultCutAction != null) {
                    enforceYouTubeInAppVideoCut(
                        blockedPackage = pkg,
                        root = root,
                        cutAction = adultCutAction,
                        reason = if (adultCutAction == YouTubeStudyEngine.PlaybackCutAction.EXIT_SEARCH_RESULTS) {
                            "Explicit search, playlist, or recommendation filtered in ${ytClient.displayName}"
                        } else {
                            "Inappropriate or mature content filtered in ${ytClient.displayName}"
                        },
                        notificationText = "Inappropriate content filtered by Freedom.",
                        sessionId = sessionId,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        miniPlayerBounds = ytSnapshot.miniPlayerBounds
                    )
                    return
                }
                continue
            }

            // -----------------------------------------------------------------
                // B. Browsers, Embedded WebViews, Google Search & Google Images
            //    (Explicit/Adult/Piracy Cut FIRST -> Loop-Free SafeSearch -> Deep DOM Inspection!)
            // -----------------------------------------------------------------
            val classForWindow = if (pkg == triggerPackage) triggerClassName else null
            val isKnownBrowserOrGoogle = BrowserUrlExtractor.isBrowserPackage(pkg) ||
                pkg == "com.google.android.googlequicksearchbox"
            val webInfo = BrowserUrlExtractor.inspectWindow(root, pkg, classForWindow)
            val isBrowserOrWeb = isKnownBrowserOrGoogle ||
                webInfo.isCustomTabOrWebView ||
                !webInfo.extractedHost.isNullOrBlank()

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

                val rawUrl = webInfo.rawUrlWithQuery ?: webInfo.extractedFullUrl
                val extractedHost = webInfo.extractedHost
                val fullUrl = webInfo.extractedFullUrl ?: rawUrl
                val isUserAllowlisted = !extractedHost.isNullOrBlank() &&
                    DomainBlockMatcher.isAllowedByWebsiteAllowlist(
                        extractedHost,
                        fullUrl,
                        allowedWebPatterns
                    )

                // B1. Direct YouTube Links & Mobile Web YouTube in Browsers / In-App WebViews (when Study Mode is ON)
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
                            val urlRes = ytVerificationTracker.evaluateCurrentPlayback(
                                activeVideoId = vid,
                                extractedChannelId = null,
                                observedHandle = null,
                                observedDisplayTitle = null,
                                observedVideoTitle = null,
                                persistedVideoToChannelMap = approved.videoToChannelIdCache,
                                nowMs = SystemClock.elapsedRealtime()
                            )
                            if (urlRes.attribution.status == YouTubeStudyEngine.ChannelAttributionStatus.FAILED) {
                                enforceContentProtectionEviction(
                                    blockedPackage = pkg,
                                    navigateHome = false,
                                    reason = "YouTube video channel verification failed (${urlRes.attribution.failureReason ?: "unverified"})",
                                    notificationText = "Video blocked — channel identity could not be verified.",
                                    sessionId = sessionId
                                )
                                return
                            }
                            if (strictMode) {
                                enforceContentProtectionEviction(
                                    blockedPackage = pkg,
                                    navigateHome = false,
                                    reason = "Verifying YouTube video channel (fail-closed in Strict Mode)",
                                    notificationText = "Verifying channel identity — retry once verified.",
                                    sessionId = sessionId
                                )
                            }
                            urlRes.shouldStartAsyncLookupForVideoId?.let { launchVideoChannelVerification(it) }
                            if (strictMode) return
                        }

                        YouTubeStudyEngine.StudyDecision.Allow -> Unit
                    }
                }

                // B2. Instant Adult Domain Index + Explicit Search Query Cut BEFORE SafeSearch Redirect!
                //     (Prevents explicit searches from reloading or opening a new tab before being cut!)
                if (!isUserAllowlisted && !extractedHost.isNullOrBlank()) {
                    if (container.domainIndex.containsDomain(extractedHost, setOf(WebsiteCategory.ADULT)) != null) {
                        enforceContentProtectionEviction(
                            blockedPackage = pkg,
                            navigateHome = false,
                            reason = "Adult domain blocked by always-on protection",
                            notificationText = "Adult site blocked by Freedom.",
                            sessionId = sessionId
                        )
                        return
                    }
                }

                val safeDecision = if (!rawUrl.isNullOrBlank()) {
                    SafeSearchEngine.evaluateUrl(
                        rawUrl = rawUrl,
                        config = contentConfig,
                        strictMode = strictMode,
                        rules = container.fastStore.getSafeSearchRules()
                    )
                } else {
                    SafeSearchEngine.Decision.NotASearchUrl
                }

                val activeSearchQuery: String? = when (safeDecision) {
                    is SafeSearchEngine.Decision.EnforceRedirect -> safeDecision.searchQuery
                    is SafeSearchEngine.Decision.Compliant -> safeDecision.searchQuery
                    else -> null
                }

                val queryCheck = InAppAdultFilterEngine.evaluateQueryOrUrl(activeSearchQuery ?: rawUrl)
                if (!isUserAllowlisted && queryCheck.isFlagged) {
                    enforceContentProtectionEviction(
                        blockedPackage = pkg,
                        navigateHome = false,
                        reason = queryCheck.reason.ifBlank { "Explicit search query or URL filtered" },
                        notificationText = "Inappropriate search filtered by Freedom.",
                        sessionId = sessionId
                    )
                    return
                }

                // B3. Loop-Free, Tab-Safe, Incognito-Preserving Universal SafeSearch Enforcement
                when (safeDecision) {
                    is SafeSearchEngine.Decision.EnforceRedirect -> {
                        // NEVER fire ACTION_VIEW out of Incognito/Private mode (that would kick the user
                        // to a Normal tab!) — inside Incognito, Freedom's direct query + DOM + domain
                        // filters protect the page in-place with zero reload or mode-switch.
                        if (!webInfo.isIncognitoOrPrivateMode) {
                            val didRedirect = enforceSafeSearchUrlRedirectOnce(
                                browserPackage = pkg,
                                providerId = safeDecision.providerId,
                                providerName = safeDecision.providerName,
                                searchQuery = safeDecision.searchQuery,
                                enforcedUrl = safeDecision.enforcedUrl,
                                sessionId = sessionId
                            )
                            if (didRedirect) return
                        }
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

                    is SafeSearchEngine.Decision.Compliant,
                    SafeSearchEngine.Decision.NotASearchUrl -> Unit
                }

                // B4. Deep Browser/WebView DOM Inspection (Works across Chrome, Brave, Edge, Firefox,
                //     Samsung Internet, Opera, Vivo Browser, Reddit In-App WebView, etc.)
                //     Anti-bounce guard: ONLY skip DOM eviction on genuine empty browser chrome
                //     (New Tab, History, Bookmarks, Downloads) with NO active scrolled WebView.
                //     Never skip when a browser merely auto-hid its URL bar while scrolling a webpage!
                val shouldSkipIdleBrowserChrome = isKnownBrowserOrGoogle &&
                    extractedHost.isNullOrBlank() &&
                    activeSearchQuery.isNullOrBlank() &&
                    (webInfo.isBrowserHomeOrHistoryScreen || !webInfo.isCustomTabOrWebView)
                if (shouldSkipIdleBrowserChrome) {
                    continue
                }
                val pageSnippets = BrowserUrlExtractor.collectCombinedContentEvidence(
                    root = root,
                    displayWidth = screenWidth,
                    displayHeight = screenHeight,
                    maxPageSnippets = 44,
                    maxCards = 18,
                    maxTotalEvidence = 64
                ).mergedEvidence

                // Mobile-web YouTube Study Mode check when browsing m.youtube.com/youtube.com in a browser
                if (contentConfig.youtubeStudyModeEnabled &&
                    !extractedHost.isNullOrBlank() &&
                    (extractedHost == "youtube.com" || extractedHost.endsWith(".youtube.com"))
                ) {
                    val isLiveLikePage = pageSnippets.any { s ->
                        YouTubeAccessibilityInspector.isLivePlayerIndicator("", s, s)
                    }
                    val isWatchLikePage = isLiveLikePage || pageSnippets.any { s ->
                        s.equals("Subscribe", ignoreCase = true) ||
                            s.equals("Subscribed", ignoreCase = true) ||
                            s.equals("Comments", ignoreCase = true)
                    }
                    if (isLiveLikePage && !contentConfig.youtubeAllowLiveFromApproved) {
                        enforceContentProtectionEviction(
                            blockedPackage = pkg,
                            navigateHome = false,
                            reason = "YouTube mobile-web Live stream blocked by Study Mode",
                            notificationText = "Live streams are blocked by Freedom Study Mode.",
                            sessionId = sessionId
                        )
                        return
                    }
                    if (isWatchLikePage) {
                        val approved = container.fastStore.readApprovedChannelsSnapshot()
                        val anyApprovedMatch = pageSnippets.any { s ->
                            val (_, canonicalCid) = YouTubeAccessibilityInspector.extractCanonicalIdsFromText(s)
                            approved.matchesAttribution(
                                channelId = canonicalCid,
                                handle = YouTubeStudyEngine.normalizeHandle(s),
                                channelTitle = s
                            )
                        }
                        if (!anyApprovedMatch) {
                            enforceContentProtectionEviction(
                                blockedPackage = pkg,
                                navigateHome = false,
                                reason = if (isLiveLikePage) {
                                    "Unapproved YouTube mobile-web Live stream blocked by Study Mode"
                                } else {
                                    "Unapproved YouTube mobile-web video blocked by Study Mode"
                                },
                                notificationText = "Only approved channels can play in Study Mode.",
                                sessionId = sessionId
                            )
                            return
                        }
                    }
                }

                // Detect visible SafeSearch toggle tampering in settings UI during Strict Mode
                if (strictMode && !extractedHost.isNullOrBlank()) {
                    if (SafeSearchEngine.detectSafeSearchSettingsTamperText(
                            host = extractedHost,
                            visibleSnippets = pageSnippets,
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

                // Permanent structural detector for piracy movie portals, stream aggregators, & mirrors
                // (e.g. fmhy.net/video, atlantic.bz, movies4u.vet, offline saved pages, rotating mirrors)
                if (!isUserAllowlisted) {
                    val streamScore = if (!extractedHost.isNullOrBlank()) {
                        MatureMediaCatalog.scorePiracyOrUnmoderatedMediaSite(
                            host = extractedHost,
                            fullUrl = fullUrl ?: extractedHost,
                            visibleTextSnippets = pageSnippets
                        )
                    } else 0
                    val isPiracyAggregatorPage = streamScore >= 6 ||
                        MatureMediaCatalog.isInAppPiracyAggregator(pageSnippets)
                    if (isPiracyAggregatorPage) {
                        if (!extractedHost.isNullOrBlank()) {
                            val normHost = DomainBlockMatcher.normalizeDomain(extractedHost)
                            if (normHost != null) {
                                val hash64 = DomainBlockMatcher.fnv1a64(normHost)
                                container.domainIndex.addAutoLearnedHash(WebsiteCategory.TV_VIDEO, hash64)
                                serviceScope.launch {
                                    container.repository.recordAutoLearnedDomainHash(hash64, WebsiteCategory.TV_VIDEO)
                                }
                            }
                        }
                        enforceContentProtectionEviction(
                            blockedPackage = pkg,
                            navigateHome = false,
                            reason = "Unmoderated streaming, piracy, or aggregator site filtered",
                            notificationText = "Unmoderated streaming site blocked by Freedom.",
                            sessionId = sessionId
                        )
                        return
                    }
                }

                val pageCheck = InAppAdultFilterEngine.evaluateActiveContent(
                    urlOrQuery = activeSearchQuery ?: rawUrl,
                    visibleSnippets = pageSnippets
                )
                if (!isUserAllowlisted && pageCheck.isFlagged) {
                    enforceContentProtectionEviction(
                        blockedPackage = pkg,
                        navigateHome = false,
                        reason = pageCheck.reason.ifBlank { "Inappropriate search results or page filtered" },
                        notificationText = "Inappropriate or mature content filtered by Freedom.",
                        sessionId = sessionId
                    )
                    return
                }
            } else {
                // C. Silent Always-On Filtering for Native Third-Party Apps (Reddit, Telegram, Discord, etc.)
                val snippets = BrowserUrlExtractor.collectCombinedContentEvidence(
                    root = root,
                    displayWidth = screenWidth,
                    displayHeight = screenHeight,
                    maxPageSnippets = 36,
                    maxCards = 14,
                    maxTotalEvidence = 52
                ).mergedEvidence
                if (MatureMediaCatalog.isInAppPiracyAggregator(snippets)) {
                    enforceContentProtectionEviction(
                        blockedPackage = pkg,
                        navigateHome = false,
                        reason = "Piracy or streaming aggregator hub filtered in-app",
                        notificationText = "Unmoderated streaming hub blocked by Freedom.",
                        sessionId = sessionId,
                        isNativeThirdPartyApp = true
                    )
                    return
                }
                val res = InAppAdultFilterEngine.evaluateActiveContent(
                    urlOrQuery = null,
                    visibleSnippets = snippets
                )
                if (res.isFlagged) {
                    enforceContentProtectionEviction(
                        blockedPackage = pkg,
                        navigateHome = false,
                        reason = res.reason.ifBlank { "Inappropriate in-app content filtered" },
                        notificationText = "Inappropriate content filtered by Freedom.",
                        sessionId = sessionId,
                        isNativeThirdPartyApp = true
                    )
                    return
                }
            }
        }
    }

    /**
     * Cuts playback INSIDE YouTube / Morphe YT / ReVanced without exiting YouTube into Android PiP:
     *  - Watch/Live: Pause -> Back (collapses to miniplayer) -> multi-pass Miniplayer Close (×) + gesture fallback.
     *  - Miniplayer: Immediately clicks Miniplayer Close (×) or taps its right-edge Close button.
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
        screenHeight: Int,
        miniPlayerBounds: Rect? = null
    ) {
        val key = "ytcut:$blockedPackage:${cutAction.name}:$reason"
        val throttleMs = if (cutAction == YouTubeStudyEngine.PlaybackCutAction.EXIT_SEARCH_RESULTS) 1_200L else 550L
        if (shouldThrottle(key, throttleMs)) return

        when (cutAction) {
            YouTubeStudyEngine.PlaybackCutAction.CLOSE_WATCH_AND_MINIPLAYER -> {
                YouTubeAccessibilityInspector.tryPauseActiveVideo(root)
                // Anti-bounce lockout: if Watch was JUST collapsed (<1.5s ago), NEVER press Back
                // a second time (that would exit Morphe YT to Android Home!). Just close miniplayer.
                if (!isWithinWatchCollapseBackLockout(blockedPackage)) {
                    lastWatchCollapseCutPkg = blockedPackage
                    lastWatchCollapseCutTimeMs = SystemClock.elapsedRealtime()
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                scheduleMiniPlayerClosePasses(blockedPackage, screenWidth, screenHeight, miniPlayerBounds)
            }

            YouTubeStudyEngine.PlaybackCutAction.CLOSE_MINIPLAYER_ONLY -> {
                val closed = YouTubeAccessibilityInspector.tryCloseMiniPlayer(root, screenWidth, screenHeight)
                if (!closed) {
                    YouTubeAccessibilityInspector.tryPauseActiveVideo(root)
                    val liveBounds = miniPlayerBounds
                        ?: YouTubeAccessibilityInspector.findLiveMiniPlayerBounds(root, screenWidth, screenHeight)
                    if (liveBounds != null && liveBounds.width() > 0) {
                        // Tap rightmost Close (×) corner of the miniplayer bar directly
                        val tapX = (liveBounds.right - (26f * resources.displayMetrics.density))
                            .coerceIn(liveBounds.left + 8f, screenWidth - 8f)
                        val tapY = liveBounds.exactCenterY().coerceIn(8f, screenHeight - 8f)
                        dispatchTapGesture(tapX, tapY)
                    }
                    scheduleMiniPlayerClosePasses(blockedPackage, screenWidth, screenHeight, liveBounds)
                }
            }

            YouTubeStudyEngine.PlaybackCutAction.EXIT_SHORTS_TO_HOME -> {
                val switchedTab = YouTubeAccessibilityInspector.tryNavigateShortsToHome(
                    root = root,
                    screenHeight = screenHeight,
                    screenWidth = screenWidth
                )
                if (!switchedTab && !isWithinWatchCollapseBackLockout(blockedPackage)) {
                    lastWatchCollapseCutPkg = blockedPackage
                    lastWatchCollapseCutTimeMs = SystemClock.elapsedRealtime()
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }

            YouTubeStudyEngine.PlaybackCutAction.EXIT_SEARCH_RESULTS -> {
                if (!isWithinWatchCollapseBackLockout(blockedPackage)) {
                    lastWatchCollapseCutPkg = blockedPackage
                    lastWatchCollapseCutTimeMs = SystemClock.elapsedRealtime()
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }

            YouTubeStudyEngine.PlaybackCutAction.CLOSE_PIP_WINDOW -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }

            YouTubeStudyEngine.PlaybackCutAction.EVICT_CLIENT_HOME -> {
                stopBackgroundYouTubePlayback(blockedPackage, sessionId, notify = false)
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
     * Immediately stops background audio playback (e.g., Morphe YT / ReVanced / YouTube Premium
     * when the user taps an unapproved video and immediately presses Home before it finishes loading).
     */
    private fun stopBackgroundYouTubePlayback(
        ytPackage: String,
        sessionId: Long,
        notify: Boolean = true
    ) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null) {
            runCatching {
                val now = SystemClock.uptimeMillis()
                am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
                am.dispatchMediaKeyEvent(KeyEvent(now, now + 20, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
                am.dispatchMediaKeyEvent(KeyEvent(now + 40, now + 40, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP, 0))
                am.dispatchMediaKeyEvent(KeyEvent(now + 40, now + 60, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP, 0))
            }
            // Grab transient audio focus briefly so background players immediately yield & pause
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setOnAudioFocusChangeListener { }
                        .build()
                    am.requestAudioFocus(req)
                    mainHandler.postDelayed({ runCatching { am.abandonAudioFocusRequest(req) } }, 450L)
                } else {
                    @Suppress("DEPRECATION")
                    am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }
            }
        }
        scheduleBackgroundProcessKill(ytPackage)

        if (notify) {
            FreedomBlockNotifier.showBlockedAlert(
                context = this,
                message = "Unverified background YouTube playback stopped by Freedom."
            )
            val container = (applicationContext as? FreedomApplication)?.container ?: return
            val appLabel = container.installedAppsProvider.getAppLabel(ytPackage)
            serviceScope.launch {
                container.repository.recordBlockEvent(
                    sessionId = sessionId,
                    packageName = ytPackage,
                    appLabel = appLabel,
                    reason = "Unverified background YouTube playback stopped"
                )
            }
        }
    }

    /**
     * Rapidly clicks YouTube / Morphe YT's bottom miniplayer Close ("×") button right after
     * collapsing a blocked Watch player so video & audio stop completely INSIDE the app.
     * Re-scans the LIVE Accessibility tree on every pass to locate the freshly rendered
     * miniplayer bar coordinates (never relies solely on stale Watch-screen bounds!).
     */
    private fun scheduleMiniPlayerClosePasses(
        ytPackage: String,
        screenWidth: Int,
        screenHeight: Int,
        fallbackMiniBounds: Rect? = null
    ) {
        val delays = longArrayOf(150L, 350L, 620L, 960L)
        var miniPlayerConfirmedClosed = false
        for ((idx, delayMs) in delays.withIndex()) {
            mainHandler.postDelayed({
                if (miniPlayerConfirmedClosed) return@postDelayed
                val activeRoot = runCatching { rootInActiveWindow }.getOrNull() ?: return@postDelayed
                if (activeRoot.packageName?.toString() != ytPackage) return@postDelayed

                val closed = YouTubeAccessibilityInspector.tryCloseMiniPlayer(activeRoot, screenWidth, screenHeight)
                if (closed) {
                    miniPlayerConfirmedClosed = true
                    return@postDelayed
                }

                // Dynamically re-locate the freshly rendered miniplayer bar on screen
                val liveBounds = YouTubeAccessibilityInspector.findLiveMiniPlayerBounds(
                    root = activeRoot,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                ) ?: if (idx == 0) fallbackMiniBounds else null

                if (liveBounds == null) {
                    // After the initial 150ms settle window, absence of any live miniplayer means it's closed!
                    if (idx >= 1) miniPlayerConfirmedClosed = true
                    return@postDelayed
                }

                YouTubeAccessibilityInspector.tryPauseActiveVideo(activeRoot)

                if (liveBounds.width() > 0 && (idx == 1 || idx == 2)) {
                    val density = resources.displayMetrics.density
                    val rightInsetDp = if (idx == 1) 26f else 36f
                    val tapX = (liveBounds.right - (rightInsetDp * density))
                        .coerceIn(liveBounds.left + 8f, screenWidth - 8f)
                    val tapY = liveBounds.exactCenterY().coerceIn(8f, screenHeight - 8f)
                    dispatchTapGesture(tapX, tapY)
                } else if (idx == 3) {
                    // Final safety pass: ensure audio is silenced if OEM blocked gesture
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (am != null) {
                        runCatching {
                            val now = SystemClock.uptimeMillis()
                            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
                            am.dispatchMediaKeyEvent(KeyEvent(now, now + 20, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
                        }
                    }
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
     * Strictly single-shot, loop-free SafeSearch enforcement:
     *  - Deduplicates per `(browserPackage, providerId, normalizedQuery)` for 10 minutes so even if
     *    Bing, Brave Search, or Google strips the query parameter from the address bar after load,
     *    Freedom NEVER reloads again or spams new tabs!
     *  - Uses Freedom's own `packageName` as `Browser.EXTRA_APPLICATION_ID` so Chrome reuses its
     *    single Freedom tab instead of spawning extra tabs.
     *  - Returns true if a redirect was dispatched, false if already enforced for this query.
     */
    private fun enforceSafeSearchUrlRedirectOnce(
        browserPackage: String,
        providerId: String,
        providerName: String,
        searchQuery: String?,
        enforcedUrl: String,
        sessionId: Long
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val normQuery = searchQuery?.trim()?.lowercase().orEmpty()
        val dedupKey = "$browserPackage:$providerId:${normQuery.hashCode()}"
        val lastAt = recentEnforcedSafeSearchQueries[dedupKey]
        if (lastAt != null && (now - lastAt) < 600_000L) {
            return false
        }
        // Keep dedup cache tiny in memory
        if (recentEnforcedSafeSearchQueries.size > 64) {
            val cutoff = now - 600_000L
            recentEnforcedSafeSearchQueries.entries.removeIf { it.value < cutoff }
        }
        recentEnforcedSafeSearchQueries[dedupKey] = now

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(enforcedUrl)).apply {
            setPackage(browserPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(Browser.EXTRA_APPLICATION_ID, packageName)
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

        val container = (applicationContext as? FreedomApplication)?.container ?: return true
        val appLabel = container.installedAppsProvider.getAppLabel(browserPackage)
        serviceScope.launch {
            container.repository.recordBlockEvent(
                sessionId = sessionId,
                packageName = browserPackage,
                appLabel = appLabel,
                reason = "$providerName enforced"
            )
        }
        return true
    }

    /**
     * Clean eviction for SafeSearch tampering, browser YouTube links, and In-App Adult Protection.
     * Never logs URLs, search queries, or explicit titles, and never attaches a floating mask overlay.
     * Uses bounded single-hop rechecks so browsers and third-party apps never bounce-exit to Home.
     */
    private fun enforceContentProtectionEviction(
        blockedPackage: String,
        navigateHome: Boolean,
        reason: String,
        notificationText: String,
        sessionId: Long,
        isNativeThirdPartyApp: Boolean = false
    ) {
        val key = "cp:$blockedPackage:$reason"
        val throttleMs = if (isNativeThirdPartyApp) 1_200L else 850L
        if (shouldThrottle(key, throttleMs)) return

        if (navigateHome) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            scheduleBackgroundProcessKill(blockedPackage)
        } else {
            val now = SystemClock.elapsedRealtime()
            if (blockedPackage == lastEvictionRecheckPkg && (now - lastEvictionTimeMs) < 2_200L) {
                lastEvictionRecheckCount++
            } else {
                lastEvictionRecheckPkg = blockedPackage
                lastEvictionRecheckCount = 1
            }
            lastEvictionTimeMs = now

            performGlobalAction(GLOBAL_ACTION_BACK)
            // Bounded single-hop recheck in case a blocked web page used an in-page redirect hop;
            // never chain infinite follow-up Back presses that could kick the user out of the app.
            if (!isNativeThirdPartyApp && lastEvictionRecheckCount <= 1) {
                mainHandler.postDelayed({ recheckActiveWindowsNow() }, 480L)
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
     * Launches a bounded, non-blocking IO lookup for [videoId] with timeout safety.
     * Binds the result to [videoId] via [ytVerificationTracker] and discards any stale
     * completion if the user already switched to a different video.
     */
    private fun launchVideoChannelVerification(videoId: String) {
        val cleanVid = videoId.trim()
        if (!YouTubeStudyEngine.isValidVideoId(cleanVid)) return
        val container = (applicationContext as? FreedomApplication)?.container ?: return
        serviceScope.launch {
            var resolvedCid: String? = null
            var failureReason: String? = null
            try {
                resolvedCid = withTimeoutOrNull(8_000L) {
                    container.repository.verifyAndCacheDirectVideoChannel(cleanVid)
                }
                if (resolvedCid == null) {
                    failureReason = "Timed out or unresolvable channel metadata"
                }
            } catch (e: Exception) {
                failureReason = e.message ?: "Verification error"
            }
            val completion = ytVerificationTracker.completeAsyncLookup(
                videoId = cleanVid,
                resolvedChannelId = resolvedCid,
                failureReason = failureReason,
                nowMs = SystemClock.elapsedRealtime()
            )
            // Discard stale results completely if the user switched videos before completion!
            if (completion.appliedToCurrentVideo) {
                mainHandler.post { recheckActiveWindowsNow() }
            }
        }
    }

    /**
     * Resolves actual Android display dimensions from WindowManager / DisplayMetrics —
     * NEVER inferred from AccessibilityNodeInfo root bounds (which may only cover a partial window).
     */
    private fun getActualDisplayDimensions(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val bounds = runCatching { wm?.currentWindowMetrics?.bounds }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val dm = resources.displayMetrics
        return dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        ytVerificationTracker.clearAllCache()
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

        /**
         * Pure decision helper controlling bounded unknown-URL classification:
         *  - Always classifies a newly navigated URL on first observation.
         *  - Allows ONE bounded follow-up classification if the initial pass occurred before
         *    the WebView DOM finished loading (`lastEvidenceCount < 6`) and meaningful DOM/card
         *    evidence has now arrived (`currentEvidenceCount >= 6`).
         */
        internal fun shouldEvaluateUnknownSiteClassification(
            isNewUrl: Boolean,
            lastEvidenceCount: Int,
            lastAttempts: Int,
            currentEvidenceCount: Int
        ): Boolean {
            if (isNewUrl) return true
            return lastAttempts < 2 && lastEvidenceCount < 6 && currentEvidenceCount >= 6
        }
    }
}
