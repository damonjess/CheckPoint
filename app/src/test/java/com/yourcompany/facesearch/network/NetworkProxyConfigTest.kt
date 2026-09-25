package com.yourcompany.facesearch.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class NetworkProxyConfigTest {

    @Test
    fun `desktop browser user agent interceptor rewrites the User-Agent header`() {
        val originalRequest = Request.Builder()
            .url("https://example.com/profile")
            .header("User-Agent", "Old-Agent")
            .build()

        val response = NetworkProxyConfig.browserUserAgentInterceptor().intercept(object : Interceptor.Chain {
            override fun request(): Request = originalRequest

            override fun proceed(request: Request): Response {
                assertEquals(
                    listOf(NetworkProxyConfig.DESKTOP_BROWSER_USER_AGENT),
                    request.headers("User-Agent")
                )
                return Response.Builder()
                    .request(request)
                    .code(200)
                    .message("OK")
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .body(okhttp3.ResponseBody.create(null, "ok"))
                    .build()
            }

            override fun connectTimeoutMillis(): Int = 10_000
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun readTimeoutMillis(): Int = 10_000
            override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun writeTimeoutMillis(): Int = 10_000
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            override fun connection(): okhttp3.Connection? = null
            override fun call(): okhttp3.Call = object : okhttp3.Call {
                override fun request(): Request = originalRequest
                override fun execute(): Response = throw IOException("not used")
                override fun enqueue(responseCallback: okhttp3.Callback) = throw UnsupportedOperationException("not used")
                override fun cancel() = Unit
                override fun isExecuted(): Boolean = false
                override fun isCanceled(): Boolean = false
                override fun timeout(): okio.Timeout = okio.Timeout.NONE
                override fun clone(): okhttp3.Call = this
            }
        })

        assertEquals(200, response.code)
    }
}
