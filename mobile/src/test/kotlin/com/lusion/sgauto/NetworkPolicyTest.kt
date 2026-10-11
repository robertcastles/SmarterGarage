package com.lusion.sgauto

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class NetworkPolicyTest {
    private val home = NetworkPolicy.testUrl("https://garage.home:3000/")

    private fun auth(testing: Boolean, diagnostic: Boolean = false, testToken: String = "test-only"): String? {
        val endpoint = if (testing) home else NetworkPolicy.productionUrl
        return NetworkPolicy.authorization(endpoint, endpoint, testing, diagnostic, "production-only", testToken)
    }

    @Test fun productionUsesProductionToken() = assertEquals("Bearer production-only", auth(false))
    @Test fun homeUsesOnlyExplicitTestToken() = assertEquals("Bearer test-only", auth(true))
    @Test fun missingTestTokenNeverFallsBackToProduction() = assertNull(auth(true, testToken = ""))
    @Test fun diagnosticsNeverUseEitherToken() {
        assertNull(auth(true, diagnostic = true))
        assertNull(auth(false, diagnostic = true))
    }
    @Test fun pingPathCannotCarryBearer() {
        assertNull(NetworkPolicy.authorization(home.resolve("/ping")!!, home, true, false, "real", "test"))
    }
    @Test fun unexpectedDestinationCannotCarryBearer() {
        assertNull(NetworkPolicy.authorization("https://other.home/".toHttpUrl(), home, true, false, "real", "test"))
        assertNull(NetworkPolicy.authorization("https://garage.home:444/".toHttpUrl(), home, true, false, "real", "test"))
    }
    @Test fun privateIpAndHomeHostnameAreSupported() {
        assertEquals("10.0.2.2", NetworkPolicy.testUrl("https://10.0.2.2:3000").host)
        assertEquals("garage.home", home.host)
    }
    @Test fun invalidOrProductionTestEndpointIsRejected() {
        listOf("", "http://garage.home", "https://api.smartthings.com", "https://user:pass@garage.home", "https://garage.home/?secret=x")
            .forEach { value ->
                assertThrows(IllegalArgumentException::class.java) { NetworkPolicy.testUrl(value) }
            }
    }
}
