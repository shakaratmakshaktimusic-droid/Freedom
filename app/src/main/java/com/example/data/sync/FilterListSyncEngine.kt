package com.example.data.sync

import android.content.Context
import com.example.data.local.FastBlockStateStore
import com.example.data.local.FilterFeedStatusEntity
import com.example.data.local.FreedomDao
import com.example.domain.CompactDomainIndex
import com.example.domain.DomainBlockMatcher
import com.example.domain.FilterUpdateFrequency
import com.example.domain.WebsiteCatalog
import com.example.domain.WebsiteCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Arrays

data class FilterSyncUiState(
    val isSyncing: Boolean = false,
    val progressFraction: Float = 0f,
    val currentStatusText: String = "Protection lists ready",
    val totalIndexedDomains: Int = 0,
    val lastSyncedMs: Long = 0L,
    val lastError: String? = null
)

/**
 * uBlock-Origin-style lightweight streaming filter list updater.
 *
 * Streams public open-source hosts/domain feeds line by line over HTTPS using ETag
 * conditional requests (0 bytes downloaded when unchanged), hashes each valid domain
 * to an 8-byte 64-bit FNV-1a Long immediately (never holding giant string lists in RAM),
 * writes compact sorted `.bin` caches per category, and atomically reloads CompactDomainIndex.
 */
class FilterListSyncEngine(
    context: Context,
    private val dao: FreedomDao,
    private val fastStore: FastBlockStateStore,
    val domainIndex: CompactDomainIndex
) {
    private val cacheDir: File = File(context.applicationContext.filesDir, "filter_lists")

    private val _syncState = MutableStateFlow(
        FilterSyncUiState(
            totalIndexedDomains = domainIndex.totalIndexedDomainCount(),
            lastSyncedMs = fastStore.getLastFilterSyncMs()
        )
    )
    val syncState: StateFlow<FilterSyncUiState> = _syncState.asStateFlow()

    suspend fun loadCachedIndexIntoMemory() = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        domainIndex.reloadFromCacheDir(cacheDir)
        val learned = dao.getAllAutoLearnedHashes()
        val grouped = HashMap<WebsiteCategory, MutableSet<Long>>()
        for (item in learned) {
            val cat = WebsiteCategory.fromId(item.categoryId) ?: continue
            grouped.getOrPut(cat) { HashSet() }.add(item.domainHash64)
        }
        domainIndex.replaceAutoLearnedHashes(grouped)
        _syncState.value = _syncState.value.copy(
            totalIndexedDomains = domainIndex.totalIndexedDomainCount(),
            lastSyncedMs = fastStore.getLastFilterSyncMs()
        )
    }

    suspend fun syncIfDue() {
        val freq = fastStore.getFilterUpdateFrequency()
        if (freq == FilterUpdateFrequency.MANUAL) return
        val last = fastStore.getLastFilterSyncMs()
        val now = System.currentTimeMillis()
        if (last == 0L || now - last >= freq.intervalMs) {
            syncAllFeeds(forceDownload = false)
        }
    }

    suspend fun syncAllFeeds(forceDownload: Boolean = true): Result<Int> = withContext(Dispatchers.IO) {
        if (_syncState.value.isSyncing) {
            return@withContext Result.success(domainIndex.totalIndexedDomainCount())
        }
        cacheDir.mkdirs()
        _syncState.value = _syncState.value.copy(
            isSyncing = true,
            progressFraction = 0.05f,
            currentStatusText = "Checking community filter feeds…",
            lastError = null
        )

        val feeds = WebsiteCatalog.COMMUNITY_FEEDS
        val existingStatuses = dao.getFilterFeedStatuses().associateBy { it.feedId }
        val hashesByCategory = HashMap<WebsiteCategory, ArrayList<Long>>()
        val updatedCategories = HashSet<WebsiteCategory>()
        var anyFailure: String? = null

        try {
            feeds.forEachIndexed { index, feed ->
                val progress = ((index + 1).toFloat() / (feeds.size + 1).toFloat()).coerceIn(0.08f, 0.92f)
                _syncState.value = _syncState.value.copy(
                    progressFraction = progress,
                    currentStatusText = "Updating ${feed.category.title} protection (${index + 1}/${feeds.size})…"
                )

                val prev = existingStatuses[feed.id]
                val fetchResult = fetchAndHashFeed(
                    urlStr = feed.url,
                    previousEtag = if (forceDownload) null else prev?.etag
                )

                when (fetchResult) {
                    is FeedFetchResult.Updated -> {
                        val bucket = hashesByCategory.getOrPut(feed.category) { ArrayList(fetchResult.hashes.size) }
                        for (h in fetchResult.hashes) {
                            bucket.add(h)
                        }
                        updatedCategories.add(feed.category)
                        dao.upsertFilterFeedStatus(
                            FilterFeedStatusEntity(
                                feedId = feed.id,
                                categoryId = feed.category.id,
                                domainCount = fetchResult.hashes.size,
                                etag = fetchResult.etag,
                                lastSyncedMs = System.currentTimeMillis(),
                                lastError = null
                            )
                        )
                    }

                    is FeedFetchResult.NotModified -> {
                        // Existing category cache on disk is already current; keep existing hashes if merging
                        dao.upsertFilterFeedStatus(
                            FilterFeedStatusEntity(
                                feedId = feed.id,
                                categoryId = feed.category.id,
                                domainCount = prev?.domainCount ?: 0,
                                etag = prev?.etag,
                                lastSyncedMs = System.currentTimeMillis(),
                                lastError = null
                            )
                        )
                    }

                    is FeedFetchResult.Error -> {
                        anyFailure = fetchResult.message
                        dao.upsertFilterFeedStatus(
                            FilterFeedStatusEntity(
                                feedId = feed.id,
                                categoryId = feed.category.id,
                                domainCount = prev?.domainCount ?: 0,
                                etag = prev?.etag,
                                lastSyncedMs = prev?.lastSyncedMs ?: 0L,
                                lastError = fetchResult.message
                            )
                        )
                    }
                }
            }

            // Persist merged compact binary hash files for every updated category
            for (category in updatedCategories) {
                val collected = hashesByCategory[category] ?: continue
                val existingOnDisk = if (!forceDownload) {
                    CompactDomainIndex.readHashFile(File(cacheDir, CompactDomainIndex.fileNameForCategory(category)))
                } else {
                    LongArray(0)
                }
                val merged = LongArray(collected.size + existingOnDisk.size)
                var i = 0
                for (h in collected) merged[i++] = h
                System.arraycopy(existingOnDisk, 0, merged, i, existingOnDisk.size)
                Arrays.sort(merged)
                val unique = CompactDomainIndex.deduplicateSorted(merged)
                CompactDomainIndex.writeSortedHashesToFile(
                    targetFile = File(cacheDir, CompactDomainIndex.fileNameForCategory(category)),
                    sortedUniqueHashes = unique
                )
            }

            // Atomically reload full binary index into RAM
            domainIndex.reloadFromCacheDir(cacheDir)
            val now = System.currentTimeMillis()
            fastStore.setLastFilterSyncMs(now)

            val total = domainIndex.totalIndexedDomainCount()
            _syncState.value = FilterSyncUiState(
                isSyncing = false,
                progressFraction = 1f,
                currentStatusText = if (anyFailure == null) {
                    "Protection lists up to date"
                } else {
                    "Updated with partial offline fallback"
                },
                totalIndexedDomains = total,
                lastSyncedMs = now,
                lastError = anyFailure
            )
            Result.success(total)
        } catch (e: Exception) {
            val msg = e.message ?: "Unable to reach filter feeds"
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                progressFraction = 0f,
                currentStatusText = "Using cached local protection lists",
                lastError = msg
            )
            Result.failure(e)
        }
    }

    private sealed class FeedFetchResult {
        data class Updated(val hashes: LongArray, val etag: String?) : FeedFetchResult()
        data object NotModified : FeedFetchResult()
        data class Error(val message: String) : FeedFetchResult()
    }

    private fun fetchAndHashFeed(urlStr: String, previousEtag: String?): FeedFetchResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FreedomFocusAndroid/2.0")
                if (!previousEtag.isNullOrBlank()) {
                    setRequestProperty("If-None-Match", previousEtag)
                }
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return FeedFetchResult.NotModified
            }
            if (code !in 200..299) {
                return FeedFetchResult.Error("HTTP $code")
            }
            val etag = conn.getHeaderField("ETag")
            val hashes = ArrayList<Long>(4096)
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8), 16 * 1024).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    parseDomainFromFilterLine(line)?.let { domain ->
                        hashes.add(DomainBlockMatcher.fnv1a64(domain))
                    }
                    line = reader.readLine()
                }
            }
            val arr = LongArray(hashes.size) { idx -> hashes[idx] }
            Arrays.sort(arr)
            FeedFetchResult.Updated(
                hashes = CompactDomainIndex.deduplicateSorted(arr),
                etag = etag
            )
        } catch (e: Exception) {
            FeedFetchResult.Error(e.message ?: "Network error")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    companion object {
        /**
         * Parses a single line from hosts (`0.0.0.0 example.com`), AdBlock/uBlock (`||example.com^`),
         * or raw domain-list (`example.com`) formats into a canonical normalized domain.
         */
        fun parseDomainFromFilterLine(rawLine: String): String? {
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || line.startsWith("[")) {
                return null
            }
            // Strip inline comments
            val hashIdx = line.indexOf('#')
            if (hashIdx != -1) line = line.substring(0, hashIdx).trim()
            if (line.isEmpty()) return null

            // Hosts format: "0.0.0.0 domain.com" or "127.0.0.1 domain.com"
            if (line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1") || line.startsWith("::")) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return null
                val candidate = parts[1]
                if (candidate == "localhost" || candidate.endsWith(".local") || candidate == "0.0.0.0") {
                    return null
                }
                return DomainBlockMatcher.normalizeDomain(candidate)
            }

            // AdBlock / uBlock rule: "||domain.com^"
            if (line.startsWith("||")) {
                val cleaned = line.removePrefix("||")
                    .substringBefore('^')
                    .substringBefore('$')
                    .substringBefore('/')
                return DomainBlockMatcher.normalizeDomain(cleaned)
            }

            // Raw domain-only line
            if (line.contains(' ') || line.contains('\t')) return null
            if (line == "localhost") return null
            return DomainBlockMatcher.normalizeDomain(line)
        }
    }
}
