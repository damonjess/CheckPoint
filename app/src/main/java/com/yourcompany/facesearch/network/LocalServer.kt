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
import io.ktor.server.plugins.cors.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.graphics.Bitmap
import io.ktor.http.content.*
import java.io.ByteArrayOutputStream

import java.util.concurrent.TimeUnit

object LocalServer {

    private var server: ApplicationEngine? = null

    fun start(context: Context) {
        if (server != null) return

        server = embeddedServer(Netty, port = 8080) {
            install(ContentNegotiation) {
                gson()
            }
            install(CORS) {
                anyHost()
            }

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

                        // Simulate real web search (we can improve this later)
                        val result = performWebSearch(imageBytes!!)

                        call.respond(result)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }
            }
        }.start(wait = false)
    }

    private suspend fun performWebSearch(imageBytes: ByteArray): Map<String, Any> {
        // TODO: Later connect to real Bing API here
        return mapOf(
            "search_id" to "local-${System.currentTimeMillis()}",
            "status" to "success",
            "match_found" to true,
            "results" to listOf(
                mapOf(
                    "name" to "Possible Match from Web",
                    "confidence" to 0.82,
                    "source" to "Internet Search",
                    "profile_url" to "https://example.com/profile",
                    "image_url" to null
                )
            )
        )
    }

    fun stop() {
        server?.stop(1000, 1000, TimeUnit.MILLISECONDS)
        server = null
    }
}
