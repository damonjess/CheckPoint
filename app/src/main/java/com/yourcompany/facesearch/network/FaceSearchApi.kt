package com.yourcompany.facesearch.network

import com.google.gson.*
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

/**
 * Visual Match model used by WebView Scraper and Termux backend.
 */
data class SerpVisualMatch(
    @SerializedName("title")
    val title: String?,
    
    @SerializedName("link")
    val link: String?, // The direct social media link (View Profile target)
    
    @SerializedName("source")
    val source: String?, // Shows "Instagram", "Facebook", "LinkedIn", etc.
    
    @SerializedName("thumbnail")
    @JsonAdapter(ThumbnailAdapter::class)
    val thumbnail: String?, // The image profile link

    val score: Int = 0,
    val embedding: FloatArray? = null
)

/**
 * Handles inconsistent "thumbnail" fields from various scrapers.
 */
class ThumbnailAdapter : JsonDeserializer<String?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): String? {
        if (json == null || json.isJsonNull) return null
        
        return when {
            json.isJsonPrimitive && json.asJsonPrimitive.isString -> json.asString
            json.isJsonObject -> {
                val obj = json.asJsonObject
                obj.get("src")?.asString 
                    ?: obj.get("link")?.asString 
                    ?: obj.get("url")?.asString
                    ?: obj.get("static")?.asString
                    ?: json.toString()
            }
            else -> json.toString()
        }
    }
}



