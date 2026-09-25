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
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val urlPattern = Pattern.compile("class=\"[^\"]*result__url[^\"]*\"[^>]*href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
    private val titlePattern = Pattern.compile("<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE)
    private val ddgRedirectPattern = Pattern.compile("uddg=([^&]+)")

    // Fallback: parse result links from the JSON API endpoint
    private val jsonApiUrlPattern = Pattern.compile("\"c\":\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE)

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
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://html.duckduckgo.com/")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }

                val html = response.body?.string().orEmpty()
                if (html.isBlank()) return@withContext emptyList()

                val results = mutableListOf<SerpVisualMatch>()

                // Parse result URLs
                val matches = urlPattern.matcher(html)
                val titles = titlePattern.matcher(html)

                val extractedTitles = mutableListOf<String>()
                while (titles.find()) {
                    val rawTitle = titles.group(1).orEmpty()
                        .replace(Regex("<[^>]*>"), "")
                        .replace("&amp;", "&")
                        .replace("&quot;", "\"")
                        .replace("&#39;", "'")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
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

                // Fallback: if HTML parsing found nothing, try the JSON API endpoint
                if (results.isEmpty()) {
                    val jsonRequest = Request.Builder()
                        .url("https://duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36")
                        .get()
                        .build()

                    try {
                        client.newCall(jsonRequest).execute().use { jsonResponse ->
                            val json = jsonResponse.body?.string().orEmpty()
                            val jsonMatcher = jsonApiUrlPattern.matcher(json)
                            while (jsonMatcher.find()) {
                                val rawHref = jsonMatcher.group(1)
                                if (rawHref != null && rawHref.contains(siteDomain) && !rawHref.contains("duckduckgo.com")) {
                                    val platform = SocialMediaDetector.detectPlatform(rawHref)
                                    results.add(
                                        SerpVisualMatch(
                                            title = "$siteDomain - $keyword",
                                            link = rawHref,
                                            source = "DuckDuckGo (${platform.name})",
                                            thumbnail = null,
                                            score = platform.baseScore + 100
                                        )
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
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
