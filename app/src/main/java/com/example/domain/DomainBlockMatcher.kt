package com.example.domain

/**
 * Pure Kotlin URL / hostname normalizer, 64-bit FNV-1a domain hasher,
 * and highest-precedence Website Allowlist matcher (supporting both domain
 * exceptions like "wikipedia.org" and path-prefix URL exceptions like
 * "youtube.com/playlist?list=study").
 */
object DomainBlockMatcher {

    private val DOMAIN_REGEX =
        Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")

    private val URL_EXTRACT_REGEX = Regex(
        pattern = "(?:https?://)?([a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+)(?::\\d+)?(/[^\\s]*)?",
        option = RegexOption.IGNORE_CASE
    )

    data class ExtractedUrl(
        val host: String,
        val fullNormalizedUrl: String // e.g. "youtube.com/watch?v=abc"
    )

    /**
     * Normalizes a domain or URL to a canonical lowercase host (stripping scheme,
     * port, path, trailing dot, and common www./m./mobile. prefixes).
     */
    fun normalizeDomain(raw: String): String? {
        var cleaned = raw.trim().lowercase()
        if (cleaned.isEmpty() || cleaned.contains(' ')) return null

        val schemeIdx = cleaned.indexOf("://")
        if (schemeIdx != -1) {
            cleaned = cleaned.substring(schemeIdx + 3)
        }
        val endIdx = cleaned.indexOfAny(charArrayOf('/', '?', '#'))
        if (endIdx != -1) {
            cleaned = cleaned.substring(0, endIdx)
        }
        val atIdx = cleaned.indexOf('@')
        if (atIdx != -1) {
            cleaned = cleaned.substring(atIdx + 1)
        }
        val colonIdx = cleaned.indexOf(':')
        if (colonIdx != -1) {
            cleaned = cleaned.substring(0, colonIdx)
        }
        cleaned = cleaned.trimEnd('.')
        cleaned = stripCommonPrefixes(cleaned)

        return if (DOMAIN_REGEX.matches(cleaned)) cleaned else null
    }

    /**
     * Normalizes a user-entered Website Allowlist rule (either a pure domain like
     * "wikipedia.org" or a domain+path like "youtube.com/@3blue1brown").
     * Returns null if invalid.
     */
    fun normalizeAllowRule(raw: String): Pair<String, Boolean>? {
        var cleaned = raw.trim().lowercase()
        if (cleaned.isEmpty() || cleaned.contains(' ')) return null

        val schemeIdx = cleaned.indexOf("://")
        if (schemeIdx != -1) {
            cleaned = cleaned.substring(schemeIdx + 3)
        }
        val slashIdx = cleaned.indexOf('/')
        val hostPartRaw = if (slashIdx != -1) cleaned.substring(0, slashIdx) else cleaned
        val pathPart = if (slashIdx != -1) cleaned.substring(slashIdx).trimEnd('/') else ""

        val host = normalizeDomain(hostPartRaw) ?: return null
        return if (pathPart.isNotEmpty() && pathPart != "/") {
            ("$host$pathPart") to true
        } else {
            host to false
        }
    }

    /**
     * Extracts both normalized host and normalized host+path from arbitrary browser URL bar
     * or WebView header text.
     */
    fun extractUrlFromUiText(rawText: String?): ExtractedUrl? {
        if (rawText.isNullOrBlank()) return null
        val trimmed = rawText.trim()

        val match = URL_EXTRACT_REGEX.find(trimmed) ?: return null
        val rawHost = match.groupValues.getOrNull(1)?.lowercase()?.trimEnd('.') ?: return null
        val host = stripCommonPrefixes(rawHost)
        if (!DOMAIN_REGEX.matches(host)) return null

        val rawPath = match.groupValues.getOrNull(2).orEmpty().trimEnd('/')
        val fullUrl = if (rawPath.isNotEmpty() && rawPath != "/") {
            "$host${rawPath.lowercase()}"
        } else {
            host
        }
        return ExtractedUrl(host = host, fullNormalizedUrl = fullUrl)
    }

    fun extractHostFromUiText(rawText: String?): String? = extractUrlFromUiText(rawText)?.host

    /**
     * Highest-precedence check: returns true if [candidateHost] / [candidateFullUrl]
     * is explicitly permitted by any entry in [allowedPatterns].
     *
     * Supports:
     *  - Domain rules (e.g. "wikipedia.org" allows "en.wikipedia.org/wiki/Focus")
     *  - Path-prefix rules (e.g. "youtube.com/@veritasium" allows "youtube.com/@veritasium/videos")
     */
    fun isAllowedByWebsiteAllowlist(
        candidateHost: String?,
        candidateFullUrl: String?,
        allowedPatterns: Set<String>
    ): Boolean {
        if (allowedPatterns.isEmpty()) return false
        val host = candidateHost?.let { normalizeDomain(it) } ?: return false
        val fullUrl = (candidateFullUrl?.lowercase()?.removePrefix("https://")?.removePrefix("http://")
            ?: host).let { raw ->
            val slash = raw.indexOf('/')
            if (slash != -1) {
                val h = normalizeDomain(raw.substring(0, slash)) ?: host
                "$h${raw.substring(slash).trimEnd('/')}"
            } else {
                host
            }
        }

        for (rule in allowedPatterns) {
            val normalizedRule = rule.trim().lowercase().trimEnd('/')
            if (normalizedRule.isEmpty()) continue
            val ruleSlash = normalizedRule.indexOf('/')
            if (ruleSlash != -1) {
                val ruleHost = normalizedRule.substring(0, ruleSlash)
                val rulePath = normalizedRule.substring(ruleSlash)
                if (hostMatchesParent(host, ruleHost)) {
                    val candidatePath = fullUrl.substringAfter(host, "")
                    if (candidatePath == rulePath || candidatePath.startsWith("$rulePath/") || candidatePath.startsWith("$rulePath?")) {
                        return true
                    }
                }
            } else {
                if (hostMatchesParent(host, normalizedRule)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Matches [candidateHost] against a set of custom blocked domains (exact or subdomain).
     */
    fun findMatchingBlockedDomain(
        candidateHost: String?,
        blockedDomains: Set<String>
    ): String? {
        if (candidateHost.isNullOrBlank() || blockedDomains.isEmpty()) return null
        val normalized = normalizeDomain(candidateHost) ?: return null

        var current = normalized
        while (true) {
            if (current in blockedDomains) return current
            val dotIdx = current.indexOf('.')
            if (dotIdx == -1 || dotIdx == current.lastIndex) break
            current = current.substring(dotIdx + 1)
        }
        return null
    }

    /**
     * Iterates over [normalizedDomain] and every parent suffix down to eTLD+1
     * (e.g. "a.b.example.com" -> "a.b.example.com", "b.example.com", "example.com").
     */
    inline fun forEachParentDomain(normalizedDomain: String, action: (String) -> Unit) {
        var current = normalizedDomain
        while (true) {
            action(current)
            val dotIdx = current.indexOf('.')
            if (dotIdx == -1) break
            val next = current.substring(dotIdx + 1)
            if (!next.contains('.')) break
            current = next
        }
    }

    /**
     * Fast, deterministic 64-bit FNV-1a hash used by CompactDomainIndex so 200,000+
     * domains fit in ~1.6 MB of sorted LongArrays with <0.01 ms binary-search lookup.
     */
    fun fnv1a64(domain: String): Long {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325UL
        for (i in 0 until domain.length) {
            hash = hash xor (domain[i].code.toLong() and 0xFFL)
            hash *= 0x100000001b3L
        }
        return hash
    }

    private fun hostMatchesParent(host: String, parent: String): Boolean {
        return host == parent || host.endsWith(".$parent")
    }

    private fun stripCommonPrefixes(host: String): String {
        var result = host
        val prefixes = listOf("www.", "m.", "mobile.")
        for (prefix in prefixes) {
            if (result.startsWith(prefix) && result.count { it == '.' } >= 2) {
                result = result.removePrefix(prefix)
            }
        }
        return result
    }
}
