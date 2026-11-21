package com.example.data.remote

import com.example.domain.ChannelLookupState
import com.example.domain.ChannelVerificationSource
import com.example.domain.VerifiedYouTubeChannel
import com.example.domain.YouTubeStudyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real 3-Tier Hybrid YouTube Permanent Channel ID (`UC...`) Verification System.
 *
 * Tier 1 (Primary — No API Key Required):
 *  - Resolves channel URLs (`youtube.com/@handle`, `youtube.com/channel/UC...`, `/c/...`),
 *    video URLs (`youtube.com/watch?v=...`, `youtu.be/...`, `/shorts/...`), or channel
 *    search queries via YouTube's public metadata/oEmbed/search endpoints.
 * Tier 2 (Optional Secondary Fallback — User's YouTube Data API v3 Key):
 *  - If Tier 1 fails or is rate-limited and the user configured an optional YouTube Data
 *    API v3 key in settings, queries official Google YouTube Data API v3 endpoints.
 * Tier 3 (Final Fallback — Manual Permanent UC... Channel ID Entry):
 *  - Strictly validates canonical `UC[A-Za-z0-9_-]{22}` format and verifies channel
 *    existence via public YouTube Atom RSS feed + channel metadata so unverified channels
 *    can NEVER be approved.
 *
 * Privacy guarantee:
 *  - Only sends the user-entered channel/video identifier or search query during explicit
 *    lookup, or the single 11-char videoId for uncached direct YouTube links.
 *  - Never transmits browsing history, other URLs, or personal user data.
 */
class YouTubeChannelResolver(
    private val httpGet: (String) -> HttpFetchResult = ::defaultHttpGet
) {

    data class HttpFetchResult(
        val statusCode: Int,
        val body: String? = null,
        val errorMessage: String? = null
    ) {
        val isSuccess: Boolean get() = statusCode in 200..299 && !body.isNullOrEmpty()
    }

    /**
     * Resolves a user input that may be:
     *  - A YouTube channel URL (`youtube.com/@3blue1brown`, `youtube.com/channel/UC...`)
     *  - A YouTube video URL (`youtube.com/watch?v=...`, `youtu.be/...`, `youtube.com/shorts/...`)
     *  - A direct `@handle`
     *  - A permanent `UC...` Channel ID
     *  - A channel name search query (e.g. "3Blue1Brown", "Khan Academy")
     */
    suspend fun resolveInput(
        rawInput: String,
        optionalApiKey: String
    ): ChannelLookupState = withContext(Dispatchers.IO) {
        val input = rawInput.trim()
        if (input.isEmpty()) {
            return@withContext ChannelLookupState.Error(
                reason = "Enter a YouTube channel URL, video URL, @handle, channel name, or UC... channel ID.",
                suggestedFallback = "Paste any YouTube channel or video link to verify its permanent ID."
            )
        }

        val apiKey = optionalApiKey.trim()

        // Case A: Raw permanent UC... channel ID
        if (YouTubeStudyEngine.isValidPermanentChannelId(input)) {
            return@withContext verifyByPermanentChannelId(input, apiKey)
        }

        // Case B: Direct @handle (e.g. "@3blue1brown")
        if (input.startsWith("@") && !input.contains(' ')) {
            val handleUrl = "https://www.youtube.com/$input"
            return@withContext resolveChannelPageOrFallback(
                channelUrl = handleUrl,
                fallbackHandle = input,
                apiKey = apiKey
            )
        }

        // Case C: YouTube URL (channel URL or video URL)
        val parsedLink = YouTubeStudyEngine.parseYouTubeUrl(input)
        if (parsedLink != null) {
            if (!parsedLink.channelId.isNullOrBlank()) {
                return@withContext verifyByPermanentChannelId(parsedLink.channelId, apiKey)
            }
            if (!parsedLink.videoId.isNullOrBlank()) {
                return@withContext resolveFromVideoId(parsedLink.videoId, apiKey)
            }
            if (!parsedLink.handle.isNullOrBlank()) {
                return@withContext resolveChannelPageOrFallback(
                    channelUrl = "https://www.youtube.com/${parsedLink.handle}",
                    fallbackHandle = parsedLink.handle,
                    apiKey = apiKey
                )
            }
            // Legacy /c/Name or /user/Name URL
            val normalizedUrl = if (input.startsWith("http://", true) || input.startsWith("https://", true)) {
                input
            } else {
                "https://$input"
            }
            return@withContext resolveChannelPageOrFallback(
                channelUrl = normalizedUrl,
                fallbackHandle = "",
                apiKey = apiKey
            )
        }

        // Case D: Channel Search Query (e.g., "Khan Academy", "Physics Wallah")
        return@withContext searchChannelsHybrid(input, apiKey)
    }

    /**
     * Tier 3 / Explicit Manual Channel ID Verification.
     * Validates canonical 24-character `UC...` format and verifies channel identity via
     * public channel page metadata, YouTube Atom RSS feed, or optional API v3.
     */
    suspend fun verifyByPermanentChannelId(
        rawChannelId: String,
        optionalApiKey: String = ""
    ): ChannelLookupState = withContext(Dispatchers.IO) {
        val cid = rawChannelId.trim()
        if (!YouTubeStudyEngine.isValidPermanentChannelId(cid)) {
            return@withContext ChannelLookupState.Error(
                reason = "Invalid YouTube Channel ID format. Permanent IDs start with 'UC' and are exactly 24 characters long.",
                suggestedFallback = "Open a channel in YouTube → Share → Copy link, then paste the URL above."
            )
        }

        // 1. Primary: Public YouTube channel page metadata (gives title, @handle, AND avatar)
        val pageRes = httpGet("https://www.youtube.com/channel/$cid")
        if (pageRes.isSuccess) {
            parseChannelFromPageHtml(pageRes.body.orEmpty(), expectedChannelId = cid)?.let { verified ->
                return@withContext ChannelLookupState.VerifiedCandidate(verified)
            }
        }

        // 2. Fallback: YouTube Data API v3 if key is configured
        val apiKey = optionalApiKey.trim()
        if (apiKey.isNotEmpty()) {
            fetchChannelViaApiV3ById(cid, apiKey)?.let { verified ->
                return@withContext ChannelLookupState.VerifiedCandidate(verified)
            }
        }

        // 3. Final verification: Official YouTube Atom RSS feed (ultra-reliable, no consent wall)
        val rssRes = httpGet("https://www.youtube.com/feeds/videos.xml?channel_id=$cid")
        if (rssRes.isSuccess) {
            parseChannelFromRssXml(rssRes.body.orEmpty(), cid)?.let { verified ->
                return@withContext ChannelLookupState.VerifiedCandidate(verified)
            }
        }

        val networkHint = when {
            pageRes.statusCode == 404 || rssRes.statusCode == 404 ->
                "No YouTube channel exists for ID '$cid'."
            pageRes.statusCode == 429 ->
                "YouTube temporarily rate-limited public lookups."
            pageRes.errorMessage != null ->
                "Network error (${pageRes.errorMessage}). Verify internet connectivity and retry."
            else ->
                "Could not verify permanent channel ID '$cid' from YouTube."
        }
        ChannelLookupState.Error(
            reason = networkHint,
            suggestedFallback = if (apiKey.isEmpty()) {
                "You can also configure an optional YouTube Data API v3 key in Advanced Settings as a secondary fallback."
            } else {
                "Double-check the UC... Channel ID or paste a direct video URL from that channel."
            }
        )
    }

    /**
     * Resolves a YouTube video ID to its verified permanent channel identity (`UC...`, `@handle`, title, avatar).
     */
    suspend fun resolveFromVideoId(
        videoId: String,
        optionalApiKey: String = ""
    ): ChannelLookupState = withContext(Dispatchers.IO) {
        val vid = videoId.trim()
        if (!YouTubeStudyEngine.isValidVideoId(vid)) {
            return@withContext ChannelLookupState.Error(
                reason = "Invalid YouTube video ID.",
                suggestedFallback = "Paste a full youtube.com/watch?v=... or youtu.be/... link."
            )
        }

        // 1. Primary: Official YouTube oEmbed endpoint -> author_url -> channel page permanent ID
        val watchUrl = "https://www.youtube.com/watch?v=$vid"
        val encodedWatch = URLEncoder.encode(watchUrl, "UTF-8")
        val oembedRes = httpGet("https://www.youtube.com/oembed?url=$encodedWatch&format=json")
        if (oembedRes.isSuccess) {
            val authorUrl = runCatching {
                MiniJson.parseObject(oembedRes.body.orEmpty())?.str("author_url").orEmpty()
            }.getOrDefault("")
            if (authorUrl.isNotBlank()) {
                val resolved = resolveChannelPageOrFallback(
                    channelUrl = authorUrl,
                    fallbackHandle = YouTubeStudyEngine.normalizeHandle(authorUrl),
                    apiKey = optionalApiKey
                )
                if (resolved is ChannelLookupState.VerifiedCandidate) {
                    return@withContext resolved
                }
            }
        }

        // 2. Primary backup: Public watch page HTML -> channelId extraction -> verify channel
        val watchHtmlRes = httpGet(watchUrl)
        if (watchHtmlRes.isSuccess) {
            val extractedCid = extractChannelIdFromHtml(watchHtmlRes.body.orEmpty())
            if (extractedCid != null) {
                return@withContext verifyByPermanentChannelId(extractedCid, optionalApiKey)
            }
        }

        // 3. Secondary Fallback: Optional YouTube Data API v3
        val apiKey = optionalApiKey.trim()
        if (apiKey.isNotEmpty()) {
            val cidFromApi = fetchVideoChannelIdViaApiV3(vid, apiKey)
            if (cidFromApi != null) {
                return@withContext verifyByPermanentChannelId(cidFromApi, apiKey)
            }
        }

        ChannelLookupState.Error(
            reason = "Could not verify the channel behind video '$vid' (${oembedRes.errorMessage ?: "HTTP ${oembedRes.statusCode}"}).",
            suggestedFallback = "Try pasting the channel URL (youtube.com/@handle) or permanent UC... ID directly."
        )
    }

    /**
     * Lightweight, privacy-minimal lookup used ONLY when an uncached direct YouTube video link
     * is opened while Study Mode is active. Sends ONLY the 11-char videoId to YouTube's public
     * endpoint to verify its permanent UC... channel ID.
     */
    suspend fun resolveVideoToPermanentChannelId(
        videoId: String,
        optionalApiKey: String = ""
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        val state = resolveFromVideoId(videoId, optionalApiKey)
        if (state is ChannelLookupState.VerifiedCandidate) {
            state.channel.channelId to state.channel.title
        } else {
            null
        }
    }

    private fun resolveChannelPageOrFallback(
        channelUrl: String,
        fallbackHandle: String,
        apiKey: String
    ): ChannelLookupState {
        // Tier 1: Direct public channel page metadata
        val pageRes = httpGet(channelUrl)
        if (pageRes.isSuccess) {
            parseChannelFromPageHtml(pageRes.body.orEmpty(), expectedChannelId = null)?.let { verified ->
                return ChannelLookupState.VerifiedCandidate(verified)
            }
        }

        // Tier 2: Optional YouTube Data API v3 fallback (by @handle)
        if (apiKey.isNotEmpty() && fallbackHandle.isNotBlank()) {
            fetchChannelViaApiV3ByHandle(fallbackHandle, apiKey)?.let { verified ->
                return ChannelLookupState.VerifiedCandidate(verified)
            }
        }

        val reason = when {
            pageRes.statusCode == 404 -> "Channel not found at $channelUrl."
            pageRes.statusCode == 429 -> "YouTube temporarily rate-limited public metadata lookups."
            pageRes.errorMessage != null -> "Unable to reach YouTube (${pageRes.errorMessage})."
            else -> "Could not extract a verified permanent channel ID from that URL."
        }
        return ChannelLookupState.Error(
            reason = reason,
            suggestedFallback = "Paste a video link from the channel, add an optional YouTube Data API v3 key, or enter the permanent UC... Channel ID manually."
        )
    }

    private fun searchChannelsHybrid(
        query: String,
        apiKey: String
    ): ChannelLookupState {
        // Tier 1: Public YouTube channel-only search (`sp=EgIQAg%3D%3D` filters strictly to Channels)
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val filteredSearchUrl = "https://www.youtube.com/results?search_query=$encodedQuery&sp=EgIQAg%3D%3D"
        val res = httpGet(filteredSearchUrl)
        if (res.isSuccess) {
            val candidates = parseSearchChannelsFromHtml(res.body.orEmpty())
            if (candidates.isNotEmpty()) {
                return ChannelLookupState.SearchResults(
                    query = query,
                    candidates = candidates,
                    source = ChannelVerificationSource.PUBLIC_METADATA
                )
            }
        }

        // Tier 1 backup: unfiltered YouTube results page (still extracts strictly channelRenderer/compactChannelRenderer)
        val fallbackSearchUrl = "https://www.youtube.com/results?search_query=$encodedQuery"
        val fallbackRes = httpGet(fallbackSearchUrl)
        if (fallbackRes.isSuccess) {
            val candidates = parseSearchChannelsFromHtml(fallbackRes.body.orEmpty())
            if (candidates.isNotEmpty()) {
                return ChannelLookupState.SearchResults(
                    query = query,
                    candidates = candidates,
                    source = ChannelVerificationSource.PUBLIC_METADATA
                )
            }
        }

        // Tier 2: Optional YouTube Data API v3 Search Fallback
        if (apiKey.isNotEmpty()) {
            val apiCandidates = searchChannelsViaApiV3(query, apiKey)
            if (apiCandidates.isNotEmpty()) {
                return ChannelLookupState.SearchResults(
                    query = query,
                    candidates = apiCandidates,
                    source = ChannelVerificationSource.YOUTUBE_DATA_API_V3
                )
            }
        }

        val reason = when {
            res.statusCode == 429 -> "YouTube temporarily rate-limited public search."
            res.errorMessage != null -> "Search failed (${res.errorMessage}). Check your internet connection."
            else -> "No verified channels matched \"$query\"."
        }
        return ChannelLookupState.Error(
            reason = reason,
            suggestedFallback = "Paste the channel URL (youtube.com/@handle), a video link from the channel, or its permanent UC... ID."
        )
    }

    // -------------------------------------------------------------------------
    // Tier 2: Optional YouTube Data API v3 Helpers (User-Provided Key Only)
    // -------------------------------------------------------------------------

    private fun fetchChannelViaApiV3ById(channelId: String, apiKey: String): VerifiedYouTubeChannel? {
        val url = "https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics&id=$channelId&key=${URLEncoder.encode(apiKey, "UTF-8")}"
        val res = httpGet(url)
        if (!res.isSuccess) return null
        return parseApiV3ChannelList(res.body.orEmpty()).firstOrNull()
    }

    private fun fetchChannelViaApiV3ByHandle(handle: String, apiKey: String): VerifiedYouTubeChannel? {
        val cleanHandle = handle.trimStart('@')
        val url = "https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics&forHandle=${URLEncoder.encode(cleanHandle, "UTF-8")}&key=${URLEncoder.encode(apiKey, "UTF-8")}"
        val res = httpGet(url)
        if (!res.isSuccess) return null
        return parseApiV3ChannelList(res.body.orEmpty()).firstOrNull()
    }

    private fun fetchVideoChannelIdViaApiV3(videoId: String, apiKey: String): String? {
        val url = "https://www.googleapis.com/youtube/v3/videos?part=snippet&id=$videoId&key=${URLEncoder.encode(apiKey, "UTF-8")}"
        val res = httpGet(url)
        if (!res.isSuccess) return null
        return runCatching {
            val items = MiniJson.parseObject(res.body.orEmpty())?.arr("items") ?: return null
            val first = items.firstOrNull() as? Map<*, *> ?: return null
            val cid = first.obj("snippet")?.str("channelId").orEmpty()
            cid.takeIf { YouTubeStudyEngine.isValidPermanentChannelId(it) }
        }.getOrNull()
    }

    private fun searchChannelsViaApiV3(query: String, apiKey: String): List<VerifiedYouTubeChannel> {
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=channel&maxResults=8&q=${URLEncoder.encode(query, "UTF-8")}&key=${URLEncoder.encode(apiKey, "UTF-8")}"
        val res = httpGet(url)
        if (!res.isSuccess) return emptyList()
        return runCatching {
            val items = MiniJson.parseObject(res.body.orEmpty())?.arr("items") ?: return emptyList()
            val out = ArrayList<VerifiedYouTubeChannel>()
            for (rawItem in items) {
                val obj = rawItem as? Map<*, *> ?: continue
                val cid = obj.obj("id")?.str("channelId").orEmpty()
                if (!YouTubeStudyEngine.isValidPermanentChannelId(cid)) continue
                val snippet = obj.obj("snippet") ?: continue
                val title = snippet.str("title").trim()
                val avatar = snippet.obj("thumbnails")?.obj("default")?.str("url").orEmpty()
                if (title.isNotEmpty()) {
                    out.add(
                        VerifiedYouTubeChannel(
                            channelId = cid,
                            handle = "",
                            title = title,
                            avatarUrl = avatar,
                            verifiedSource = ChannelVerificationSource.YOUTUBE_DATA_API_V3
                        )
                    )
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun parseApiV3ChannelList(json: String): List<VerifiedYouTubeChannel> {
        return runCatching {
            val items = MiniJson.parseObject(json)?.arr("items") ?: return emptyList()
            val out = ArrayList<VerifiedYouTubeChannel>()
            for (rawItem in items) {
                val obj = rawItem as? Map<*, *> ?: continue
                val cid = obj.str("id")
                if (!YouTubeStudyEngine.isValidPermanentChannelId(cid)) continue
                val snippet = obj.obj("snippet") ?: continue
                val title = snippet.str("title").trim()
                val rawHandle = snippet.str("customUrl").trim()
                val handle = when {
                    rawHandle.startsWith("@") -> rawHandle.lowercase()
                    rawHandle.isNotEmpty() -> "@${rawHandle.lowercase()}"
                    else -> ""
                }
                val thumbs = snippet.obj("thumbnails")
                val avatar = thumbs?.obj("medium")?.str("url")
                    ?.ifBlank { thumbs.obj("default")?.str("url").orEmpty() }
                    .orEmpty()
                val subsRaw = obj.obj("statistics")?.str("subscriberCount").orEmpty()
                val subsLabel = subsRaw.toLongOrNull()?.let { formatSubscriberCount(it) }.orEmpty()
                if (title.isNotEmpty()) {
                    out.add(
                        VerifiedYouTubeChannel(
                            channelId = cid,
                            handle = handle,
                            title = title,
                            avatarUrl = avatar,
                            subscriberSummary = subsLabel,
                            verifiedSource = ChannelVerificationSource.YOUTUBE_DATA_API_V3
                        )
                    )
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

    private fun Map<*, *>.arr(key: String): List<Any?>? = this[key] as? List<Any?>

    private fun Map<*, *>.str(key: String): String = when (val v = this[key]) {
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> ""
    }

    /**
     * Ultra-lightweight, zero-dependency pure-Kotlin JSON parser so oEmbed and
     * optional YouTube Data API v3 responses work identically on Android and JVM tests.
     */
    private class MiniJson(private val src: String) {
        private var pos = 0

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= src.length) return null
            return when (src[pos]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseString()
                't' -> if (src.startsWith("true", pos)) { pos += 4; true } else null
                'f' -> if (src.startsWith("false", pos)) { pos += 5; false } else null
                'n' -> if (src.startsWith("null", pos)) { pos += 4; null } else null
                else -> parseNumber()
            }
        }

        private fun parseObj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // '{'
            skipWs()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (pos < src.length) {
                skipWs()
                if (pos >= src.length || src[pos] != '"') break
                val key = parseString()
                skipWs()
                if (pos >= src.length || src[pos] != ':') break
                pos++ // ':'
                map[key] = parseValue()
                skipWs()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                } else if (pos < src.length && src[pos] == '}') {
                    pos++
                    break
                } else {
                    break
                }
            }
            return map
        }

        private fun parseArr(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // '['
            skipWs()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (pos < src.length) {
                list.add(parseValue())
                skipWs()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                } else if (pos < src.length && src[pos] == ']') {
                    pos++
                    break
                } else {
                    break
                }
            }
            return list
        }

        private fun parseString(): String {
            pos++ // opening '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> if (pos < src.length) {
                        when (val esc = src[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> if (pos + 4 <= src.length) {
                                val hex = src.substring(pos, pos + 4)
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                pos += 4
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): String {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] in "-+.eE")) pos++
            return src.substring(start, pos)
        }

        companion object {
            @Suppress("UNCHECKED_CAST")
            fun parseObject(json: String): Map<String, Any?>? =
                MiniJson(json).parseValue() as? Map<String, Any?>
        }
    }

    companion object {
        private val EXTERNAL_ID_REGEX = Regex("\"(?:externalId|channelId|browseId)\"\\s*:\\s*\"(UC[A-Za-z0-9_-]{22})\"")
        private val CANONICAL_LINK_REGEX = Regex("https?://www\\.youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})")
        private val OG_TITLE_REGEX = Regex("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"")
        private val OG_IMAGE_REGEX = Regex("<meta\\s+property=\"og:image\"\\s+content=\"([^\"]+)\"")
        private val VANITY_URL_REGEX = Regex("\"vanityChannelUrl\"\\s*:\\s*\"https?://www\\.youtube\\.com/(@[A-Za-z0-9._-]+)\"")
        private val CANONICAL_HANDLE_REGEX = Regex("\"canonicalBaseUrl\"\\s*:\\s*\"/(@[A-Za-z0-9._-]+)\"")
        private val RSS_TITLE_REGEX = Regex("<title>([^<]+)</title>")
        private val CHANNEL_RENDERER_REGEX = Regex(
            "\"channelRenderer\"\\s*:\\s*\\{\"channelId\"\\s*:\\s*\"(UC[A-Za-z0-9_-]{22})\".*?\"title\"\\s*:\\s*\\{\"simpleText\"\\s*:\\s*\"([^\"]+)\"",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        fun extractChannelIdFromHtml(html: String): String? {
            CANONICAL_LINK_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { return it }
            EXTERNAL_ID_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { return it }
            return null
        }

        fun parseChannelFromPageHtml(
            html: String,
            expectedChannelId: String?
        ): VerifiedYouTubeChannel? {
            val cid = expectedChannelId?.takeIf { YouTubeStudyEngine.isValidPermanentChannelId(it) }
                ?: extractChannelIdFromHtml(html)
                ?: return null

            val rawTitle = OG_TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)?.let(::unescapeHtml)
                ?: Regex("\"title\"\\s*:\\s*\"([^\"]{1,120})\"").find(html)?.groupValues?.getOrNull(1)?.let(::unescapeJson)
                ?: return null

            val cleanTitle = rawTitle.removeSuffix(" - YouTube").trim()
            if (cleanTitle.isEmpty() || cleanTitle.equals("YouTube", ignoreCase = true)) return null

            val handle = VANITY_URL_REGEX.find(html)?.groupValues?.getOrNull(1)?.lowercase()
                ?: CANONICAL_HANDLE_REGEX.find(html)?.groupValues?.getOrNull(1)?.lowercase()
                ?: ""

            val avatarUrl = OG_IMAGE_REGEX.find(html)?.groupValues?.getOrNull(1)?.let(::unescapeHtml).orEmpty()

            return VerifiedYouTubeChannel(
                channelId = cid,
                handle = handle,
                title = cleanTitle,
                avatarUrl = avatarUrl,
                verifiedSource = ChannelVerificationSource.PUBLIC_METADATA
            )
        }

        fun parseChannelFromRssXml(xml: String, channelId: String): VerifiedYouTubeChannel? {
            if (!YouTubeStudyEngine.isValidPermanentChannelId(channelId)) return null
            if (!xml.contains("<yt:channelId>") && !xml.contains(channelId)) return null
            val title = RSS_TITLE_REGEX.find(xml)?.groupValues?.getOrNull(1)?.let(::unescapeHtml)?.trim()
                ?: return null
            if (title.isEmpty()) return null
            return VerifiedYouTubeChannel(
                channelId = channelId,
                handle = "",
                title = title,
                avatarUrl = "",
                verifiedSource = ChannelVerificationSource.MANUAL_ID_RSS
            )
        }

        fun parseSearchChannelsFromHtml(html: String): List<VerifiedYouTubeChannel> {
            val seen = LinkedHashMap<String, VerifiedYouTubeChannel>()
            // Support BOTH desktop ("channelRenderer") and mobile ("compactChannelRenderer") JSON blocks
            val markers = listOf("\"channelRenderer\":{", "\"compactChannelRenderer\":{")
            for (marker in markers) {
                var searchStart = 0
                while (seen.size < 10) {
                    val idx = html.indexOf(marker, searchStart)
                    if (idx == -1) break
                    val endIdx = (idx + 2800).coerceAtMost(html.length)
                    val block = html.substring(idx, endIdx)
                    searchStart = idx + marker.length

                    val cid = Regex("\"channelId\"\\s*:\\s*\"(UC[A-Za-z0-9_-]{22})\"").find(block)
                        ?.groupValues?.getOrNull(1)
                        ?: continue
                    if (cid in seen) continue

                    val title = Regex("\"(?:title|displayName)\"\\s*:\\s*\\{\"simpleText\"\\s*:\\s*\"([^\"]+)\"").find(block)
                        ?.groupValues?.getOrNull(1)?.let(::unescapeJson)?.trim()
                        ?: Regex("\"(?:title|displayName)\"\\s*:\\s*\\{\"runs\"\\s*:\\s*\\[\\{\"text\"\\s*:\\s*\"([^\"]+)\"").find(block)
                            ?.groupValues?.getOrNull(1)?.let(::unescapeJson)?.trim()
                        ?: continue
                    if (title.isEmpty()) continue

                    val handle = CANONICAL_HANDLE_REGEX.find(block)?.groupValues?.getOrNull(1)?.lowercase()
                        ?: Regex("\"(?:subscriberCountText|videoCountText)\"\\s*:\\s*\\{\"simpleText\"\\s*:\\s*\"(@[A-Za-z0-9._-]+)\"").find(block)
                            ?.groupValues?.getOrNull(1)?.lowercase()
                        ?: ""

                    val avatarRaw = Regex("\"thumbnails\"\\s*:\\s*\\[\\{\"url\"\\s*:\\s*\"([^\"]+)\"").find(block)
                        ?.groupValues?.getOrNull(1)?.let(::unescapeJson).orEmpty()
                    val avatarUrl = when {
                        avatarRaw.startsWith("//") -> "https:$avatarRaw"
                        else -> avatarRaw
                    }

                    val subs = Regex("\"videoCountText\"\\s*:\\s*\\{\"simpleText\"\\s*:\\s*\"([^\"]+)\"").find(block)
                        ?.groupValues?.getOrNull(1)?.let(::unescapeJson)
                        ?.takeIf { !it.startsWith("@") }
                        ?: Regex("\"subscriberCountText\"\\s*:\\s*\\{\"simpleText\"\\s*:\\s*\"([^\"]+)\"").find(block)
                            ?.groupValues?.getOrNull(1)?.let(::unescapeJson)
                            ?.takeIf { !it.startsWith("@") }
                        ?: Regex("\"subscriberCountText\"\\s*:\\s*\\{\"runs\"\\s*:\\s*\\[\\{\"text\"\\s*:\\s*\"([^\"]+)\"").find(block)
                            ?.groupValues?.getOrNull(1)?.let(::unescapeJson)
                        ?: ""

                    seen[cid] = VerifiedYouTubeChannel(
                        channelId = cid,
                        handle = handle,
                        title = title,
                        avatarUrl = avatarUrl,
                        subscriberSummary = subs,
                        verifiedSource = ChannelVerificationSource.PUBLIC_METADATA
                    )
                }
            }
            // Fallback regex pass if YouTube slightly adjusts JSON ordering
            if (seen.isEmpty()) {
                CHANNEL_RENDERER_REGEX.findAll(html).take(8).forEach { m ->
                    val cid = m.groupValues[1]
                    val title = unescapeJson(m.groupValues[2]).trim()
                    if (cid !in seen && title.isNotEmpty()) {
                        seen[cid] = VerifiedYouTubeChannel(
                            channelId = cid,
                            handle = "",
                            title = title,
                            avatarUrl = "",
                            verifiedSource = ChannelVerificationSource.PUBLIC_METADATA
                        )
                    }
                }
            }
            return seen.values.toList()
        }

        private fun defaultHttpGet(urlStr: String): HttpFetchResult {
            var conn: HttpURLConnection? = null
            return try {
                val isSearchResults = urlStr.contains("/results?")
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 9_000
                    readTimeout = 12_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty(
                        "User-Agent",
                        if (isSearchResults) {
                            // Desktop UA avoids m.youtube.com redirect and returns rich channelRenderer JSON
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                        } else {
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                        }
                    )
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    // Bypass EU consent interstitial for public metadata extraction
                    setRequestProperty("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+667")
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.let { s ->
                    BufferedReader(InputStreamReader(s, Charsets.UTF_8), 16 * 1024).use { reader ->
                        val sb = StringBuilder(64 * 1024)
                        val buf = CharArray(8192)
                        var total = 0
                        // Cap read size to 1.2 MB to keep memory ultra-light
                        while (total < 1_200_000) {
                            val n = reader.read(buf)
                            if (n <= 0) break
                            sb.append(buf, 0, n)
                            total += n
                        }
                        sb.toString()
                    }
                }
                HttpFetchResult(statusCode = code, body = body)
            } catch (e: Exception) {
                HttpFetchResult(statusCode = -1, errorMessage = e.message ?: "Network unavailable")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

        private fun formatSubscriberCount(count: Long): String = when {
            count >= 1_000_000 -> String.format("%.1fM subscribers", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK subscribers", count / 1_000.0)
            else -> "$count subscribers"
        }

        private fun unescapeHtml(s: String): String = s
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

        private fun unescapeJson(s: String): String = s
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
    }
}
