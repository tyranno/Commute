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

    /** Room runs a multi-entity delete inside one transaction, so a batch either lands whole or not
     * at all — no half-cleared pile of duplicates if something fails midway. */
    @Delete
    suspend fun delete(events: List<CommuteEvent>)

    @Query("DELETE FROM commute_events")
    suspend fun deleteAll()

    @Query("SELECT * FROM commute_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CommuteEvent>>

    @Query("SELECT * FROM commute_events ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<CommuteEvent>

    /** Newest event that still counts. Excluded rows are skipped: the service uses this as the
     * floor for backdating and to spot a revertible auto-퇴근, and a record the user has taken out
     * of their history shouldn't steer either. */
    @Query("SELECT * FROM commute_events WHERE excluded = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLast(): CommuteEvent?
}
