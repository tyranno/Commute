package com.commute.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommuteDao {
    @Insert
    suspend fun insert(event: CommuteEvent): Long

    @Query("SELECT * FROM commute_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CommuteEvent>>

    @Query("SELECT * FROM commute_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLast(): CommuteEvent?
}
