package com.yourcompany.facesearch.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScanResultDao {
    @Query("SELECT * FROM scan_results WHERE queryTarget = :target")
    suspend fun getPreviousResultsForTarget(target: String): List<ScanResultEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResults(results: List<ScanResultEntity>)
}
