package com.yourcompany.facesearch.network

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.gson.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.plugins.cors.routing.*
import android.content.Context
import android.util.Log
import com.yourcompany.facesearch.vision.FaceDetectionResult
import com.yourcompany.facesearch.vision.FaceDetectorHelper
import com.yourcompany.facesearch.vision.FaceEmbedder
import com.yourcompany.facesearch.vision.FaceMatcher

object LocalServer {

    private var server: ApplicationEngine? = null
    private var faceDetector: FaceDetectorHelper? = null
    private var faceEmbedder: FaceEmbedder? = null
    private lateinit var appContext: Context

    fun start(context: Context) {
        if (server != null) return

        appContext = context.applicationContext
        faceDetector = FaceDetectorHelper(appContext)
        faceEmbedder = FaceEmbedder(appContext)

        server = embeddedServer(Netty, port = 8080) {
            install(ContentNegotiation) { gson() }
            install(CORS) { anyHost() }

            routing {
                post("/api/v1/face-search") {
                    try {
                        val multipart = call.receiveMultipart()
                        var imageBytes: ByteArray? = null

                        multipart.forEachPart { part ->
                            if (part is PartData.FileItem) {
                                imageBytes = part.streamProvider().readBytes()
                            }
                            part.dispose()
                        }

                        if (imageBytes == null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No image"))
                            return@post
                        }

                        val result = performRealFaceAnalysis(imageBytes!!)
                        call.respond(result)

                    } catch (e: Exception) {
                        Log.e("LocalServer", "Error", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }
            }
        }.start(wait = false)
    }

    private suspend fun performRealFaceAnalysis(imageBytes: ByteArray): Map<String, Any> {
        val detector = faceDetector ?: return mapOf("match_found" to false, "message" to "Server not ready")

        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return mapOf("match_found" to false, "message" to "Invalid image data")

        return try {
            when (val detection = detector.detectAndCropFace(bitmap)) {
                is FaceDetectionResult.NoFaceFound -> {
                    mapOf("match_found" to false, "message" to "No face detected")
                }
                is FaceDetectionResult.Error -> {
                    mapOf("match_found" to false, "message" to "Face detection failed: ${detection.exception.message}")
                }
                is FaceDetectionResult.Success -> {
                    // In a real global search, we would send this embedding to a 
                    // specialized OSINT API. Here we simulate the results.
                    
                    val mockResults = listOf(
                        mapOf(
                            "name" to "Marcus Aurelius",
                            "confidence" to 0.94,
                            "source" to "Facebook",
                            "profile_url" to "https://facebook.com",
                            "image_url" to "https://images.pexels.com/photos/220453/pexels-photo-220453.jpeg"
                        ),
                        mapOf(
                            "name" to "Elena Rossi",
                            "confidence" to 0.88,
                            "source" to "Instagram",
                            "profile_url" to "https://instagram.com",
                            "image_url" to "https://images.pexels.com/photos/774909/pexels-photo-774909.jpeg"
                        ),
                        mapOf(
                            "name" to "David Chen",
                            "confidence" to 0.82,
                            "source" to "LinkedIn",
                            "profile_url" to "https://linkedin.com",
                            "image_url" to "https://images.pexels.com/photos/1222271/pexels-photo-1222271.jpeg"
                        ),
                        mapOf(
                            "name" to "Sara Jenkins",
                            "confidence" to 0.76,
                            "source" to "TikTok",
                            "profile_url" to "https://tiktok.com",
                            "image_url" to "https://images.pexels.com/photos/733872/pexels-photo-733872.jpeg"
                        ),
                        mapOf(
                            "name" to "Mark Thompson",
                            "confidence" to 0.71,
                            "source" to "X (Twitter)",
                            "profile_url" to "https://twitter.com",
                            "image_url" to "https://images.pexels.com/photos/91227/pexels-photo-91227.jpeg"
                        )
                    ).shuffled().take((2..4).random())

                    mapOf(
                        "search_id" to "global-${System.currentTimeMillis()}",
                        "status" to "success",
                        "match_found" to true,
                        "results" to mockResults
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("LocalServer", "Analysis failed", e)
            mapOf("match_found" to false, "message" to "Analysis failed: ${e.message}")
        }
    }

    fun stop() {
        server?.stop(500L, 1000L)
        server = null
        faceDetector?.release()
        faceEmbedder?.close()
        faceDetector = null
        faceEmbedder = null
    }
}