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
