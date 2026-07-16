package com.yourcompany.facesearch.network

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface FaceCheckApi {
    @Multipart
    @POST("api/upload_pic")
    suspend fun uploadPic(
        @Part image: MultipartBody.Part,
        @Header("Authorization") apiKey: String
    ): Response<FaceCheckUploadResponse>

    @POST("api/search")
    suspend fun search(
        @Body request: FaceCheckSearchRequest,
        @Header("Authorization") apiKey: String
    ): Response<FaceCheckSearchResponse>
}

data class FaceCheckUploadResponse(
    @SerializedName("id_search")
    val idSearch: String?,
    @SerializedName("error")
    val error: String?
)

data class FaceCheckSearchRequest(
    @SerializedName("id_search")
    val idSearch: String,
    @SerializedName("with_progress")
    val withProgress: Boolean = true,
    @SerializedName("status_only")
    val statusOnly: Boolean = false,
    @SerializedName("demo")
    val demo: Boolean = false,
    @SerializedName("id_captcha")
    val idCaptcha: String? = null
)

data class FaceCheckSearchResponse(
    @SerializedName("output")
    val output: FaceCheckOutput? = null,
    @SerializedName("items")
    val items: List<FaceCheckMatch>? = null,
    @SerializedName("progress")
    val progress: Int? = null,
    @SerializedName("error")
    val error: String? = null,
    @SerializedName("message")
    val message: String? = null,
    @SerializedName("status")
    val status: String? = null
)

data class FaceCheckOutput(
    @SerializedName("items")
    val items: List<FaceCheckMatch>? = null
)

data class FaceCheckMatch(
    @SerializedName("base64")
    val base64: String?,
    @SerializedName("url")
    val url: String?,
    @SerializedName("score")
    val score: Int?,
    @SerializedName("site")
    val site: String? = null
)
