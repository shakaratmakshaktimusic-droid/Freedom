package com.example.domain

import androidx.compose.runtime.Immutable

/**
 * Always-on Content Protection configuration.
 * All three engines (YouTube Study Mode, Universal SafeSearch, In-App Adult Filtering)
 * work independently even outside focus sessions, and automatically lock against
 * disabling or weakening whenever Strict Mode is active.
 */
@Immutable
data class ContentProtectionConfig(
    val youtubeStudyModeEnabled: Boolean = false,
    val youtubeMaskFeedCards: Boolean = true,
    val youtubeAllowShortsFromApproved: Boolean = false,
    val youtubeAllowLiveFromApproved: Boolean = false,
    val youtubeDataApiKey: String = "",
    val safeSearchEnabled: Boolean = false,
    val blockUnverifiedSearchEnginesInStrictMode: Boolean = true,
    val inAppAdultFilterEnabled: Boolean = false,
    val blockImageSearchInStrictMode: Boolean = false,
    val blockUnfilterableAppsInStrictMode: Boolean = false
) {
    val anyProtectionEnabled: Boolean
        get() = youtubeStudyModeEnabled || safeSearchEnabled || inAppAdultFilterEnabled
}

enum class YouTubeClientSupportTier(val label: String, val canVerifyChannelsReliably: Boolean) {
    FULL_STUDY_MODE("Full Study Mode", true),
    WATCH_VERIFICATION_ONLY("Watch-Page Verification", true),
    UNVERIFIABLE("Unverifiable (Blocked in Strict Mode)", false);

    companion object {
        fun fromName(raw: String?): YouTubeClientSupportTier =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNVERIFIABLE
    }
}

enum class YouTubeClientFamily(val label: String) {
    OFFICIAL("Official YouTube"),
    MORPHE("Morphe YT"),
    REVANCED("YouTube ReVanced / Patched"),
    THIRD_PARTY("Third-Party Client")
}

@Immutable
data class YouTubeClientProfile(
    val packageName: String,
    val displayName: String,
    val family: YouTubeClientFamily,
    val supportTier: YouTubeClientSupportTier,
    val supportsFeedCardMasking: Boolean,
    val isInstalled: Boolean = false,
    val notes: String = ""
)

enum class ChannelVerificationSource(val label: String) {
    PUBLIC_METADATA("Verified via Public Metadata"),
    YOUTUBE_DATA_API_V3("Verified via YouTube Data API v3"),
    MANUAL_ID_RSS("Verified via Channel ID + RSS");

    companion object {
        fun fromName(raw: String?): ChannelVerificationSource =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PUBLIC_METADATA
    }
}

@Immutable
data class VerifiedYouTubeChannel(
    val channelId: String, // Permanent canonical UC... ID (24 chars)
    val handle: String, // Normalized "@handle" or empty if channel has no handle
    val title: String,
    val avatarUrl: String,
    val subscriberSummary: String = "",
    val verifiedSource: ChannelVerificationSource = ChannelVerificationSource.PUBLIC_METADATA,
    val verifiedAtMs: Long = System.currentTimeMillis()
)

sealed class ChannelLookupState {
    data object Idle : ChannelLookupState()
    data class Loading(val statusText: String) : ChannelLookupState()
    data class VerifiedCandidate(val channel: VerifiedYouTubeChannel) : ChannelLookupState()
    data class SearchResults(
        val query: String,
        val candidates: List<VerifiedYouTubeChannel>,
        val source: ChannelVerificationSource
    ) : ChannelLookupState()
    data class Error(
        val reason: String,
        val suggestedFallback: String
    ) : ChannelLookupState()
}

enum class SafeSearchEnforceMethod(val label: String) {
    URL_PARAM("Strict URL Enforcement"),
    COOKIE_ONLY("Settings Guard Only");

    companion object {
        fun fromName(raw: String?): SafeSearchEnforceMethod =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: URL_PARAM
    }
}

@Immutable
data class SafeSearchProviderRule(
    val providerId: String,
    val displayName: String,
    val hosts: List<String>,
    val searchPaths: List<String>,
    val strictParamKey: String,
    val strictParamValue: String,
    val unsafeParamValues: List<String> = emptyList(),
    val settingsPaths: List<String> = emptyList(),
    val imageIndicators: List<String> = emptyList(),
    val enforceMethod: SafeSearchEnforceMethod = SafeSearchEnforceMethod.URL_PARAM
)
