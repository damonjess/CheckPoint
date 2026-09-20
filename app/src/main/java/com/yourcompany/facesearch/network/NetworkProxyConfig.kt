package com.yourcompany.facesearch.network

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy

object NetworkProxyConfig {
    var isProxyEnabled: Boolean = false
    var proxyHost: String = "127.0.0.1"
    var proxyPort: Int = 9050 // Default Orbot SOCKS5 port

    fun applyProxy(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (isProxyEnabled) {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort))
            builder.proxy(proxy)
        }
        return builder
    }
}
