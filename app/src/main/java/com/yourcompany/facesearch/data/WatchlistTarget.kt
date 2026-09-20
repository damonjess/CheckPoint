package com.yourcompany.facesearch.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watchlist_targets")
data class WatchlistTarget(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val probeImagePath: String,
    val probeHash: String,
    val pollingIntervalHours: Long = 24L,
    val lastCheckedTimestamp: Long = 0L,
    val lastResultCount: Int = 0
)
