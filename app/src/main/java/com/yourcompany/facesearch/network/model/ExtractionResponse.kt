package com.yourcompany.facesearch.network.model

data class ExtractionResponse(
    val success: Boolean,
    val highResUrl: String? = null,
    val error: String? = null
)
