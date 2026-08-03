package com.commute.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticEventDao {
    @Insert
    suspend fun insert(event: DiagnosticEvent): Long

    @Query(
        "SELECT * FROM diagnostic_events WHERE timestamp >= :fromInclusive AND timestamp < :toExclusive " +
            "ORDER BY timestamp DESC"
    )
    fun observeBetween(fromInclusive: Long, toExclusive: Long): Flow<List<DiagnosticEvent>>

    /** Rolling-window cleanup — see `DIAGNOSTIC_RETENTION_MS` in `WifiMonitorService`. */
    @Query("DELETE FROM diagnostic_events WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
