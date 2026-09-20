package com.yourcompany.facesearch.data

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.net.URLEncoder
import java.util.Locale

/**
 * Optional identity details supplied by the device owner. This is saved only in
 * the app-private files directory and is never uploaded by this store.
 */
data class IdentityProfile(
    val fullName: String = "",
    val aliases: String = "",
    val handles: String = "",
    val email: String = "",
    val phone: String = "",
    val city: String = "",
    val website: String = ""
)

enum class ProfileLeadStrength { PROVIDED_HANDLE, NAME_VARIANT, EMAIL_DERIVED }

enum class ProfileStatus { UNCHECKED, LIKELY_EXISTS, NOT_FOUND, CHECKING, ERROR }

enum class ProfileConfidence { STRONG, POSSIBLE, WEAK }

data class PublicProfileLead(
    val platform: String,
    val handle: String,
    val url: String,
    val strength: ProfileLeadStrength,
    val evidence: String,
    var status: ProfileStatus = ProfileStatus.UNCHECKED,
    var confidence: ProfileConfidence = ProfileConfidence.WEAK,
    var pageTitle: String = "",
    var statusCode: Int = 0
)

object IdentityProfileStore {
    private const val FILE_NAME = "identity_profile.json"
    private val gson = Gson()

    fun load(context: Context): IdentityProfile = try {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) IdentityProfile()
        else gson.fromJson(file.readText(), IdentityProfile::class.java) ?: IdentityProfile()
    } catch (_: Exception) {
        IdentityProfile()
    }

    fun save(context: Context, profile: IdentityProfile) {
        File(context.filesDir, FILE_NAME).writeText(gson.toJson(profile))
    }
}

/**
 * Produces manually reviewable public-profile routes from identity clues the
 * device owner supplied. It does not log into, scrape, or query a platform.
 */
object PublicProfileLeadGenerator {
    private const val MAX_VARIANTS = 12

    private data class PlatformTemplate(
        val name: String,
        val checkPath: String = "",
        val urlFor: (String) -> String
    )

    private val platforms = listOf(
        PlatformTemplate("Instagram") { h -> "https://www.instagram.com/$h/" },
        PlatformTemplate("Facebook") { h -> "https://www.facebook.com/$h" },
        PlatformTemplate("TikTok") { h -> "https://www.tiktok.com/@$h" },
        PlatformTemplate("Snapchat") { h -> "https://www.snapchat.com/add/$h" },
        PlatformTemplate("X / Twitter") { h -> "https://x.com/$h" },
        PlatformTemplate("LinkedIn") { h -> "https://www.linkedin.com/in/$h" },
        PlatformTemplate("GitHub") { h -> "https://github.com/$h" },
        PlatformTemplate("YouTube") { h -> "https://www.youtube.com/@$h" },
        PlatformTemplate("Reddit") { h -> "https://www.reddit.com/user/$h/" },
        PlatformTemplate("Threads") { h -> "https://www.threads.net/@$h" },
        PlatformTemplate("Bluesky") { h -> "https://bsky.app/profile/$h.bsky.social" },
        PlatformTemplate("Mastodon") { h -> "https://mastodon.social/@$h" },
        PlatformTemplate("Quora") { h -> "https://www.quora.com/profile/$h" },
        PlatformTemplate("Behance") { h -> "https://www.behance.net/$h" },
        PlatformTemplate("Dev.to") { h -> "https://dev.to/$h" },
        PlatformTemplate("SoundCloud") { h -> "https://soundcloud.com/$h" },
        PlatformTemplate("Spotify") { h -> "https://open.spotify.com/user/$h" },
        PlatformTemplate("Keybase") { h -> "https://keybase.io/$h" },
        PlatformTemplate("Flickr") { h -> "https://www.flickr.com/people/$h" },
        PlatformTemplate("Tumblr") { h -> "https://$h.tumblr.com" },
        PlatformTemplate("VK") { h -> "https://vk.com/$h" },
        PlatformTemplate("VSCO") { h -> "https://vsco.co/$h" },
        PlatformTemplate("Patreon") { h -> "https://www.patreon.com/$h" },
        PlatformTemplate("Substack") { h -> "https://$h.substack.com" },
        PlatformTemplate("GitLab") { h -> "https://gitlab.com/$h" },
        PlatformTemplate("Stack Overflow") { h -> "https://stackoverflow.com/users/$h" },
        PlatformTemplate("Product Hunt") { h -> "https://www.producthunt.com/@$h" },
        PlatformTemplate("Dribbble") { h -> "https://dribbble.com/$h" },
        PlatformTemplate("OnlyFans") { h -> "https://onlyfans.com/$h" },
        PlatformTemplate("Fansly") { h -> "https://fansly.com/$h" },
        PlatformTemplate("Linktree") { h -> "https://linktr.ee/$h" },
        PlatformTemplate("Twitch") { h -> "https://www.twitch.tv/$h" },
        PlatformTemplate("Kick") { h -> "https://kick.com/$h" },
        PlatformTemplate("Pinterest") { h -> "https://www.pinterest.com/$h/" },
        PlatformTemplate("Medium") { h -> "https://medium.com/@$h" },
        PlatformTemplate("HackerNews") { h -> "https://news.ycombinator.com/user?id=$h" },
        PlatformTemplate("OK.ru") { h -> "https://ok.ru/$h" },
        PlatformTemplate("Gravatar") { h -> "https://gravatar.com/$h" },
        PlatformTemplate("Steam") { h -> "https://steamcommunity.com/id/$h" },
        PlatformTemplate("Roblox") { h -> "https://www.roblox.com/user.aspx?username=$h" },
        PlatformTemplate("About.me") { h -> "https://about.me/$h" }
    )

    fun generate(profile: IdentityProfile): List<PublicProfileLead> {
        val suppliedHandles = splitValues(profile.handles)
            .map(::normalizeHandle)
            .filter { it.length >= 2 }
            .distinct()
        val nameVariants = generateNameVariants(profile)
            .filterNot { it in suppliedHandles }
            .take(MAX_VARIANTS)

        val leads = buildList {
            // Provided handles — strong leads
            suppliedHandles.forEach { handle ->
                platforms.forEach { platform ->
                    add(
                        PublicProfileLead(
                            platform = platform.name,
                            handle = handle,
                            url = platform.urlFor(handle),
                            strength = ProfileLeadStrength.PROVIDED_HANDLE,
                            evidence = "Built from a handle you provided"
                        )
                    )
                }
            }
            // Name variants — weaker leads
            nameVariants.forEach { handle ->
                platforms.forEach { platform ->
                    add(
                        PublicProfileLead(
                            platform = platform.name,
                            handle = handle,
                            url = platform.urlFor(handle),
                            strength = ProfileLeadStrength.NAME_VARIANT,
                            evidence = "Name or alias variant — review manually"
                        )
                    )
                }
            }
            // Email-derived leads
            if (profile.email.isNotBlank()) {
                val emailHandle = extractEmailHandle(profile.email)
                if (emailHandle.length >= 2) {
                    platforms.forEach { platform ->
                        add(
                            PublicProfileLead(
                                platform = platform.name,
                                handle = emailHandle,
                                url = platform.urlFor(emailHandle),
                                strength = ProfileLeadStrength.EMAIL_DERIVED,
                                evidence = "Derived from your email address"
                            )
                        )
                    }
                }
            }
        }
        return leads.distinctBy { it.url }
    }

    fun generateWebQueries(profile: IdentityProfile): List<Pair<String, String>> {
        val terms = buildList {
            profile.fullName.trim().takeIf { it.length >= 2 }?.let { add(it) }
            splitValues(profile.aliases).filter { it.length >= 2 }.forEach { add(it) }
            splitValues(profile.handles).map { normalizeHandle(it) }.filter { it.length >= 2 }.forEach { add(it) }
            profile.email.trim().takeIf { it.isNotBlank() }?.let { add(it) }
        }.distinct().take(MAX_VARIANTS)

        val siteGroups = listOf(
            "Western Socials" to listOf("instagram.com", "facebook.com", "x.com", "tiktok.com", "linkedin.com"),
            "Regional Networks (VK/Weibo)" to listOf("vk.com", "ok.ru", "weibo.com", "douyin.com"),
            "Paste Archives & Dumps" to listOf("pastebin.com", "ghostbin.co", "gist.github.com", "justpaste.it", "controlc.com")
        )

        val searchEngine = "https://www.google.com/search?q="
        val queries = mutableListOf<Pair<String, String>>()

        terms.forEach { term ->
            siteGroups.forEach { (groupName, sites) ->
                val siteList = sites.joinToString(" OR ") { "site:$it" }
                val queryStr = "\"$term\" ($siteList)"
                val url = searchEngine + URLEncoder.encode(queryStr, "UTF-8")
                queries.add("Search: \"$term\" ($groupName)" to url)
            }
        }
        return queries
    }

    /**
     * Generate email-specific OSINT queries for Epieos, WhatsMyName, Gravatar, and breach checks.
     */
    fun generateEmailQueries(profile: IdentityProfile): List<Pair<String, String>> {
        if (profile.email.isBlank()) return emptyList()
        val email = profile.email.trim()
        val emailHandle = extractEmailHandle(email)
        val results = mutableListOf<Pair<String, String>>()

        results.add("Epieos Email OSINT" to "https://epieos.com/?q=$email")
        
        // FIX: WhatsMyName is the modern replacement for Namechk and supports direct URL querying
        results.add("WhatsMyName (Username Run)" to "https://whatsmyname.app/?q=$emailHandle")
        
        results.add("IntelBase OSINT" to "https://intelbase.is/")
        
        results.add("HaveIBeenPwned (Breaches)" to "https://haveibeenpwned.com/")

        results.add("Google: Email Search" to "https://www.google.com/search?q=" +
            URLEncoder.encode("\"$email\"", "UTF-8"))

        results.add("DuckDuckGo: Email Search" to "https://duckduckgo.com/?q=" +
            URLEncoder.encode("\"$email\"", "UTF-8"))

        return results
    }

    /**
     * Helper to normalize raw phone numbers into standard E.164.
     */
    private fun normalizePhoneE164(rawPhone: String): String {
        val clean = rawPhone.trim()
        if (clean.isBlank()) return ""
        val digitsOnly = clean.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length < 7) return ""

        if (clean.startsWith("+")) {
            return "+$digitsOnly"
        }
        if (digitsOnly.length == 11 && digitsOnly.startsWith("07")) {
            return "+44${digitsOnly.substring(1)}"
        }
        if (digitsOnly.length in 10..11 && digitsOnly.startsWith("0")) {
            return "+44${digitsOnly.substring(1)}"
        }
        return "+$digitsOnly"
    }

    /**
     * Generate phone-based OSINT queries (PhoneInfoga & Phonia style).
     */
    fun generatePhoneQueries(profile: IdentityProfile): List<Pair<String, String>> {
        if (profile.phone.isBlank()) return emptyList()
        val rawPhone = profile.phone.trim()
        val digitsOnly = rawPhone.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length < 7) return emptyList()

        val e164 = normalizePhoneE164(rawPhone)
        val cleanDigits = e164.replace(Regex("[^0-9]"), "")
        val results = mutableListOf<Pair<String, String>>()

        // Direct Messaging
        results.add("WhatsApp Profile (wa.me)" to "https://wa.me/$cleanDigits")
        results.add("Telegram Direct Link" to "https://t.me/$e164")
        results.add("Viber Chat Direct" to "https://viber.click/$cleanDigits")

        // Caller ID & Owner Databases
        results.add("Sync.ME Social Registry" to "https://sync.me/search/?number=$e164")
        results.add("TrueCaller Caller ID" to "https://www.truecaller.com/search/gb/$cleanDigits")
        results.add("ShouldIAnswer Reputation" to "https://www.shouldianswer.com/phone-number/$e164")
        results.add("Tellows Reputation Index" to "https://www.tellows.co.uk/num/$digitsOnly")
        results.add("WhoCalledMe Directory" to "https://whocalled.co.uk/phone-number/$digitsOnly")
        results.add("UnknownPhone Registry" to "https://www.unknownphone.com/search.php?num=$digitsOnly")
        results.add("NumLookup (Owner & Line)" to "https://www.numlookup.com/")
        results.add("FreeCarrierLookup (Carrier)" to "https://freecarrierlookup.com/")

        // Breach & Leaks
        results.add("Intelligence X Data Leaks" to "https://intelx.io/?s=$cleanDigits")

        // Search Engine Dorks
        val combinedQuery = URLEncoder.encode("\"$e164\" OR \"$digitsOnly\"", "UTF-8")
        results.add("Google Dork: Exact Number" to "https://www.google.com/search?q=$combinedQuery")
        results.add("Google Dork: Facebook Profiles" to "https://www.google.com/search?q=" + URLEncoder.encode("site:facebook.com AND (\"$e164\" OR \"$digitsOnly\")", "UTF-8"))
        results.add("Google Dork: Social Networks" to "https://www.google.com/search?q=" + URLEncoder.encode("(site:instagram.com OR site:x.com OR site:linkedin.com) AND (\"$e164\" OR \"$digitsOnly\")", "UTF-8"))
        results.add("Google Dork: Document Leaks" to "https://www.google.com/search?q=" + URLEncoder.encode("(filetype:pdf OR filetype:txt OR site:pastebin.com) AND (\"$e164\" OR \"$digitsOnly\")", "UTF-8"))

        // Multi-Engine
        results.add("DuckDuckGo Phone Search" to "https://duckduckgo.com/?q=$combinedQuery")
        results.add("Bing Global Search" to "https://www.bing.com/search?q=$combinedQuery")
        results.add("Yandex International" to "https://yandex.com/search/?text=$combinedQuery")

        return results
    }

    /**
     * Generate CLI/Termux commands for advanced OSINT tools based on user profile.
     */
    fun generateCliCommands(profile: IdentityProfile): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val email = profile.email.trim()
        val emailHandle = if (email.isNotBlank()) extractEmailHandle(email) else ""
        val handles = splitValues(profile.handles).map(::normalizeHandle).filter { it.length >= 2 }
        
        val targetUser = handles.firstOrNull() ?: emailHandle
        val phoneE164 = if (profile.phone.isNotBlank()) normalizePhoneE164(profile.phone) else ""

        if (targetUser.isNotBlank()) {
            results.add("Sherlock (Username OSINT)" to "sherlock $targetUser")
            results.add("Blackbird (Username OSINT)" to "python blackbird.py -u $targetUser")
            results.add("Blackbird (PDF Report)" to "python blackbird.py -u $targetUser --pdf")
            results.add("Tookie (Username OSINT)" to "python3 brib.py -u $targetUser")
        }

        if (email.isNotBlank()) {
            results.add("Holehe (Email OSINT)" to "holehe $email")
            results.add("Blackbird (Email OSINT)" to "python blackbird.py -e $email")
        }

        if (phoneE164.isNotBlank() && phoneE164.length > 3) {
            results.add("PhoneInfoga (Standard Scan)" to "phoneinfoga scan -n \"$phoneE164\"")
            results.add("PhoneInfoga (NumVerify Plugin)" to "phoneinfoga scan -n \"$phoneE164\" --input numverify")
            results.add("Ignorant (Social Phone Check)" to "ignorant -n \"$phoneE164\"")
            results.add("Phonia (Phone OSINT Scanner)" to "python3 phonia.py -n \"$phoneE164\"")
            results.add("PhoneInfoga (Extract Links)" to "phoneinfoga scan -n \"$phoneE164\" | grep -oE 'https?://[^ ]+' > ~/phone_links.txt")
        }
        
        return results
    }

    /**
     * Export leads as a plain text report.
     */
    fun exportLeads(profile: IdentityProfile, leads: List<PublicProfileLead>): String {
        val sb = StringBuilder()
        sb.appendLine("=== CheckPoint Profile Discovery Report ===")
        sb.appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date())}")
        sb.appendLine()
        sb.appendLine("Identity Details:")
        if (profile.fullName.isNotBlank()) sb.appendLine("  Name: ${profile.fullName}")
        if (profile.aliases.isNotBlank()) sb.appendLine("  Aliases: ${profile.aliases}")
        if (profile.handles.isNotBlank()) sb.appendLine("  Handles: ${profile.handles}")
        if (profile.email.isNotBlank()) sb.appendLine("  Email: ${profile.email}")
        if (profile.phone.isNotBlank()) sb.appendLine("  Phone: ${profile.phone}")
        if (profile.city.isNotBlank()) sb.appendLine("  City: ${profile.city}")
        if (profile.website.isNotBlank()) sb.appendLine("  Website: ${profile.website}")
        sb.appendLine()

        val checked = leads.filter { it.status == ProfileStatus.LIKELY_EXISTS }
        val unchecked = leads.filter { it.status == ProfileStatus.UNCHECKED || it.status == ProfileStatus.CHECKING }
        val notFound = leads.filter { it.status == ProfileStatus.NOT_FOUND }

        if (checked.isNotEmpty()) {
            sb.appendLine("=== Likely Existing Profiles (${checked.size}) ===")
            checked.forEach { lead ->
                sb.appendLine("  [${lead.confidence.name}] ${lead.platform} @${lead.handle}")
                sb.appendLine("    URL: ${lead.url}")
                if (lead.pageTitle.isNotBlank()) sb.appendLine("    Title: ${lead.pageTitle}")
                sb.appendLine()
            }
        }

        if (unchecked.isNotEmpty()) {
            sb.appendLine("=== Unchecked Leads (${unchecked.size}) ===")
            unchecked.take(50).forEach { lead ->
                sb.appendLine("  ${lead.platform} @${lead.handle} — ${lead.url}")
            }
            if (unchecked.size > 50) sb.appendLine("  ... and ${unchecked.size - 50} more")
            sb.appendLine()
        }

        if (notFound.isNotEmpty()) {
            sb.appendLine("=== Not Found (${notFound.size}) ===")
            sb.appendLine("  ${notFound.size} URLs returned 404 or error.")
            sb.appendLine()
        }

        sb.appendLine("Total leads generated: ${leads.size}")
        return sb.toString()
    }

    private fun generateNameVariants(profile: IdentityProfile): List<String> {
        val seeds = buildList {
            profile.fullName.trim().takeIf { it.length >= 2 }?.let { add(it) }
            splitValues(profile.aliases).filter { it.length >= 2 }.forEach { add(it) }
        }
        return seeds.flatMap { seed ->
            val normalized = normalizeHandle(seed)
            val words = seed.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9 ]"), " ")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
            buildList {
                add(normalized)
                if (words.size >= 2) {
                    val first = words.first()
                    val last = words.last()
                    add("$first$last")
                    add("$first.$last")
                    add("${first}_$last")
                    add("$first-$last")
                    add(first.first().toString() + last)
                    add("$first${last.first()}")
                    add("${first.first()}.$last")
                    add("${first.first()}_$last")
                    add("$first${last.first()}")
                    // Common number suffixes
                    add("$first$last" + "1")
                    add("$first$last" + "99")
                    add("$first.$last" + "1")
                    add("${first}_$last" + "1")
                    // Reversed
                    add("$last$first")
                    add("$last.$first")
                }
            }
        }.map { normalizeHandle(it) }.filter { it.length >= 2 }.distinct()
    }

    private fun extractEmailHandle(email: String): String {
        val atIdx = email.indexOf('@')
        return if (atIdx > 0) normalizeHandle(email.substring(0, atIdx)) else normalizeHandle(email)
    }

    private fun splitValues(value: String): List<String> =
        value.split(',', '\n', ';').map { it.trim() }.filter { it.isNotBlank() }

    private fun normalizeHandle(value: String): String = value
        .lowercase(Locale.US)
        .removePrefix("@")
        .replace(Regex("[^a-z0-9._-]"), "")
}
