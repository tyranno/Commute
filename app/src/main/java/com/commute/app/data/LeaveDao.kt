package com.commute.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LeaveDao {
    @Insert
    suspend fun insert(entry: LeaveEntry): Long

    @Insert
    suspend fun insertAll(entries: List<LeaveEntry>)

    @Update
    suspend fun update(entry: LeaveEntry)

    @Delete
    suspend fun delete(entry: LeaveEntry)

    @Query("DELETE FROM leave_entries")
    suspend fun deleteAll()

    @Query("SELECT * FROM leave_entries ORDER BY date DESC")
    fun observeAll(): Flow<List<LeaveEntry>>

    @Query("SELECT * FROM leave_entries ORDER BY date ASC")
    suspend fun getAllOnce(): List<LeaveEntry>
}
