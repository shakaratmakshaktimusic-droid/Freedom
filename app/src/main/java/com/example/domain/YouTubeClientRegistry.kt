package com.example.domain

/**
 * Registry and individual capability profiler for YouTube clients:
 *  - Official YouTube
 *  - Morphe YT (multiple known & custom-cloned Morphe package signatures)
 *  - YouTube ReVanced / RVX / patched YouTube clients
 *  - Known third-party YouTube clients (NewPipe, Tubular, LibreTube, Grayjay, SkyTube, etc.)
 *
 * Each client is evaluated independently rather than assuming automatic support.
 * Clients whose Accessibility tree does not reliably expose channel attribution are
 * explicitly classified as UNVERIFIABLE and blocked during Strict Mode.
 */
object YouTubeClientRegistry {

    private val KNOWN_CLIENTS: List<YouTubeClientProfile> = listOf(
        // Official YouTube
        YouTubeClientProfile(
            packageName = "com.google.android.youtube",
            displayName = "YouTube",
            family = YouTubeClientFamily.OFFICIAL,
            supportTier = YouTubeClientSupportTier.FULL_STUDY_MODE,
            supportsFeedCardMasking = true,
            notes = "Feed card masking, watch-page verification, Shorts & Live controls."
        ),
        // Morphe YT variants (official & patched builds)
        YouTubeClientProfile(
            packageName = "app.morphe.android.youtube",
            displayName = "Morphe YT",
            family = YouTubeClientFamily.MORPHE,
            supportTier = YouTubeClientSupportTier.FULL_STUDY_MODE,
            supportsFeedCardMasking = true,
            notes = "Full YouTube-compatible view hierarchy: feed masking, watch verification, Shorts & Live blocking."
        ),
        YouTubeClientProfile(
            packageName = "com.morphe.android.youtube",
            displayName = "Morphe YT",
            family = YouTubeClientFamily.MORPHE,
            supportTier = YouTubeClientSupportTier.FULL_STUDY_MODE,
            supportsFeedCardMasking = true,
            notes = "Full YouTube-compatible view hierarchy: feed masking, watch verification, Shorts & Live blocking."
        ),
        YouTubeClientProfile(
            packageName = "app.morphe.youtube",
            displayName = "Morphe YT",
            family = YouTubeClientFamily.MORPHE,
            supportTier = YouTubeClientSupportTier.FULL_STUDY_MODE,
            supportsFeedCardMasking = true,
            notes = "Full YouTube-compatible view hierarchy: feed masking, watch verification, Shorts & Live blocking."
        ),
        YouTubeClientProfile(
            packageName = "io.morphe.android.youtube",
            displayName = "Morphe YT",
            family = YouTubeClientFamily.MORPHE,
            supportTier = YouTubeClientSupportTier.FULL_STUDY_MODE,
            supportsFeedCardMasking = true,
            notes = "Full YouTube-compatible view hierarchy: feed masking, watch verification, Shorts & Live blocking."
        ),
        // YouTube ReVanced & RVX / Patched Clients
        YouTubeClientProfile(
            packageName = "app.revanced.android.youtube",
            displayName = "YouTube ReVanced",
            family = YouTubeClientFamily.REVANCED,
            supportTier = YouTubeClientSupportTier.FULL_STUDY_MODE,
            supportsFeedCardMasking = true,
            notes = "Patched YouTube client with compatible Accessibility hierarchy."
        ),
        YouTubeClientProfile(
            packageName = "app.rvx.android.youtube",
            displayName = "YouTube RVX",
            family = YouTubeClientFamily.REVANCED,
            supportTier = YouTubeClientSupportTier.FULL_STUDY_MODE,
            supportsFeedCardMasking = true,
            notes = "ReVanced Extended client with compatible Accessibility hierarchy."
        ),
        YouTubeClientProfile(
            packageName = "app.inotia00.android.youtube",
            displayName = "YouTube RVX (Inotia00)",
            family = YouTubeClientFamily.REVANCED,
            supportTier = YouTubeClientSupportTier.FULL_STUDY_MODE,
            supportsFeedCardMasking = true,
            notes = "ReVanced Extended build with compatible Accessibility hierarchy."
        ),
        YouTubeClientProfile(
            packageName = "com.vanced.android.youtube",
            displayName = "YouTube Vanced",
            family = YouTubeClientFamily.REVANCED,
            supportTier = YouTubeClientSupportTier.FULL_STUDY_MODE,
            supportsFeedCardMasking = true,
            notes = "Legacy patched YouTube client."
        ),
        // Supported Third-Party Clients (Standard Android View / Compose Accessibility Trees)
        YouTubeClientProfile(
            packageName = "org.schabi.newpipe",
            displayName = "NewPipe",
            family = YouTubeClientFamily.THIRD_PARTY,
            supportTier = YouTubeClientSupportTier.WATCH_VERIFICATION_ONLY,
            supportsFeedCardMasking = false,
            notes = "Supports watch-page channel verification via channel header nodes."
        ),
        YouTubeClientProfile(
            packageName = "org.polymorphicshade.tubular",
            displayName = "Tubular",
            family = YouTubeClientFamily.THIRD_PARTY,
            supportTier = YouTubeClientSupportTier.WATCH_VERIFICATION_ONLY,
            supportsFeedCardMasking = false,
            notes = "NewPipe fork supporting watch-page channel verification."
        ),
        YouTubeClientProfile(
            packageName = "InfinityLoop1309.NewPipeEnhanced",
            displayName = "PipePipe",
            family = YouTubeClientFamily.THIRD_PARTY,
            supportTier = YouTubeClientSupportTier.WATCH_VERIFICATION_ONLY,
            supportsFeedCardMasking = false,
            notes = "NewPipe fork supporting watch-page channel verification."
        ),
        YouTubeClientProfile(
            packageName = "com.github.libretube",
            displayName = "LibreTube",
            family = YouTubeClientFamily.THIRD_PARTY,
            supportTier = YouTubeClientSupportTier.WATCH_VERIFICATION_ONLY,
            supportsFeedCardMasking = false,
            notes = "Supports watch-page channel verification via uploader header."
        ),
        // Unverifiable / Custom-Rendered Third-Party Clients (Blocked fail-closed in Strict Mode)
        YouTubeClientProfile(
            packageName = "com.futo.platformplayer",
            displayName = "Grayjay",
            family = YouTubeClientFamily.THIRD_PARTY,
            supportTier = YouTubeClientSupportTier.UNVERIFIABLE,
            supportsFeedCardMasking = false,
            notes = "Multi-platform custom renderer; channel attribution cannot be verified reliably — blocked in Strict Mode."
        ),
        YouTubeClientProfile(
            packageName = "free.rm.skytube.oss",
            displayName = "SkyTube",
            family = YouTubeClientFamily.THIRD_PARTY,
            supportTier = YouTubeClientSupportTier.UNVERIFIABLE,
            supportsFeedCardMasking = false,
            notes = "Inconsistent Accessibility channel nodes — blocked in Strict Mode."
        ),
        YouTubeClientProfile(
            packageName = "free.rm.skytube.extra",
            displayName = "SkyTube Extra",
            family = YouTubeClientFamily.THIRD_PARTY,
            supportTier = YouTubeClientSupportTier.UNVERIFIABLE,
            supportsFeedCardMasking = false,
            notes = "Inconsistent Accessibility channel nodes — blocked in Strict Mode."
        ),
        YouTubeClientProfile(
            packageName = "com.liskovsoft.smarttubetv2.beta",
            displayName = "SmartTube",
            family = YouTubeClientFamily.THIRD_PARTY,
            supportTier = YouTubeClientSupportTier.UNVERIFIABLE,
            supportsFeedCardMasking = false,
            notes = "TV-layout custom renderer — blocked in Strict Mode."
        )
    )

    private val KNOWN_BY_PACKAGE: Map<String, YouTubeClientProfile> =
        KNOWN_CLIENTS.associateBy { it.packageName }

    /**
     * Identifies whether [packageName] (and optional human [appLabel]) corresponds to
     * a known or dynamically cloned YouTube client (e.g., custom-signed Morphe YT or ReVanced APK).
     */
    fun identifyClient(
        packageName: String,
        appLabel: String? = null
    ): YouTubeClientProfile? {
        KNOWN_BY_PACKAGE[packageName]?.let { return it }

        val pkg = packageName.lowercase()
        val label = appLabel?.lowercase().orEmpty()

        // Exclude YouTube Studio / YouTube Music / Kids from general video client matching unless explicitly YT
        if (pkg == "com.google.android.apps.youtube.music" ||
            pkg == "com.google.android.apps.youtube.creator" ||
            pkg == "com.google.android.apps.youtube.kids"
        ) {
            return null
        }

        // Dynamic detection for custom-packaged Morphe YT builds
        if (pkg.contains("morphe") && (pkg.contains("youtube") || pkg.contains(".yt") || label.contains("yt") || label.contains("youtube"))) {
            return YouTubeClientProfile(
                packageName = packageName,
                displayName = appLabel?.takeIf { it.isNotBlank() } ?: "Morphe YT",
                family = YouTubeClientFamily.MORPHE,
                supportTier = YouTubeClientSupportTier.FULL_STUDY_MODE,
                supportsFeedCardMasking = true,
                isInstalled = true,
                notes = "Detected custom Morphe YT package using YouTube-compatible Accessibility layout."
            )
        }

        // Dynamic detection for custom-packaged ReVanced / RVX / YouTube patched clones
        if ((pkg.contains("revanced") || pkg.contains("rvx") || pkg.contains("vanced")) &&
            (pkg.contains("youtube") || label.contains("youtube"))
        ) {
            return YouTubeClientProfile(
                packageName = packageName,
                displayName = appLabel?.takeIf { it.isNotBlank() } ?: "Patched YouTube Client",
                family = YouTubeClientFamily.REVANCED,
                supportTier = YouTubeClientSupportTier.FULL_STUDY_MODE,
                supportsFeedCardMasking = true,
                isInstalled = true,
                notes = "Detected patched YouTube client package."
            )
        }

        // Unknown third-party YouTube wrapper/clone: treat honestly as UNVERIFIABLE (fail-closed in Strict Mode)
        if ((pkg.contains("youtube") || pkg.contains("newpipe") || pkg.contains("libretube") || pkg.contains("ytube")) &&
            !pkg.startsWith("com.aistudio.freedom")
        ) {
            return YouTubeClientProfile(
                packageName = packageName,
                displayName = appLabel?.takeIf { it.isNotBlank() } ?: packageName,
                family = YouTubeClientFamily.THIRD_PARTY,
                supportTier = YouTubeClientSupportTier.UNVERIFIABLE,
                supportsFeedCardMasking = false,
                isInstalled = true,
                notes = "Unverified third-party YouTube client — blocked during Strict Mode."
            )
        }

        return null
    }

    /**
     * Scans installed launchable packages and returns every detected YouTube client
     * along with core reference entries (Official YouTube & Morphe YT) so users can
     * inspect exact compatibility status on their device.
     */
    fun discoverInstalledClients(
        installedApps: List<Pair<String, String>>
    ): List<YouTubeClientProfile> {
        val result = LinkedHashMap<String, YouTubeClientProfile>()
        for ((pkg, label) in installedApps) {
            val profile = identifyClient(pkg, label) ?: continue
            result[pkg] = profile.copy(
                displayName = label.ifBlank { profile.displayName },
                isInstalled = true
            )
        }
        return result.values.sortedWith(
            compareBy<YouTubeClientProfile> { it.family.ordinal }.thenBy { it.displayName.lowercase() }
        )
    }

    fun allKnownReferencePackages(): Set<String> = KNOWN_BY_PACKAGE.keys
}
