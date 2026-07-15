package com.commute.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CommuteDao {
    @Insert
    suspend fun insert(event: CommuteEvent): Long

    @Update
    suspend fun update(event: CommuteEvent)

    @Delete
    suspend fun delete(event: CommuteEvent)

    @Query("SELECT * FROM commute_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CommuteEvent>>

    @Query("SELECT * FROM commute_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLast(): CommuteEvent?
}
