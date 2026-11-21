# Changelog

All notable changes to **Freedom** are documented in this file.

## [Unreleased]

### Added
- **Universal Content Detection Upgrade**:
  - Single-pass bounded `BrowserUrlExtractor.collectCombinedContentEvidence` combines page text with visible card/thumbnail/recommendation `text` and `contentDescription` labels, deduplicates evidence, and uses actual Android display dimensions (`WindowManager.currentWindowMetrics` / `resources.displayMetrics` — never inferred from `AccessibilityNodeInfo` root bounds).
  - Integrated combined evidence into `UnknownSiteClassifier`, Browser/WebView DOM protection, and native third-party app protection without weakening classification thresholds, adding OCR/ML, or adding continuous polling.
- **Reliable YouTube Channel Identification (`YouTubeChannelVerificationTracker`)**:
  - Explicit channel attribution states (`VERIFIED`, `PENDING`, `UNKNOWN`, `FAILED`) eliminating hardcoded `detectedChannelId = null` and preventing display-name/handle spoofing.
  - Video-session-bound asynchronous verification keyed by `videoId` with duplicate-lookup deduplication, bounded LRU + TTL cache, stale-result discarding on video switches, and fail-closed Strict Mode guarantees.
- **YouTube Live Streaming Support for Verified Approved Channels**:
  - Enabled verified approved-channel Live streams by default (`youtubeAllowLiveFromApproved = true` with migrated `cp_yt_allow_live_v2` preference) while keeping unapproved and unknown Live streams strictly blocked.
  - Added Live Chat / `"Top chat"` / `"watching now"` player detection in `YouTubeAccessibilityInspector`, `/live/<videoId>` HTML + `externalChannelId` fallback in `YouTubeChannelResolver`, and strict separation between Android PiP mode (`win.isPip`) and YouTube's in-app bottom miniplayer (`ScreenMode.MINI_PLAYER`).
- **Delayed DOM Re-Classification, Scrolled-WebView Coverage & YouTube In-App Search/Playlist/Fast-Miniplayer Fixes**:
  - Fixed premature pre-load DOM classification lockout (allows one bounded re-check once DOM snippets render) and scrolled-WebView empty-URL-bar skipping.
  - Upgraded YouTube in-app Search Results, Playlist, and non-History recommendation filtering (catching converging queries like `"naked"` + `"Nude Artistic Photoshoot"` / `"Nude Art"` while preserving safe educational queries and Watch History bounce-free behavior).
  - Added Signal 2c (bottom-band Close/ProgressBar miniplayer detection) plus a single bounded 360ms follow-up re-scan to stop fast swipe-down-before-buffering miniplayer bypasses.
- **Mature Media & Piracy Portal Catalog (`MatureMediaCatalog`)**:
  - On-device hashed database of 18+/TV-MA/adult web series and explicit films (e.g., *Shameless*, *Euphoria*, *Spartacus*, *Gandii Baat*, *Charmsukh*, *365 Days*, *Fifty Shades*, *Lust Stories*, *Mirzapur*, etc.) enforced across YouTube and browsers without false-flagging ordinary non-media text.
  - Permanent structural detector for free movie streaming sites, piracy download portals, offline saved pages, and stream-aggregator directories (`WEB-DL`, `HDRip`, `480p | 720p | 1080p`, `Dual Audio`, `External Subs`, `Stream Aggregators`, `TV SHOW` + `WEB SERIES`, `IMDb`/`TMDB` streaming pages, and rotating mirrors).
  - Auto-updating open-source **Piracy** and **Torrent** community blocklists in background filter sync alongside Adult and Gambling feeds.
- **Standalone `[R]` / `A-Rated` / `18+` Movie Page Detection**: Detects R-rated, A-rated, TV-MA, NC-17, and YouTube age-restricted/viewer-discretion banners paired with movie/streaming metadata.
- **Expanded Browser & In-App WebView Support**: Added explicit URL-bar support for 30+ Android browsers (including Vivo Browser, Oppo/ColorOS Browser, Cromite, Aloha, Soul, Phoenix) plus embedded WebViews and native hubs inside Reddit (`r/FREEMEDIAHECKYEAH`, FMHY), Telegram, and Discord.
- **Incognito / Private Mode Detection**: Detects Incognito/Private tabs across Chrome, Brave, Edge, Firefox, Samsung Internet, and Opera so Freedom protects Incognito in-place without switching to Normal mode.
- **YouTube Mobile-Web Study Mode**: Extends YouTube Study Mode channel allowlisting to `m.youtube.com` / `youtube.com` watch pages inside web browsers.
- **In-App YouTube Channel Search**: Search YouTube channels directly by name inside YouTube Study Mode (desktop + mobile result parsing, keyboard Search action, subscriber summary, and "Approved" badges) without copying URLs.

### Changed
- **Silent Always-On SafeSearch & Adult Protection**: SafeSearch and in-app adult/mature-media protection now run automatically and permanently in the background once Accessibility is enabled, with all UI off-switches removed. Only **YouTube Study Mode** remains as a user-managed toggle and channel allowlist (still locked in Strict Mode).
- **Tightened Adult Filter Policy & Multilingual Unicode Support**:
  - Removed the biology/anatomy/medical exemption loophole.
  - Preserved only genuine spiritual and anti-addiction recovery context (e.g., ISKCON / Brahmacharya / overcoming addiction) when no explicit site, consumption, maturity, or suggestive-clip signals appear, plus trusted approved YouTube channels in Study Mode.
  - Upgraded tokenizer to full Unicode NFKC + combining marks (`\p{M}`) covering English, Hindi (Devanagari), Hinglish, German (`Sexueller`, `Ekstase`, `Ungeschnittene`), Spanish, French, Portuguese, Arabic, Russian, Turkish, and Indonesian.
- **Deep Browser DOM Scanning**: Increased bounded WebView DOM traversal depth (`<= 16`, up to 48 snippets) across all browsers while preserving 1-character rating badges (`R`, `A`) and truncating long paragraphs instead of discarding them.

### Fixed
- **Frozen Floating Overlay Bug**: Permanently removed floating card-mask overlays across Google Search, browsers, and YouTube; blocked searches, pages, and videos are cut directly.
- **SafeSearch Reload Loops, New-Tab Spam & Multi-Account Google**:
  - Explicit queries and adult domains are now cut immediately *before* SafeSearch redirection.
  - Added 10-minute per-(browser, provider, query) deduplication and single-tab reuse ID so Google, Bing, and Brave Search never reload-loop or spawn duplicate tabs.
  - Appended `ssui=on` alongside `safe=active` on Google Search for consistent multi-account SafeSearch enforcement.
- **YouTube Picture-in-Picture, Fast Miniplayer Swipe-Down & Background Audio Leaks**:
  - Unapproved YouTube/Morphe YT/ReVanced videos are now cut *inside* YouTube (Pause → Back → multi-pass bottom miniplayer `×` close + right-edge tap fallback; Shorts → Home tab) instead of exiting YouTube into Android PiP.
  - Tightened YouTube event throttling to 120ms with 3 independent bottom-miniplayer detectors to stop fast click + swipe-down bypasses.
  - Added background media stop (`KEYCODE_MEDIA_PAUSE` / `KEYCODE_MEDIA_STOP` + transient audio focus + background process kill) when leaving YouTube for Home before an unapproved video finishes loading.
- **Morphe YT / YouTube Bounce-Loop, Miniplayer & Custom Default-Tab Fix**:
  - Fixed a root misclassification where the bottom miniplayer's `"Pause video"` button triggered `WATCH_PLAYER` fallback detection on Home/History screens and fired `GLOBAL_ACTION_BACK`, kicking Morphe YT to Android Home and triggering Vivo's shrinking/mini-window bounce loop.
  - Strictly gated YouTube/Morphe/ReVanced adult filtering so Watch History, Profile, Subscriptions, and any custom Morphe default screen **never** fire `Back` on old thumbnails; only active playback (`Watch`, `Live`, `Shorts`, `Miniplayer`) and active explicit Search Results are cut.
  - Added a 1.5s Watch-Collapse `Back` lockout and live Accessibility tree re-inspection (`findLiveMiniPlayerBounds` + Play/Pause right-sibling `×` matching + expanded `99%` bottom edge tolerance) so collapsed videos close cleanly inside the bottom miniplayer without ever exiting the app.
  - Removed any hard dependency on a "Home" tab when exiting Shorts — switches cleanly to whatever non-Shorts default/custom bottom-dock tab the user configured in Morphe YT / ReVanced.
  - Applied cross-feature anti-bounce guards across browsers (skipping DOM eviction on empty New Tab/History/Bookmarks screens) and native third-party apps (bounded single-hop recheck and settle cooldown).
- **Public YouTube Channel Search Encoding**: Fixed double-encoded channel search filter parameter (`sp=EgIQAg%3D%3D`) and added desktop User-Agent + `displayName`/`runs` JSON fallback parsing.
