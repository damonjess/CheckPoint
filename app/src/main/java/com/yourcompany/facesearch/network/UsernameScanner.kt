package com.yourcompany.facesearch.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object UsernameScanner {

    private val fastClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class TargetPlatform(
        val name: String,
        val urlTemplate: String,
        val expectedMinStatus: Int = 200,
        val expectedMaxStatus: Int = 299
    )

    private val platforms = listOf(
        TargetPlatform("GitHub", "https://github.com/%s"),
        TargetPlatform("Instagram", "https://www.instagram.com/%s/"),
        TargetPlatform("X / Twitter", "https://x.com/%s"),
        TargetPlatform("TikTok", "https://www.tiktok.com/@%s"),
        TargetPlatform("Reddit", "https://www.reddit.com/user/%s"),
        TargetPlatform("Telegram", "https://t.me/%s"),
        TargetPlatform("Pinterest", "https://www.pinterest.com/%s/"),
        TargetPlatform("Medium", "https://medium.com/@%s"),
        TargetPlatform("Twitch", "https://www.twitch.tv/%s"),
        TargetPlatform("Linktree", "https://linktr.ee/%s")
    )

    suspend fun scanUsername(
        rawUsername: String,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        val username = rawUsername.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "")
            .trim()

        if (username.length < 3 || username.all { it.isDigit() }) return@withContext emptyList()

        onLog("Scanning username handles for '@$username' across 10 social networks...")

        val activeProfiles = platforms.map { target ->
            async {
                val profileUrl = String.format(target.urlTemplate, username)
                try {
                    val request = Request.Builder()
                        .url(profileUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .head()
                        .build()

                    fastClient.newCall(request).execute().use { response ->
                        if (response.code in target.expectedMinStatus..target.expectedMaxStatus) {
                            val platform = SocialMediaDetector.detectPlatform(profileUrl)
                            onLog("✓ Active profile handle found on ${target.name}: @$username")
                            SerpVisualMatch(
                                title = "${target.name} Profile (@$username)",
                                link = profileUrl,
                                source = "${target.name} (Direct Handle)",
                                thumbnail = null,
                                score = platform.baseScore + 500
                            )
                        } else null
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()

        if (activeProfiles.isEmpty()) {
            onLog("ℹ No active direct handles detected for '@$username'")
        }
        activeProfiles
    }
}
