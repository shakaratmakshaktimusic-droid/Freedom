package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BlocklistProfileEntity::class,
        BlocklistAllowedAppEntity::class,
        AllowedWebsiteEntity::class,
        CustomBlockedWebsiteEntity::class,
        AutoLearnedHashEntity::class,
        FilterFeedStatusEntity::class,
        ScheduleEntity::class,
        FocusSessionEntity::class,
        BlockEventEntity::class,
        ApprovedYouTubeChannelEntity::class,
        YouTubeVideoChannelCacheEntity::class,
        SafeSearchProviderRuleEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class FreedomDatabase : RoomDatabase() {
    abstract fun freedomDao(): FreedomDao

    companion object {
        @Volatile
        private var INSTANCE: FreedomDatabase? = null

        /**
         * Non-destructive upgrade from v3 -> v4 so existing users updating via the
         * permanent release APK preserve all blocklists, allowed apps, allowed websites,
         * schedules, and session history intact.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `approved_youtube_channels` (
                        `channelId` TEXT NOT NULL,
                        `handle` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `avatarUrl` TEXT NOT NULL,
                        `subscriberSummary` TEXT NOT NULL,
                        `verifiedSource` TEXT NOT NULL,
                        `verifiedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`channelId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `youtube_video_channel_cache` (
                        `videoId` TEXT NOT NULL,
                        `channelId` TEXT NOT NULL,
                        `channelTitle` TEXT NOT NULL,
                        `verifiedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `safesearch_provider_rules` (
                        `providerId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `hostsCsv` TEXT NOT NULL,
                        `searchPathsCsv` TEXT NOT NULL,
                        `strictParamKey` TEXT NOT NULL,
                        `strictParamValue` TEXT NOT NULL,
                        `unsafeParamValuesCsv` TEXT NOT NULL,
                        `settingsPathsCsv` TEXT NOT NULL,
                        `imageIndicatorsCsv` TEXT NOT NULL,
                        `enforceMethod` TEXT NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`providerId`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): FreedomDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FreedomDatabase::class.java,
                    "freedom_blocker.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
