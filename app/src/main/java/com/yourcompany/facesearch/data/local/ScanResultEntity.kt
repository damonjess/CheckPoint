package com.yourcompany.facesearch.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_results")
data class ScanResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val queryTarget: String,
    val platform: String,
    val profileUrl: String,
    val timestamp: Long,
    val confidenceScore: Float = 0f,
    val confidenceTier: String = "LOW_CONFIDENCE_NOISE"
)
