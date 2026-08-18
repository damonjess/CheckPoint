package com.yourcompany.facesearch.data

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.Locale

/**
 * Optional identity details supplied by the device owner. This is saved only in
 * the app-private files directory and is never uploaded by this store.
 */
data class IdentityProfile(
    val fullName: String = "",
    val aliases: String = "",
    val handles: String = "",
    val city: String = "",
    val website: String = ""
)

enum class ProfileLeadStrength { PROVIDED_HANDLE, NAME_VARIANT }

data class PublicProfileLead(
    val platform: String,
    val handle: String,
    val url: String,
    val strength: ProfileLeadStrength,
    val evidence: String
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
    private const val MAX_VARIANTS = 8

    private data class PlatformTemplate(
        val name: String,
        val urlFor: (String) -> String
    )

    private val platforms = listOf(
        PlatformTemplate("Instagram") { handle -> "https://www.instagram.com/$handle/" },
        PlatformTemplate("Facebook") { handle -> "https://www.facebook.com/$handle" },
        PlatformTemplate("TikTok") { handle -> "https://www.tiktok.com/@$handle" },
        PlatformTemplate("Snapchat") { handle -> "https://www.snapchat.com/add/$handle" },
        PlatformTemplate("X") { handle -> "https://x.com/$handle" },
        PlatformTemplate("LinkedIn") { handle -> "https://www.linkedin.com/in/$handle" },
        PlatformTemplate("GitHub") { handle -> "https://github.com/$handle" },
        PlatformTemplate("YouTube") { handle -> "https://www.youtube.com/@$handle" },
        PlatformTemplate("Reddit") { handle -> "https://www.reddit.com/user/$handle/" }
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
        }
        return leads.distinctBy { it.url }
    }

    fun generateWebQueries(profile: IdentityProfile): List<String> {
        val terms = buildList {
            profile.fullName.trim().takeIf { it.length >= 2 }?.let { value -> add(value) }
            splitValues(profile.aliases).filter { it.length >= 2 }.forEach { value -> add(value) }
            splitValues(profile.handles).map { value -> normalizeHandle(value) }.filter { it.length >= 2 }.forEach { value -> add(value) }
        }.distinct().take(MAX_VARIANTS)

        return terms.map { term ->
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(
                "\"$term\" (site:instagram.com OR site:facebook.com OR site:tiktok.com OR site:snapchat.com)",
                "UTF-8"
            )
        }
    }

    private fun generateNameVariants(profile: IdentityProfile): List<String> {
        val seeds = buildList {
            profile.fullName.trim().takeIf { it.length >= 2 }?.let { value -> add(value) }
            splitValues(profile.aliases).filter { it.length >= 2 }.forEach { value -> add(value) }
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
                    add(words.joinToString("."))
                    add(words.joinToString("_"))
                    add(words.joinToString("-"))
                    add(words.first().first() + words.last())
                }
            }
        }.map { value -> normalizeHandle(value) }.filter { it.length >= 2 }.distinct()
    }

    private fun splitValues(value: String): List<String> =
        value.split(',', '\n', ';').map { part -> part.trim() }.filter { part -> part.isNotBlank() }

    private fun normalizeHandle(value: String): String = value
        .lowercase(Locale.US)
        .removePrefix("@")
        .replace(Regex("[^a-z0-9._-]"), "")
}
