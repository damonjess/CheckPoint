package com.yourcompany.facesearch.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.facesearch.data.SolidSearchRepository
import com.yourcompany.facesearch.data.local.ScanResultDao
import com.yourcompany.facesearch.data.local.ScanResultEntity
import com.yourcompany.facesearch.domain.ConfidenceScoringEngine
import com.yourcompany.facesearch.domain.DifferentialAuditor
import com.yourcompany.facesearch.domain.IntelligenceEvidence
import com.yourcompany.facesearch.domain.OutputParser
import com.yourcompany.facesearch.service.OsintScanService
import com.yourcompany.facesearch.utils.OsintReportExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OsintScanViewModel(
    private val scanResultDao: ScanResultDao,
    private val searchRepository: SolidSearchRepository = SolidSearchRepository()
) : ViewModel() {

    private val _terminalLogs = MutableStateFlow("")
    val terminalLogs: StateFlow<String> = _terminalLogs.asStateFlow()

    private val _auditReport = MutableStateFlow<DifferentialAuditor.AuditReport?>(null)
    val auditReport: StateFlow<DifferentialAuditor.AuditReport?> = _auditReport.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun runTargetSearch(context: Context, tool: String, target: String) {
        if (_isScanning.value) return

        viewModelScope.launch {
            _isScanning.value = true
            _terminalLogs.value = ""
            
            // 1. Launch Android Foreground Service to protect execution against Doze mode
            startProtectedService(context, target)

            val logBuilder = StringBuilder()

            // 2. Execute via Solid Search Repository with timeout and mutex locks
            val success = searchRepository.executeSafeSearch(tool, target) { logLine ->
                logBuilder.appendLine(logLine)
                _terminalLogs.value = logBuilder.toString()
            }

            if (success) {
                // 3. Extract verified URLs using regex output parser
                val rawOutput = _terminalLogs.value
                val validUrls = OutputParser.extractValidUrls(rawOutput)
                val scoringEngine = ConfidenceScoringEngine()

                // 4. Map to database entities, score with ConfidenceScoringEngine, and execute differential audit
                val currentEntities = validUrls.map { url ->
                    val platform = OutputParser.extractPlatform(url)
                    val hasHandle = url.contains(target, ignoreCase = true)
                    val evidence = IntelligenceEvidence(
                        faceCosineSimilarity = 0.75f,
                        handleMatchScore = if (hasHandle) 0.9f else 0.5f,
                        keywordCoOccurrenceCount = if (hasHandle) 2 else 1,
                        hasVerifiedMetadata = platform != "Unknown"
                    )
                    val evaluated = scoringEngine.evaluateLead(url, evidence)

                    ScanResultEntity(
                        queryTarget = target,
                        platform = platform,
                        profileUrl = url,
                        timestamp = System.currentTimeMillis(),
                        confidenceScore = evaluated.finalProbability,
                        confidenceTier = evaluated.confidenceTier
                    )
                }

                val historicalEntities = scanResultDao.getPreviousResultsForTarget(target)
                val report = DifferentialAuditor.audit(currentEntities, historicalEntities)
                
                _auditReport.value = report

                // 5. Save latest snapshot to Room SQLite
                if (currentEntities.isNotEmpty()) {
                    scanResultDao.insertResults(currentEntities)
                }
            }

            _isScanning.value = false
            stopProtectedService(context)
        }
    }

    fun exportNetworkTopologyReport(
        context: Context,
        target: String,
        results: List<ScanResultEntity>,
        verifiedCount: Int,
        socialCount: Int,
        leadCount: Int
    ): Intent? {
        val file = OsintReportExporter.exportToPdfWithGraphSummary(
            context = context,
            target = target,
            results = results,
            verifiedCount = verifiedCount,
            socialCount = socialCount,
            leadCount = leadCount
        )

        return file?.let {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                it
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun startProtectedService(context: Context, target: String) {
        val intent = Intent(context, OsintScanService::class.java).apply {
            putExtra(OsintScanService.EXTRA_TARGET, target)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopProtectedService(context: Context) {
        val intent = Intent(context, OsintScanService::class.java)
        context.stopService(intent)
    }
}
