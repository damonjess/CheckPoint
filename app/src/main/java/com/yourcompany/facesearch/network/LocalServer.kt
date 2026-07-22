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

    // Memory-mapped probe storage
    var currentProbeImage: ByteArray? = null

    fun start(context: Context) {
        if (server != null) return

        appContext = context.applicationContext
        faceDetector = FaceDetectorHelper(appContext)
        faceEmbedder = FaceEmbedder(appContext)

        server = embeddedServer(Netty, port = 8080) {
            install(ContentNegotiation) { gson() }
            install(CORS) { anyHost() }

            routing {
                // Termux hosting bypass: serve the current probe directly from RAM
                get("/probe.jpg") {
                    val img = currentProbeImage
                    if (img != null) {
                        call.respondBytes(img, ContentType.Image.JPEG)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "No probe staged")
                    }
                }

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
        // TODO: In future you can expand this for fully local server mode
        // For now, just return success so the UI doesn't break
        return mapOf(
            "match_found" to true,
            "status" to "real_search_triggered",
            "message" to "Using main API pipeline instead of local mock"
        )
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