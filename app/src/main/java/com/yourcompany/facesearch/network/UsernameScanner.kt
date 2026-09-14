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
        TargetPlatform("Linktree", "https://linktr.ee/%s"),
        TargetPlatform("Threads", "https://www.threads.net/@%s"),
        TargetPlatform("Bluesky", "https://bsky.app/profile/%s.bsky.social"),
        TargetPlatform("Mastodon", "https://mastodon.social/@%s"),
        TargetPlatform("Snapchat", "https://www.snapchat.com/add/%s"),
        TargetPlatform("Quora", "https://www.quora.com/profile/%s"),
        TargetPlatform("Behance", "https://www.behance.net/%s"),
        TargetPlatform("Dev.to", "https://dev.to/%s"),
        TargetPlatform("SoundCloud", "https://soundcloud.com/%s"),
        TargetPlatform("Spotify", "https://open.spotify.com/user/%s"),
        TargetPlatform("Keybase", "https://keybase.io/%s"),
        TargetPlatform("YouTube", "https://www.youtube.com/@%s"),
        TargetPlatform("Flickr", "https://www.flickr.com/people/%s"),
        TargetPlatform("Tumblr", "https://%s.tumblr.com"),
        TargetPlatform("VK", "https://vk.com/%s"),
        TargetPlatform("VSCO", "https://vsco.co/%s"),
        TargetPlatform("Patreon", "https://www.patreon.com/%s"),
        TargetPlatform("Substack", "https://%s.substack.com"),
        TargetPlatform("GitLab", "https://gitlab.com/%s"),
        TargetPlatform("Stack Overflow", "https://stackoverflow.com/users/%s"),
        TargetPlatform("HackerNews", "https://news.ycombinator.com/user?id=%s"),
        TargetPlatform("Product Hunt", "https://www.producthunt.com/@%s"),
        TargetPlatform("Dribbble", "https://dribbble.com/%s"),
        TargetPlatform("OnlyFans", "https://onlyfans.com/%s"),
        TargetPlatform("Fansly", "https://fansly.com/%s")
    )

    suspend fun scanUsername(
        rawUsername: String,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        val username = rawUsername.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "")
            .trim()

        if (username.length < 3 || username.all { it.isDigit() }) return@withContext emptyList()

        onLog("Scanning username handles for '@$username' across ${platforms.size} social networks...")

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
