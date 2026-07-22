package com.yourcompany.facesearch.network

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded

interface ImageUploadApi {
    @Multipart
    @POST("1/upload")
    suspend fun upload(
        @Query("key") apiKey: String,
        @Part image: MultipartBody.Part
    ): Response<ImgBBResponse>

    @FormUrlEncoded
    @POST("1/upload")
    suspend fun uploadBase64(
        @Field("key") apiKey: String,
        @Field("image") base64Image: String
    ): Response<ImgBBResponse>
}

data class ImgBBResponse(
    val data: ImgBBData?,
    val success: Boolean,
    val status: Int? = null,
    @com.google.gson.annotations.SerializedName("status_code") val statusCode: Int? = null,
    val error: ImgBBError? = null
)

data class ImgBBError(
    val message: String?,
    val code: Int?
)

data class ImgBBData(
    val id: String?,
    val url: String?,
    val display_url: String?,
    val thumb: ImgBBImageInfo? = null,
    val medium: ImgBBImageInfo? = null
)

data class ImgBBImageInfo(
    val url: String?
)
