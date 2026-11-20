package com.example.domain

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

/**
 * Ultra-light, memory-safe 64-bit FNV-1a sorted-LongArray domain index.
 *
 * Design goals:
 *  - Holds 200,000+ regularly updated community domains in ~1.6 MB total (~8 bytes/domain).
 *  - Binary-search lookup takes <0.01 ms on the Accessibility thread with zero allocations.
 *  - Underlying domain strings are never retained or displayed in the UI.
 *  - Supports instant atomic reload after filter sync and single-hash auto-learning insertion.
 */
class CompactDomainIndex {

    @Volatile
    private var categoryHashes: Map<WebsiteCategory, LongArray> = buildBuiltinOnlyMap()

    private val autoLearnedByCategory = ConcurrentHashMap<WebsiteCategory, MutableSet<Long>>()

    fun containsDomain(candidateHost: String?, activeCategories: Set<WebsiteCategory>): WebsiteCategory? {
        if (candidateHost.isNullOrBlank() || activeCategories.isEmpty()) return null
        val normalized = DomainBlockMatcher.normalizeDomain(candidateHost) ?: return null
        val snapshot = categoryHashes

        for (category in activeCategories) {
            val sortedHashes = snapshot[category]
            val learned = autoLearnedByCategory[category]

            var matched = false
            DomainBlockMatcher.forEachParentDomain(normalized) { suffix ->
                if (!matched) {
                    val h = DomainBlockMatcher.fnv1a64(suffix)
                    if (sortedHashes != null && Arrays.binarySearch(sortedHashes, h) >= 0) {
                        matched = true
                    } else if (learned != null && h in learned) {
                        matched = true
                    }
                }
            }
            if (matched) return category
        }
        return null
    }

    fun addAutoLearnedHash(category: WebsiteCategory, hash64: Long) {
        val set = autoLearnedByCategory.getOrPut(category) {
            ConcurrentHashMap.newKeySet()
        }
        set.add(hash64)
    }

    fun replaceAutoLearnedHashes(hashesByCategory: Map<WebsiteCategory, Set<Long>>) {
        autoLearnedByCategory.clear()
        for ((cat, set) in hashesByCategory) {
            val concurrent = ConcurrentHashMap.newKeySet<Long>()
            concurrent.addAll(set)
            autoLearnedByCategory[cat] = concurrent
        }
    }

    /**
     * Reloads built-in domain hashes merged with any downloaded per-category `.bin`
     * hash caches stored in [cacheDir]. Runs off the main thread and swaps atomically.
     */
    fun reloadFromCacheDir(cacheDir: File) {
        val merged = HashMap<WebsiteCategory, LongArray>(WebsiteCategory.entries.size)
        for (category in WebsiteCategory.entries) {
            val builtinSet = WebsiteCatalog.BUILTIN_DOMAINS[category].orEmpty()
            val file = File(cacheDir, fileNameForCategory(category))
            val diskHashes = readHashFile(file)

            val combined = LongArray(builtinSet.size + diskHashes.size)
            var idx = 0
            for (domain in builtinSet) {
                combined[idx++] = DomainBlockMatcher.fnv1a64(domain)
            }
            System.arraycopy(diskHashes, 0, combined, idx, diskHashes.size)
            Arrays.sort(combined)
            merged[category] = deduplicateSorted(combined)
        }
        categoryHashes = merged
    }

    fun totalIndexedDomainCount(): Int {
        var total = 0
        for (arr in categoryHashes.values) {
            total += arr.size
        }
        for (set in autoLearnedByCategory.values) {
            total += set.size
        }
        return total
    }

    fun countForCategory(category: WebsiteCategory): Int {
        val base = categoryHashes[category]?.size ?: 0
        val learned = autoLearnedByCategory[category]?.size ?: 0
        return base + learned
    }

    companion object {
        private const val BINARY_MAGIC = 0x46524431 // "FRD1"

        fun fileNameForCategory(category: WebsiteCategory): String =
            "cat_${category.id.lowercase()}.bin"

        fun writeSortedHashesToFile(targetFile: File, sortedUniqueHashes: LongArray) {
            targetFile.parentFile?.mkdirs()
            val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            DataOutputStream(BufferedOutputStream(tmpFile.outputStream(), 16 * 1024)).use { out ->
                out.writeInt(BINARY_MAGIC)
                out.writeInt(sortedUniqueHashes.size)
                for (h in sortedUniqueHashes) {
                    out.writeLong(h)
                }
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tmpFile.renameTo(targetFile)
        }

        fun readHashFile(file: File): LongArray {
            if (!file.exists() || file.length() < 8L) return LongArray(0)
            return runCatching {
                DataInputStream(BufferedInputStream(file.inputStream(), 16 * 1024)).use { input ->
                    val magic = input.readInt()
                    if (magic != BINARY_MAGIC) return@use LongArray(0)
                    val count = input.readInt().coerceIn(0, 2_000_000)
                    val arr = LongArray(count)
                    for (i in 0 until count) {
                        arr[i] = input.readLong()
                    }
                    arr
                }
            }.getOrDefault(LongArray(0))
        }

        fun deduplicateSorted(sorted: LongArray): LongArray {
            if (sorted.size <= 1) return sorted
            var uniqueCount = 1
            for (i in 1 until sorted.size) {
                if (sorted[i] != sorted[i - 1]) uniqueCount++
            }
            if (uniqueCount == sorted.size) return sorted
            val out = LongArray(uniqueCount)
            out[0] = sorted[0]
            var w = 1
            for (i in 1 until sorted.size) {
                if (sorted[i] != sorted[i - 1]) {
                    out[w++] = sorted[i]
                }
            }
            return out
        }

        private fun buildBuiltinOnlyMap(): Map<WebsiteCategory, LongArray> {
            val map = HashMap<WebsiteCategory, LongArray>(WebsiteCategory.entries.size)
            for (category in WebsiteCategory.entries) {
                val domains = WebsiteCatalog.BUILTIN_DOMAINS[category].orEmpty()
                val arr = LongArray(domains.size)
                var i = 0
                for (d in domains) {
                    arr[i++] = DomainBlockMatcher.fnv1a64(d)
                }
                Arrays.sort(arr)
                map[category] = deduplicateSorted(arr)
            }
            return map
        }
    }
}
