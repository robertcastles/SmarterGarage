package com.lusion.sgauto

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Home-test transport is intentionally permissive, but never inherits production credentials. */
internal object NetworkPolicy {
    val productionUrl = "https://api.smartthings.com/".toHttpUrl()

    fun testUrl(value: String): HttpUrl {
        val url = value.trim().toHttpUrl()
        require(url.isHttps) { "Test server must use HTTPS" }
        require(url.host != productionUrl.host) { "Test server must not be SmartThings production" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "URL credentials are not supported" }
        require(url.query == null && url.fragment == null) { "Test URL must not contain a query or fragment" }
        return url
    }

    fun authorization(
        destination: HttpUrl,
        endpoint: HttpUrl,
        testing: Boolean,
        diagnostic: Boolean,
        productionToken: String,
        testToken: String
    ): String? {
        if (diagnostic || destination.encodedPath == "/ping") return null
        if (destination.scheme != endpoint.scheme || destination.host != endpoint.host ||
            destination.port != endpoint.port) return null
        if (!testing && endpoint != productionUrl) return null
        val token = (if (testing) testToken else productionToken).trim()
        return token.takeIf { it.isNotEmpty() }?.let { "Bearer $it" }
    }
}
