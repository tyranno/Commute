package com.commute.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CommuteEvent::class, LeaveEntry::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class CommuteDatabase : RoomDatabase() {
    abstract fun commuteDao(): CommuteDao
    abstract fun leaveDao(): LeaveDao

    companion object {
        @Volatile
        private var INSTANCE: CommuteDatabase? = null

        /** Adds the 연차/반차/외출 table without touching commute_events. Real recorded history now
         * exists on-device (it was hand-recovered once already), so a destructive migration here
         * would wipe it — this migration preserves it by only creating the new table. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `leave_entries` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`type` TEXT NOT NULL, " +
                        "`date` INTEGER NOT NULL, " +
                        "`startMinute` INTEGER, " +
                        "`endMinute` INTEGER, " +
                        "`note` TEXT NOT NULL)"
                )
            }
        }

        fun getInstance(context: Context): CommuteDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CommuteDatabase::class.java,
                    "commute.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    // Last-resort only: real data exists now, so proper migrations (above) are the
                    // path across schema changes. This just avoids a crash-loop if an unforeseen
                    // version gap ever appears with no migration.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
