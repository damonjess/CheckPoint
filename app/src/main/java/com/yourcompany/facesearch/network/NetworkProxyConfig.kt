package com.yourcompany.facesearch.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy

object NetworkProxyConfig {
    const val DESKTOP_BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"

    var isProxyEnabled: Boolean = false
    var proxyHost: String = "127.0.0.1"
    var proxyPort: Int = 9050 // Default Orbot SOCKS5 port

    fun browserUserAgentInterceptor(): Interceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val requestWithUserAgent = originalRequest.newBuilder()
            .removeHeader("User-Agent")
            .addHeader("User-Agent", DESKTOP_BROWSER_USER_AGENT)
            .build()
        chain.proceed(requestWithUserAgent)
    }

    fun applyDesktopBrowserUserAgent(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        return builder.addNetworkInterceptor(browserUserAgentInterceptor())
    }

    fun applyProxy(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (isProxyEnabled) {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort))
            builder.proxy(proxy)
        }
        return builder
    }
}
