package com.yourcompany.facesearch.domain

import com.yourcompany.facesearch.data.local.ScanResultEntity

object DifferentialAuditor {

    data class AuditReport(
        val newlyDiscovered: List<ScanResultEntity>,
        val previouslyKnown: List<ScanResultEntity>
    )

    fun audit(currentScan: List<ScanResultEntity>, historicalScan: List<ScanResultEntity>): AuditReport {
        val historicalUrls = historicalScan.map { it.profileUrl }.toSet()

        val newlyDiscovered = currentScan.filter { it.profileUrl !in historicalUrls }
        val previouslyKnown = currentScan.filter { it.profileUrl in historicalUrls }

        return AuditReport(newlyDiscovered, previouslyKnown)
    }
}
