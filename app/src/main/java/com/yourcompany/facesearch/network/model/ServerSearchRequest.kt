package com.yourcompany.facesearch.network.model

data class ServerSearchRequest(
    val imageUrl: String,
    val keywordHint: String? = null,
    val searchMode: String = "HYPER",
    val localBypassUrl: String? = null,
    val localFaceUrl: String? = null,
    val searchTarget: String? = null
)



