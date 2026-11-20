package com.example.domain

/**
 * 16 hidden website filter categories matching the clean reference taxonomy.
 * Underlying built-in, synced community, and auto-learned domain lists are NEVER
 * shown in the UI — users only toggle categories and add explicit Website Allowlist URLs.
 */
enum class WebsiteCategory(
    val id: String,
    val title: String,
    val subtitle: String
) {
    SOCIAL("SOCIAL", "Social", "Social networks, microblogging, and feeds"),
    META("META", "Meta", "Facebook, Instagram, Threads, and Messenger web"),
    MESSAGING("MESSAGING", "Messaging", "Web chat apps, Discord, Slack, and web messengers"),
    SEARCH_ENGINES("SEARCH_ENGINES", "Search Engines", "Major web search portals"),
    NEWS("NEWS", "News", "Breaking news, headlines, and tabloids"),
    POLITICS("POLITICS", "Politics", "Political commentary and news portals"),
    SHOPPING("SHOPPING", "Shopping", "Online stores, marketplaces, and deal sites"),
    TV_VIDEO("TV_VIDEO", "TV/Video", "Streaming video, shorts, and live broadcasts"),
    SPORTS("SPORTS", "Sports", "Live scores, sports news, and fantasy leagues"),
    BLOGS("BLOGS", "Blogs", "Publishing platforms, newsletters, and forums"),
    FOOD_DELIVERY("FOOD_DELIVERY", "Food Delivery", "Restaurant ordering and delivery portals"),
    GAMES("GAMES", "Games", "Browser games, game stores, and esports portals"),
    TIME_WASTERS("TIME_WASTERS", "Time Wasters", "Memes, viral quizzes, and endless scroll hubs"),
    DATING("DATING", "Dating", "Dating apps and matchmaking web portals"),
    GAMBLING("GAMBLING", "Gambling", "Betting, sportsbooks, poker, and online casinos"),
    ADULT("ADULT", "Adult", "Explicit adult content, tubes, and mirror sites");

    companion object {
        fun fromId(id: String?): WebsiteCategory? = entries.firstOrNull { it.id == id }

        fun parseCsv(csv: String): Set<WebsiteCategory> {
            if (csv.isBlank()) return emptySet()
            return csv.split(',').mapNotNull { fromId(it.trim()) }.toSet()
        }

        fun toCsv(categories: Collection<WebsiteCategory>): String =
            categories.distinct().sortedBy { it.ordinal }.joinToString(",") { it.id }
    }
}

enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("SYSTEM", "System"),
    DARK("DARK", "Forest Dark"),
    LIGHT("LIGHT", "Clean Light");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

enum class DndMode(val id: String, val label: String) {
    PRIORITY_ONLY("PRIORITY_ONLY", "Priority only"),
    ALARMS_ONLY("ALARMS_ONLY", "Alarms only");

    companion object {
        fun fromId(id: String?): DndMode = entries.firstOrNull { it.id == id } ?: PRIORITY_ONLY
    }
}

enum class FilterUpdateFrequency(val id: String, val label: String, val intervalMs: Long) {
    DAILY("DAILY", "Daily", 24 * 60 * 60_000L),
    WEEKLY("WEEKLY", "Weekly", 7 * 24 * 60 * 60_000L),
    MANUAL("MANUAL", "Manual only", Long.MAX_VALUE);

    companion object {
        fun fromId(id: String?): FilterUpdateFrequency =
            entries.firstOrNull { it.id == id } ?: DAILY
    }
}

data class CommunityFilterFeed(
    val id: String,
    val category: WebsiteCategory,
    val url: String
)

object WebsiteCatalog {

    /**
     * Public, actively maintained open-source categorized domain lists (uBlock/Pi-hole style)
     * streamed directly into compact 64-bit hash files on-device.
     */
    val COMMUNITY_FEEDS: List<CommunityFilterFeed> = listOf(
        CommunityFilterFeed(
            id = "stevenblack_porn",
            category = WebsiteCategory.ADULT,
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"
        ),
        CommunityFilterFeed(
            id = "blocklistproject_porn",
            category = WebsiteCategory.ADULT,
            url = "https://raw.githubusercontent.com/blocklistproject/Lists/master/alt-version/porn-nl.txt"
        ),
        CommunityFilterFeed(
            id = "stevenblack_gambling",
            category = WebsiteCategory.GAMBLING,
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts"
        ),
        CommunityFilterFeed(
            id = "blocklistproject_gambling",
            category = WebsiteCategory.GAMBLING,
            url = "https://raw.githubusercontent.com/blocklistproject/Lists/master/alt-version/gambling-nl.txt"
        ),
        CommunityFilterFeed(
            id = "stevenblack_social",
            category = WebsiteCategory.SOCIAL,
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/social-only/hosts"
        ),
        CommunityFilterFeed(
            id = "blocklistproject_facebook",
            category = WebsiteCategory.META,
            url = "https://raw.githubusercontent.com/blocklistproject/Lists/master/alt-version/facebook-nl.txt"
        ),
        CommunityFilterFeed(
            id = "blocklistproject_twitter",
            category = WebsiteCategory.SOCIAL,
            url = "https://raw.githubusercontent.com/blocklistproject/Lists/master/alt-version/twitter-nl.txt"
        ),
        CommunityFilterFeed(
            id = "blocklistproject_tiktok",
            category = WebsiteCategory.TV_VIDEO,
            url = "https://raw.githubusercontent.com/blocklistproject/Lists/master/alt-version/tiktok-nl.txt"
        ),
        CommunityFilterFeed(
            id = "stevenblack_fakenews",
            category = WebsiteCategory.NEWS,
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/fakenews-only/hosts"
        ),
        CommunityFilterFeed(
            id = "blocklistproject_redirect",
            category = WebsiteCategory.TIME_WASTERS,
            url = "https://raw.githubusercontent.com/blocklistproject/Lists/master/alt-version/redirect-nl.txt"
        )
    )

    /**
     * Built-in offline baseline domains for all 16 categories so protection works
     * immediately on day 1 before the first community filter sync completes.
     * Kept completely hidden from the UI.
     */
    val BUILTIN_DOMAINS: Map<WebsiteCategory, Set<String>> = mapOf(
        WebsiteCategory.SOCIAL to setOf(
            "instagram.com", "facebook.com", "fb.com", "threads.net", "tiktok.com",
            "x.com", "twitter.com", "reddit.com", "old.reddit.com", "snapchat.com",
            "pinterest.com", "tumblr.com", "linkedin.com", "bsky.app", "mastodon.social",
            "quora.com", "vk.com", "weibo.com", "nextdoor.com", "clubhouse.com"
        ),
        WebsiteCategory.META to setOf(
            "facebook.com", "fb.com", "fbcdn.net", "instagram.com", "threads.net",
            "messenger.com", "whatsapp.com", "web.whatsapp.com", "meta.com"
        ),
        WebsiteCategory.MESSAGING to setOf(
            "discord.com", "discordapp.com", "web.telegram.org", "telegram.org", "t.me",
            "slack.com", "signal.org", "skype.com", "viber.com", "line.me", "wechat.com"
        ),
        WebsiteCategory.SEARCH_ENGINES to setOf(
            "google.com", "bing.com", "duckduckgo.com", "search.yahoo.com", "yahoo.com",
            "search.brave.com", "yandex.com", "baidu.com", "ecosia.org", "startpage.com", "ask.com"
        ),
        WebsiteCategory.NEWS to setOf(
            "cnn.com", "bbc.com", "bbc.co.uk", "nytimes.com", "theguardian.com",
            "reuters.com", "apnews.com", "washingtonpost.com", "foxnews.com", "nbcnews.com",
            "cbsnews.com", "abcnews.go.com", "wsj.com", "bloomberg.com", "forbes.com",
            "businessinsider.com", "dailymail.co.uk", "huffpost.com", "ndtv.com",
            "timesofindia.indiatimes.com", "hindustantimes.com", "indianexpress.com",
            "news.google.com", "news.ycombinator.com", "theverge.com", "techcrunch.com"
        ),
        WebsiteCategory.POLITICS to setOf(
            "politico.com", "thehill.com", "realclearpolitics.com", "fivethirtyeight.com",
            "breitbart.com", "salon.com", "slate.com", "vox.com", "nationalreview.com",
            "motherjones.com", "thedailybeast.com", "axios.com", "drudgereport.com"
        ),
        WebsiteCategory.SHOPPING to setOf(
            "amazon.com", "amazon.in", "amazon.co.uk", "ebay.com", "flipkart.com",
            "walmart.com", "target.com", "aliexpress.com", "temu.com", "shein.com",
            "etsy.com", "bestbuy.com", "myntra.com", "ajio.com", "meesho.com",
            "nykaa.com", "ikea.com", "wayfair.com", "shopify.com", "wish.com", "slickdeals.net"
        ),
        WebsiteCategory.TV_VIDEO to setOf(
            "youtube.com", "youtu.be", "tiktok.com", "netflix.com", "twitch.tv",
            "disneyplus.com", "hotstar.com", "jiocinema.com", "primevideo.com",
            "hulu.com", "max.com", "hbomax.com", "peacocktv.com", "paramountplus.com",
            "crunchyroll.com", "vimeo.com", "dailymotion.com", "rumble.com", "kick.com",
            "sonyliv.com", "zee5.com", "bilibili.com", "plex.tv", "pluto.tv"
        ),
        WebsiteCategory.SPORTS to setOf(
            "espn.com", "espncricinfo.com", "cricbuzz.com", "bleacherreport.com",
            "cbssports.com", "nbcsports.com", "skysports.com", "goal.com", "nba.com",
            "nfl.com", "mlb.com", "nhl.com", "premierleague.com", "fifa.com",
            "formula1.com", "theathletic.com", "sportingnews.com", "flashscore.com"
        ),
        WebsiteCategory.BLOGS to setOf(
            "medium.com", "substack.com", "wordpress.com", "blogspot.com", "blogger.com",
            "ghost.io", "dev.to", "hashnode.dev", "livejournal.com", "wattpad.com",
            "goodreads.com", "ao3.org", "archiveofourown.org", "fanfiction.net"
        ),
        WebsiteCategory.FOOD_DELIVERY to setOf(
            "ubereats.com", "doordash.com", "grubhub.com", "zomato.com", "swiggy.com",
            "deliveroo.com", "deliveroo.co.uk", "just-eat.co.uk", "postmates.com",
            "instacart.com", "blinkit.com", "zeptonow.com", "dominos.com", "pizzahut.com"
        ),
        WebsiteCategory.GAMES to setOf(
            "store.steampowered.com", "steampowered.com", "epicgames.com", "roblox.com",
            "minecraft.net", "ign.com", "gamespot.com", "kotaku.com", "polygon.com",
            "chess.com", "lichess.org", "poki.com", "crazygames.com", "miniclip.com",
            "coolmathgames.com", "itch.io", "newgrounds.com", "friv.com", "addictinggames.com",
            "ea.com", "playstation.com", "xbox.com", "nintendo.com", "riotgames.com"
        ),
        WebsiteCategory.TIME_WASTERS to setOf(
            "9gag.com", "imgur.com", "buzzfeed.com", "boredpanda.com", "knowyourmeme.com",
            "ifunny.co", "theonion.com", "cracked.com", "xkcd.com", "cheezburger.com",
            "distractify.com", "ranker.com", "listverse.com", "tmz.com", "eonline.com",
            "people.com", "usmagazine.com", "pagesix.com", "vice.com"
        ),
        WebsiteCategory.DATING to setOf(
            "tinder.com", "bumble.com", "hinge.co", "okcupid.com", "match.com",
            "pof.com", "badoo.com", "eharmony.com", "grindr.com", "feeld.co",
            "zoosk.com", "elitesingles.com", "silversingles.com", "happn.com", "ashleymadison.com"
        ),
        WebsiteCategory.GAMBLING to setOf(
            "bet365.com", "draftkings.com", "fanduel.com", "betmgm.com", "caesars.com",
            "pokerstars.com", "888casino.com", "williamhill.com", "ladbrokes.com",
            "betway.com", "stake.com", "1xbet.com", "parimatch.com", "bovada.lv",
            "unibet.com", "paddypower.com", "betfair.com", "dream11.com", "mpl.live",
            "rummycircle.com", "jungleerummy.com", "my11circle.com", "roobet.com", "bc.game"
        ),
        WebsiteCategory.ADULT to setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
            "youporn.com", "spankbang.com", "eporner.com", "beeg.com", "tube8.com",
            "chaturbate.com", "onlyfans.com", "stripchat.com", "bongacams.com",
            "livejasmin.com", "cam4.com", "myfreecams.com", "rule34.xxx", "nhentai.net",
            "hanime.tv", "hentaihaven.xxx", "brazzers.com", "bangbros.com", "realitykings.com",
            "naughtyamerica.com", "motherless.com", "tnaflix.com", "txxx.com", "hqporner.com",
            "daftsex.com", "porntrex.com", "youjizz.com", "ixxx.com", "drtuber.com",
            "fapello.com", "coomer.su", "kemono.su", "erome.com", "fansly.com", "manyvids.com"
        )
    )

    /**
     * Known safe educational, documentation, productivity, and reference domains skipped by
     * the lightweight unknown-site auto-classifier to prevent false positives.
     */
    val CLASSIFIER_SAFE_DOMAINS: Set<String> = setOf(
        "wikipedia.org", "wikimedia.org", "wiktionary.org", "github.com", "gitlab.com",
        "stackoverflow.com", "stackexchange.com", "developer.android.com", "kotlinlang.org",
        "docs.google.com", "drive.google.com", "calendar.google.com", "mail.google.com",
        "notion.so", "khanacademy.org", "coursera.org", "edx.org", "mit.edu", "stanford.edu",
        "harvard.edu", "nih.gov", "cdc.gov", "who.int", "mayoclinic.org", "webmd.com",
        "britannica.com", "archive.org", "wolframalpha.com", "desmos.com", "overleaf.com"
    )

    val CLASSIFIER_SAFE_TLDS: Set<String> = setOf(".edu", ".gov", ".ac.uk", ".ac.in", ".edu.au")
}
