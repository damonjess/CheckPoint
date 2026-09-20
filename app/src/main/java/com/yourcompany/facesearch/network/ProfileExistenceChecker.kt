package com.yourcompany.facesearch.network

import android.util.Log
import com.yourcompany.facesearch.data.ProfileConfidence
import com.yourcompany.facesearch.data.ProfileStatus
import com.yourcompany.facesearch.data.PublicProfileLead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks whether generated profile URLs likely exist by sending HTTP requests
 * and analysing the response status code and page title.
 *
 * Many social platforms return 200 for non-existent profiles (soft 404s), so
 * this checker also inspects page content for common "not found" indicators.
 */
class ProfileExistenceChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val completed = AtomicInteger(0)
    private var total = 0

    suspend fun checkLeads(
        leads: List<PublicProfileLead>,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> }
    ): List<PublicProfileLead> = withContext(Dispatchers.IO) {
        total = leads.size
        completed.set(0)

        // Process in batches of 15 to avoid overwhelming the network
        leads.chunked(15).flatMap { batch ->
            coroutineScope {
                batch.map { lead ->
                    async {
                        val result = checkSingle(lead)
                        val done = completed.incrementAndGet()
                        onProgress(done, total)
                        result
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun checkSingle(lead: PublicProfileLead): PublicProfileLead = withContext(Dispatchers.IO) {
        lead.status = ProfileStatus.CHECKING
        try {
            val request = Request.Builder()
                .url(lead.url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            lead.statusCode = response.code

            val body = response.body?.string() ?: ""
            val title = extractTitle(body)
            lead.pageTitle = title

            lead.status = when {
                response.code == 404 -> ProfileStatus.NOT_FOUND
                response.code in 200..299 && !isSoftNotFound(body, title, lead) -> {
                    lead.confidence = scoreConfidence(lead, title, body)
                    ProfileStatus.LIKELY_EXISTS
                }
                response.code in 300..399 -> {
                    // Redirect — treat as possible
                    lead.confidence = ProfileConfidence.POSSIBLE
                    ProfileStatus.LIKELY_EXISTS
                }
                response.code == 429 -> {
                    // Rate limited — leave as unchecked
                    lead.status = ProfileStatus.UNCHECKED
                    lead.confidence = ProfileConfidence.WEAK
                    ProfileStatus.UNCHECKED
                }
                else -> {
                    lead.confidence = ProfileConfidence.WEAK
                    if (response.code >= 500) ProfileStatus.ERROR else ProfileStatus.NOT_FOUND
                }
            }

            response.close()
        } catch (e: Exception) {
            Log.w(TAG, "Check failed for ${lead.url}: ${e.message}")
            lead.status = ProfileStatus.ERROR
            lead.confidence = ProfileConfidence.WEAK
        }
        lead
    }

    private fun extractTitle(html: String): String {
        val titleStart = html.indexOf("<title", ignoreCase = true)
        if (titleStart < 0) return ""
        val contentStart = html.indexOf(">", titleStart) + 1
        val titleEnd = html.indexOf("</title>", contentStart, ignoreCase = true)
        if (titleEnd < 0 || contentStart >= titleEnd) return ""
        return html.substring(contentStart, titleEnd).trim().replace("&amp;", "&")
    }

    private fun isSoftNotFound(body: String, title: String, lead: PublicProfileLead): Boolean {
        val lowerTitle = title.lowercase()
        val lowerBody = body.lowercase().take(5000)

        val notFoundPhrases = listOf(
            "page not found",
            "user not found",
            "sorry, this page",
            "profile not found",
            "doesn't exist",
            "does not exist",
            "not available",
            "no longer available",
            "account suspended",
            "account unavailable",
            "this profile is not available",
            "page isn't available",
            "content unavailable",
            "sorry, that page doesn",
            "couldn't find",
            "could not find",
            "not found - 404",
            "404",
            "sorry, page not found",
            "this account doesn't exist",
            "the page you were looking for"
        )

        // Check title first — more reliable
        if (notFoundPhrases.any { lowerTitle.contains(it) }) return true

        // Then check body, but only for platforms known to use soft 404s
        val softNotFoundPlatforms = setOf(
            "Instagram", "Facebook", "TikTok", "X / Twitter", "LinkedIn",
            "Pinterest", "OK.ru", "Roblox", "Gravatar"
        )
        if (lead.platform in softNotFoundPlatforms) {
            if (notFoundPhrases.any { lowerBody.contains(it) }) return true
        }

        return false
    }

    private fun scoreConfidence(lead: PublicProfileLead, title: String, body: String): ProfileConfidence {
        val handle = lead.handle.lowercase()
        val lowerTitle = title.lowercase()
        val lowerBody = body.lowercase().take(3000)

        // STRONG: page title or body contains the handle
        val handleInTitle = handle.isNotEmpty() && lowerTitle.contains(handle)
        val handleInBody = handle.isNotEmpty() && lowerBody.contains(handle)

        // STRONG: provided handle (not a name variant) + 200 response
        val isProvidedHandle = lead.strength == com.yourcompany.facesearch.data.ProfileLeadStrength.PROVIDED_HANDLE
        val isEmailDerived = lead.strength == com.yourcompany.facesearch.data.ProfileLeadStrength.EMAIL_DERIVED

        return when {
            handleInTitle && isProvidedHandle -> ProfileConfidence.STRONG
            handleInTitle -> ProfileConfidence.STRONG
            handleInBody && isProvidedHandle -> ProfileConfidence.STRONG
            handleInBody -> ProfileConfidence.POSSIBLE
            isEmailDerived -> ProfileConfidence.POSSIBLE
            isProvidedHandle -> ProfileConfidence.POSSIBLE
            else -> ProfileConfidence.WEAK
        }
    }

    companion object {
        private const val TAG = "ProfileChecker"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
