package com.example.domain

/**
 * Lightweight, bounded pure-Kotlin heuristic classifier for unrecognized websites.
 *
 * Performance & battery guarantees:
 *  - Zero ML models, zero background polling, zero network calls.
 *  - Caller invokes ONLY once per newly navigated unknown URL during an active session.
 *  - Skips safe domains (.edu/.gov/Wikipedia/docs) and user Website Allowlist immediately.
 *  - Inspects normalized host/URL path + at most ~40 bounded visible page snippets (<2 ms).
 *  - Requires high-confidence explicit tokens or multiple converging signals so normal
 *    educational/news pages are not falsely flagged.
 */
object UnknownSiteClassifier {

    data class ClassificationResult(
        val category: WebsiteCategory,
        val normalizedHost: String,
        val score: Int
    )

    // Strong host/URL indicators (weight 6 -> instant match when category is active)
    private val ADULT_STRONG_HOST_TOKENS = listOf(
        "porn", "xxx", "xnxx", "xvideos", "xhamster", "hentai", "nsfw", "redtube",
        "youporn", "spankbang", "chaturbate", "stripchat", "camgirl", "livecam",
        "fap", "milf", "rule34", "erotica", "sexvideo", "sextube", "javhd", "brazzers",
        "onlyfans", "adultvideo", "escort", "nude", "porntube"
    )
    private val ADULT_PAGE_SIGNALS = listOf(
        "18+ only", "adults only", "must be 18", "free porn", "porn videos",
        "xxx videos", "live sex", "sex cams", "hentai videos", "explicit content",
        "amateur porn", "watch porn", "adult entertainment", "hardcore", "nsfw"
    )

    private val GAMBLING_STRONG_HOST_TOKENS = listOf(
        "casino", "sportsbook", "betting", "poker", "blackjack", "roulette",
        "slotmachine", "jackpot", "1xbet", "bet365", "parimatch", "rummy", "teenpatti"
    )
    private val GAMBLING_PAGE_SIGNALS = listOf(
        "place your bet", "online casino", "live roulette", "sports betting",
        "welcome bonus", "free spins", "real money poker", "bet now", "odds boost",
        "jackpot slots", "play rummy", "deposit bonus"
    )

    private val DATING_STRONG_HOST_TOKENS = listOf(
        "dating", "hookup", "singles", "matchmaker", "datefinder", "flirt"
    )
    private val DATING_PAGE_SIGNALS = listOf(
        "meet singles", "local singles", "find your match", "dating site",
        "swipe right", "online dating", "chat with singles", "casual hookup"
    )

    private val GAMES_STRONG_HOST_TOKENS = listOf(
        "minigames", "poki", "crazygames", "browsergame", "playgame", "arcade", "unblockedgames"
    )
    private val GAMES_PAGE_SIGNALS = listOf(
        "play free online games", "browser games", "unblocked games", "arcade games",
        "play now for free", "multiplayer io game"
    )

    private val SHOPPING_PAGE_SIGNALS = listOf(
        "add to cart", "buy now", "free shipping", "flash sale", "shop deals",
        "checkout now", "order summary", "add to bag"
    )

    private val NEWS_PAGE_SIGNALS = listOf(
        "breaking news", "latest headlines", "top stories", "world news",
        "live updates", "subscribe to read"
    )

    private val FOOD_DELIVERY_PAGE_SIGNALS = listOf(
        "order food online", "food delivery near", "deliver to your door",
        "track your order", "restaurants near you"
    )

    private const val SCORE_THRESHOLD = 6

    fun classify(
        host: String,
        fullUrl: String,
        visibleTextSnippets: List<String>,
        activeCategories: Set<WebsiteCategory>
    ): ClassificationResult? {
        if (activeCategories.isEmpty()) return null
        val normalizedHost = DomainBlockMatcher.normalizeDomain(host) ?: return null
        if (isSafeDomain(normalizedHost)) return null

        val lowerHost = normalizedHost.lowercase()
        val lowerUrl = fullUrl.lowercase()
        // Cap visible text to keep worst-case work strictly under ~2 ms
        val boundedPageText = visibleTextSnippets
            .take(40)
            .joinToString(" ") { it.take(120) }
            .lowercase()

        var bestCategory: WebsiteCategory? = null
        var bestScore = 0

        fun evaluate(category: WebsiteCategory, score: Int) {
            if (category in activeCategories && score >= SCORE_THRESHOLD && score > bestScore) {
                bestCategory = category
                bestScore = score
            }
        }

        if (WebsiteCategory.ADULT in activeCategories) {
            evaluate(
                WebsiteCategory.ADULT,
                scoreSignals(lowerHost, lowerUrl, boundedPageText, ADULT_STRONG_HOST_TOKENS, ADULT_PAGE_SIGNALS)
            )
        }
        if (WebsiteCategory.GAMBLING in activeCategories) {
            evaluate(
                WebsiteCategory.GAMBLING,
                scoreSignals(lowerHost, lowerUrl, boundedPageText, GAMBLING_STRONG_HOST_TOKENS, GAMBLING_PAGE_SIGNALS)
            )
        }
        if (WebsiteCategory.DATING in activeCategories) {
            evaluate(
                WebsiteCategory.DATING,
                scoreSignals(lowerHost, lowerUrl, boundedPageText, DATING_STRONG_HOST_TOKENS, DATING_PAGE_SIGNALS)
            )
        }
        if (WebsiteCategory.GAMES in activeCategories) {
            evaluate(
                WebsiteCategory.GAMES,
                scoreSignals(lowerHost, lowerUrl, boundedPageText, GAMES_STRONG_HOST_TOKENS, GAMES_PAGE_SIGNALS)
            )
        }
        if (WebsiteCategory.SHOPPING in activeCategories) {
            evaluate(
                WebsiteCategory.SHOPPING,
                scorePageOnly(boundedPageText, SHOPPING_PAGE_SIGNALS)
            )
        }
        if (WebsiteCategory.NEWS in activeCategories) {
            evaluate(
                WebsiteCategory.NEWS,
                scorePageOnly(boundedPageText, NEWS_PAGE_SIGNALS)
            )
        }
        if (WebsiteCategory.FOOD_DELIVERY in activeCategories) {
            evaluate(
                WebsiteCategory.FOOD_DELIVERY,
                scorePageOnly(boundedPageText, FOOD_DELIVERY_PAGE_SIGNALS)
            )
        }

        val winner = bestCategory ?: return null
        return ClassificationResult(
            category = winner,
            normalizedHost = normalizedHost,
            score = bestScore
        )
    }

    private fun scoreSignals(
        lowerHost: String,
        lowerUrl: String,
        boundedPageText: String,
        strongHostTokens: List<String>,
        pageSignals: List<String>
    ): Int {
        var score = 0
        for (token in strongHostTokens) {
            if (lowerHost.contains(token)) {
                score += 6
                break
            } else if (lowerUrl.contains("/$token") || lowerUrl.contains("=$token")) {
                score += 4
                break
            }
        }
        var pageHits = 0
        for (signal in pageSignals) {
            if (boundedPageText.contains(signal)) {
                pageHits++
                score += 3
                if (pageHits >= 3) break
            }
        }
        return score
    }

    private fun scorePageOnly(boundedPageText: String, pageSignals: List<String>): Int {
        if (boundedPageText.isBlank()) return 0
        var hits = 0
        for (signal in pageSignals) {
            if (boundedPageText.contains(signal)) {
                hits++
                if (hits >= 2) return 6
            }
        }
        return 0
    }

    fun isSafeDomain(normalizedHost: String): Boolean {
        if (WebsiteCatalog.CLASSIFIER_SAFE_TLDS.any { normalizedHost.endsWith(it) }) return true
        var safe = false
        DomainBlockMatcher.forEachParentDomain(normalizedHost) { parent ->
            if (parent in WebsiteCatalog.CLASSIFIER_SAFE_DOMAINS) safe = true
        }
        return safe
    }
}
