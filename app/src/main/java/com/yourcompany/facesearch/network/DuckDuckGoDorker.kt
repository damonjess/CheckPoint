package com.yourcompany.facesearch.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object DuckDuckGoDorker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val urlPattern = Pattern.compile("class=\"[^\"]*result__url[^\"]*\"[^>]*href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
    private val titlePattern = Pattern.compile("<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE)
    private val ddgRedirectPattern = Pattern.compile("uddg=([^&]+)")

    suspend fun dork(
        siteDomain: String,
        keyword: String,
        onLog: (String) -> Unit = {}
    ): List<SerpVisualMatch> = withContext(Dispatchers.IO) {
        val query = "site:$siteDomain \"$keyword\""
        try {
            val formBody = FormBody.Builder()
                .add("q", query)
                .add("b", "")
                .add("kl", "us-en")
                .build()

            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/")
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile; rv:109.0) Gecko/120.0 Firefox/120.0")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://html.duckduckgo.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }

                val html = response.body?.string().orEmpty()
                if (html.isBlank()) return@withContext emptyList()

                val results = mutableListOf<SerpVisualMatch>()
                val matches = urlPattern.matcher(html)
                val titles = titlePattern.matcher(html)

                val extractedTitles = mutableListOf<String>()
                while (titles.find()) {
                    val rawTitle = titles.group(1).orEmpty()
                        .replace(Regex("<[^>]*>"), "")
                        .replace("&amp;", "&")
                        .replace("&quot;", "\"")
                        .replace("&#39;", "'")
                        .trim()
                    if (rawTitle.isNotBlank()) {
                        extractedTitles.add(rawTitle)
                    }
                }

                var idx = 0
                while (matches.find()) {
                    var rawHref = matches.group(1).orEmpty()
                    if (rawHref.contains("uddg=")) {
                        val m = ddgRedirectPattern.matcher(rawHref)
                        if (m.find()) {
                            rawHref = URLDecoder.decode(m.group(1).orEmpty(), "UTF-8")
                        }
                    }

                    if (rawHref.startsWith("http") && !rawHref.contains("duckduckgo.com")) {
                        val pageTitle = extractedTitles.getOrNull(idx) ?: "$siteDomain - $keyword"
                        val platform = SocialMediaDetector.detectPlatform(rawHref)
                        results.add(
                            SerpVisualMatch(
                                title = pageTitle,
                                link = rawHref,
                                source = "DuckDuckGo (${platform.name})",
                                thumbnail = null,
                                score = platform.baseScore + 100
                            )
                        )
                        idx++
                    }
                }

                if (results.isNotEmpty()) {
                    onLog("✓ DuckDuckGo OSINT: Found ${results.size} link(s) for '$siteDomain'")
                }
                results.distinctBy { it.link }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
