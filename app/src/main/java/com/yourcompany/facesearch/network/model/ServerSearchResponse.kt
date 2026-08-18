package com.yourcompany.facesearch.network.model

data class ServerSearchResponse(
    val success: Boolean,
    val matches: List<Match>? = null,
    val error: String? = null,
    val meta: SearchMeta? = null
)

data class SearchMeta(
    val engines: Map<String, EngineMeta>? = null,
    val blockedEngines: List<String>? = null,
    val totalMs: Long? = null
)

data class EngineMeta(
    val count: Int,
    val ms: Long? = null,
    val error: String? = null
)

data class Match(
    val title: String,
    val link: String,
    val thumbnail: String? = null,
    val source: String,
    val isSocial: Boolean,
    val score: Int
)



