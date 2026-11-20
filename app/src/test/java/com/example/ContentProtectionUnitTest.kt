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

    // -------------------------------------------------------------------------
    // 5. On-Device In-App Adult-Content Filtering & Strict Mode Guards
    // -------------------------------------------------------------------------

    @Test
    fun inAppAdultFilter_flagsExplicitQueriesAndPreservesEducationalContent() {
        val explicit = InAppAdultFilterEngine.evaluateQueryOrUrl("free xxx porn video")
        assertTrue(explicit.isFlagged)

        val explicitCard = InAppAdultFilterEngine.evaluateCardText(listOf("Hot uncensored adult movie 18+"))
        assertTrue(explicitCard.isFlagged)

        val educationalBio = InAppAdultFilterEngine.evaluateQueryOrUrl("NCERT Class 12 human reproduction biology lecture")
        assertFalse(educationalBio.isFlagged)

        val educationalMed = InAppAdultFilterEngine.evaluateCardText(listOf("Breast cancer early screening medical science lecture"))
        assertFalse(educationalMed.isFlagged)
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
