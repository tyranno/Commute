package com.commute.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CommuteDao {
    /** Restore in one transaction so a failure part-way can't leave the user with their entire
     * history deleted and nothing inserted — as separate calls, a throw on insert was
     * unrecoverable data loss reported only as a "복원 실패" toast. */
    @Transaction
    suspend fun replaceAll(events: List<CommuteEvent>) {
        deleteAll()
        insertAll(events)
    }

    @Insert
    suspend fun insert(event: CommuteEvent): Long

    @Insert
    suspend fun insertAll(events: List<CommuteEvent>)

    @Update
    suspend fun update(event: CommuteEvent)

    @Delete
    suspend fun delete(event: CommuteEvent)

    @Query("DELETE FROM commute_events")
    suspend fun deleteAll()

    @Query("SELECT * FROM commute_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CommuteEvent>>

    @Query("SELECT * FROM commute_events ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<CommuteEvent>

    @Query("SELECT * FROM commute_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLast(): CommuteEvent?
}
