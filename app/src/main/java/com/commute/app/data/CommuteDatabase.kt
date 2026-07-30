package com.commute.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CommuteEvent::class, LeaveEntry::class, Holiday::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class CommuteDatabase : RoomDatabase() {
    abstract fun commuteDao(): CommuteDao
    abstract fun leaveDao(): LeaveDao
    abstract fun holidayDao(): HolidayDao

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

        /** Adds [CommuteEvent.excluded]. A plain ADD COLUMN with a default, so every existing
         * record stays exactly as it is and simply starts out not-excluded. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `commute_events` ADD COLUMN `excluded` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds the 공휴일/사용자 지정 휴일 table — additive, same reasoning as MIGRATION_2_3. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `holidays` (" +
                        "`date` INTEGER NOT NULL PRIMARY KEY, " +
                        "`name` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL)"
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
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    // Last-resort only: real data exists now, so proper migrations (above) are the
                    // path across schema changes. This just avoids a crash-loop if an unforeseen
                    // version gap ever appears with no migration.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
