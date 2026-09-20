package com.yourcompany.facesearch.network.model

import com.google.gson.annotations.SerializedName

data class ImgbbResponse(
    @SerializedName("data")
    val data: ImgbbData?,
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("status")
    val status: Int
)

data class ImgbbData(
    @SerializedName("id")
    val id: String?,
    @SerializedName("url")
    val url: String?,
    @SerializedName("display_url")
    val displayUrl: String?
)
