package com.yourcompany.facesearch.domain

import android.net.Uri
import com.yourcompany.facesearch.data.local.ScanResultEntity
import com.yourcompany.facesearch.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

data class PivotTask(
    val tool: String,
    val target: String,
    val pivotSource: String
)

object PivotingCoordinator {
    // Matches standard handles preceded by @ or domain-specific user paths
    private val handlePattern = Pattern.compile("(?:@|/user/|/u/|instagram\\.com/|twitter\\.com/|x\\.com/|github\\.com/|tiktok\\.com/@)([a-zA-Z0-9._-]{2,32})")

    // Matches basic email structures
    private val emailPattern = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

    /**
     * Phase 1: Extracts secondary targets (alternate usernames/emails) from the URLs and
     * page titles of the initial VERIFIED visual scan results.
     */
    fun extractPivotTasks(verifiedResults: List<ScanResultEntity>): List<PivotTask> {
        val tasks = mutableSetOf<PivotTask>()

        // Block any URL pointing to an individual media item, status, or post container
        val mediaPathBlacklist = listOf(
            "/p/", "/reel/", "/reels/", "/tv/", "/status/",
            "/video/", "/watch", "/shorts/", "/comments/"
        )

        verifiedResults.forEach { entity ->
            val url = entity.profileUrl

            // Skip candidate URLs that are posts rather than profile roots
            if (mediaPathBlacklist.any { url.contains(it, ignoreCase = true) }) {
                return@forEach
            }

            // Extract Usernames
            val handleMatcher = handlePattern.matcher(url)
            while (handleMatcher.find()) {
                val handle = handleMatcher.group(1)?.trimEnd('.') ?: continue
                if (handle.isNotBlank() && handle.length > 2 && !isGenericPath(handle)) {
                    tasks.add(PivotTask("sherlock", handle, "Username extracted from ${entity.platform}"))
                }
            }

            // Extract Emails from page titles or snippet hints if available
            val emailMatcher = emailPattern.matcher(entity.queryTarget)
            while (emailMatcher.find()) {
                val email = emailMatcher.group()
                if (email.isNotBlank()) {
                    tasks.add(PivotTask("holehe", email, "Email extracted from metadata"))
                }
            }
        }

        return tasks.toList()
    }

    /**
     * Phase 2: Executes the extracted pivot tasks in parallel against the local Termux backend.
     */
    suspend fun executePivots(
        tasks: List<PivotTask>,
        backendUrl: String = "http://127.0.0.1:3000",
        onLog: (String) -> Unit = {}
    ): List<ScanResultEntity> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScanResultEntity>()
        val api = RetrofitClient.getInstance(backendUrl)

        onLog("[*] PivotingCoordinator initiated. Executing ${tasks.size} deep-dive tasks...")

        // Execute in parallel batches to prevent overwhelming the local Node.js server
        tasks.chunked(3).forEach { batch ->
            coroutineScope {
                val deferredTasks = batch.map { task ->
                    async {
                        try {
                            onLog("[+] Running ${task.tool} against target: ${task.target}")

                            val response = api.executeCommand(
                                mapOf(
                                    "command" to task.tool,
                                    "args" to listOf(task.target)
                                )
                            )

                            if (response.isSuccessful) {
                                val body = response.body()
                                val output = body?.get("output")?.toString() ?: ""

                                // Parse the stdout from the Termux command back into entity objects
                                parseCommandOutput(task, output).also { parsed ->
                                    if (parsed.isNotEmpty()) {
                                        onLog("✓ ${task.tool} pivot successful: Discovered ${parsed.size} new nodes for ${task.target}")
                                    }
                                }
                            } else {
                                onLog("⚠ Pivot task failed for ${task.target}: HTTP ${response.code()}")
                                emptyList<ScanResultEntity>()
                            }
                        } catch (e: Exception) {
                            onLog("⚠ Error executing pivot for ${task.target}: ${e.message}")
                            emptyList<ScanResultEntity>()
                        }
                    }
                }

                results.addAll(deferredTasks.awaitAll().flatten())
            }
        }

        onLog("[✓] Recursive pivoting complete. Discovered ${results.size} total secondary nodes.")
        return@withContext results
    }

    /**
     * Phase 3: Parses the raw stdout from Sherlock/Holehe back into structured data.
     */
    private fun parseCommandOutput(task: PivotTask, stdout: String): List<ScanResultEntity> {
        val newNodes = mutableListOf<ScanResultEntity>()
        val timestamp = System.currentTimeMillis()

        if (task.tool == "sherlock") {
            // Sherlock outputs found profiles generally in the format: "[+] Platform: https://url..."
            val lines = stdout.split("\n")
            lines.forEach { line ->
                if (line.contains("http", ignoreCase = true)) {
                    val url = line.substringAfter("http").let { "http$it" }.trim()
                    if (isValidUrl(url)) {
                        newNodes.add(
                            ScanResultEntity(
                                queryTarget = task.target,
                                platform = OutputParser.extractPlatform(url),
                                profileUrl = url,
                                timestamp = timestamp,
                                confidenceScore = 0.85f,
                                confidenceTier = "PIVOT_DISCOVERY"
                            )
                        )
                    }
                }
            }
        } else if (task.tool == "holehe") {
            // Holehe outputs "[+] email@domain.com is used on Platform"
            val lines = stdout.split("\n")
            lines.forEach { line ->
                if (line.contains("[+]") && line.contains("used on", ignoreCase = true)) {
                    val platform = line.substringAfter("used on").trim()
                    newNodes.add(
                        ScanResultEntity(
                            queryTarget = task.target,
                            platform = platform,
                            profileUrl = "holehe_discovery://${platform.lowercase()}",
                            timestamp = timestamp,
                            confidenceScore = 0.90f,
                            confidenceTier = "PIVOT_DISCOVERY"
                        )
                    )
                }
            }
        }

        return newNodes
    }

    private fun isGenericPath(handle: String): Boolean {
        val generics = setOf(
            // General site paths
            "home", "profile", "login", "signup", "user", "explore", "search", "about",
            // Content paths (stops the 'reel' bug)
            "reel", "reels", "p", "post", "posts", "shorts", "watch", "status", "video",
            "videos", "photo", "photos", "story", "stories", "tv", "channel", "c",
            // Common language/country codes
            "en", "us", "uk", "fr", "es",
            // Generic publisher/meme names to ignore
            "news", "entertainment", "meme", "memes", "daily", "pics"
        )

        if (generics.contains(handle.lowercase())) return true

        // In PivotingCoordinator.kt -> isGenericPath()
        // Alphanumeric strings with mixed case and no vowels/separators are usually post shortcodes
        if (handle.length in 8..15 && handle.none { it == '_' || it == '.' } && handle.count { it.isDigit() } >= 2) {
            return true
        }

        return false
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val parsed = Uri.parse(url)
            parsed.scheme in listOf("http", "https") && !parsed.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }
}
