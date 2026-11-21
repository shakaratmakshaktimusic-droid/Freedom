package com.example.domain

import java.text.Normalizer

/**
 * On-device, zero-VPN, privacy-preserving In-App Adult & Mature-Media Classifier.
 *
 * Operates strictly in memory on accessible titles, search queries, descriptions,
 * maturity/age-restriction markers, and URL path/query tokens across:
 *  - Official YouTube, Morphe YT, ReVanced, and third-party YouTube clients
 *  - Google Search, Google Images, Bing, DuckDuckGo, Yahoo, Brave, and browsers
 *  - Other apps exposing inspectable Accessibility text/labels
 *
 * Policy guarantees:
 *  1. Unicode-aware NFKC tokenization covering English, Hindi (Devanagari),
 *     Hinglish / Romanized Indian terms, and major global languages (Spanish,
 *     French, Portuguese, German, Arabic, Russian, Turkish, Indonesian).
 *  2. NO biology/anatomy/medical exemption loophole.
 *  3. Spiritual & anti-addiction recovery framing ONLY (e.g., ISKCON / Brahmacharya /
 *     overcoming addiction) is permitted when NO explicit site/consumption terms appear.
 *  4. Detects 18+/A-rated/TV-MA/age-restricted media, adult OTT tags, and suggestive
 *     movie/web-series/trailer/item-song clips.
 *  5. Uses 64-bit FNV-1a hashed token sets computed from encoded roots so explicit
 *     strings never appear in Freedom's UI. Zero network calls, zero browsing history saved.
 */
object InAppAdultFilterEngine {

    data class DetectionResult(
        val isFlagged: Boolean,
        val confidenceScore: Int,
        val reason: String = ""
    )

    // Unicode-aware tokenizer preserving Indic (Devanagari/Tamil/Telugu/Bengali letters + vowel matras \p{M}),
    // Arabic, Cyrillic, CJK, and accented Latin letters & digits.
    private val UNICODE_TOKEN_SPLIT_REGEX = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val COMBINING_DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

    // Explicit site / consumption markers that NEVER qualify for spiritual/recovery exemption
    private val HARD_CONSUMPTION_TOKENS: Set<Long> by lazy {
        decodeLexicon(
            listOf(
                // Explicit domains & streaming/cam sites
                "eHh4", "eHZpZGVvcw==", "eG54eA==", "cG9ybmh1Yg==", "eGhhbXN0ZXI=",
                "cmVkdHViZQ==", "eW91cG9ybg==", "c3BhbmtiYW5n", "aGVudGFp", "b25seWZhbnM=",
                "Y2FtZ2lybA==", "Y2FtZ2lybHM=", "Y2hhdHVyYmF0ZQ==", "c3RyaXBjaGF0", "Y2FtNA==",
                "Ym9uZ2FjYW1z", "bGl2ZWphenptaW4=", "cnVsZTM0", "ZTYyMQ==", "bG9saWNvbg==",
                "dWxsdQ==", "YWx0YmFsYWpp", "a29va3U=", "bnVlZmxpa3M=", "cHJpbWVwbGF5",
                "dm9vdmk=", "YmVzaGFyYW1z", "Zmxpem1vdmllcw==", "dW5jZW5zb3JlZA=="
            )
        )
    }

    // High-confidence explicit single tokens (English + Hinglish + Devanagari + Multilingual)
    private val HIGH_CONFIDENCE_TOKENS: Set<Long> by lazy {
        decodeLexicon(
            listOf(
                // English explicit
                "cG9ybg==", "cG9ybm8=", "cG9ybm9ncmFwaHk=", "eHh4", "eHZpZGVvcw==",
                "eG54eA==", "cG9ybmh1Yg==", "eGhhbXN0ZXI=", "cmVkdHViZQ==", "eW91cG9ybg==",
                "c3BhbmtiYW5n", "aGVudGFp", "b25seWZhbnM=", "Y2FtZ2lybA==", "Y2FtZ2lybHM=",
                "Y2hhdHVyYmF0ZQ==", "c3RyaXBjaGF0", "Y2FtNA==", "Ym9uZ2FjYW1z", "bnNmdw==",
                "bWlsZg==", "Ymxvd2pvYg==", "aGFuZGpvYg==", "ZmVsbGF0aW8=", "Y3VubmlsaW5ndXM=",
                "ZXJvdGljYQ==", "ZmFwcGVu", "ZmFwcGluZw==", "bWFzdHVyYmF0ZQ==", "bWFzdHVyYmF0aW9u",
                "b3JnYXNt", "ZWphY3VsYXRpb24=", "Z2FuZ2Jhbmc=", "dGhyZWVzb21l", "YmRzbQ==",
                "ZGlsZG8=", "dnVsdmE=", "bmlwcGxlcw==", "Ym9vYnM=", "dGl0cw==", "cHVzc3k=",
                "Y29jaw==", "ZGljaw==", "bGl2ZWphenptaW4=", "c2V4dGFwZQ==", "cmV2ZW5nZXBvcm4=",
                "bG9saWNvbg==", "eWFvaQ==", "cnVsZTM0", "ZTYyMQ==", "bnVkZXM=", "dG9wbGVzcw==",
                "dW5kcmVzcw==", "bGluZ2VyaWVwb3Ju", "c2V4dmlkZW8=", "c2V4dmlkZW9z",
                "YWR1bHR2aWRlbw==", "YWR1bHR2aWRlb3M=", "ZXhwbGljaXR2aWRlbw==",
                // Hinglish / Romanized Indian explicit & adult OTT
                "Y2h1ZGFp", "Y2hvZG5h", "Y2h1dA==", "bHVuZA==", "Z2FhbmQ=", "Z2FuZA==",
                "cmFuZGk=", "bmFuZ2k=", "bmFuZ2E=", "c3VoYWdyYXQ=", "c3VoYWdyYWF0",
                "Y2h1Y2hp", "Y2h1Y2hpeWE=", "Z2FuZGliYWF0", "Y2hhcm1zdWto", "cGFsYW5ndG9k",
                "dWxsdQ==", "YWx0YmFsYWpp", "a29va3U=", "bnVlZmxpa3M=", "cHJpbWVwbGF5",
                "dm9vdmk=", "YmVzaGFyYW1z", "Ymx1ZWZpbG0=", "YmZ2aWRlbw==", "ZGVzaXNl",
                "ZGVzaXNleA==", "bXV0aA==", "bXV0aG1hcm5h", "aGFzdGhtYWl0aHVu",
                // Hindi Devanagari native script explicit roots
                "4KS44KWH4KSV4KWN4KS4", "4KSq4KWL4KSw4KWN4KSo", "4KSa4KWB4KSm4KS+4KSI",
                "4KSa4KWB4KSk", "4KSy4KSC4KSh", "4KSo4KSC4KSX4KWA", "4KSF4KS24KWN4KSy4KWA4KSy",
                "4KS44KWB4KS54KS+4KSX4KSw4KS+4KSk", "4KS44KSC4KSt4KWL4KSX", "4KS54KS44KWN4KSk4KSu4KWI4KSl4KWB4KSo",
                // Spanish / French / Portuguese / German / Italian / Turkish / Indonesian
                "cG9ybm9ncmFmaWE=", "ZGVzbnVkYQ==", "ZGVzbnVkYXM=", "Zm9sbGFy", "Y29nZXI=",
                "cHV0YQ==", "c2V4bw==", "Y2FsZW50dXJh", "YmFpc2Vy", "c2V4dWVs", "c2V4dWVsbGU=",
                "bnVl", "bnVkaXRl", "cHV0YXJpYQ==", "Zm9kZXI=", "Z296YXI=", "c2V4dWFsZGFkZQ==",
                "Zmlja2Vu", "bmFja3Q=", "c2NobGFtcGU=", "c2lraXM=", "c2V2aXNtZQ==", "Ym9rZXA=",
                "bmdlbnRvdA==", "YnVnZ2ls", "YnVnaWw=", "Y2lwYW4=",
                // Arabic explicit roots
                "2LPZg9iz", "2KXYqNin2K3Zitip", "2KfYqNin2K3Zitip", "2LnYp9ix2YrYqQ==", "2KzZhtiz2Yo=",
                // Russian Cyrillic explicit roots
                "0L/QvtGA0L3Qvg==", "0YHQtdC60YE=", "0Y3RgNC+0YLQuNC60LA=", "0LPQvtC70LDRjw==", "0L/QvtGA0L3Rg9GF0LA="
            )
        )
    }

    // Medium-confidence single tokens (require score >= 3 via pairing with another signal or maturity tag)
    private val MEDIUM_CONFIDENCE_TOKENS: Set<Long> by lazy {
        decodeLexicon(
            listOf(
                "bnVkZQ==", "bmFrZWQ=", "c2V4eQ==", "aG90Z2lybA==", "aG90Z2lybHM=",
                "ZXJvdGlj", "c2V4", "c2V4dWFs", "YWR1bHQ=", "ZXhwbGljaXQ=",
                "dW5jZW5zb3JlZA==", "c2VkdWN0aXZl", "ZmV0aXNo", "c3RyaXBwZXI=", "c3RyaXB0ZWFzZQ==",
                "bGFwZGFuY2U=", "bGluZ2VyaWU=", "YmlraW5paGF1bA==", "dHJ5b25oYXVs", "dHdlcms=",
                "dHdlcmtpbmc=", "Ym9vdHk=", "Y2xlYXZhZ2U=", "Z2lybG51ZGU=", "bWFuZ2Fuc2Z3",
                // Suggestive Bollywood / movie / Hinglish medium signals
                "YmhhaGhp", "YmhhYmhp", "ZGVzaQ==", "Ym9sZA==", "aG90", "a2lzc2luZw==",
                "cm9tYW5jZQ==", "cm9tYW50aWM=", "c3RlYW15", "aW50aW1hdGU=", "c2Vuc3VhbA==",
                "YmlraW5p", "YnJh", "cGFudHk=", "cGFudGllcw==", "aXRlbQ==", "d2Vic2VyaWVz",
                "dHJhaWxlcg==", "c2NlbmU=", "c2NlbmVz", "Y2xpcA==", "Y2xpcHM=", "bW92aWU="
            )
        )
    }

    // High-confidence multi-word phrases (explicit + 18+/adult OTT + suggestive movie/trailer clips + Hinglish)
    private val HIGH_CONFIDENCE_PHRASES: Set<Long> by lazy {
        decodeLexicon(
            listOf(
                // Explicit English phrases
                "aG90IHNleA==", "c2V4IHZpZGVv", "c2V4IHZpZGVvcw==", "bnVkZSBnaXJs",
                "bnVkZSBnaXJscw==", "bmFrZWQgZ2lybA==", "bmFrZWQgZ2lybHM=", "bnVkZSB2aWRlbw==",
                "bnVkZSB2aWRlb3M=", "bmFrZWQgdmlkZW8=", "bmFrZWQgdmlkZW9z", "bnVkZSBib2R5",
                "bmFrZWQgYm9keQ==", "ZmVtYWxlIGFuYXRvbXk=", "YWR1bHQgdmlkZW8=",
                "YWR1bHQgdmlkZW9z", "YWR1bHQgY29udGVudA==", "MTggcGx1cyB2aWRlbw==",
                "MTggcGx1cyBtb3ZpZQ==", "aG90IHJvbWFuY2Ugc2NlbmU=", "YnJhIHRyeSBvbg==",
                "dHJhbnNwYXJlbnQgdHJ5IG9u", "c2VlIHRocm91Z2ggdHJ5IG9u", "bGluZ2VyaWUgdHJ5IG9u",
                "YmlraW5pIHRyeSBvbg==", "aG90IHdlYiBzZXJpZXM=", "dW5jZW5zb3JlZCBzY2VuZQ==",
                "c2V4eSBkYW5jZQ==", "aG90IGRhbmNl", "c2V4IHN0b3J5", "c2V4IHN0b3JpZXM=",
                "ZXJvdGljIHN0b3J5", "ZXJvdGljIG1vdmll", "YWR1bHQgbW92aWU=", "c29mdGNvcmUgbW92aWU=",
                // 18+/A-rated, Adult OTT & Suggestive Movie/Trailer Clips
                "Ym9sZCBzY2VuZQ==", "Ym9sZCBzY2VuZXM=", "aG90IHNjZW5l", "aG90IHNjZW5lcw==",
                "a2lzc2luZyBzY2VuZQ==", "a2lzc2luZyBzY2VuZXM=", "YmVkcm9vbSBzY2VuZQ==",
                "YmVkcm9vbSBzY2VuZXM=", "aW50aW1hdGUgc2NlbmU=", "aW50aW1hdGUgc2NlbmVz",
                "cm9tYW50aWMgc2NlbmU=", "cm9tYW50aWMgc2NlbmVz", "c3RlYW15IHNjZW5l",
                "bG92ZSBtYWtpbmc=", "aXRlbSBzb25n", "aXRlbSBzb25ncw==", "aXRlbSBudW1iZXI=",
                "aG90IHNvbmc=", "aG90IHNvbmdz", "c2V4eSBzb25n", "Ym9sZCB3ZWIgc2VyaWVz",
                "YWR1bHQgd2ViIHNlcmllcw==", "MTggcGx1cyB3ZWIgc2VyaWVz", "dW5jZW5zb3JlZCB0cmFpbGVy",
                "aG90IHRyYWlsZXI=", "Ym9sZCB0cmFpbGVy", "dWxsdSB3ZWIgc2VyaWVz",
                "dWxsdSB0cmFpbGVy", "Z2FuZGkgYmFhdA==", "cGFsYW5nIHRvZA==", "Ymx1ZSBmaWxt",
                "YmYgdmllZGVv", "YmYgdmlkZW8=", "ZGVzaSBiaGFiaGk=", "aG90IGJoYWJoaQ==",
                "aG90IGRlc2k=", "ZGVzaSByb21hbmNl", "ZGVzaSBtYXNhbGE=", "YWR1bHQgY29tZWR5",
                "c2V4IGNvbWVkeQ==", "dmlld2VyIGRpc2NyZXRpb24=", "YWR1bHRzIG9ubHk=",
                "YWR1bHQgb25seQ==", "YWdlIHJlc3RyaWN0ZWQ=", "Y29uZmlybSB5b3VyIGFnZQ==",
                // Multilingual phrases
                "dmlkZW8gcG9ybm8=", "cGVsacK7Y3VsYSBwb3Jubw==", "Y2hpY2EgZGVzbnVkYQ==",
                "ZmlsbGUgbnVl", "ZmlsbSBwb3Jubw==", "2KfZgdmE2KfZhSDYp9io2KfYrdmK2Kk=",
                "0L/QvtGA0L3QviDQstC40LTQtdC+", "4KSX4KSC4KSm4KWAIA==", "4KS44KWH4KSV4KWN4KS4IOCkteClgOCkoeCkv+Ckr+Cliw==",
                "4KSF4KS24KWN4KSy4KWA4KSyIOCkteClgOCkoeCkv+Ckr+Cliw=="
            )
        )
    }

    // YouTube / browser maturity & age-restriction banner markers (catches mature trailers/videos with clean titles!)
    private val MATURITY_MARKERS = listOf(
        "age-restricted",
        "age restricted",
        "sign in to confirm your age",
        "confirm your age",
        "viewer discretion is advised",
        "viewer discretion",
        "u/a 18+",
        "a-rated",
        "rated a ",
        "tv-ma",
        "nc-17",
        "adults only",
        "adult only",
        "mature audiences only",
        "explicit content",
        "18+ only",
        "18+ trailer",
        "18+ web series",
        "18+ movie",
        "18+ scene"
    )

    // STRICTLY Spiritual & Anti-Addiction Recovery framing ONLY (NO biology/anatomy/medical loophole!)
    private val SPIRITUAL_OR_RECOVERY_CONTEXT_TERMS = listOf(
        // Spiritual traditions & teachers
        "iskcon", "bhagavad gita", "gita", "prabhupada", "srila prabhupada",
        "brahmacharya", "brahmachari", "celibacy", "satsang", "pravachan",
        "katha", "swami", "swamiji", "acharya", "gurudev", "monk", "spiritual",
        "spirituality", "devotion", "bhakti", "krishna", "vedic", "upanishad",
        "vivekananda", "gaur gopal", "amogh lila", "premanand", "sadhguru",
        "ramakrishna", "hanuman", "meditation", "mindfulness", "self control",
        "self-control", "conquer lust", "overcome lust", "control lust",
        // Anti-addiction & recovery framing
        "addiction", "quit porn", "stop porn", "overcome porn", "beat porn",
        "porn recovery", "porn addiction", "stop masturbation", "quit masturbation",
        "overcome masturbation", "masturbation addiction", "nofap", "no fap",
        "dopamine detox", "freedom from porn", "effects of porn", "side effects of porn",
        "harmful effects", "brain damage", "rewire your brain", "how to quit",
        "how to stop", "how to overcome", "recovery journey", "addiction recovery",
        // Hindi Devanagari spiritual / recovery terms
        "ब्रह्मचर्य", "इस्कॉन", "भगवद गीता", "सत्संग", "प्रवचन", "व्यसन मुक्ति",
        "लत कैसे छोड़ें", "लत से छुटकारा", "आत्म संयम", "काम वासना पर नियंत्रण"
    )

    // Consumption intent phrases that override spiritual/recovery context if someone tries to game the filter
    private val EXPLICIT_CONSUMPTION_PHRASES = listOf(
        "watch free", "free video", "free videos", "full video", "full movie",
        "hd video", "download", "live cam", "webcam", "leaked", "mms",
        "viral video", "uncensored", "hot scene", "bold scene", "kissing scene",
        "item song", "try on", "try-on"
    )

    /**
     * Evaluates a user search query or URL string (in memory only).
     */
    fun evaluateQueryOrUrl(rawText: String?): DetectionResult {
        if (rawText.isNullOrBlank()) return DetectionResult(false, 0)
        return scoreText(rawText, threshold = 3)
    }

    /**
     * Evaluates a visible card, search result snippet, or video title+subtitle.
     */
    fun evaluateCardText(snippets: List<String>): DetectionResult {
        if (snippets.isEmpty()) return DetectionResult(false, 0)
        val combined = snippets.joinToString(" ")
        return scoreText(combined, threshold = 3)
    }

    /**
     * Evaluates active page / video title + visible metadata snippets.
     * Note: When YouTube Study Mode verifies the video belongs to an explicitly approved
     * channel, callers pass `isApprovedYouTubeChannel = true` so approved spiritual/study
     * channels discussing overcoming addiction are not falsely cut.
     */
    fun evaluateActiveContent(
        urlOrQuery: String?,
        visibleSnippets: List<String>,
        isApprovedYouTubeChannel: Boolean = false
    ): DetectionResult {
        if (isApprovedYouTubeChannel) return DetectionResult(false, 0)

        // Check combined context first so spiritual/recovery framing on the page
        // (e.g. "ISKCON Desire Tree" channel name next to "Overcome Porn Addiction" video title)
        // is evaluated accurately as one holistic context!
        val combinedContext = buildString {
            if (!urlOrQuery.isNullOrBlank()) append(urlOrQuery).append(' ')
            visibleSnippets.take(12).forEach { append(it).append(' ') }
        }.trim()

        if (!urlOrQuery.isNullOrBlank()) {
            val urlScore = scoreText(
                raw = urlOrQuery,
                threshold = 3,
                surroundingContext = combinedContext
            )
            if (urlScore.isFlagged) return urlScore
        }

        if (visibleSnippets.isEmpty()) return DetectionResult(false, 0)

        var totalScore = 0
        var flaggedSnippets = 0
        for (snippet in visibleSnippets.take(40)) {
            val res = scoreText(
                raw = snippet,
                threshold = 3,
                surroundingContext = combinedContext
            )
            if (res.isFlagged) {
                flaggedSnippets++
                totalScore += res.confidenceScore
                if (totalScore >= 4 || flaggedSnippets >= 2) {
                    return DetectionResult(
                        isFlagged = true,
                        confidenceScore = totalScore,
                        reason = "Inappropriate in-app content detected"
                    )
                }
            }
        }
        if (flaggedSnippets >= 1 && totalScore >= 3) {
            return DetectionResult(
                isFlagged = true,
                confidenceScore = totalScore,
                reason = "Inappropriate in-app content detected"
            )
        }
        return DetectionResult(false, totalScore)
    }

    private fun scoreText(
        raw: String,
        threshold: Int,
        surroundingContext: String = raw
    ): DetectionResult {
        val normalized = normalizeUnicodeForMatching(raw)
        if (normalized.length < 2) return DetectionResult(false, 0)

        val contextNormalized = if (surroundingContext === raw) {
            normalized
        } else {
            normalizeUnicodeForMatching(surroundingContext)
        }

        // 0. Check for explicit YouTube / browser maturity & age-restriction banners
        val hasMaturityMarker = MATURITY_MARKERS.any { normalized.contains(it) }

        val tokens = UNICODE_TOKEN_SPLIT_REGEX.split(normalized).filter { it.length >= 2 }
        if (tokens.isEmpty() && !hasMaturityMarker) return DetectionResult(false, 0)

        var score = 0
        var hasHardConsumptionSignal = false
        var hasExplicitPhrase = false

        if (hasMaturityMarker) {
            score += 4
        }

        // 1. Single-token checks (Unicode + Hinglish + Devanagari + Global languages)
        for (tok in tokens) {
            val h = DomainBlockMatcher.fnv1a64(tok)
            if (h in HARD_CONSUMPTION_TOKENS) {
                score += 5
                hasHardConsumptionSignal = true
            } else if (h in HIGH_CONFIDENCE_TOKENS) {
                score += 4
            } else if (h in MEDIUM_CONFIDENCE_TOKENS) {
                score += 1
            }
        }

        // 2. 2-gram and 3-gram phrase checks (explicit + suggestive clips/movies/OTT + Hinglish)
        for (i in 0 until tokens.size - 1) {
            val bi = "${tokens[i]} ${tokens[i + 1]}"
            if (DomainBlockMatcher.fnv1a64(bi) in HIGH_CONFIDENCE_PHRASES) {
                score += 4
                hasExplicitPhrase = true
            }
            if (i + 2 < tokens.size) {
                val tri = "$bi ${tokens[i + 2]}"
                if (DomainBlockMatcher.fnv1a64(tri) in HIGH_CONFIDENCE_PHRASES) {
                    score += 4
                    hasExplicitPhrase = true
                }
            }
        }

        // 3. Explicit age-gate signal ("18+" paired with any medium/suggestive signal)
        if ((normalized.contains("18+") || normalized.contains("18 plus")) && score >= 1) {
            score += 3
        }

        if (EXPLICIT_CONSUMPTION_PHRASES.any { contextNormalized.contains(it) }) {
            hasHardConsumptionSignal = true
        }

        // 4. Spiritual & Anti-Addiction Recovery Exemption ONLY
        // (NO biology/anatomy/medical loophole!)
        // Permits discussions like "ISKCON on porn addiction" or "Quit porn & Brahmacharya"
        // ONLY when NO hard site/consumption/suggestive-clip signals appear.
        if (score >= threshold && !hasHardConsumptionSignal && !hasExplicitPhrase && !hasMaturityMarker) {
            val hasSpiritualOrRecoveryContext = SPIRITUAL_OR_RECOVERY_CONTEXT_TERMS.any {
                contextNormalized.contains(it)
            }
            if (hasSpiritualOrRecoveryContext) {
                return DetectionResult(
                    isFlagged = false,
                    confidenceScore = 0,
                    reason = ""
                )
            }
        }

        val flagged = score >= threshold
        return DetectionResult(
            isFlagged = flagged,
            confidenceScore = score,
            reason = if (flagged) "Adult or mature content filtered by Freedom" else ""
        )
    }

    /**
     * Unicode-safe normalization:
     *  - Applies NFKC compatibility normalization (folds fullwidth/fancy Unicode fonts used to evade filters).
     *  - Lowercases cleanly.
     *  - Folds Latin accents (e.g. "desnudá" -> "desnuda", "érotique" -> "erotique") while
     *    preserving Indic (Devanagari/Tamil/etc.), Arabic, and Cyrillic characters intact!
     */
    internal fun normalizeUnicodeForMatching(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase().trim()
        // Only strip Latin diacritics; do NOT decompose Devanagari matras or Arabic vowel marks
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            if (ch in '\u00C0'..'\u024F') {
                val decomp = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                sb.append(COMBINING_DIACRITICS_REGEX.replace(decomp, ""))
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun decodeLexicon(encoded: List<String>): Set<Long> {
        val out = HashSet<Long>(encoded.size * 2)
        for (b64 in encoded) {
            val decoded = decodeBase64Utf8(b64)?.let(::normalizeUnicodeForMatching) ?: continue
            if (decoded.isNotEmpty()) {
                out.add(DomainBlockMatcher.fnv1a64(decoded))
            }
        }
        return out
    }

    /**
     * Pure-Kotlin UTF-8 Base64 decoder (works identically in Android Runtime and JVM unit tests).
     */
    private fun decodeBase64Utf8(input: String): String? {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = input.filter { it == '=' || it in table }
        if (clean.length % 4 != 0) return null
        val bytes = ArrayList<Byte>((clean.length * 3) / 4)
        var i = 0
        while (i < clean.length) {
            val c0 = table.indexOf(clean[i])
            val c1 = table.indexOf(clean[i + 1])
            val c2 = if (clean[i + 2] == '=') -1 else table.indexOf(clean[i + 2])
            val c3 = if (clean[i + 3] == '=') -1 else table.indexOf(clean[i + 3])
            if (c0 < 0 || c1 < 0) return null
            bytes.add(((c0 shl 2) or (c1 shr 4)).toByte())
            if (c2 >= 0) {
                bytes.add((((c1 and 0x0F) shl 4) or (c2 shr 2)).toByte())
                if (c3 >= 0) {
                    bytes.add((((c2 and 0x03) shl 6) or c3).toByte())
                }
            }
            i += 4
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
