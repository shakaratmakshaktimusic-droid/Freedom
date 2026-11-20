package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AllowedWebsiteEntity
import com.example.data.local.AutoLearnedHashEntity
import com.example.data.local.BlocklistAllowedAppEntity
import com.example.data.local.BlocklistProfileEntity
import com.example.data.local.CustomBlockedWebsiteEntity
import com.example.data.local.FastBlockStateStore
import com.example.data.local.FreedomDatabase
import com.example.data.local.ScheduleEntity
import com.example.domain.DndMode
import com.example.domain.DomainBlockMatcher
import com.example.domain.FilterUpdateFrequency
import com.example.domain.ScheduleCalculator
import com.example.domain.SettingsWaitStateMachine
import com.example.domain.ThemeMode
import com.example.domain.WebsiteCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ExampleRobolectricTest {

    @Test
    fun appResources_matchFreedomName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Freedom", context.getString(R.string.app_name))
    }

    @Test
    fun fastBlockStateStore_persistsZeroVpnSessionPreferencesAndSettingsUnlock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = FastBlockStateStore(context)

        store.writeSession(
            isActive = true,
            sessionId = 42L,
            blocklistId = 7L,
            label = "Deep Work",
            endWallTimeMs = 999_999L,
            endElapsedRealtimeMs = 500_000L,
            strictMode = true,
            fullInternetBrowsingBlock = true,
            allowedPackages = setOf("com.android.chrome", "com.example.notes"),
            allowedWebPatterns = setOf("wikipedia.org", "youtube.com/@veritasium"),
            customBlockedDomains = setOf("distracting.example"),
            blockedWebCategories = setOf(WebsiteCategory.SOCIAL, WebsiteCategory.ADULT)
        )

        store.setThemeMode(ThemeMode.DARK)
        store.setDndEnabled(true)
        store.setDndMode(DndMode.ALARMS_ONLY)
        store.setHideBlockedNotificationsEnabled(true)
        store.setFilterUpdateFrequency(FilterUpdateFrequency.WEEKLY)

        val expiry = store.grantTemporarySettingsUnlock(nowElapsedRealtimeMs = 100_000L)
        assertEquals(100_000L + SettingsWaitStateMachine.TEMPORARY_UNLOCK_DURATION_MS, expiry)

        val reloadedStore = FastBlockStateStore(context)
        val snap = reloadedStore.readSnapshot()
        assertTrue(snap.isActive)
        assertEquals(42L, snap.sessionId)
        assertEquals(7L, snap.blocklistId)
        assertEquals("Deep Work", snap.label)
        assertTrue(snap.strictMode)
        assertTrue(snap.fullInternetBrowsingBlock)
        assertTrue("com.example.notes" in snap.allowedPackages)
        assertTrue("wikipedia.org" in snap.allowedWebPatterns)
        assertTrue("distracting.example" in snap.customBlockedDomains)
        assertEquals(setOf(WebsiteCategory.SOCIAL, WebsiteCategory.ADULT), snap.blockedWebCategories)
        assertEquals(expiry, snap.settingsUnlockedUntilElapsedMs)
        assertTrue(snap.enableDndDuringSession)
        assertTrue(snap.hideBlockedNotifications)

        assertEquals(ThemeMode.DARK, reloadedStore.getThemeMode())
        assertEquals(DndMode.ALARMS_ONLY, reloadedStore.getDndMode())
        assertEquals(FilterUpdateFrequency.WEEKLY, reloadedStore.getFilterUpdateFrequency())

        reloadedStore.clearSession()
        val cleared = reloadedStore.readSnapshot()
        assertFalse(cleared.isActive)
        assertEquals(0L, cleared.settingsUnlockedUntilElapsedMs)
    }

    @Test
    fun roomDatabaseV3_crudForBlocklistsAllowedWebsitesAutoLearnedHashesAndSchedules() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FreedomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.freedomDao()

            // 1. Reusable Blocklist Profile + Allowed Apps
            val blocklistId = dao.insertBlocklist(
                BlocklistProfileEntity(
                    name = "Deep Study",
                    fullInternetBrowsingBlock = true,
                    blockedWebCategoriesCsv = WebsiteCategory.toCsv(
                        setOf(WebsiteCategory.SOCIAL, WebsiteCategory.ADULT, WebsiteCategory.GAMBLING)
                    )
                )
            )
            dao.upsertBlocklistAllowedApp(
                BlocklistAllowedAppEntity(
                    blocklistId = blocklistId,
                    packageName = "com.example.anki",
                    appLabel = "AnkiDroid"
                )
            )
            val allowedApps = dao.getAllowedPackagesForBlocklist(blocklistId)
            assertEquals(listOf("com.example.anki"), allowedApps)

            // 2. Website Allowlist (URL Exceptions) + Custom Blocked Websites
            dao.upsertAllowedWebsite(AllowedWebsiteEntity(pattern = "wikipedia.org", isPathRule = false))
            dao.upsertAllowedWebsite(AllowedWebsiteEntity(pattern = "youtube.com/@3blue1brown", isPathRule = true))
            val allowPatterns = dao.getAllowedWebsitePatterns().toSet()
            assertEquals(setOf("wikipedia.org", "youtube.com/@3blue1brown"), allowPatterns)

            dao.upsertCustomBlockedWebsite(CustomBlockedWebsiteEntity(domain = "distracting-site.com"))
            assertEquals(listOf("distracting-site.com"), dao.getCustomBlockedDomains())

            // 3. Hidden 64-bit Auto-Learned Domain Hashes
            val hash64 = DomainBlockMatcher.fnv1a64("unknown-mirror.xyz")
            dao.insertAutoLearnedHash(
                AutoLearnedHashEntity(domainHash64 = hash64, categoryId = WebsiteCategory.ADULT.id)
            )
            val learned = dao.getAllAutoLearnedHashes()
            assertEquals(1, learned.size)
            assertEquals(hash64, learned.first().domainHash64)

            // 4. Both Recurring and One-Time "Later" Schedules
            val recurringId = dao.insertSchedule(
                ScheduleEntity(
                    name = "Weekday Work",
                    blocklistId = blocklistId,
                    isRecurring = true,
                    daysMask = ScheduleCalculator.WEEKDAYS_MASK,
                    startMinuteOfDay = 9 * 60,
                    endMinuteOfDay = 17 * 60,
                    strictMode = true,
                    enabled = true
                )
            )
            val laterId = dao.insertSchedule(
                ScheduleEntity(
                    name = "Tonight Exam Prep",
                    blocklistId = blocklistId,
                    isRecurring = false,
                    oneTimeStartMs = 1_000_000L,
                    oneTimeEndMs = 2_000_000L,
                    strictMode = true,
                    enabled = true
                )
            )
            val enabled = dao.getEnabledSchedules()
            assertEquals(2, enabled.size)
            assertTrue(enabled.any { it.id == recurringId && it.isRecurring })
            assertTrue(enabled.any { it.id == laterId && !it.isRecurring })

            // Expired one-time schedule auto-disable
            dao.disableExpiredOneTimeSchedules(nowMs = 2_500_000L)
            val remainingEnabled = dao.getEnabledSchedules()
            assertEquals(1, remainingEnabled.size)
            assertEquals(recurringId, remainingEnabled.first().id)
        } finally {
            db.close()
        }
    }
}
