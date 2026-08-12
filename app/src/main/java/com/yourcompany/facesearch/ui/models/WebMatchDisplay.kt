package com.yourcompany.facesearch.ui.models

import java.util.Locale

data class WebMatchDisplay(
    val name: String,
    val source: String,
    val profileUrl: String,
    val score: Int,
    val imageUrl: Any? = null,
    val extraImages: List<String> = emptyList(),
    val isSocial: Boolean = false,
    val confidence: Float = 0f, // 0.0 to 1.0
    val isHighResLoading: Boolean = false
) {
    val displayName: String by lazy {
        var n = name
            .replace(Regex("^\\d+\\s*[×xX]\\s*\\d+[A-Za-zА-Яа-я]?\\s*"), "") // strip "620×634А"
            .replace(Regex("\\.(jpg|jpeg|png|gif|webp|svg)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\s*-\\s*"), "")
            .trim()
            .take(120)

        // If title is just a domain, try to make it look nicer
        if (n.contains(Regex("^[a-zA-Z0-9-]+\\.[a-z]{2,}$"))) {
            n = n.substringBefore(".").replaceFirstChar { 
                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() 
            }
        }
        
        n.ifBlank { "Visual Match" }
    }
}
