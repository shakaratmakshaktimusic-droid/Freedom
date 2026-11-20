package com.example

import com.example.data.sync.FilterListSyncEngine
import com.example.domain.BlockDecisionEngine
import com.example.domain.CompactDomainIndex
import com.example.domain.DomainBlockMatcher
import com.example.domain.ScheduleCalculator
import com.example.domain.SettingsWaitStateMachine
import com.example.domain.StrictModePolicy
import com.example.domain.UnknownSiteClassifier
import com.example.domain.WebsiteCategory
import com.example.ui.theme.DurationWheelBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class FreedomEngineUnitTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val ownPkg = "com.aistudio.freedom.kxrmvq"
    private val launcherExempt = setOf("com.android.launcher3")
    private val domainIndex = CompactDomainIndex()

    private fun utcTime(year: Int, monthZeroBased: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance(utc).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroBased)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    // ---------------------------------------------------------
    // 1. ScheduleCalculator (Recurring + Overnight + Later)
    // ---------------------------------------------------------

    @Test
    fun scheduleCalculator_sameDayWindow_activeAndInactive() {
        val work = ScheduleCalculator.ScheduleSpec(
            id = 1L,
            name = "Workday",
            blocklistId = 10L,
            isRecurring = true,
            daysMask = ScheduleCalculator.WEEKDAYS_MASK,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            strictMode = true,
            enabled = true
        )
        // Monday 2026-01-05 10:30 UTC
        val monMorning = utcTime(2026, Calendar.JANUARY, 5, 10, 30)
        val active = ScheduleCalculator.getActiveWindow(work, monMorning, utc)
        assertNotNull(active)
        assertEquals(utcTime(2026, Calendar.JANUARY, 5, 17, 0), active!!.endEpochMs)

        // Saturday 2026-01-10 10:30 UTC
        val satMorning = utcTime(2026, Calendar.JANUARY, 10, 10, 30)
        assertNull(ScheduleCalculator.getActiveWindow(work, satMorning, utc))
    }

    @Test
    fun scheduleCalculator_overnightWindow_spansMidnight() {
        val sleep = ScheduleCalculator.ScheduleSpec(
            id = 2L,
            name = "Sleep",
            blocklistId = 20L,
            isRecurring = true,
            daysMask = ScheduleCalculator.DAY_MON,
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 6 * 60,
            strictMode = true,
            enabled = true
        )
        // Monday 23:15 UTC -> active until Tuesday 06:00 UTC
        val monNight = utcTime(2026, Calendar.JANUARY, 5, 23, 15)
        val activeNight = ScheduleCalculator.getActiveWindow(sleep, monNight, utc)
        assertNotNull(activeNight)
        assertEquals(utcTime(2026, Calendar.JANUARY, 6, 6, 0), activeNight!!.endEpochMs)

        // Tuesday 03:30 UTC -> still active from Monday overnight window
        val tueEarly = utcTime(2026, Calendar.JANUARY, 6, 3, 30)
        val activeEarly = ScheduleCalculator.getActiveWindow(sleep, tueEarly, utc)
        assertNotNull(activeEarly)
        assertEquals(utcTime(2026, Calendar.JANUARY, 6, 6, 0), activeEarly!!.endEpochMs)

        // Tuesday 06:00 UTC -> expired
        val tueSix = utcTime(2026, Calendar.JANUARY, 6, 6, 0)
        assertNull(ScheduleCalculator.getActiveWindow(sleep, tueSix, utc))
    }

    @Test
    fun scheduleCalculator_oneTimeLaterSchedule_andOverlapMerge() {
        val laterStart = utcTime(2026, Calendar.JANUARY, 5, 14, 0)
        val laterEnd = utcTime(2026, Calendar.JANUARY, 5, 16, 0)
        val later = ScheduleCalculator.ScheduleSpec(
            id = 3L,
            name = "Exam Prep",
            blocklistId = 30L,
            isRecurring = false,
            oneTimeStartMs = laterStart,
            oneTimeEndMs = laterEnd,
            strictMode = true,
            enabled = true
        )
        val recurring = ScheduleCalculator.ScheduleSpec(
            id = 4L,
            name = "Afternoon Study",
            blocklistId = 40L,
            isRecurring = true,
            daysMask = ScheduleCalculator.EVERY_DAY_MASK,
            startMinuteOfDay = 13 * 60,
            endMinuteOfDay = 15 * 60,
            strictMode = false,
            enabled = true
        )

        val duringOverlap = utcTime(2026, Calendar.JANUARY, 5, 14, 30)
        val dominant = ScheduleCalculator.findDominantActiveWindow(
            schedules = listOf(later, recurring),
            nowMs = duringOverlap,
            timeZone = utc
        )
        assertNotNull(dominant)
        assertTrue(dominant!!.schedule.strictMode)
        assertEquals(laterEnd, dominant.endEpochMs)
        assertEquals(setOf(30L, 40L), dominant.allActiveBlocklistIds)

        val beforeLater = utcTime(2026, Calendar.JANUARY, 5, 12, 0)
        val next = ScheduleCalculator.findNextTransition(listOf(later), beforeLater, utc)
        assertNotNull(next)
        assertTrue(next!!.isStart)
        assertEquals(laterStart, next.triggerEpochMs)
    }

    // ---------------------------------------------------------
    // 2. DomainBlockMatcher & Highest-Precedence Website Allowlist
    // ---------------------------------------------------------

    @Test
    fun domainBlockMatcher_normalizesDomainsAndAllowRules() {
        assertEquals("youtube.com", DomainBlockMatcher.normalizeDomain("https://www.YouTube.com:443/watch?v=123"))
        assertEquals("reddit.com", DomainBlockMatcher.normalizeDomain("m.reddit.com/r/kotlin"))
        assertNull(DomainBlockMatcher.normalizeDomain("not a domain"))

        val domainRule = DomainBlockMatcher.normalizeAllowRule("https://www.wikipedia.org/")
        assertEquals("wikipedia.org" to false, domainRule)

        val pathRule = DomainBlockMatcher.normalizeAllowRule("https://www.youtube.com/@3Blue1Brown/")
        assertEquals("youtube.com/@3blue1brown" to true, pathRule)

        val extracted = DomainBlockMatcher.extractUrlFromUiText("https://en.wikipedia.org/wiki/Deep_Work")
        assertNotNull(extracted)
        assertEquals("en.wikipedia.org", extracted!!.host)
        assertEquals("en.wikipedia.org/wiki/deep_work", extracted.fullNormalizedUrl)
    }

    @Test
    fun domainBlockMatcher_websiteAllowlist_supportsDomainsAndPathPrefixes() {
        val allowPatterns = setOf("wikipedia.org", "youtube.com/@3blue1brown")

        assertTrue(
            DomainBlockMatcher.isAllowedByWebsiteAllowlist(
                candidateHost = "en.wikipedia.org",
                candidateFullUrl = "en.wikipedia.org/wiki/Focus",
                allowedPatterns = allowPatterns
            )
        )
        assertTrue(
            DomainBlockMatcher.isAllowedByWebsiteAllowlist(
                candidateHost = "youtube.com",
                candidateFullUrl = "youtube.com/@3blue1brown/videos",
                allowedPatterns = allowPatterns
            )
        )
        assertFalse(
            DomainBlockMatcher.isAllowedByWebsiteAllowlist(
                candidateHost = "youtube.com",
                candidateFullUrl = "youtube.com/shorts/random",
                allowedPatterns = allowPatterns
            )
        )
    }

    // ---------------------------------------------------------
    // 3. CompactDomainIndex & FilterListSyncEngine Parser
    // ---------------------------------------------------------

    @Test
    fun compactDomainIndex_matchesBuiltinCategoriesAndBinaryRoundTrip() {
        assertEquals(
            WebsiteCategory.SOCIAL,
            domainIndex.containsDomain("m.instagram.com", setOf(WebsiteCategory.SOCIAL))
        )
        assertEquals(
            WebsiteCategory.ADULT,
            domainIndex.containsDomain("www.pornhub.com", setOf(WebsiteCategory.ADULT))
        )
        assertNull(
            domainIndex.containsDomain("en.wikipedia.org", WebsiteCategory.entries.toSet())
        )

        // Binary cache write/read round-trip + auto-learned hash insertion
        val tempDir = File(System.getProperty("java.io.tmpdir"), "freedom_index_test_${System.nanoTime()}")
        try {
            val freshHash = DomainBlockMatcher.fnv1a64("new-mirror-betting.xyz")
            val binFile = File(tempDir, CompactDomainIndex.fileNameForCategory(WebsiteCategory.GAMBLING))
            CompactDomainIndex.writeSortedHashesToFile(binFile, longArrayOf(freshHash))

            val loadedIndex = CompactDomainIndex()
            loadedIndex.reloadFromCacheDir(tempDir)
            assertEquals(
                WebsiteCategory.GAMBLING,
                loadedIndex.containsDomain("sub.new-mirror-betting.xyz", setOf(WebsiteCategory.GAMBLING))
            )

            val autoHash = DomainBlockMatcher.fnv1a64("brandnew-unknown-mirror.top")
            loadedIndex.addAutoLearnedHash(WebsiteCategory.ADULT, autoHash)
            assertEquals(
                WebsiteCategory.ADULT,
                loadedIndex.containsDomain("brandnew-unknown-mirror.top", setOf(WebsiteCategory.ADULT))
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun filterListSyncEngine_parsesHostsAdBlockAndRawFormats() {
        assertEquals("example-blocked.com", FilterListSyncEngine.parseDomainFromFilterLine("0.0.0.0 example-blocked.com"))
        assertEquals("bet-mirror.net", FilterListSyncEngine.parseDomainFromFilterLine("127.0.0.1 www.bet-mirror.net # comment"))
        assertEquals("adblock-site.org", FilterListSyncEngine.parseDomainFromFilterLine("||adblock-site.org^"))
        assertEquals("raw-domain.io", FilterListSyncEngine.parseDomainFromFilterLine("raw-domain.io"))
        assertNull(FilterListSyncEngine.parseDomainFromFilterLine("# full line comment"))
        assertNull(FilterListSyncEngine.parseDomainFromFilterLine("0.0.0.0 localhost"))
        assertNull(FilterListSyncEngine.parseDomainFromFilterLine("! AdBlock header"))
    }

    // ---------------------------------------------------------
    // 4. UnknownSiteClassifier (Ultra-Light Bounded Heuristic)
    // ---------------------------------------------------------

    @Test
    fun unknownSiteClassifier_detectsUnknownDistractingSitesAndSkipsSafeDomains() {
        val adultHit = UnknownSiteClassifier.classify(
            host = "fresh-new-porntube-mirror.xyz",
            fullUrl = "fresh-new-porntube-mirror.xyz/videos",
            visibleTextSnippets = listOf("18+ only", "Free Porn Videos"),
            activeCategories = setOf(WebsiteCategory.ADULT)
        )
        assertNotNull(adultHit)
        assertEquals(WebsiteCategory.ADULT, adultHit!!.category)

        // Must NOT trigger when category isn't active
        val inactiveCat = UnknownSiteClassifier.classify(
            host = "fresh-new-porntube-mirror.xyz",
            fullUrl = "fresh-new-porntube-mirror.xyz/videos",
            visibleTextSnippets = listOf("18+ only"),
            activeCategories = setOf(WebsiteCategory.SHOPPING)
        )
        assertNull(inactiveCat)

        // Must skip safe domains like Wikipedia & .edu
        val safeWiki = UnknownSiteClassifier.classify(
            host = "en.wikipedia.org",
            fullUrl = "en.wikipedia.org/wiki/Online_gambling",
            visibleTextSnippets = listOf("online casino", "sports betting", "place your bet"),
            activeCategories = setOf(WebsiteCategory.GAMBLING)
        )
        assertNull(safeWiki)

        val safeEdu = UnknownSiteClassifier.classify(
            host = "cs.stanford.edu",
            fullUrl = "cs.stanford.edu/casino-probability",
            visibleTextSnippets = listOf("online casino", "blackjack"),
            activeCategories = setOf(WebsiteCategory.GAMBLING)
        )
        assertNull(safeEdu)
    }

    // ---------------------------------------------------------
    // 5. SettingsWaitStateMachine (Fixed 5-Min Wait -> 3-Min Unlock)
    // ---------------------------------------------------------

    @Test
    fun settingsWaitStateMachine_enforces5MinWaitAnd3MinUnlock() {
        assertEquals(300_000L, SettingsWaitStateMachine.EMERGENCY_WAIT_DURATION_MS)
        assertEquals(180_000L, SettingsWaitStateMachine.TEMPORARY_UNLOCK_DURATION_MS)

        val start = 10_000L
        val target = start + SettingsWaitStateMachine.EMERGENCY_WAIT_DURATION_MS
        assertFalse(SettingsWaitStateMachine.hasCompletedWait(start + 299_999L, target))
        assertTrue(SettingsWaitStateMachine.hasCompletedWait(start + 300_000L, target))

        val unlockExpiry = SettingsWaitStateMachine.computeNewUnlockExpiry(target)
        assertTrue(SettingsWaitStateMachine.isTemporarilyUnlocked(target + 179_999L, unlockExpiry))
        assertFalse(SettingsWaitStateMachine.isTemporarilyUnlocked(target + 180_000L, unlockExpiry))
    }

    // ---------------------------------------------------------
    // 6. BlockDecisionEngine (Zero-VPN Multi-Window, Strict System Locks,
    //    Website Allowlist Precedence, Full Internet Browsing Block)
    // ---------------------------------------------------------

    @Test
    fun decisionEngine_hardBlocksTamperSystemAppsAndRequires5MinWaitForSettingsOnly() {
        val snap = BlockDecisionEngine.ActiveBlockSnapshot(
            isActive = true,
            sessionId = 1L,
            label = "Locked Deep Work",
            endWallTimeMs = 200_000L,
            endElapsedRealtimeMs = 150_000L,
            strictMode = true,
            allowedPackages = setOf("com.android.chrome")
        )

        // Play Store & Package Installer are HARD-BLOCKED with NO Settings wait door
        val playStore = BlockDecisionEngine.evaluate(
            foregroundPackage = "com.android.vending",
            windowClassName = null,
            extractedHost = null,
            snapshot = snap,
            domainIndex = domainIndex,
            ownPackageName = ownPkg,
            dynamicExemptPackages = launcherExempt,
            nowWallMs = 100_000L,
            nowElapsedRealtimeMs = 50_000L
        )
        assertTrue(playStore is BlockDecisionEngine.Decision.BlockApp)
        assertTrue((playStore as BlockDecisionEngine.Decision.BlockApp).isSystemTamperLock)

        // Native Android Settings opens 5-minute Emergency Waiting Room
        val settingsLocked = BlockDecisionEngine.evaluate(
            foregroundPackage = "com.android.settings",
            windowClassName = "com.android.settings.SubSettings",
            extractedHost = null,
            snapshot = snap,
            domainIndex = domainIndex,
            ownPackageName = ownPkg,
            dynamicExemptPackages = launcherExempt,
            nowWallMs = 100_000L,
            nowElapsedRealtimeMs = 50_000L
        )
        assertTrue(settingsLocked is BlockDecisionEngine.Decision.RequireSettingsWait)

        // During the 3-minute temporary unlock window, Android Settings is allowed
        val unlockedSnap = snap.copy(settingsUnlockedUntilElapsedMs = 230_000L)
        val settingsUnlocked = BlockDecisionEngine.evaluate(
            foregroundPackage = "com.android.settings",
            windowClassName = "com.android.settings.SubSettings",
            extractedHost = null,
            snapshot = unlockedSnap,
            domainIndex = domainIndex,
            ownPackageName = ownPkg,
            dynamicExemptPackages = launcherExempt,
            nowWallMs = 100_000L,
            nowElapsedRealtimeMs = 60_000L
        )
        assertEquals(BlockDecisionEngine.Decision.Allow, settingsUnlocked)

        // Emergency dialer is ALWAYS allowed
        val emergency = BlockDecisionEngine.evaluate(
            foregroundPackage = "com.android.emergency",
            windowClassName = null,
            extractedHost = null,
            snapshot = snap,
            domainIndex = domainIndex,
            ownPackageName = ownPkg,
            dynamicExemptPackages = launcherExempt,
            nowWallMs = 100_000L,
            nowElapsedRealtimeMs = 50_000L
        )
        assertEquals(BlockDecisionEngine.Decision.Allow, emergency)
    }

    @Test
    fun decisionEngine_dualClockImmunityAndFullBrowsingBlockWithAllowlistOverride() {
        val snap = BlockDecisionEngine.ActiveBlockSnapshot(
            isActive = true,
            sessionId = 2L,
            label = "No Browsing Study",
            endWallTimeMs = 200_000L,
            endElapsedRealtimeMs = 150_000L,
            strictMode = true,
            fullInternetBrowsingBlock = true,
            allowedPackages = setOf("com.android.chrome", "com.whatsapp"),
            allowedWebPatterns = setOf("wikipedia.org"),
            blockedWebCategories = setOf(WebsiteCategory.SOCIAL, WebsiteCategory.ADULT)
        )

        // Wall clock jumped 10 hours forward, monotonic clock still active -> MUST stay active!
        assertTrue(snap.isStillActive(nowWallMs = 99_999_999L, nowElapsedRealtimeMs = 100_000L))

        // Unallowlisted website in Chrome -> BlockBrowsing
        val blockedSite = BlockDecisionEngine.evaluate(
            foregroundPackage = "com.android.chrome",
            windowClassName = null,
            extractedHost = "reddit.com",
            extractedFullUrl = "reddit.com/r/all",
            isKnownBrowser = true,
            snapshot = snap,
            domainIndex = domainIndex,
            ownPackageName = ownPkg,
            dynamicExemptPackages = launcherExempt,
            nowWallMs = 100_000L,
            nowElapsedRealtimeMs = 50_000L
        )
        assertTrue(blockedSite is BlockDecisionEngine.Decision.BlockBrowsing)

        // In-app WebView link clicked inside allowlisted WhatsApp -> BlockBrowsing (isInAppWebView = true)
        val inAppLink = BlockDecisionEngine.evaluate(
            foregroundPackage = "com.whatsapp",
            windowClassName = "android.webkit.WebView",
            extractedHost = "news.ycombinator.com",
            extractedFullUrl = "news.ycombinator.com",
            isKnownBrowser = false,
            isCustomTabOrWebView = true,
            snapshot = snap,
            domainIndex = domainIndex,
            ownPackageName = ownPkg,
            dynamicExemptPackages = launcherExempt,
            nowWallMs = 100_000L,
            nowElapsedRealtimeMs = 50_000L
        )
        assertTrue(inAppLink is BlockDecisionEngine.Decision.BlockBrowsing)
        assertTrue((inAppLink as BlockDecisionEngine.Decision.BlockBrowsing).isInAppWebView)

        // Explicitly Allowlisted Website (wikipedia.org) OVERRIDES Full Internet Browsing Block!
        val allowedWiki = BlockDecisionEngine.evaluate(
            foregroundPackage = "com.android.chrome",
            windowClassName = null,
            extractedHost = "en.wikipedia.org",
            extractedFullUrl = "en.wikipedia.org/wiki/Neuroscience",
            isKnownBrowser = true,
            snapshot = snap,
            domainIndex = domainIndex,
            ownPackageName = ownPkg,
            dynamicExemptPackages = launcherExempt,
            nowWallMs = 100_000L,
            nowElapsedRealtimeMs = 50_000L
        )
        assertEquals(BlockDecisionEngine.Decision.Allow, allowedWiki)
    }

    @Test
    fun decisionEngine_multiWindowSplitScreenAndPipCaughtSimultaneously() {
        val snap = BlockDecisionEngine.ActiveBlockSnapshot(
            isActive = true,
            sessionId = 3L,
            label = "Deep Work",
            endWallTimeMs = 200_000L,
            endElapsedRealtimeMs = 150_000L,
            strictMode = false,
            allowedPackages = setOf("com.example.notes")
        )

        val windows = listOf(
            BlockDecisionEngine.ObservedWindow(
                packageName = "com.example.notes",
                isSplitScreenActive = true
            ),
            BlockDecisionEngine.ObservedWindow(
                packageName = "com.google.android.youtube",
                isPictureInPicture = true
            )
        )

        val decision = BlockDecisionEngine.evaluateWindows(
            windows = windows,
            snapshot = snap,
            domainIndex = domainIndex,
            ownPackageName = ownPkg,
            dynamicExemptPackages = launcherExempt,
            nowWallMs = 100_000L,
            nowElapsedRealtimeMs = 50_000L
        )
        assertTrue(decision is BlockDecisionEngine.Decision.BlockApp)
        val blockApp = decision as BlockDecisionEngine.Decision.BlockApp
        assertEquals("com.google.android.youtube", blockApp.packageName)
        assertTrue(blockApp.isPictureInPicture)
    }

    // ---------------------------------------------------------
    // 7. StrictModePolicy Guards
    // ---------------------------------------------------------

    @Test
    fun strictModePolicy_blocksWeakeningActionsDuringLockedMode() {
        assertTrue(StrictModePolicy.canStopSession(true) is StrictModePolicy.GuardResult.Denied)
        assertTrue(StrictModePolicy.canStopSession(false) is StrictModePolicy.GuardResult.Permitted)

        assertTrue(StrictModePolicy.canAddAllowedApp(true) is StrictModePolicy.GuardResult.Denied)
        assertTrue(StrictModePolicy.canRemoveAllowedApp() is StrictModePolicy.GuardResult.Permitted)

        assertTrue(StrictModePolicy.canAddAllowedWebsite(true) is StrictModePolicy.GuardResult.Denied)
        assertTrue(StrictModePolicy.canWeakenBlocklist(true) is StrictModePolicy.GuardResult.Denied)
        assertTrue(StrictModePolicy.canDisableAutoDetect(true) is StrictModePolicy.GuardResult.Denied)
        assertTrue(StrictModePolicy.canDisableLockedModeSetting(true) is StrictModePolicy.GuardResult.Denied)

        assertTrue(
            StrictModePolicy.canModifyOrDisableSchedule(5L, 5L, true) is StrictModePolicy.GuardResult.Denied
        )
        assertTrue(
            StrictModePolicy.canModifyOrDisableSchedule(6L, 5L, true) is StrictModePolicy.GuardResult.Permitted
        )
    }

    // ---------------------------------------------------------
    // 8. 0-24H Rotary Wheel Clamping & Vivo Small/Mini Window Flagging
    // ---------------------------------------------------------

    @Test
    fun durationWheelBounds_supportsMinuteByMinuteUpTo24Hours() {
        assertEquals(1, DurationWheelBounds.clampDuration(0, 0))
        assertEquals(6, DurationWheelBounds.clampDuration(0, 6))
        assertEquals(1439, DurationWheelBounds.clampDuration(23, 59))
        assertEquals(1440, DurationWheelBounds.clampDuration(24, 0))
        assertEquals(1440, DurationWheelBounds.clampDuration(24, 45))
        assertEquals(14 to 37, DurationWheelBounds.splitDuration(14 * 60 + 37))
    }

    @Test
    fun decisionEngine_flagsFloatingSmallAndMiniWindowsForDedicatedTeardown() {
        val snap = BlockDecisionEngine.ActiveBlockSnapshot(
            isActive = true,
            sessionId = 9L,
            label = "Study",
            endWallTimeMs = 200_000L,
            endElapsedRealtimeMs = 150_000L,
            strictMode = true,
            allowedPackages = setOf("com.example.notes")
        )

        val windows = listOf(
            BlockDecisionEngine.ObservedWindow(packageName = "com.example.notes"),
            BlockDecisionEngine.ObservedWindow(
                packageName = "com.instagram.android",
                isFloatingWindow = true
            )
        )

        val decision = BlockDecisionEngine.evaluateWindows(
            windows = windows,
            snapshot = snap,
            domainIndex = domainIndex,
            ownPackageName = ownPkg,
            dynamicExemptPackages = launcherExempt,
            nowWallMs = 100_000L,
            nowElapsedRealtimeMs = 50_000L
        )
        assertTrue(decision is BlockDecisionEngine.Decision.BlockApp)
        val blockApp = decision as BlockDecisionEngine.Decision.BlockApp
        assertEquals("com.instagram.android", blockApp.packageName)
        assertTrue(blockApp.isFloatingWindow)
        assertTrue(blockApp.isMultiWindowOrPip)
    }
}
