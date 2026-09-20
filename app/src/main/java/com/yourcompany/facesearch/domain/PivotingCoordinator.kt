package com.yourcompany.facesearch.domain

import com.yourcompany.facesearch.data.local.ScanResultEntity
import java.util.regex.Pattern

data class PivotTask(
    val tool: String,
    val target: String,
    val pivotSource: String
)

object PivotingCoordinator {
    private val handlePattern = Pattern.compile("(?<=/@|/user/|/u/|instagram\\.com/|twitter\\.com/|github\\.com/)[a-zA-Z0-9._-]{2,32}")

    /**
     * Extracts secondary targets (alternate usernames/handles) from initial scan results
     * to execute recursive OSINT pivoting sweeps.
     */
    fun extractPivotTasks(results: List<ScanResultEntity>): List<PivotTask> {
        val tasks = mutableSetOf<PivotTask>()

        results.forEach { entity ->
            val url = entity.profileUrl
            val matcher = handlePattern.matcher(url)
            while (matcher.find()) {
                val handle = matcher.group()
                if (handle.isNotBlank() && handle.length > 2) {
                    tasks.add(
                        PivotTask(
                            tool = "sherlock",
                            target = handle,
                            pivotSource = "${entity.platform} (${entity.queryTarget})"
                        )
                    )
                }
            }
        }

        return tasks.toList()
    }
}
