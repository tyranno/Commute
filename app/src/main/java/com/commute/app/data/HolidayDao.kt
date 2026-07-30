package com.commute.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HolidayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(holiday: Holiday)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(holidays: List<Holiday>)

    @Delete
    suspend fun delete(holiday: Holiday)

    /** Clears every [HolidaySource.AUTO] row whose date falls in `[fromInclusive, toExclusive)` —
     * run right before re-inserting a fresh fetch for that range, so a year that no longer has a
     * holiday on some date (a rare but real possibility, e.g. a corrected calendar) doesn't leave
     * the stale row behind. [HolidaySource.CUSTOM] rows are untouched no matter the range. */
    @Query("DELETE FROM holidays WHERE source = 'AUTO' AND date >= :fromInclusive AND date < :toExclusive")
    suspend fun deleteAutoInRange(fromInclusive: Long, toExclusive: Long)

    @Query("DELETE FROM holidays")
    suspend fun deleteAll()

    @Query("SELECT * FROM holidays ORDER BY date ASC")
    fun observeAll(): Flow<List<Holiday>>

    @Query("SELECT * FROM holidays ORDER BY date ASC")
    suspend fun getAllOnce(): List<Holiday>
}
