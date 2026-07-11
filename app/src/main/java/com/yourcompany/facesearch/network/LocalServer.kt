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
                        var imageReceived = false

                        multipart.forEachPart { part ->
                            if (part is PartData.FileItem) {
                                imageReceived = true
                            }
                            part.dispose()
                        }

                        if (!imageReceived) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No image"))
                            return@post
                        }

                        // Real web search simulation with realistic results
                        val result = performWebSearchSimulation()
                        call.respond(result)

                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Unknown error"))
                        )
                    }
                }
            }
        }.start(wait = false)
    }

    private fun performWebSearchSimulation(): Map<String, Any> {
        return mapOf(
            "search_id" to "local-${System.currentTimeMillis()}",
            "status" to "success",
            "match_found" to true,
            "results" to listOf(
                mapOf(
                    "name" to "Alex Johnson",
                    "confidence" to 0.89,
                    "source" to "LinkedIn / Twitter",
                    "profile_url" to "https://linkedin.com/in/alexjohnson",
                    "image_url" to "https://picsum.photos/id/64/300/300"
                ),
                mapOf(
                    "name" to "Sarah Chen",
                    "confidence" to 0.76,
                    "source" to "Instagram / Facebook",
                    "profile_url" to "https://instagram.com/sarahchen",
                    "image_url" to "https://picsum.photos/id/65/300/300"
                ),
                mapOf(
                    "name" to "Michael Rodriguez",
                    "confidence" to 0.68,
                    "source" to "Public Web",
                    "profile_url" to "https://example.com/michael",
                    "image_url" to "https://picsum.photos/id/66/300/300"
                )
            )
        )
    }

    fun stop() {
        server?.stop(1000L, 1000L)
        server = null
    }
}
