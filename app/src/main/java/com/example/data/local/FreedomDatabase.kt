package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
        BlockEventEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class FreedomDatabase : RoomDatabase() {
    abstract fun freedomDao(): FreedomDao

    companion object {
        @Volatile
        private var INSTANCE: FreedomDatabase? = null

        fun getInstance(context: Context): FreedomDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FreedomDatabase::class.java,
                    "freedom_blocker.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
