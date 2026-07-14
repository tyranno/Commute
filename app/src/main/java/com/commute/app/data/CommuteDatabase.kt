package com.commute.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [CommuteEvent::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class CommuteDatabase : RoomDatabase() {
    abstract fun commuteDao(): CommuteDao

    companion object {
        @Volatile
        private var INSTANCE: CommuteDatabase? = null

        fun getInstance(context: Context): CommuteDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CommuteDatabase::class.java,
                    "commute.db"
                )
                    // Pre-release app with no live-tested data yet; simplest path across schema
                    // changes is to drop and recreate rather than write a migration.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
