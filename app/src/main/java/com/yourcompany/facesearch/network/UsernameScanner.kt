package com.yourcompany.facesearch.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object UsernameScanner {

    private val fastClient = NetworkProxyConfig.applyDesktopBrowserUserAgent(
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
    ).build()

    private data class TargetPlatform(
        val name: String,
        val urlTemplate: String,
        val customCheckUrl: ((String) -> String)? = null
    )

    private val platforms = listOf(
        // Major Social & Messaging
        TargetPlatform("GitHub", "https://github.com/%s"),
        TargetPlatform("Reddit", "https://www.reddit.com/user/%s"),
        TargetPlatform("Telegram", "https://t.me/%s"),
        TargetPlatform("X / Twitter", "https://x.com/%s"),
        TargetPlatform("Instagram", "https://www.instagram.com/%s/"),
        TargetPlatform("TikTok", "https://www.tiktok.com/@%s"),
        TargetPlatform("Pinterest", "https://www.pinterest.com/%s/"),
        TargetPlatform("Medium", "https://medium.com/@%s"),
        TargetPlatform("Twitch", "https://www.twitch.tv/%s"),
        TargetPlatform("Linktree", "https://linktr.ee/%s"),
        TargetPlatform("Threads", "https://www.threads.net/@%s"),
        TargetPlatform("Bluesky", "https://bsky.app/profile/%s.bsky.social", customCheckUrl = { "https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=$it.bsky.social" }),
        TargetPlatform("Mastodon", "https://mastodon.social/@%s"),
        TargetPlatform("Snapchat", "https://www.snapchat.com/add/%s"),
        TargetPlatform("Quora", "https://www.quora.com/profile/%s"),

        // Developer & Tech Ecosystem
        TargetPlatform("GitLab", "https://gitlab.com/%s"),
        TargetPlatform("Bitbucket", "https://bitbucket.org/%s/"),
        TargetPlatform("Stack Overflow", "https://stackoverflow.com/users/%s"),
        TargetPlatform("HackerNews", "https://news.ycombinator.com/user?id=%s"),
        TargetPlatform("Dev.to", "https://dev.to/%s"),
        TargetPlatform("CodePen", "https://codepen.io/%s"),
        TargetPlatform("Replit", "https://replit.com/@%s"),
        TargetPlatform("npm", "https://www.npmjs.com/~%s"),
        TargetPlatform("PyPI", "https://pypi.org/user/%s"),
        TargetPlatform("DockerHub", "https://hub.docker.com/u/%s"),
        TargetPlatform("LeetCode", "https://leetcode.com/u/%s/"),
        TargetPlatform("HackerRank", "https://www.hackerrank.com/profile/%s"),
        TargetPlatform("Codeforces", "https://codeforces.com/profile/%s"),
        TargetPlatform("Kaggle", "https://www.kaggle.com/%s"),
        TargetPlatform("Product Hunt", "https://www.producthunt.com/@%s"),
        TargetPlatform("Keybase", "https://keybase.io/%s"),

        // Design, Music & Content Media
        TargetPlatform("Behance", "https://www.behance.net/%s"),
        TargetPlatform("Dribbble", "https://dribbble.com/%s"),
        TargetPlatform("SoundCloud", "https://soundcloud.com/%s"),
        TargetPlatform("Spotify", "https://open.spotify.com/user/%s"),
        TargetPlatform("Bandcamp", "https://bandcamp.com/%s"),
        TargetPlatform("YouTube", "https://www.youtube.com/@%s"),
        TargetPlatform("Vimeo", "https://vimeo.com/%s"),
        TargetPlatform("Rumble", "https://rumble.com/c/%s"),
        TargetPlatform("Kick", "https://kick.com/%s"),
        TargetPlatform("Flickr", "https://www.flickr.com/people/%s"),
        TargetPlatform("Tumblr", "https://%s.tumblr.com"),
        TargetPlatform("VSCO", "https://vsco.co/%s"),

        // Gaming, Hobbies & Niche Communities
        TargetPlatform("Steam", "https://steamcommunity.com/id/%s"),
        TargetPlatform("Duolingo", "https://www.duolingo.com/profile/%s"),
        TargetPlatform("Chess.com", "https://www.chess.com/member/%s"),
        TargetPlatform("Lichess", "https://lichess.org/@/%s"),
        TargetPlatform("Letterboxd", "https://letterboxd.com/%s/"),
        TargetPlatform("Goodreads", "https://www.goodreads.com/%s"),
        TargetPlatform("Roblox", "https://www.roblox.com/user.aspx?username=%s"),
        TargetPlatform("Speedrun.com", "https://www.speedrun.com/user/%s"),

        // Creator Economy & Monetization
        TargetPlatform("Patreon", "https://www.patreon.com/%s"),
        TargetPlatform("Substack", "https://%s.substack.com"),
        TargetPlatform("BuyMeACoffee", "https://www.buymeacoffee.com/%s"),
        TargetPlatform("Ko-fi", "https://ko-fi.com/%s"),
        TargetPlatform("OnlyFans", "https://onlyfans.com/%s"),
        TargetPlatform("Fansly", "https://fansly.com/%s"),

        // Regional & Global Personal Profiles
        TargetPlatform("VK", "https://vk.com/%s"),
        TargetPlatform("About.me", "https://about.me/%s"),
        TargetPlatform("Gravatar", "https://en.gravatar.com/%s")
    )

    private val notFoundPhrases = listOf(
        "page not found",
        "user not found",
        "sorry, this page",
        "profile not found",
        "doesn't exist",
        "does not exist",
        "not available",
        "no longer available",
        "account suspended",
        "this profile is not available",
        "page isn't available",
        "content unavailable",
        "couldn't find",
        "this account doesn't exist",
        "the page you were looking for",
        "no such user",
        "nobody on reddit goes by that name",
        "404 not found",
        "member not found",
        "player not found"
    )

    suspend fun scanUsername(
        rawUsername: String,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        val username = rawUsername.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "")
            .trim()

        if (username.length < 2 || username.length > 30) {
            onLog("⚠ Username '$username' is too short or too long — skipping scan.")
            return@withContext emptyList()
        }
        if (username.all { it.isDigit() }) return@withContext emptyList()
        if (username.contains("http") || username.contains("www") || 
            username.contains("shutterstock") || username.contains("gettyimages") ||
            username.contains("alamy") || username.contains("istockphoto")) {
            onLog("⚠ Username looks like a URL or stock photo reference — skipping scan.")
            return@withContext emptyList()
        }

        onLog("Scanning username handles for '@$username' across ${platforms.size} platforms...")

        val activeProfiles = platforms.map { target ->
            async {
                val checkUrl = target.customCheckUrl?.invoke(username) ?: String.format(target.urlTemplate, username)
                val canonicalProfileUrl = String.format(target.urlTemplate, username)

                try {
                    val request = Request.Builder()
                        .url(checkUrl)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .get()
                        .build()

                    fastClient.newCall(request).execute().use { response ->
                        if (response.code in 200..299) {
                            val bodyText = response.body?.string()?.take(8000)?.lowercase() ?: ""

                            // Bluesky API check
                            if (target.name == "Bluesky") {
                                if (bodyText.contains("\"handle\"") || bodyText.contains("\"did\"")) {
                                    val platform = SocialMediaDetector.detectPlatform(canonicalProfileUrl)
                                    onLog("✓ Active profile handle found on ${target.name}: @$username")
                                    return@async SerpVisualMatch(
                                        title = "${target.name} Profile (@$username)",
                                        link = canonicalProfileUrl,
                                        source = "${target.name} (Direct Handle)",
                                        thumbnail = null,
                                        score = platform.baseScore + 500
                                    )
                                } else return@async null
                            }

                            // Telegram check
                            if (target.name == "Telegram") {
                                val isNotFoundTelegram = bodyText.contains("if you have telegram, you can contact") && !bodyText.contains("tgme_page_title")
                                if (isNotFoundTelegram) return@async null
                            }

                            // Check soft 404 phrases
                            if (notFoundPhrases.any { bodyText.contains(it) }) {
                                null
                            } else {
                                val platform = SocialMediaDetector.detectPlatform(canonicalProfileUrl)
                                onLog("✓ Active profile handle found on ${target.name}: @$username")
                                SerpVisualMatch(
                                    title = "${target.name} Profile (@$username)",
                                    link = canonicalProfileUrl,
                                    source = "${target.name} (Direct Handle)",
                                    thumbnail = null,
                                    score = platform.baseScore + 500
                                )
                            }
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
