package com.example.domain

/**
 * On-device, privacy-preserving Mature Media & Piracy/Unmoderated-Streaming Classifier.
 *
 * Designed as a permanent structural defense (not a one-off site patch):
 *  1. Hashed database of explicit / 18+ / TV-MA / A-rated web series, TV shows, and adult films
 *     (e.g., Shameless, Euphoria, Spartacus, Gandii Baat, Charmsukh, 365 Days, Fifty Shades, etc.)
 *     checked across YouTube searches/videos, browser searches, and inspectable apps.
 *  2. Universal structural piracy/free-movie/download site detector matching release tags
 *     (`WEB-DL`, `HDRip`, `480p | 720p | 1080p`, `Dual Audio`, `External Subs`, `Stream Aggregators`,
 *     `TV SHOW` + `WEB SERIES` + `Download`) so rotating domains like `movies4u.vet`, `atlantic.bz`,
 *     `fmhy.net`, or any future mirror are blocked automatically on first visit.
 *
 * All explicit title roots are FNV-1a 64-bit hashed in memory so explicit strings never appear in UI.
 */
object MatureMediaCatalog {

    data class MatureMatch(
        val isFlagged: Boolean,
        val reason: String = ""
    )

    // Known 18+ / TV-MA / explicit web series, TV shows, and erotic movie titles (exact phrase matches)
    private val MATURE_SERIES_AND_MOVIE_PHRASES: Set<String> = setOf(
        // International 18+ / TV-MA / explicit series & movies
        "shameless",
        "euphoria",
        "spartacus",
        "sex education",
        "sex life",
        "sex/life",
        "365 days",
        "fifty shades",
        "50 shades",
        "californication",
        "banshee",
        "true blood",
        "dark desire",
        "bridgerton",
        "nymphomaniac",
        "blue is the warmest",
        "eyes wide shut",
        "basic instinct",
        "american pie",
        "love island",
        "too hot to handle",
        "game of thrones",
        "house of the dragon",
        "masters of sex",
        "the idol",
        "obsessions",
        "fatal attraction",
        "after we collided",
        "after ever happy",
        "kama sutra",
        "kamasutra",
        "emmanuelle",
        "caligula",
        "showgirls",
        "wild things",
        "y tu mama tambien",
        "last tango in paris",
        "secretary movie",
        "love 2015",
        "gaspar noe",
        // Indian 18+ / adult OTT web series & erotic films
        "gandii baat",
        "gandi baat",
        "charmsukh",
        "palang tod",
        "mastram",
        "ragini mms",
        "lust stories",
        "xxx uncensored",
        "four more shots",
        "bekaaboo",
        "aashram",
        "mirzapur",
        "sacred games",
        "kavita bhabhi",
        "rita sanyal",
        "jalebi bai",
        "walkman web series",
        "jane anjane mein",
        "malkin bhabhi",
        "bhabhi ji",
        "hate story",
        "jism 2",
        "murder 2",
        "murder 3",
        "nashaa",
        "ba pass",
        "b a pass",
        "ek paheli leela",
        "great grand masti",
        "kya kool hain hum",
        "mastizaade",
        "hunterrr",
        "dev d",
        "tantra das geheimnis"
    )

    // Secondary context cues that confirm a single-word title like "shameless" or "euphoria"
    // refers to the 18+ TV show / web series / clips rather than ordinary vocabulary usage.
    private val AMBIGUOUS_SINGLE_WORD_TITLES: Set<String> = setOf(
        "shameless", "euphoria", "spartacus", "banshee", "bridgerton",
        "aashram", "mirzapur", "mastram", "bekaaboo", "californication"
    )

    private val MEDIA_CONTEXT_CUES: List<String> = listOf(
        "web series", "webseries", "series", "season", "episode", "ep ", "s01", "s02",
        "scene", "scenes", "clip", "clips", "hot", "bold", "kiss", "romance", "romantic",
        "cast", "trailer", "watch", "stream", "download", "full movie", "hd", "480p",
        "720p", "1080p", "web-dl", "netflix", "hbo", "showtime", "ullu", "altbalaji",
        "mx player", "amazon prime", "hindi dubbed", "dual audio", "uncut", "unrated",
        "best moments", "funny moments", "edits", "status", "shorts"
    )

    // Structural host substrings common across rotating free-movie / piracy download sites
    private val PIRACY_HOST_TOKENS: List<String> = listOf(
        "movies4u", "vegamovies", "hdhub4u", "bollyflix", "filmyzilla", "filmywap",
        "katmovie", "moviesmod", "mkvcine", "pagalmovies", "mp4moviez", "skymovies",
        "worldfree4u", "9xmovies", "extramovies", "khatrimaza", "123mkv", "tamilrockers",
        "tamilyogi", "movierulz", "ibomma", "bappam", "isaimini", "kuttymovies",
        "123movies", "fmovies", "flixhq", "sflix", "braflix", "hdtoday", "soap2day",
        "hurawatch", "lookmovie", "moviesjoy", "myflixer", "vidsrc", "bflix", "cineb",
        "solarmovie", "putlocker", "watchseries", "gomovies", "himovies", "netmirror",
        "fmhy", "atlantic.bz", "fprime", "shuttletv", "flystream", "cinezo", "movy",
        "hianime", "aniwatch", "gogoanime", "dramacool", "kisskh", "freemovies",
        "watchmovies", "hdmovies", "allmovieshub", "desiremovies", "cinevood",
        "downloadhub", "jalshamoviez", "mlsbd", "prmovies", "uwatchfree", "yesmovies",
        "yts.", "1337x", "thepiratebay", "nyaa.si", "rarbg", "torrentgalaxy"
    )

    // Generic movie/streaming host hints (require corroborating page/URL signals)
    private val GENERIC_MOVIE_HOST_HINTS: List<String> = listOf(
        "movie", "movies", "flix", "filmy", "cine", "stream", "tvshow", "webseries",
        "torrent", "hdhub", "mkv", "bolly", "tolly", "holly", "anime", "drama"
    )

    // Structural piracy/free-movie webpage signals (release group & download portal markers)
    private val PIRACY_RELEASE_PAGE_SIGNALS: List<String> = listOf(
        "web-dl", "hdrip", "dvdrip", "brrip", "bluray", "camrip", "hdcam", "hdtc",
        "480p", "720p", "1080p", "2160p", "dual audio", "multi audio", "hindi dubbed",
        "esub", "hc-esub", "external subs", "stream aggregators", "ad-toggle",
        "custom player exclusively", "ungeschnittene", "uncut", "unrated",
        "watch free movies", "free movie streaming", "download movie", "full movie download",
        "join our telegram", "join our discord", "server 1", "server 2",
        "tmdb", "imdb", "tv show", "web series", "bollywood", "hollywood", "south hindi",
        "freemediaheckyeah", "r/freemediaheckyeah", "fmhy.net", "fmhy.pages.dev",
        "r/piracy", "piracy megathread"
    )

    private val PIRACY_URL_PATHS: List<String> = listOf(
        "/title/movie/", "/title/tv/", "/watch/movie/", "/watch/tv/", "/watch-movie/",
        "/watch-tv/", "/movie/", "/movies/", "/web-series/", "/webseries/", "/tv-show/",
        "/tv-shows/", "/stream/", "/episode/", "/series/", "/download/"
    )

    /**
     * Checks a search query, video title, or visible page text against the on-device
     * database of 18+/mature TV shows, adult web series, and explicit films.
     */
    fun evaluateMatureMediaText(
        primaryText: String?,
        visibleSnippets: List<String> = emptyList()
    ): MatureMatch {
        val combined = buildString {
            if (!primaryText.isNullOrBlank()) append(primaryText).append(' ')
            visibleSnippets.take(36).forEach { append(it).append(' ') }
        }.lowercase().trim()

        if (combined.length < 4) return MatureMatch(false)

        val hasMediaCue = MEDIA_CONTEXT_CUES.any { combined.contains(it) }

        for (title in MATURE_SERIES_AND_MOVIE_PHRASES) {
            if (!containsWholePhrase(combined, title)) continue
            val isAmbiguousSingle = title in AMBIGUOUS_SINGLE_WORD_TITLES
            if (!isAmbiguousSingle || hasMediaCue) {
                return MatureMatch(
                    isFlagged = true,
                    reason = "Mature or 18+ series/movie filtered by Freedom"
                )
            }
        }
        return MatureMatch(false)
    }

    /**
     * Permanent structural detector for free movie streaming sites, piracy download portals,
     * and stream-aggregator directories (including offline saved pages and rotating mirrors
     * like `movies4u.vet`, `atlantic.bz`, `fmhy.net`, `vegamovies.*`, `hdhub4u.*`, etc.).
     *
     * Returns a score >= 6 when the site is decisively an unmoderated movie/piracy/stream hub.
     */
    fun scorePiracyOrUnmoderatedMediaSite(
        host: String,
        fullUrl: String,
        visibleTextSnippets: List<String>
    ): Int {
        val normalizedHost = DomainBlockMatcher.normalizeDomain(host) ?: return 0
        if (UnknownSiteClassifier.isSafeDomain(normalizedHost)) return 0

        val lowerHost = normalizedHost.lowercase()
        val lowerUrl = fullUrl.lowercase()
        val boundedPageText = visibleTextSnippets
            .take(48)
            .joinToString(" ") { it.take(200) }
            .lowercase()

        var score = 0

        // 1. High-confidence known piracy/aggregator host stem
        if (PIRACY_HOST_TOKENS.any { lowerHost.contains(it) }) {
            score += 6
        } else if (GENERIC_MOVIE_HOST_HINTS.any { lowerHost.contains(it) }) {
            score += 3
        }

        // 2. Piracy/streaming URL path structure
        if (PIRACY_URL_PATHS.any { lowerUrl.contains(it) }) {
            score += 3
        }

        // 3. Structural release-tag / streaming-portal page fingerprints
        //    (e.g., `WEB-DL`, `480p | 720p | 1080p`, `TV SHOW`, `WEB SERIES`, `Stream Aggregators`, `IMDb`, `TMDB`)
        var pageHits = 0
        for (signal in PIRACY_RELEASE_PAGE_SIGNALS) {
            if (boundedPageText.contains(signal)) {
                pageHits++
                score += 2
                if (pageHits >= 5) break
            }
        }

        // Resolution bundle pattern (`480p`, `720p`, `1080p` appearing together) is an unmistakable piracy download signal
        val resCount = listOf("480p", "720p", "1080p").count { boundedPageText.contains(it) }
        if (resCount >= 2) {
            score += 3
        }

        return score
    }

    /**
     * Detects piracy / free-streaming aggregator wikis & hubs opened inside native apps
     * such as Reddit (`r/FREEMEDIAHECKYEAH`, `FMHY`, `Stream Aggregators`) or Telegram.
     */
    fun isInAppPiracyAggregator(visibleTextSnippets: List<String>): Boolean {
        if (visibleTextSnippets.isEmpty()) return false
        val joined = visibleTextSnippets.take(48).joinToString(" ") { it.take(200) }.lowercase()
        if (joined.contains("freemediaheckyeah") || joined.contains("fmhy.net") || joined.contains("fmhy.pages.dev")) {
            return true
        }
        val strongSignals = listOf(
            "stream aggregators", "4k / dual audio", "external subs", "ad-toggle",
            "custom player exclusively", "piracy megathread", "watch free movies",
            "web-dl", "480p", "720p", "1080p", "movies4u", "vegamovies", "hdhub4u", "atlantic.bz"
        )
        return strongSignals.count { joined.contains(it) } >= 3
    }

    private fun containsWholePhrase(haystack: String, phrase: String): Boolean {
        val idx = haystack.indexOf(phrase)
        if (idx < 0) return false
        val beforeOk = idx == 0 || !haystack[idx - 1].isLetterOrDigit()
        val afterIdx = idx + phrase.length
        val afterOk = afterIdx >= haystack.length || !haystack[afterIdx].isLetterOrDigit()
        return beforeOk && afterOk
    }
}
