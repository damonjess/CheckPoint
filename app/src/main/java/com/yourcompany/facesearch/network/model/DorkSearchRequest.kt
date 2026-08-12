package com.yourcompany.facesearch.network.model

data class DorkSearchRequest(
    val keyword: String,
    val sites: List<String>
)
