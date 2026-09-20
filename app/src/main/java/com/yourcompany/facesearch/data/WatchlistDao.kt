package com.yourcompany.facesearch.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist_targets ORDER BY id DESC")
    fun getAllTargetsFlow(): Flow<List<WatchlistTarget>>

    @Query("SELECT * FROM watchlist_targets ORDER BY id DESC")
    suspend fun getAllTargets(): List<WatchlistTarget>

    @Query("SELECT * FROM watchlist_targets WHERE id = :id")
    suspend fun getTargetById(id: Long): WatchlistTarget?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTarget(target: WatchlistTarget): Long

    @Delete
    suspend fun deleteTarget(target: WatchlistTarget)

    @Query("UPDATE watchlist_targets SET lastCheckedTimestamp = :timestamp, lastResultCount = :resultCount WHERE id = :id")
    suspend fun updateCheckStats(id: Long, timestamp: Long, resultCount: Int)
}
