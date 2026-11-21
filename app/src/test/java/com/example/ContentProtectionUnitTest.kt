package com.example

import com.example.data.remote.YouTubeChannelResolver
import com.example.domain.ChannelLookupState
import com.example.domain.ChannelVerificationSource
import com.example.domain.ContentProtectionConfig
import com.example.domain.InAppAdultFilterEngine
import com.example.domain.SafeSearchEngine
import com.example.domain.StrictModePolicy
import com.example.domain.YouTubeClientFamily
import com.example.domain.YouTubeClientRegistry
import com.example.domain.YouTubeClientSupportTier
import com.example.domain.YouTubeStudyEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentProtectionUnitTest {

    private val approvedSnapshot = YouTubeStudyEngine.ApprovedChannelSnapshot(
        channelIds = setOf("UCYO_jab_esuFRV4b17AJtAw", "UC4a-Gbdw7vOaccHmFo40b9g"),
        normalizedHandles = setOf("@3blue1brown", "@khanacademy"),
        normalizedTitles = setOf("3blue1brown", "khan academy"),
        videoToChannelIdCache = mapOf(
            "aircAruvnKk" to "UCYO_jab_esuFRV4b17AJtAw", // Approved
            "dQw4w9WgXcQ" to "UCuAXFkgsw1L7xaCfnd5JJOw"  // Unapproved
        )
    )

    private val studyConfig = ContentProtectionConfig(
        youtubeStudyModeEnabled = true,
        youtubeMaskFeedCards = true,
        youtubeAllowShortsFromApproved = false,
        youtubeAllowLiveFromApproved = false,
        safeSearchEnabled = true,
        blockUnverifiedSearchEnginesInStrictMode = true,
        inAppAdultFilterEnabled = true,
        blockImageSearchInStrictMode = true
    )

    // -------------------------------------------------------------------------
    // 1. Regain-Style YouTube Study Mode (Allowlist, Feed Cards, Shorts/Live, Direct Links)
    // -------------------------------------------------------------------------

    @Test
    fun youtubeStudyMode_allowsApprovedChannel_andBlocksUnapprovedOrUnverifiedInStrictMode() {
        val allowed = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
            detectedChannelId = null,
            detectedHandle = "@3Blue1Brown",
            detectedChannelTitle = "3Blue1Brown",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = true
        )
        assertTrue(allowed is YouTubeStudyEngine.StudyDecision.Allow)

        val blockedOther = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
            detectedChannelId = null,
            detectedHandle = "@randomgaming",
            detectedChannelTitle = "Random Gaming",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = true
        )
        assertTrue(blockedOther is YouTubeStudyEngine.StudyDecision.BlockPlayback)

        // Fail-closed in Strict Mode when channel attribution cannot be read
        val blockedUnverified = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
            detectedChannelId = null,
            detectedHandle = null,
            detectedChannelTitle = null,
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = true
        )
        assertTrue(blockedUnverified is YouTubeStudyEngine.StudyDecision.BlockPlayback)
    }

    @Test
    fun youtubeStudyMode_feedCardsKeepApprovedVisibleAndMaskUnapproved() {
        val approvedCardMasked = YouTubeStudyEngine.shouldMaskFeedCard(
            cardTextCandidates = listOf("But what is a neural network? · 3Blue1Brown · 18M views · 6 years ago"),
            isShortCard = false,
            isLiveCard = false,
            approved = approvedSnapshot,
            config = studyConfig
        )
        assertFalse("Approved channel card must remain unobstructed in Home/Search", approvedCardMasked)

        val unapprovedCardMasked = YouTubeStudyEngine.shouldMaskFeedCard(
            cardTextCandidates = listOf("Top 10 Viral Pranks · DistractionTV · 4M views · 2 days ago"),
            isShortCard = false,
            isLiveCard = false,
            approved = approvedSnapshot,
            config = studyConfig
        )
        assertTrue("Unapproved channel card must be masked", unapprovedCardMasked)

        // Shorts from approved channel are still masked when Shorts are disallowed
        val approvedShortMasked = YouTubeStudyEngine.shouldMaskFeedCard(
            cardTextCandidates = listOf("Quick matrix trick · 3Blue1Brown"),
            isShortCard = true,
            isLiveCard = false,
            approved = approvedSnapshot,
            config = studyConfig
        )
        assertTrue("Shorts must be blocked unless explicitly allowed", approvedShortMasked)
    }

    @Test
    fun youtubeStudyMode_directVideoAndChannelLinksVerified() {
        // Cached approved video link -> Allow
        val okVideo = YouTubeStudyEngine.evaluateDirectYouTubeUrl(
            rawUrl = "https://www.youtube.com/watch?v=aircAruvnKk",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = true
        )
        assertTrue(okVideo is YouTubeStudyEngine.StudyDecision.Allow)

        // Cached unapproved video link -> Block
        val badVideo = YouTubeStudyEngine.evaluateDirectYouTubeUrl(
            rawUrl = "https://youtu.be/dQw4w9WgXcQ",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = true
        )
        assertTrue(badVideo is YouTubeStudyEngine.StudyDecision.BlockPlayback)

        // Uncached video link -> NeedsVideoChannelVerification (no blind allow!)
        val uncached = YouTubeStudyEngine.evaluateDirectYouTubeUrl(
            rawUrl = "https://m.youtube.com/watch?v=abcdefghijk",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = true
        )
        assertTrue(uncached is YouTubeStudyEngine.StudyDecision.NeedsVideoChannelVerification)

        // Direct Shorts link blocked by default
        val shorts = YouTubeStudyEngine.evaluateDirectYouTubeUrl(
            rawUrl = "https://www.youtube.com/shorts/aircAruvnKk",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = true
        )
        assertTrue(shorts is YouTubeStudyEngine.StudyDecision.BlockPlayback)
    }

    // -------------------------------------------------------------------------
    // 2. Multi-Client Registry (Official YouTube, Morphe YT, ReVanced, Third-Party)
    // -------------------------------------------------------------------------

    @Test
    fun clientRegistry_identifiesOfficialMorpheRevancedAndBlocksUnverifiableInStrictMode() {
        val official = YouTubeClientRegistry.identifyClient("com.google.android.youtube", "YouTube")
        assertNotNull(official)
        assertEquals(YouTubeClientFamily.OFFICIAL, official?.family)
        assertEquals(YouTubeClientSupportTier.FULL_STUDY_MODE, official?.supportTier)

        val morphe = YouTubeClientRegistry.identifyClient("app.morphe.android.youtube", "Morphe YT")
        assertNotNull(morphe)
        assertEquals(YouTubeClientFamily.MORPHE, morphe?.family)
        assertEquals(YouTubeClientSupportTier.FULL_STUDY_MODE, morphe?.supportTier)

        val customMorphe = YouTubeClientRegistry.identifyClient("org.custom.morphe.yt", "Morphe YT")
        assertNotNull(customMorphe)
        assertEquals(YouTubeClientFamily.MORPHE, customMorphe?.family)

        val revanced = YouTubeClientRegistry.identifyClient("app.revanced.android.youtube", "YouTube ReVanced")
        assertNotNull(revanced)
        assertEquals(YouTubeClientFamily.REVANCED, revanced?.family)

        val grayjay = YouTubeClientRegistry.identifyClient("com.futo.platformplayer", "Grayjay")
        assertNotNull(grayjay)
        assertEquals(YouTubeClientSupportTier.UNVERIFIABLE, grayjay?.supportTier)

        val strictDecision = YouTubeStudyEngine.evaluateClientCompatibility(grayjay!!, strictMode = true)
        assertTrue(strictDecision is YouTubeStudyEngine.StudyDecision.BlockPlayback)
    }

    // -------------------------------------------------------------------------
    // 3. Hybrid 3-Tier YouTube Channel ID Resolver
    // -------------------------------------------------------------------------

    @Test
    fun hybridChannelResolver_verifiesPublicHtml_fallsBackToApiV3_andSupportsManualRss() = runTest {
        val sampleChannelHtml = """
            <html><head>
            <link rel="canonical" href="https://www.youtube.com/channel/UCYO_jab_esuFRV4b17AJtAw">
            <meta property="og:title" content="3Blue1Brown">
            <meta property="og:image" content="https://yt3.googleusercontent.com/avatar.jpg">
            </head><body>"canonicalBaseUrl":"/@3blue1brown"</body></html>
        """.trimIndent()

        val tier1Resolver = YouTubeChannelResolver { url ->
            if (url.contains("@3blue1brown")) {
                YouTubeChannelResolver.HttpFetchResult(200, sampleChannelHtml)
            } else {
                YouTubeChannelResolver.HttpFetchResult(404)
            }
        }
        val t1Result = tier1Resolver.resolveInput("https://www.youtube.com/@3blue1brown", "")
        assertTrue(t1Result is ChannelLookupState.VerifiedCandidate)
        val verified1 = (t1Result as ChannelLookupState.VerifiedCandidate).channel
        assertEquals("UCYO_jab_esuFRV4b17AJtAw", verified1.channelId)
        assertEquals("@3blue1brown", verified1.handle)
        assertEquals("3Blue1Brown", verified1.title)
        assertEquals(ChannelVerificationSource.PUBLIC_METADATA, verified1.verifiedSource)

        // Tier 2 API v3 Fallback when public lookup returns 429 rate-limit
        val apiJson = """
            {"items":[{"id":"UC4a-Gbdw7vOaccHmFo40b9g","snippet":{"title":"Khan Academy","customUrl":"@khanacademy","thumbnails":{"medium":{"url":"https://yt3.ggpht.com/ka.jpg"}}},"statistics":{"subscriberCount":"8500000"}}]}
        """.trimIndent()
        val tier2Resolver = YouTubeChannelResolver { url ->
            when {
                url.contains("googleapis.com/youtube/v3/channels") -> YouTubeChannelResolver.HttpFetchResult(200, apiJson)
                else -> YouTubeChannelResolver.HttpFetchResult(429)
            }
        }
        val t2Result = tier2Resolver.resolveInput("https://www.youtube.com/@khanacademy", "TEST_USER_API_KEY")
        assertTrue(t2Result is ChannelLookupState.VerifiedCandidate)
        val verified2 = (t2Result as ChannelLookupState.VerifiedCandidate).channel
        assertEquals("UC4a-Gbdw7vOaccHmFo40b9g", verified2.channelId)
        assertEquals(ChannelVerificationSource.YOUTUBE_DATA_API_V3, verified2.verifiedSource)

        // Tier 3 Manual UC... Channel ID -> RSS verification when channel page fails
        val rssXml = "<feed><yt:channelId>UCYO_jab_esuFRV4b17AJtAw</yt:channelId><title>3Blue1Brown</title></feed>"
        val tier3Resolver = YouTubeChannelResolver { url ->
            if (url.contains("/feeds/videos.xml?channel_id=UCYO_jab_esuFRV4b17AJtAw")) {
                YouTubeChannelResolver.HttpFetchResult(200, rssXml)
            } else {
                YouTubeChannelResolver.HttpFetchResult(503)
            }
        }
        val t3Result = tier3Resolver.verifyByPermanentChannelId("UCYO_jab_esuFRV4b17AJtAw", "")
        assertTrue(t3Result is ChannelLookupState.VerifiedCandidate)
        assertEquals(
            ChannelVerificationSource.MANUAL_ID_RSS,
            (t3Result as ChannelLookupState.VerifiedCandidate).channel.verifiedSource
        )

        // Tier 1 Video URL -> oEmbed -> Channel permanent ID verification
        val oembedResolver = YouTubeChannelResolver { url ->
            when {
                url.contains("/oembed?") -> YouTubeChannelResolver.HttpFetchResult(
                    200,
                    """{"title":"Neural Networks","author_name":"3Blue1Brown","author_url":"https://www.youtube.com/@3blue1brown"}"""
                )
                url.contains("@3blue1brown") -> YouTubeChannelResolver.HttpFetchResult(200, sampleChannelHtml)
                else -> YouTubeChannelResolver.HttpFetchResult(404)
            }
        }
        val videoRes = oembedResolver.resolveInput("https://youtu.be/aircAruvnKk", "")
        assertTrue(videoRes is ChannelLookupState.VerifiedCandidate)
        assertEquals("UCYO_jab_esuFRV4b17AJtAw", (videoRes as ChannelLookupState.VerifiedCandidate).channel.channelId)

        // Malformed ID is strictly rejected (never approves unverified channel)
        val badId = tier3Resolver.verifyByPermanentChannelId("NOT_A_VALID_ID", "")
        assertTrue(badId is ChannelLookupState.Error)
    }

    // -------------------------------------------------------------------------
    // 4. Dedicated Universal SafeSearch Engine
    // -------------------------------------------------------------------------

    @Test
    fun safeSearchEngine_enforcesStrictParamsAcrossProvidersAndPreventsLoops() {
        // Google without safe=active -> EnforceRedirect with safe=active
        val googleEnforce = SafeSearchEngine.evaluateUrl(
            rawUrl = "https://www.google.com/search?q=linear+algebra",
            config = studyConfig,
            strictMode = true
        )
        assertTrue(googleEnforce is SafeSearchEngine.Decision.EnforceRedirect)
        val enforcedGoogleUrl = (googleEnforce as SafeSearchEngine.Decision.EnforceRedirect).enforcedUrl
        assertTrue(enforcedGoogleUrl.contains("safe=active"))

        // Already compliant Google URL -> Compliant (no redirect loop!)
        val googleCompliant = SafeSearchEngine.evaluateUrl(
            rawUrl = enforcedGoogleUrl,
            config = studyConfig,
            strictMode = true
        )
        assertTrue(googleCompliant is SafeSearchEngine.Decision.Compliant)

        // Bing Strict (`adlt=strict`)
        val bingEnforce = SafeSearchEngine.evaluateUrl(
            rawUrl = "https://www.bing.com/search?q=calculus&adlt=off",
            config = studyConfig,
            strictMode = true
        )
        assertTrue(bingEnforce is SafeSearchEngine.Decision.EnforceRedirect)
        assertTrue((bingEnforce as SafeSearchEngine.Decision.EnforceRedirect).enforcedUrl.contains("adlt=strict"))

        // DuckDuckGo Strict (`kp=1`)
        val ddgEnforce = SafeSearchEngine.evaluateUrl(
            rawUrl = "https://duckduckgo.com/?q=physics&kp=-2",
            config = studyConfig,
            strictMode = true
        )
        assertTrue(ddgEnforce is SafeSearchEngine.Decision.EnforceRedirect)
        assertTrue((ddgEnforce as SafeSearchEngine.Decision.EnforceRedirect).enforcedUrl.contains("kp=1"))

        // Yahoo Strict (`vm=r`)
        val yahooEnforce = SafeSearchEngine.evaluateUrl(
            rawUrl = "https://search.yahoo.com/search?p=chemistry",
            config = studyConfig,
            strictMode = true
        )
        assertTrue(yahooEnforce is SafeSearchEngine.Decision.EnforceRedirect)
        assertTrue((yahooEnforce as SafeSearchEngine.Decision.EnforceRedirect).enforcedUrl.contains("vm=r"))
    }

    @Test
    fun safeSearchEngine_blocksSettingsTamper_imageSearchInStrictMode_andUnverifiedEngines() {
        val tamper = SafeSearchEngine.evaluateUrl(
            rawUrl = "https://www.google.com/safesearch",
            config = studyConfig,
            strictMode = true
        )
        assertTrue(tamper is SafeSearchEngine.Decision.BlockSettingsTamper)

        val googleImagesBlocked = SafeSearchEngine.evaluateUrl(
            rawUrl = "https://www.google.com/search?q=nature&tbm=isch&safe=active",
            config = studyConfig,
            strictMode = true
        )
        assertTrue(googleImagesBlocked is SafeSearchEngine.Decision.BlockImageSearch)

        val unknownEngineBlocked = SafeSearchEngine.evaluateUrl(
            rawUrl = "https://searx.be/search?q=anything",
            config = studyConfig,
            strictMode = true
        )
        assertTrue(unknownEngineBlocked is SafeSearchEngine.Decision.BlockUnverifiedSearchEngine)
    }

    @Test
    fun youtubeStudyMode_nonPipInAppCutActionsForWatchMiniplayerAndShorts() {
        // Watch player from unapproved channel -> CLOSE_WATCH_AND_MINIPLAYER (never GLOBAL_ACTION_HOME)
        val blockedWatch = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
            detectedChannelId = null,
            detectedHandle = "@distraction",
            detectedChannelTitle = "Distraction TV",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = true
        )
        assertTrue(blockedWatch is YouTubeStudyEngine.StudyDecision.BlockPlayback)
        assertEquals(
            YouTubeStudyEngine.PlaybackCutAction.CLOSE_WATCH_AND_MINIPLAYER,
            (blockedWatch as YouTubeStudyEngine.StudyDecision.BlockPlayback).cutAction
        )
        assertFalse(blockedWatch.navigateHome)

        // Unapproved video lingering in bottom Miniplayer -> CLOSE_MINIPLAYER_ONLY
        val blockedMini = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.MINI_PLAYER,
            detectedChannelId = null,
            detectedHandle = null,
            detectedChannelTitle = "Distraction TV",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = false,
            lastApprovedWatchActive = false
        )
        assertTrue(blockedMini is YouTubeStudyEngine.StudyDecision.BlockPlayback)
        assertEquals(
            YouTubeStudyEngine.PlaybackCutAction.CLOSE_MINIPLAYER_ONLY,
            (blockedMini as YouTubeStudyEngine.StudyDecision.BlockPlayback).cutAction
        )

        // Approved video minimized to bottom Miniplayer -> Allow
        val allowedMini = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.MINI_PLAYER,
            detectedChannelId = null,
            detectedHandle = null,
            detectedChannelTitle = null,
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = true,
            lastApprovedWatchActive = true
        )
        assertTrue(allowedMini is YouTubeStudyEngine.StudyDecision.Allow)

        // Shorts player -> EXIT_SHORTS_TO_HOME (never exits YouTube into PiP)
        val blockedShort = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.SHORTS_PLAYER,
            detectedChannelId = null,
            detectedHandle = "@3Blue1Brown",
            detectedChannelTitle = "3Blue1Brown",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = true
        )
        assertTrue(blockedShort is YouTubeStudyEngine.StudyDecision.BlockPlayback)
        assertEquals(
            YouTubeStudyEngine.PlaybackCutAction.EXIT_SHORTS_TO_HOME,
            (blockedShort as YouTubeStudyEngine.StudyDecision.BlockPlayback).cutAction
        )
    }

    @Test
    fun contentProtectionDefaults_keepSafeSearchAndAdultAlwaysOnWithZeroCardMasks() {
        val defaults = ContentProtectionConfig()
        assertTrue(defaults.safeSearchEnabled)
        assertTrue(defaults.inAppAdultFilterEnabled)
        assertFalse(defaults.youtubeMaskFeedCards)
        assertTrue(defaults.anyProtectionEnabled)
    }

    @Test
    fun publicChannelSearch_usesSingleEncodedChannelFilterAndParsesDesktopAndMobileRenderers() = runTest {
        val requestedUrls = mutableListOf<String>()
        val desktopSearchHtml = """
            <html><body><script>var ytInitialData = {"contents":{"channelRenderer":{"channelId":"UCYO_jab_esuFRV4b17AJtAw","title":{"simpleText":"3Blue1Brown"},"canonicalBaseUrl":"/@3blue1brown","subscriberCountText":{"simpleText":"6.5M subscribers"},"thumbnail":{"thumbnails":[{"url":"//yt3.googleusercontent.com/3b1b.jpg"}]}}}};
            </script></body></html>
        """.trimIndent()

        val desktopResolver = YouTubeChannelResolver { url ->
            requestedUrls.add(url)
            YouTubeChannelResolver.HttpFetchResult(200, desktopSearchHtml)
        }
        val desktopResult = desktopResolver.resolveInput("3Blue1Brown", "")
        assertTrue(requestedUrls.first().contains("sp=EgIQAg%3D%3D"))
        assertFalse(requestedUrls.first().contains("%253D"))
        assertTrue(desktopResult is ChannelLookupState.SearchResults)
        val desktopChannels = (desktopResult as ChannelLookupState.SearchResults).candidates
        assertEquals(1, desktopChannels.size)
        assertEquals("UCYO_jab_esuFRV4b17AJtAw", desktopChannels[0].channelId)
        assertEquals("3Blue1Brown", desktopChannels[0].title)
        assertEquals("@3blue1brown", desktopChannels[0].handle)
        assertEquals("6.5M subscribers", desktopChannels[0].subscriberSummary)

        val mobileSearchHtml = """
            <html><body><script>var ytInitialData = {"compactChannelRenderer":{"channelId":"UC4a-Gbdw7vOaccHmFo40b9g","displayName":{"runs":[{"text":"ISKCON Desire Tree"}]},"canonicalBaseUrl":"/@iskcondesiretree","subscriberCountText":{"runs":[{"text":"2.1M subscribers"}]},"thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/idt.jpg"}]}}};
            </script></body></html>
        """.trimIndent()
        val mobileResolver = YouTubeChannelResolver {
            YouTubeChannelResolver.HttpFetchResult(200, mobileSearchHtml)
        }
        val mobileResult = mobileResolver.resolveInput("ISKCON Desire Tree", "")
        assertTrue(mobileResult is ChannelLookupState.SearchResults)
        val mobileChannels = (mobileResult as ChannelLookupState.SearchResults).candidates
        assertEquals(1, mobileChannels.size)
        assertEquals("UC4a-Gbdw7vOaccHmFo40b9g", mobileChannels[0].channelId)
        assertEquals("ISKCON Desire Tree", mobileChannels[0].title)
        assertEquals("@iskcondesiretree", mobileChannels[0].handle)
    }

    // -------------------------------------------------------------------------
    // 5. On-Device In-App Adult-Content Filtering (Spiritual-Only, Mature Media, Multilingual)
    // -------------------------------------------------------------------------

    @Test
    fun inAppAdultFilter_allowsSpiritualAndRecoveryContext_butRemovesBiologyLoophole() {
        // Explicit queries still blocked
        val explicit = InAppAdultFilterEngine.evaluateQueryOrUrl("free xxx porn video")
        assertTrue(explicit.isFlagged)

        // Genuine spiritual / anti-addiction recovery content is permitted
        val iskconTalk = InAppAdultFilterEngine.evaluateActiveContent(
            urlOrQuery = "How to overcome porn addiction",
            visibleSnippets = listOf("ISKCON Desire Tree", "Brahmacharya talk on controlling lust")
        )
        assertFalse("Spiritual anti-addiction recovery talk must be allowed", iskconTalk.isFlagged)

        val recoveryQuery = InAppAdultFilterEngine.evaluateQueryOrUrl("how to quit porn addiction brahmacharya")
        assertFalse("Anti-addiction recovery search must be allowed", recoveryQuery.isFlagged)

        // Keyword stuffing spiritual terms alongside explicit consumption/sites is STILL blocked
        val gamedSpiritual = InAppAdultFilterEngine.evaluateQueryOrUrl("watch free xxx porn video iskcon")
        assertTrue("Hard explicit site/consumption terms must never be exempted", gamedSpiritual.isFlagged)

        // Biology/anatomy loophole is REMOVED
        val anatomyLoophole = InAppAdultFilterEngine.evaluateQueryOrUrl("naked female anatomy nude video biology")
        assertTrue("Biology/anatomy keywords must NOT exempt explicit content", anatomyLoophole.isFlagged)

        // Approved YouTube channel in Study Mode is trusted even when discussing addiction recovery
        val approvedChannelVideo = InAppAdultFilterEngine.evaluateActiveContent(
            urlOrQuery = "Overcoming Porn & Masturbation",
            visibleSnippets = listOf("Freedom talk"),
            isApprovedYouTubeChannel = true
        )
        assertFalse("Explicitly approved YouTube channel must not be falsely cut", approvedChannelVideo.isFlagged)
    }

    @Test
    fun inAppAdultFilter_blocksMatureMediaTrailersAdultOttAndMultilingualUnicode() {
        // 18+/A-rated/age-restricted YouTube banners & adult OTT / suggestive movie clips
        assertTrue(
            InAppAdultFilterEngine.evaluateCardText(
                listOf("Official Trailer", "Age-restricted video — Sign in to confirm your age")
            ).isFlagged
        )
        assertTrue(
            InAppAdultFilterEngine.evaluateQueryOrUrl("Ullu hot web series bold scene trailer").isFlagged
        )
        assertTrue(
            InAppAdultFilterEngine.evaluateCardText(
                listOf("Bollywood New Movie Item Song Kissing Scene")
            ).isFlagged
        )

        // Hindi (Devanagari) & Hinglish
        assertTrue(InAppAdultFilterEngine.evaluateQueryOrUrl("अश्लील वीडियो").isFlagged)
        assertTrue(InAppAdultFilterEngine.evaluateQueryOrUrl("पोर्न वीडियो").isFlagged)
        assertTrue(InAppAdultFilterEngine.evaluateQueryOrUrl("desi bhabhi hot video").isFlagged)

        // Global languages (Spanish, Arabic, Russian) + fancy Unicode NFKC evasion
        assertTrue(InAppAdultFilterEngine.evaluateQueryOrUrl("chica desnudá video porno").isFlagged)
        assertTrue(InAppAdultFilterEngine.evaluateQueryOrUrl("أفلام إباحية").isFlagged)
        assertTrue(InAppAdultFilterEngine.evaluateQueryOrUrl("порно видео").isFlagged)
        assertTrue(InAppAdultFilterEngine.evaluateQueryOrUrl("ｐｏｒｎ ｖｉｄｅｏ").isFlagged)
    }

    @Test
    fun streamingAggregatorsAndRRatedPirateMovieSites_areBlockedEvenOnUnknownMirrors() {
        // 1. fmhy.net/video (stream aggregator directory)
        val fmhyScore = com.example.domain.UnknownSiteClassifier.scoreUnmoderatedStreamingSite(
            host = "fmhy.net",
            fullUrl = "https://fmhy.net/video",
            visibleTextSnippets = listOf(
                "Stream Aggregators",
                "Sites that check and play streams within their own custom player exclusively",
                "Atlantic or fprime - 4K / Dual Audio / External Subs / Ad-Toggle"
            )
        )
        assertTrue("fmhy.net/video must be flagged as an unmoderated streaming aggregator", fmhyScore >= 6)

        // 2. Rotating unknown mirror of a stream aggregator
        val unknownAggregatorScore = com.example.domain.UnknownSiteClassifier.scoreUnmoderatedStreamingSite(
            host = "freshmirror99.pages.dev",
            fullUrl = "https://freshmirror99.pages.dev/video",
            visibleTextSnippets = listOf(
                "Stream Aggregators",
                "4K / Dual Audio / External Subs / Ad-Toggle"
            )
        )
        assertTrue("Unknown rotating stream aggregator mirror must be caught heuristically", unknownAggregatorScore >= 6)

        // 3. atlantic.bz/title/movie/1368337 (pirate movie streaming site with [R] rating badge)
        val atlanticScore = com.example.domain.UnknownSiteClassifier.scoreUnmoderatedStreamingSite(
            host = "atlantic.bz",
            fullUrl = "https://atlantic.bz/title/movie/1368337",
            visibleTextSnippets = listOf("THE ODYSSEY", "Resume", "2026 2h 53m", "R", "IMDb", "TMDB 8.0", "Join our Discord")
        )
        assertTrue("atlantic.bz must be flagged as an unmoderated streaming site", atlanticScore >= 6)

        val unknownMovieMirrorScore = com.example.domain.UnknownSiteClassifier.scoreUnmoderatedStreamingSite(
            host = "newstream77.bz",
            fullUrl = "https://newstream77.bz/title/movie/1368337",
            visibleTextSnippets = listOf("THE ODYSSEY", "Resume", "IMDb", "TMDB 8.0", "Join our Discord")
        )
        assertTrue("Unknown rotating movie mirror must be caught heuristically", unknownMovieMirrorScore >= 6)

        // 4. R-rated movie streaming page caught by InAppAdultFilterEngine
        val rRatedMoviePage = InAppAdultFilterEngine.evaluateActiveContent(
            urlOrQuery = "https://atlantic.bz/title/movie/1368337",
            visibleSnippets = listOf("THE ODYSSEY", "Resume", "2026 2h 53m", "R", "IMDb", "TMDB 8.0", "Director: Christopher Nolan")
        )
        assertTrue("R-rated movie streaming page must be flagged by mature-media filter", rRatedMoviePage.isFlagged)

        // 5. Safe educational page mentioning the letter "R" (e.g. R programming) is NOT false-flagged
        val rProgramming = InAppAdultFilterEngine.evaluateActiveContent(
            urlOrQuery = "https://www.r-project.org/about.html",
            visibleSnippets = listOf("The R Project for Statistical Computing", "R", "Data analysis and graphics")
        )
        assertFalse("Normal non-movie page containing 'R' must not be falsely flagged", rProgramming.isFlagged)
    }

    @Test
    fun matureSeriesDatabase_movies4uOffline_redditFmhy_andGoogleMultiAccountSafeSearch() {
        // 1. Google SafeSearch includes ssui=on for multi-account SafeSearch lock
        val googleEnforce = SafeSearchEngine.evaluateUrl(
            rawUrl = "https://www.google.com/search?q=linear+algebra",
            config = studyConfig,
            strictMode = true
        )
        assertTrue(googleEnforce is SafeSearchEngine.Decision.EnforceRedirect)
        val enforcedUrl = (googleEnforce as SafeSearchEngine.Decision.EnforceRedirect).enforcedUrl
        assertTrue(enforcedUrl.contains("safe=active"))
        assertTrue(enforcedUrl.contains("ssui=on"))

        // 2. Adult web series & explicit TV shows (e.g. "shameless web series" on YouTube)
        assertTrue(InAppAdultFilterEngine.evaluateQueryOrUrl("shameless web series").isFlagged)
        assertTrue(InAppAdultFilterEngine.evaluateQueryOrUrl("euphoria hot scenes").isFlagged)
        assertTrue(InAppAdultFilterEngine.evaluateQueryOrUrl("gandii baat full episode").isFlagged)
        assertFalse(
            "Ordinary non-media usage of 'shameless' should not be falsely flagged",
            InAppAdultFilterEngine.evaluateQueryOrUrl("shameless corruption in local council news").isFlagged
        )

        // 3. Offline Chrome URL bar (`Offline | movies4u.vet`) & structural piracy portal detection
        val extracted = com.example.domain.DomainBlockMatcher.extractUrlFromUiText("Offline | movies4u.vet")
        assertEquals("movies4u.vet", extracted?.host)

        val movies4uSnippets = listOf(
            "MOVIES4U",
            "TV SHOW",
            "URDU",
            "WEB SERIES",
            "Tantra Das Geheimnis Sexueller Ekstase (2026) WEB-DL English + German 1080p",
            "Tantra (2024) WEB-DL Hindi 480p | 720p | 1080p"
        )
        assertTrue(
            "movies4u.vet must be blocked structurally",
            com.example.domain.MatureMediaCatalog.scorePiracyOrUnmoderatedMediaSite(
                host = "movies4u.vet",
                fullUrl = "https://movies4u.vet",
                visibleTextSnippets = movies4uSnippets
            ) >= 6
        )
        assertTrue(
            "Brand-new unknown piracy download portal mirror must also be blocked structurally",
            com.example.domain.MatureMediaCatalog.scorePiracyOrUnmoderatedMediaSite(
                host = "freshdownloads88.vet",
                fullUrl = "https://freshdownloads88.vet",
                visibleTextSnippets = movies4uSnippets
            ) >= 6
        )
        assertTrue(
            "German explicit film title on movies4u.vet must also trigger InAppAdultFilterEngine",
            InAppAdultFilterEngine.evaluateActiveContent("movies4u.vet", movies4uSnippets).isFlagged
        )

        // 4. Reddit r/FREEMEDIAHECKYEAH wiki / in-app FMHY detection
        assertTrue(
            "Reddit r/FREEMEDIAHECKYEAH must be caught in-app",
            com.example.domain.MatureMediaCatalog.isInAppPiracyAggregator(
                listOf("r/FREEMEDIAHECKYEAH", "Wiki", "Streaming / Video", "fmhy.net")
            )
        )
    }

    @Test
    fun morpheYouTube_watchHistoryNeverBounceExits_whileActivePlaybackAndExplicitSearchCutCleanly() {
        val watchHistorySnippets = listOf(
            "History",
            "Sex Education",
            "After We Collided // Shower Scene",
            "XXX-Rated Scenes",
            "Subscriptions",
            "You"
        )

        // 1. Watch History / Custom Default Morphe Tab with old explicit thumbnails -> MUST return null (ZERO Back / ZERO exit!)
        val historyAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH,
            activeVideoTitle = null,
            activeSearchQuery = null,
            isSearchResultsScreen = false,
            visibleSnippets = watchHistorySnippets,
            isApprovedYouTubeChannel = false
        )
        assertEquals(
            "Watch History or custom default Morphe tab must NEVER fire Back or exit the app",
            null,
            historyAction
        )

        // 2. Tapping that exact flagged video opens WATCH_PLAYER -> CLOSE_WATCH_AND_MINIPLAYER
        val watchAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
            activeVideoTitle = "After We Collided // Shower Scene",
            activeSearchQuery = null,
            isSearchResultsScreen = false,
            visibleSnippets = listOf("After We Collided // Shower Scene", "Comments", "Share"),
            isApprovedYouTubeChannel = false
        )
        assertEquals(YouTubeStudyEngine.PlaybackCutAction.CLOSE_WATCH_AND_MINIPLAYER, watchAction)

        // 3. Video lingering in bottom MINI_PLAYER -> CLOSE_MINIPLAYER_ONLY (never fires Back!)
        val miniAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.MINI_PLAYER,
            activeVideoTitle = "Sex Education hot scenes",
            activeSearchQuery = null,
            isSearchResultsScreen = false,
            visibleSnippets = watchHistorySnippets,
            isApprovedYouTubeChannel = false
        )
        assertEquals(YouTubeStudyEngine.PlaybackCutAction.CLOSE_MINIPLAYER_ONLY, miniAction)

        // 4. Active explicit YouTube Search Results screen -> EXIT_SEARCH_RESULTS (single Back)
        val explicitSearchAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH,
            activeVideoTitle = null,
            activeSearchQuery = "shameless web series",
            isSearchResultsScreen = true,
            visibleSnippets = listOf("shameless web series"),
            isApprovedYouTubeChannel = false
        )
        assertEquals(YouTubeStudyEngine.PlaybackCutAction.EXIT_SEARCH_RESULTS, explicitSearchAction)

        // 5. Safe YouTube Search Results -> null (no interruption)
        val safeSearchAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH,
            activeVideoTitle = null,
            activeSearchQuery = "linear algebra lecture",
            isSearchResultsScreen = true,
            visibleSnippets = listOf("linear algebra lecture", "3Blue1Brown"),
            isApprovedYouTubeChannel = false
        )
        assertEquals(null, safeSearchAction)
    }

    @Test
    fun stage1_universalContentDetection_mergesAndDeduplicatesPageAndCardEvidenceWithoutWeakeningThresholds() {
        // 1. Merges page text + visible card/thumbnail accessibility labels, deduplicating case/whitespace
        val merged = com.example.blocker.BrowserUrlExtractor.mergeAndDeduplicateEvidence(
            pageSnippets = listOf("Watch Online", "R", "  watch   online ", "A"),
            cardSnippets = listOf("watch online", "HD 1080p Dual Audio WEB-DL", "18+", "hd 1080p dual audio web-dl"),
            maxTotal = 16
        )
        assertEquals(
            listOf("Watch Online", "R", "A", "HD 1080p Dual Audio WEB-DL", "18+"),
            merged
        )

        // 2. Single suspicious word on a visible card must NOT independently trigger a block
        val singleSuspiciousCard = com.example.blocker.BrowserUrlExtractor.mergeAndDeduplicateEvidence(
            pageSnippets = listOf("History Museum Blog", "Weekly Newsletter"),
            cardSnippets = listOf("Naked mole rat biology facts"),
            maxTotal = 16
        )
        assertFalse(
            "Single medium word on a visible card must not independently trigger a block",
            InAppAdultFilterEngine.evaluateActiveContent(
                urlOrQuery = "https://historyblog.example.org/animals",
                visibleSnippets = singleSuspiciousCard
            ).isFlagged
        )

        // 3. Combined page + card evidence strengthens existing UnknownSiteClassifier & MatureMediaCatalog
        val combinedPiracyEvidence = com.example.blocker.BrowserUrlExtractor.mergeAndDeduplicateEvidence(
            pageSnippets = listOf("Latest Releases", "R"),
            cardSnippets = listOf(
                "Watch Free HD Movies Online - Dual Audio 720p 1080p WEB-DL",
                "Stream Aggregators & External Subs · TV SHOW · WEB SERIES"
            ),
            maxTotal = 24
        )
        assertTrue(
            "Combined page + card evidence must catch unknown piracy/streaming hubs through existing classifiers",
            com.example.domain.MatureMediaCatalog.isInAppPiracyAggregator(combinedPiracyEvidence) ||
                com.example.domain.MatureMediaCatalog.scorePiracyOrUnmoderatedMediaSite(
                    host = "unknown-mirror-xyz.example",
                    fullUrl = "https://unknown-mirror-xyz.example/movies",
                    visibleTextSnippets = combinedPiracyEvidence
                ) >= 6
        )
    }

    @Test
    fun stage2_youtubeInAppSearchPlaylistsRecommendations_andDelayedDomClassificationTests() {
        // 1. Searching "naked" with "Nude Artistic Photoshoot" / "Nude Art" cards/playlists -> BLOCKED before opening a video!
        val nakedSearchAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH,
            activeVideoTitle = null,
            activeSearchQuery = "naked",
            isSearchResultsScreen = true,
            visibleSnippets = listOf("naked", "Videos", "Playlists"),
            isApprovedYouTubeChannel = false,
            visibleCardSnippets = listOf(
                "Nude Artistic Photoshoot - Behind the Scenes",
                "Nude Art · Playlist · 24 videos"
            )
        )
        assertEquals(
            "Searching 'naked' with nude photoshoot/art cards must cut in-app before opening a video",
            YouTubeStudyEngine.PlaybackCutAction.EXIT_SEARCH_RESULTS,
            nakedSearchAction
        )

        // 2. Searching safe educational content with ambiguous words -> ALLOWED
        val nakedMoleRatAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH,
            activeVideoTitle = null,
            activeSearchQuery = "naked mole rat documentary",
            isSearchResultsScreen = true,
            visibleSnippets = listOf("naked mole rat documentary", "National Geographic"),
            isApprovedYouTubeChannel = false,
            visibleCardSnippets = listOf(
                "Naked Mole Rat Documentary - Underground Colonies",
                "How Naked Mole Rats Survive Without Oxygen"
            )
        )
        assertEquals("Safe educational documentary with 'naked' must stay allowed", null, nakedMoleRatAction)

        val nakedTruthAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH,
            activeVideoTitle = null,
            activeSearchQuery = "the naked truth about history",
            isSearchResultsScreen = true,
            visibleSnippets = listOf("the naked truth about history", "Ancient Civilizations"),
            isApprovedYouTubeChannel = false,
            visibleCardSnippets = listOf("The Naked Truth About Roman Engineering")
        )
        assertEquals("Safe idiom 'the naked truth' must stay allowed", null, nakedTruthAction)

        // 3. Opening a playlist containing disallowed content -> BLOCKED
        val playlistAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH,
            activeVideoTitle = "Nude Art",
            activeSearchQuery = null,
            isSearchResultsScreen = false,
            visibleSnippets = listOf("Nude Art", "Play all", "Shuffle"),
            isApprovedYouTubeChannel = false,
            isPlaylistScreen = true,
            visibleCardSnippets = listOf("Nude Artistic Photoshoot Part 1", "Artistic Nude Studio")
        )
        assertEquals(
            "Opening a playlist with disallowed content must cut cleanly",
            YouTubeStudyEngine.PlaybackCutAction.EXIT_SEARCH_RESULTS,
            playlistAction
        )

        // 4. Viewing non-History recommendations containing disallowed content -> BLOCKED
        val badRecommendationsAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH,
            activeVideoTitle = null,
            activeSearchQuery = null,
            isSearchResultsScreen = false,
            visibleSnippets = listOf("Home", "Subscriptions", "All"),
            isApprovedYouTubeChannel = false,
            isWatchHistoryOrProfileScreen = false,
            visibleCardSnippets = listOf("Nude Artistic Photoshoot 4K", "Artistic Nude Photography")
        )
        assertEquals(
            "Non-History recommendations showing explicit/nude cards must be blocked",
            YouTubeStudyEngine.PlaybackCutAction.EXIT_SEARCH_RESULTS,
            badRecommendationsAction
        )

        // 5. Watch History / Profile screen STILL NEVER bounce-exits Morphe YT!
        val historyScreenAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH,
            activeVideoTitle = null,
            activeSearchQuery = null,
            isSearchResultsScreen = false,
            visibleSnippets = listOf("Watch history", "You"),
            isApprovedYouTubeChannel = false,
            isWatchHistoryOrProfileScreen = true,
            visibleCardSnippets = listOf("Nude Artistic Photoshoot 4K")
        )
        assertEquals("Watch History/Profile screen must never bounce-exit", null, historyScreenAction)

        // 6. Searching for an approved educational channel -> ALLOWED
        val approvedSearchAction = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.FEED_OR_SEARCH,
            activeVideoTitle = null,
            activeSearchQuery = "3Blue1Brown essence of calculus",
            isSearchResultsScreen = true,
            visibleSnippets = listOf("3Blue1Brown essence of calculus", "3Blue1Brown"),
            isApprovedYouTubeChannel = false,
            visibleCardSnippets = listOf("The essence of calculus - 3Blue1Brown")
        )
        assertEquals("Searching an approved educational channel must stay allowed", null, approvedSearchAction)

        // 7. Approved channel: normal educational video ALLOWED, explicit/nude video STILL BLOCKED!
        val approvedNormalVideo = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
            activeVideoTitle = "Essence of Linear Algebra",
            activeSearchQuery = null,
            isSearchResultsScreen = false,
            visibleSnippets = listOf("3Blue1Brown", "Subscribe", "Comments"),
            isApprovedYouTubeChannel = true
        )
        assertEquals("Normal video from an approved channel must play", null, approvedNormalVideo)

        val approvedExplicitVideo = YouTubeStudyEngine.evaluateInAppAdultCutAction(
            screenMode = YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
            activeVideoTitle = "Nude Artistic Photoshoot",
            activeSearchQuery = null,
            isSearchResultsScreen = false,
            visibleSnippets = listOf("Nude Artistic Photoshoot", "Subscribe", "Comments"),
            isApprovedYouTubeChannel = true
        )
        assertEquals(
            "Inappropriate video must still be blocked even if uploaded to an approved channel",
            YouTubeStudyEngine.PlaybackCutAction.CLOSE_WATCH_AND_MINIPLAYER,
            approvedExplicitVideo
        )

        // 8. Delayed WebView DOM classification: allows ONE bounded follow-up pass when initial scan ran pre-load
        assertTrue(
            com.example.blocker.FreedomAccessibilityService.shouldEvaluateUnknownSiteClassification(
                isNewUrl = true,
                lastEvidenceCount = 0,
                lastAttempts = 0,
                currentEvidenceCount = 2
            )
        )
        assertFalse(
            "Still-empty DOM must not waste a second classification attempt",
            com.example.blocker.FreedomAccessibilityService.shouldEvaluateUnknownSiteClassification(
                isNewUrl = false,
                lastEvidenceCount = 2,
                lastAttempts = 1,
                currentEvidenceCount = 3
            )
        )
        assertTrue(
            "Once DOM content renders (>=6 snippets), allow ONE bounded re-classification on same URL",
            com.example.blocker.FreedomAccessibilityService.shouldEvaluateUnknownSiteClassification(
                isNewUrl = false,
                lastEvidenceCount = 2,
                lastAttempts = 1,
                currentEvidenceCount = 12
            )
        )
        assertFalse(
            "Never exceed 2 classification evaluations per URL",
            com.example.blocker.FreedomAccessibilityService.shouldEvaluateUnknownSiteClassification(
                isNewUrl = false,
                lastEvidenceCount = 2,
                lastAttempts = 2,
                currentEvidenceCount = 20
            )
        )
    }

    @Test
    fun stage3_reliableYouTubeChannelIdentification_andAsyncVideoSwitchSafety() {
        // 1. Canonical ID extraction ONLY parses real YouTube URLs / canonical UC... IDs — NEVER guesses from names or @handles
        val (vidWatch, cidWatch) = com.example.blocker.YouTubeAccessibilityInspector.extractCanonicalIdsFromText(
            "https://www.youtube.com/watch?v=aircAruvnKk"
        )
        assertEquals("aircAruvnKk", vidWatch)
        assertEquals(null, cidWatch)

        val (vidLive, _) = com.example.blocker.YouTubeAccessibilityInspector.extractCanonicalIdsFromText(
            "https://www.youtube.com/live/LiveStrm123"
        )
        assertEquals("LiveStrm123", vidLive)

        val (_, cidDirect) = com.example.blocker.YouTubeAccessibilityInspector.extractCanonicalIdsFromText(
            "https://www.youtube.com/channel/UCYO_jab_esuFRV4b17AJtAw"
        )
        assertEquals("UCYO_jab_esuFRV4b17AJtAw", cidDirect)

        val (vidFromHandle, cidFromHandle) = com.example.blocker.YouTubeAccessibilityInspector.extractCanonicalIdsFromText("@3blue1brown")
        assertEquals("Handle must never be treated as a verified UC... channel ID", null, cidFromHandle)
        assertEquals(null, vidFromHandle)

        val (vidFromName, cidFromName) = com.example.blocker.YouTubeAccessibilityInspector.extractCanonicalIdsFromText("3Blue1Brown")
        assertEquals("Display name must never be guessed as a UC... channel ID", null, cidFromName)
        assertEquals(null, vidFromName)

        // 2. Spoofed display name / handle CANNOT override a VERIFIED unapproved permanent UC... ID
        val spoofedAttribution = YouTubeStudyEngine.ChannelAttribution(
            status = YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED,
            channelId = "UCuAXFkgsw1L7xaCfnd5JJOw", // Unapproved permanent ID
            videoId = "dQw4w9WgXcQ",
            observedHandle = "@3blue1brown",
            observedDisplayTitle = "3Blue1Brown"
        )
        val spoofDecision = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
            detectedChannelId = spoofedAttribution.verifiedChannelId,
            detectedHandle = "@3blue1brown",
            detectedChannelTitle = "3Blue1Brown",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = false,
            attribution = spoofedAttribution
        )
        assertTrue(
            "Verified unapproved UC... ID must block even if display name/handle spoofs an approved channel",
            spoofDecision is YouTubeStudyEngine.StudyDecision.BlockPlayback
        )

        // 3. PENDING and FAILED attribution NEVER silently become approved
        val pendingAttr = YouTubeStudyEngine.ChannelAttribution(
            status = YouTubeStudyEngine.ChannelAttributionStatus.PENDING,
            videoId = "NewVideo123",
            observedHandle = "@3blue1brown",
            observedDisplayTitle = "3Blue1Brown"
        )
        val pendingNonStrict = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
            detectedChannelId = null,
            detectedHandle = "@3blue1brown",
            detectedChannelTitle = "3Blue1Brown",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = false,
            attribution = pendingAttr
        )
        assertTrue(
            "PENDING attribution must never silently become Allow",
            pendingNonStrict is YouTubeStudyEngine.StudyDecision.NeedsVideoChannelVerification
        )
        val pendingStrict = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
            detectedChannelId = null,
            detectedHandle = "@3blue1brown",
            detectedChannelTitle = "3Blue1Brown",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = true,
            attribution = pendingAttr
        )
        assertTrue(
            "PENDING attribution must fail closed in Strict Mode",
            pendingStrict is YouTubeStudyEngine.StudyDecision.BlockPlayback
        )

        val failedAttr = YouTubeStudyEngine.ChannelAttribution(
            status = YouTubeStudyEngine.ChannelAttributionStatus.FAILED,
            videoId = "NewVideo123",
            failureReason = "Timeout"
        )
        val failedDecision = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
            detectedChannelId = null,
            detectedHandle = "@3blue1brown",
            detectedChannelTitle = "3Blue1Brown",
            approved = approvedSnapshot,
            config = studyConfig,
            strictMode = false,
            attribution = failedAttr
        )
        assertTrue("FAILED attribution must block playback", failedDecision is YouTubeStudyEngine.StudyDecision.BlockPlayback)

        // 4. Video-ID-keyed tracker: dedup concurrent lookups, discard stale results after switching videos, bound cache & TTL
        val tracker = com.example.domain.YouTubeChannelVerificationTracker(
            maxCacheEntries = 2,
            verifiedTtlMs = 10_000L,
            failedTtlMs = 5_000L
        )
        val resA1 = tracker.evaluateCurrentPlayback(
            activeVideoId = "VideoA11111",
            extractedChannelId = null,
            observedHandle = "@3blue1brown",
            observedDisplayTitle = "3Blue1Brown",
            observedVideoTitle = "Video A",
            persistedVideoToChannelMap = emptyMap(),
            nowMs = 1_000L
        )
        assertEquals(YouTubeStudyEngine.ChannelAttributionStatus.PENDING, resA1.attribution.status)
        assertEquals("VideoA11111", resA1.shouldStartAsyncLookupForVideoId)
        assertTrue(tracker.isLookupInFlight("VideoA11111"))

        // Duplicate observation of same videoId while in flight must NOT start a duplicate lookup
        val resA2 = tracker.evaluateCurrentPlayback(
            activeVideoId = "VideoA11111",
            extractedChannelId = null,
            observedHandle = "@3blue1brown",
            observedDisplayTitle = "3Blue1Brown",
            observedVideoTitle = "Video A",
            persistedVideoToChannelMap = emptyMap(),
            nowMs = 1_200L
        )
        assertEquals(YouTubeStudyEngine.ChannelAttributionStatus.PENDING, resA2.attribution.status)
        assertEquals("Duplicate concurrent lookup must be prevented", null, resA2.shouldStartAsyncLookupForVideoId)

        // User switches rapidly to VideoB before VideoA finishes!
        val resB = tracker.evaluateCurrentPlayback(
            activeVideoId = "VideoB22222",
            extractedChannelId = null,
            observedHandle = null,
            observedDisplayTitle = "Other Channel",
            observedVideoTitle = "Video B",
            persistedVideoToChannelMap = emptyMap(),
            nowMs = 1_400L
        )
        assertTrue("Switching videos must set videoSwitched = true", resB.videoSwitched)
        assertEquals("VideoB22222", resB.shouldStartAsyncLookupForVideoId)

        // Stale VideoA lookup completes AFTER switching to VideoB -> must NOT apply to VideoB!
        val staleCompletion = tracker.completeAsyncLookup(
            videoId = "VideoA11111",
            resolvedChannelId = "UCYO_jab_esuFRV4b17AJtAw",
            nowMs = 1_600L
        )
        assertFalse(
            "Stale verification result for previous video must be discarded for active video",
            staleCompletion.appliedToCurrentVideo
        )
        assertEquals(YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED, staleCompletion.attribution.status)

        // Malformed channel ID from lookup -> FAILED (never verified)
        val malformedCompletion = tracker.completeAsyncLookup(
            videoId = "VideoB22222",
            resolvedChannelId = "@not_a_uc_id",
            nowMs = 1_700L
        )
        assertTrue(malformedCompletion.appliedToCurrentVideo)
        assertEquals(YouTubeStudyEngine.ChannelAttributionStatus.FAILED, malformedCompletion.attribution.status)
        assertEquals(null, malformedCompletion.attribution.verifiedChannelId)

        // Bounded LRU cache eviction (maxCacheEntries = 2): adding 3rd video keeps size <= 2
        tracker.completeAsyncLookup("VideoC33333", "UC4a-Gbdw7vOaccHmFo40b9g", nowMs = 1_800L)
        assertTrue("Verification cache must stay strictly bounded", tracker.cachedSize() <= 2)

        // Expired TTL entry is invalidated cleanly instead of being treated as permanent proof
        val expiredCheck = tracker.evaluateCurrentPlayback(
            activeVideoId = "VideoC33333",
            extractedChannelId = null,
            observedHandle = null,
            observedDisplayTitle = null,
            observedVideoTitle = "Video C",
            persistedVideoToChannelMap = emptyMap(),
            nowMs = 50_000L
        )
        assertEquals(
            "Expired cached videoId must re-verify instead of trusting stale cache",
            YouTubeStudyEngine.ChannelAttributionStatus.PENDING,
            expiredCheck.attribution.status
        )
    }

    @Test
    fun stage4_youtubeLiveStreaming_approvedAllowed_unapprovedAndUnknownBlocked_miniplayerAndTransitions() {
        // 1. Default ContentProtectionConfig enables Live streams for verified approved channels
        val defaultStudyConfig = ContentProtectionConfig(youtubeStudyModeEnabled = true)
        assertTrue(
            "Default Study Mode config must allow Live streams from verified approved channels",
            defaultStudyConfig.youtubeAllowLiveFromApproved
        )

        val approvedVerifiedAttribution = YouTubeStudyEngine.ChannelAttribution(
            status = YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED,
            channelId = "UCYO_jab_esuFRV4b17AJtAw",
            videoId = "LiveStrm123",
            observedHandle = "@3blue1brown",
            observedDisplayTitle = "3Blue1Brown"
        )
        val unapprovedVerifiedAttribution = YouTubeStudyEngine.ChannelAttribution(
            status = YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED,
            channelId = "UC_unapprovedChannelId12",
            videoId = "LiveUnap999",
            observedHandle = "@randomstreamer",
            observedDisplayTitle = "Random Streamer"
        )

        // 2. Normal uploaded VOD from verified approved channel -> Allow
        assertEquals(
            YouTubeStudyEngine.StudyDecision.Allow,
            YouTubeStudyEngine.evaluateActivePlayback(
                screenMode = YouTubeStudyEngine.ScreenMode.WATCH_PLAYER,
                detectedChannelId = approvedVerifiedAttribution.verifiedChannelId,
                detectedHandle = "@3blue1brown",
                detectedChannelTitle = "3Blue1Brown",
                approved = approvedSnapshot,
                config = defaultStudyConfig,
                strictMode = true,
                attribution = approvedVerifiedAttribution
            )
        )

        // 3. LIVE stream from that SAME verified approved channel -> Allow (normal & Strict Mode)
        assertEquals(
            "Verified approved channel Live stream must play just like normal videos",
            YouTubeStudyEngine.StudyDecision.Allow,
            YouTubeStudyEngine.evaluateActivePlayback(
                screenMode = YouTubeStudyEngine.ScreenMode.LIVE_PLAYER,
                detectedChannelId = approvedVerifiedAttribution.verifiedChannelId,
                detectedHandle = "@3blue1brown",
                detectedChannelTitle = "3Blue1Brown",
                approved = approvedSnapshot,
                config = defaultStudyConfig,
                strictMode = true,
                attribution = approvedVerifiedAttribution
            )
        )

        // 4. Direct /live/<videoId> URLs follow exact channel-approval policy
        val liveApprovedSnapshot = approvedSnapshot.copy(
            videoToChannelIdCache = approvedSnapshot.videoToChannelIdCache +
                ("LiveStrm123" to "UCYO_jab_esuFRV4b17AJtAw") +
                ("LiveUnap999" to "UC_unapprovedChannelId12")
        )
        assertEquals(
            "Cached verified approved /live/ URL must be allowed",
            YouTubeStudyEngine.StudyDecision.Allow,
            YouTubeStudyEngine.evaluateDirectYouTubeUrl(
                rawUrl = "https://www.youtube.com/live/LiveStrm123?feature=share",
                approved = liveApprovedSnapshot,
                config = defaultStudyConfig,
                strictMode = true
            )
        )
        assertTrue(
            "Cached unapproved /live/ URL must be blocked",
            YouTubeStudyEngine.evaluateDirectYouTubeUrl(
                rawUrl = "https://www.youtube.com/live/LiveUnap999",
                approved = liveApprovedSnapshot,
                config = defaultStudyConfig,
                strictMode = true
            ) is YouTubeStudyEngine.StudyDecision.BlockPlayback
        )
        val uncachedLiveDecision = YouTubeStudyEngine.evaluateDirectYouTubeUrl(
            rawUrl = "https://www.youtube.com/live/LiveNew7777",
            approved = liveApprovedSnapshot,
            config = defaultStudyConfig,
            strictMode = false
        )
        assertTrue(
            "Uncached /live/ URL must require channel verification rather than blind allow",
            uncachedLiveDecision is YouTubeStudyEngine.StudyDecision.NeedsVideoChannelVerification &&
                uncachedLiveDecision.videoId == "LiveNew7777" &&
                uncachedLiveDecision.isLive
        )

        // 5. LIVE stream from an unapproved verified channel -> BlockPlayback
        val unapprovedLive = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.LIVE_PLAYER,
            detectedChannelId = unapprovedVerifiedAttribution.verifiedChannelId,
            detectedHandle = "@randomstreamer",
            detectedChannelTitle = "Random Streamer",
            approved = approvedSnapshot,
            config = defaultStudyConfig,
            strictMode = false,
            attribution = unapprovedVerifiedAttribution
        )
        assertTrue("Unapproved Live stream must remain blocked", unapprovedLive is YouTubeStudyEngine.StudyDecision.BlockPlayback)
        assertEquals(
            YouTubeStudyEngine.PlaybackCutAction.CLOSE_WATCH_AND_MINIPLAYER,
            (unapprovedLive as YouTubeStudyEngine.StudyDecision.BlockPlayback).cutAction
        )

        // 6. LIVE stream with unavailable / UNKNOWN channel metadata (NOT an active approved session) -> BLOCKED!
        val unknownEmptyLive = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.LIVE_PLAYER,
            detectedChannelId = null,
            detectedHandle = null,
            detectedChannelTitle = null,
            approved = approvedSnapshot,
            config = defaultStudyConfig,
            strictMode = false,
            lastApprovedWatchActive = false,
            attribution = YouTubeStudyEngine.ChannelAttribution(
                status = YouTubeStudyEngine.ChannelAttributionStatus.UNKNOWN
            )
        )
        assertTrue(
            "Unknown channel must NEVER automatically become approved simply because it is broadcasting live",
            unknownEmptyLive is YouTubeStudyEngine.StudyDecision.BlockPlayback
        )

        // 7. LIVE stream with PENDING or FAILED attribution -> NEVER silently approved
        val pendingLiveStrict = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.LIVE_PLAYER,
            detectedChannelId = null,
            detectedHandle = null,
            detectedChannelTitle = null,
            approved = approvedSnapshot,
            config = defaultStudyConfig,
            strictMode = true,
            attribution = YouTubeStudyEngine.ChannelAttribution(
                status = YouTubeStudyEngine.ChannelAttributionStatus.PENDING,
                videoId = "LivePend111"
            )
        )
        assertTrue(pendingLiveStrict is YouTubeStudyEngine.StudyDecision.BlockPlayback)

        val pendingLiveNonStrict = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.LIVE_PLAYER,
            detectedChannelId = null,
            detectedHandle = null,
            detectedChannelTitle = null,
            approved = approvedSnapshot,
            config = defaultStudyConfig,
            strictMode = false,
            attribution = YouTubeStudyEngine.ChannelAttribution(
                status = YouTubeStudyEngine.ChannelAttributionStatus.PENDING,
                videoId = "LivePend111"
            )
        )
        assertTrue(
            pendingLiveNonStrict is YouTubeStudyEngine.StudyDecision.NeedsVideoChannelVerification &&
                pendingLiveNonStrict.isLive
        )

        val failedLive = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.LIVE_PLAYER,
            detectedChannelId = null,
            detectedHandle = null,
            detectedChannelTitle = "3Blue1Brown",
            approved = approvedSnapshot,
            config = defaultStudyConfig,
            strictMode = false,
            attribution = YouTubeStudyEngine.ChannelAttribution(
                status = YouTubeStudyEngine.ChannelAttributionStatus.FAILED,
                videoId = "LiveFail222",
                failureReason = "timeout"
            )
        )
        assertTrue(failedLive is YouTubeStudyEngine.StudyDecision.BlockPlayback)

        // 8. Approved Live stream with Live Chat temporarily covering channel header -> stays Allowed ONLY while same approved session is active
        assertEquals(
            "Already-verified approved Live session must not get killed when Live Chat hides channel header",
            YouTubeStudyEngine.StudyDecision.Allow,
            YouTubeStudyEngine.evaluateActivePlayback(
                screenMode = YouTubeStudyEngine.ScreenMode.LIVE_PLAYER,
                detectedChannelId = null,
                detectedHandle = null,
                detectedChannelTitle = null,
                approved = approvedSnapshot,
                config = defaultStudyConfig,
                strictMode = true,
                lastApprovedWatchActive = true,
                attribution = YouTubeStudyEngine.ChannelAttribution(
                    status = YouTubeStudyEngine.ChannelAttributionStatus.UNKNOWN
                )
            )
        )
        // But if an unapproved channel is observed on that player, it is IMMEDIATELY blocked even if lastApprovedWatchActive was true!
        assertTrue(
            YouTubeStudyEngine.evaluateActivePlayback(
                screenMode = YouTubeStudyEngine.ScreenMode.LIVE_PLAYER,
                detectedChannelId = null,
                detectedHandle = "@randomstreamer",
                detectedChannelTitle = "Random Streamer",
                approved = approvedSnapshot,
                config = defaultStudyConfig,
                strictMode = false,
                lastApprovedWatchActive = true
            ) is YouTubeStudyEngine.StudyDecision.BlockPlayback
        )

        // 9. Explicit user opt-out (youtubeAllowLiveFromApproved = false) still blocks Live streams even for approved channels
        assertTrue(
            YouTubeStudyEngine.evaluateActivePlayback(
                screenMode = YouTubeStudyEngine.ScreenMode.LIVE_PLAYER,
                detectedChannelId = approvedVerifiedAttribution.verifiedChannelId,
                detectedHandle = "@3blue1brown",
                detectedChannelTitle = "3Blue1Brown",
                approved = approvedSnapshot,
                config = defaultStudyConfig.copy(youtubeAllowLiveFromApproved = false),
                strictMode = false,
                attribution = approvedVerifiedAttribution
            ) is YouTubeStudyEngine.StudyDecision.BlockPlayback
        )

        // 10. YouTube In-App Miniplayer (distinct from Android PiP): approved Live stays allowed, unapproved Live closes miniplayer only
        assertEquals(
            YouTubeStudyEngine.StudyDecision.Allow,
            YouTubeStudyEngine.evaluateActivePlayback(
                screenMode = YouTubeStudyEngine.ScreenMode.MINI_PLAYER,
                detectedChannelId = null,
                detectedHandle = null,
                detectedChannelTitle = null,
                approved = approvedSnapshot,
                config = defaultStudyConfig,
                strictMode = true,
                lastApprovedWatchActive = true
            )
        )
        val unapprovedMiniLive = YouTubeStudyEngine.evaluateActivePlayback(
            screenMode = YouTubeStudyEngine.ScreenMode.MINI_PLAYER,
            detectedChannelId = null,
            detectedHandle = "@randomstreamer",
            detectedChannelTitle = "Random Streamer",
            approved = approvedSnapshot,
            config = defaultStudyConfig,
            strictMode = false,
            lastApprovedWatchActive = true
        )
        assertTrue(unapprovedMiniLive is YouTubeStudyEngine.StudyDecision.BlockPlayback)
        assertEquals(
            YouTubeStudyEngine.PlaybackCutAction.CLOSE_MINIPLAYER_ONLY,
            (unapprovedMiniLive as YouTubeStudyEngine.StudyDecision.BlockPlayback).cutAction
        )

        // 11. Live <-> VOD transitions via YouTubeChannelVerificationTracker clear prior video attribution cleanly
        val tracker = com.example.domain.YouTubeChannelVerificationTracker()
        val liveStart = tracker.evaluateCurrentPlayback(
            activeVideoId = "LiveStrm123",
            extractedChannelId = "UCYO_jab_esuFRV4b17AJtAw",
            observedHandle = "@3blue1brown",
            observedDisplayTitle = "3Blue1Brown",
            observedVideoTitle = "Live Q&A",
            persistedVideoToChannelMap = emptyMap(),
            nowMs = 1_000L
        )
        assertEquals(YouTubeStudyEngine.ChannelAttributionStatus.VERIFIED, liveStart.attribution.status)

        val switchToVod = tracker.evaluateCurrentPlayback(
            activeVideoId = "VodUnap4567",
            extractedChannelId = null,
            observedHandle = null,
            observedDisplayTitle = "Other Creator",
            observedVideoTitle = "Random VOD",
            persistedVideoToChannelMap = emptyMap(),
            nowMs = 1_500L
        )
        assertTrue("Switching from Live to VOD must trigger videoSwitched = true", switchToVod.videoSwitched)
        assertEquals(YouTubeStudyEngine.ChannelAttributionStatus.PENDING, switchToVod.attribution.status)
        assertEquals(null, switchToVod.attribution.verifiedChannelId)

        // 12. Live player indicator helper catches active Live Chat / viewer counts without matching Home feed "Live" chip
        assertTrue(com.example.blocker.YouTubeAccessibilityInspector.isLivePlayerIndicator("com.google.android.youtube:id/live_chat_container", "", ""))
        assertTrue(com.example.blocker.YouTubeAccessibilityInspector.isLivePlayerIndicator("", "Top chat", ""))
        assertTrue(com.example.blocker.YouTubeAccessibilityInspector.isLivePlayerIndicator("", "14K watching now", ""))
        assertFalse(
            "Plain Home feed 'Live' filter chip must NOT be misclassified as an active LIVE_PLAYER",
            com.example.blocker.YouTubeAccessibilityInspector.isLivePlayerIndicator("", "Live", "Live")
        )

        // 13. Resolver HTML extractor handles YouTube Live externalChannelId payloads
        assertEquals(
            "UCYO_jab_esuFRV4b17AJtAw",
            com.example.data.remote.YouTubeChannelResolver.extractChannelIdFromHtml(
                """{"videoDetails":{"externalChannelId":"UCYO_jab_esuFRV4b17AJtAw","isLive":true}}"""
            )
        )
    }

    @Test
    fun strictModePolicy_locksContentProtectionChanges() {
        assertTrue(
            StrictModePolicy.canDisableContentProtection("YouTube Study Mode", true) is StrictModePolicy.GuardResult.Denied
        )
        assertTrue(
            StrictModePolicy.canModifyApprovedYouTubeChannels(true) is StrictModePolicy.GuardResult.Denied
        )
        assertTrue(
            StrictModePolicy.canModifyApprovedYouTubeChannels(false) is StrictModePolicy.GuardResult.Permitted
        )
    }
}
