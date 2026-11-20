package com.example.domain

/**
 * On-device, zero-VPN, privacy-preserving In-App Adult-Content Classifier.
 *
 * Operates strictly in memory on accessible titles, search queries, descriptions,
 * image alt-text/labels, and URL path/query tokens across:
 *  - Official YouTube, Morphe YT, ReVanced, and third-party YouTube clients
 *  - Google Search, Google Images, Bing, DuckDuckGo, Yahoo, Brave, and browsers
 *  - Other apps exposing inspectable Accessibility text/labels
 *
 * Design guarantees:
 *  - Uses 64-bit FNV-1a hashed token sets + normalized phrase checks so explicit
 *    terms are NEVER stored in user-visible lists or rendered in Freedom's UI.
 *  - Includes educational/medical context dampeners to avoid blocking legitimate
 *    study material (anatomy, biology, health, cancer research, history).
 *  - Zero network calls, zero browsing history saved.
 */
object InAppAdultFilterEngine {

    data class DetectionResult(
        val isFlagged: Boolean,
        val confidenceScore: Int,
        val reason: String = ""
    )

    private val TOKEN_SPLIT_REGEX = Regex("[^a-z0-9]+")

    // High-confidence single-token hashes (64-bit FNV-1a of normalized lowercase tokens)
    // Computed deterministically at init from compact encoded roots so no explicit strings appear in UI.
    private val HIGH_CONFIDENCE_TOKENS: Set<Long> by lazy {
        decodeLexicon(
            listOf(
                "cG9ybg==", "cG9ybm8=", "cG9ybm9ncmFwaHk=", "eHh4", "eHZpZGVvcw==",
                "eG54eA==", "cG9ybmh1Yg==", "eGhhbXN0ZXI=", "cmVkdHViZQ==", "eW91cG9ybg==",
                "c3BhbmtiYW5n", "aGVudGFp", "b25seWZhbnM=", "Y2FtZ2lybA==", "Y2FtZ2lybHM=",
                "Y2hhdHVyYmF0ZQ==", "c3RyaXBjaGF0", "Y2FtNA==", "Ym9uZ2FjYW1z",
                "bnNmdw==", "bWlsZg==", "Ymxvd2pvYg==", "aGFuZGpvYg==", "ZmVsbGF0aW8=",
                "Y3VubmlsaW5ndXM=", "ZXJvdGljYQ==", "ZmFwcGVu", "ZmFwcGluZw==", "bWFzdHVyYmF0ZQ==",
                "bWFzdHVyYmF0aW9u", "b3JnYXNt", "ZWphY3VsYXRpb24=", "Z2FuZ2Jhbmc=", "dGhyZWVzb21l",
                "YmRzbQ==", "ZGlsZG8=", "dnVsdmE=", "bmlwcGxlcw==", "Ym9vYnM=", "dGl0cw==",
                "cHVzc3k=", "Y29jaw==", "ZGljaw==", "bGl2ZWphenptaW4=", "c2V4dGFwZQ==",
                "cmV2ZW5nZXBvcm4=", "bG9saWNvbg==", "eWFvaQ==", "cnVsZTM0", "ZTYyMQ==",
                "bnVkZXM=", "dG9wbGVzcw==", "dW5kcmVzcw==", "bGluZ2VyaWVwb3Ju", "c2V4dmlkZW8=",
                "c2V4dmlkZW9z", "YWR1bHR2aWRlbw==", "YWR1bHR2aWRlb3M=", "ZXhwbGljaXR2aWRlbw=="
            )
        )
    }

    // Medium-confidence single tokens (require score >= 2 or pairing with another signal)
    private val MEDIUM_CONFIDENCE_TOKENS: Set<Long> by lazy {
        decodeLexicon(
            listOf(
                "bnVkZQ==", "bmFrZWQ=", "c2V4eQ==", "aG90Z2lybA==", "aG90Z2lybHM=",
                "ZXJvdGlj", "c2V4", "c2V4dWFs", "YWR1bHQ=", "MTgr", "ZXhwbGljaXQ=",
                "dW5jZW5zb3JlZA==", "c2VkdWN0aXZl", "ZmV0aXNo", "c3RyaXBwZXI=", "c3RyaXB0ZWFzZQ==",
                "bGFwZGFuY2U=", "bGluZ2VyaWU=", "YmlraW5paGF1bA==", "dHJ5b25oYXVs", "dHdlcms=",
                "dHdlcmtpbmc=", "Ym9vdHk=", "Y2xlYXZhZ2U=", "Z2lybG51ZGU=", "bWFuZ2Fuc2Z3"
            )
        )
    }

    // High-confidence multi-word phrase hashes
    private val HIGH_CONFIDENCE_PHRASES: Set<Long> by lazy {
        decodeLexicon(
            listOf(
                "aG90IHNleA==", "c2V4IHZpZGVv", "c2V4IHZpZGVvcw==", "bnVkZSBnaXJs",
                "bnVkZSBnaXJscw==", "bmFrZWQgZ2lybA==", "bmFrZWQgZ2lybHM=", "YWR1bHQgdmlkZW8=",
                "YWR1bHQgdmlkZW9z", "YWR1bHQgY29udGVudA==", "MTggcGx1cyB2aWRlbw==",
                "MTggcGx1cyBtb3ZpZQ==", "aG90IHJvbWFuY2Ugc2NlbmU=", "YnJhIHRyeSBvbg==",
                "dHJhbnNwYXJlbnQgdHJ5IG9u", "c2VlIHRocm91Z2ggdHJ5IG9u", "bGluZ2VyaWUgdHJ5IG9u",
                "YmlraW5pIHRyeSBvbg==", "aG90IHdlYiBzZXJpZXM=", "dW5jZW5zb3JlZCBzY2VuZQ==",
                "c2V4eSBkYW5jZQ==", "aG90IGRhbmNl", "c2V4IHN0b3J5", "c2V4IHN0b3JpZXM=",
                "ZXJvdGljIHN0b3J5", "ZXJvdGljIG1vdmll", "YWR1bHQgbW92aWU=", "c29mdGNvcmUgbW92aWU="
            )
        )
    }

    // Educational / medical context phrases that lower medium-signal false positives
    private val EDUCATIONAL_CONTEXT_TERMS = listOf(
        "anatomy", "biology", "medical", "medicine", "cancer", "clinic", "hospital",
        "ncert", "lecture", "class 10", "class 12", "neet", "upsc", "jee",
        "reproductive system", "human reproduction", "health education",
        "psychology", "sociology", "history documentary", "literature", "science",
        "textbook", "exam", "syllabus", "university", "khan academy", "mit opencourseware"
    )

    /**
     * Evaluates a user search query or URL string (in memory only).
     */
    fun evaluateQueryOrUrl(rawText: String?): DetectionResult {
        if (rawText.isNullOrBlank()) return DetectionResult(false, 0)
        return scoreText(rawText, threshold = 3)
    }

    /**
     * Evaluates a visible feed card, search result snippet, or image caption/alt-text
     * so explicit cards can be covered BEFORE interaction.
     */
    fun evaluateCardText(snippets: List<String>): DetectionResult {
        if (snippets.isEmpty()) return DetectionResult(false, 0)
        val combined = snippets.joinToString(" ")
        return scoreText(combined, threshold = 3)
    }

    /**
     * Evaluates active page / video title + metadata snippets.
     */
    fun evaluateActiveContent(
        urlOrQuery: String?,
        visibleSnippets: List<String>
    ): DetectionResult {
        if (!urlOrQuery.isNullOrBlank()) {
            val urlScore = scoreText(urlOrQuery, threshold = 3)
            if (urlScore.isFlagged) return urlScore
        }
        if (visibleSnippets.isEmpty()) return DetectionResult(false, 0)

        var totalScore = 0
        var flaggedSnippets = 0
        for (snippet in visibleSnippets.take(40)) {
            val res = scoreText(snippet, threshold = 3)
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
        // Single strong high-confidence hit on a title/header is sufficient
        if (flaggedSnippets >= 1 && totalScore >= 3) {
            return DetectionResult(
                isFlagged = true,
                confidenceScore = totalScore,
                reason = "Inappropriate in-app content detected"
            )
        }
        return DetectionResult(false, totalScore)
    }

    private fun scoreText(raw: String, threshold: Int): DetectionResult {
        val lower = raw.lowercase().trim()
        if (lower.length < 3) return DetectionResult(false, 0)

        val tokens = TOKEN_SPLIT_REGEX.split(lower).filter { it.length >= 2 }
        if (tokens.isEmpty()) return DetectionResult(false, 0)

        var score = 0
        var hasHighConfidence = false

        // 1. Single-token checks
        for (tok in tokens) {
            val h = DomainBlockMatcher.fnv1a64(tok)
            if (h in HIGH_CONFIDENCE_TOKENS) {
                score += 4
                hasHighConfidence = true
            } else if (h in MEDIUM_CONFIDENCE_TOKENS) {
                score += 1
            }
        }

        // 2. 2-gram and 3-gram phrase checks
        for (i in 0 until tokens.size - 1) {
            val bi = "${tokens[i]} ${tokens[i + 1]}"
            if (DomainBlockMatcher.fnv1a64(bi) in HIGH_CONFIDENCE_PHRASES) {
                score += 4
                hasHighConfidence = true
            }
            if (i + 2 < tokens.size) {
                val tri = "$bi ${tokens[i + 2]}"
                if (DomainBlockMatcher.fnv1a64(tri) in HIGH_CONFIDENCE_PHRASES) {
                    score += 4
                    hasHighConfidence = true
                }
            }
        }

        // 3. Explicit age-gate signal ("18+" paired with medium signal)
        if (lower.contains("18+") && score >= 1) {
            score += 2
        }

        // 4. Educational / medical dampener for ambiguous medium-only matches
        if (!hasHighConfidence && score > 0) {
            val hasEducationalContext = EDUCATIONAL_CONTEXT_TERMS.any { lower.contains(it) }
            if (hasEducationalContext) {
                score = (score - 2).coerceAtLeast(0)
            }
        }

        val flagged = score >= threshold
        return DetectionResult(
            isFlagged = flagged,
            confidenceScore = score,
            reason = if (flagged) "Adult content filtered by Freedom" else ""
        )
    }

    private fun decodeLexicon(encoded: List<String>): Set<Long> {
        val out = HashSet<Long>(encoded.size * 2)
        for (b64 in encoded) {
            val decoded = decodeBase64Ascii(b64)?.trim()?.lowercase() ?: continue
            if (decoded.isNotEmpty()) {
                out.add(DomainBlockMatcher.fnv1a64(decoded))
            }
        }
        return out
    }

    /**
     * Pure-Kotlin Base64 decoder (works identically in Android Runtime and JVM unit tests
     * without android.util.Base64 stubs).
     */
    private fun decodeBase64Ascii(input: String): String? {
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
