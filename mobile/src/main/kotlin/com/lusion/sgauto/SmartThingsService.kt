package com.lusion.sgauto

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.lusion.sgauto.PreferenceUtility.getPreference
import com.lusion.sgauto.PreferenceUtility.setPreference
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Custom DNS resolver that falls back to public DNS servers when the system resolver fails.
 * This addresses the intermittent "Unable to resolve host" errors that can occur on Android
 * even when network connectivity is fine.
 *
 * Successful resolutions are cached in preferences to provide fallback IPs.
 *
 * Note: Always pass applicationContext to avoid memory leaks.
 */
private class FallbackDns(context: Context) : Dns {
    // Store applicationContext to avoid memory leaks
    private val appContext: Context = context.applicationContext

    companion object {
        private const val TAG = "FallbackDns"

        // Public DNS servers to use as fallback
        private const val GOOGLE_DNS = "8.8.8.8"
        private const val CLOUDFLARE_DNS = "1.1.1.1"

        // Preference keys for cached IPs
        private const val PREF_CACHED_IP_1 = "dns_cached_ip_1"
        private const val PREF_CACHED_IP_2 = "dns_cached_ip_2"
        private const val PREF_CACHED_IP_TIMESTAMP = "dns_cached_ip_timestamp"
        private const val CACHED_IP_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

        // In-memory cache for the current session
        private val sessionCache = mutableMapOf<String, CachedAddresses>()
        private const val SESSION_CACHE_TTL_MS = 60_000L // 1 minute

        private data class CachedAddresses(
            val addresses: List<InetAddress>,
            val timestamp: Long
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // Check session cache first
        val cached = sessionCache[hostname]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < SESSION_CACHE_TTL_MS) {
            Log.d(TAG, "Using session cached DNS for $hostname: ${cached.addresses}")
            return cached.addresses
        }

        // Try system DNS first
        try {
            val systemAddresses = Dns.SYSTEM.lookup(hostname)
            if (systemAddresses.isNotEmpty()) {
                cacheAddresses(hostname, systemAddresses)
                return systemAddresses
            }
        } catch (e: UnknownHostException) {
            Log.w(TAG, "System DNS failed for $hostname: ${e.message}")
        }

        // Fallback 1: Try public DNS servers (Google, Cloudflare)
        Log.i(TAG, "Attempting fallback DNS resolution for $hostname using public DNS")
        val publicDnsAddresses = resolveViaPublicDns(hostname)
        if (publicDnsAddresses.isNotEmpty()) {
            cacheAddresses(hostname, publicDnsAddresses)
            return publicDnsAddresses
        }

        // Fallback 2: Try cached IPs from preferences
        Log.i(TAG, "Attempting to use preference-cached IPs for $hostname")
        val prefCachedAddresses = getPreferenceCachedAddresses(hostname)
        if (prefCachedAddresses.isNotEmpty()) {
            sessionCache[hostname] = CachedAddresses(prefCachedAddresses, System.currentTimeMillis())
            return prefCachedAddresses
        }

        // If all fails, throw the exception
        throw UnknownHostException("Unable to resolve host \"$hostname\" using system, public DNS, and cached fallbacks")
    }

    /**
     * Resolve hostname using public DNS servers via raw UDP DNS queries.
     * This bypasses Android's system resolver entirely.
     */
    private fun resolveViaPublicDns(hostname: String): List<InetAddress> {
        val dnsServers = listOf(GOOGLE_DNS, CLOUDFLARE_DNS)

        for (dnsServer in dnsServers) {
            try {
                val addresses = performDnsQuery(hostname, dnsServer)
                if (addresses.isNotEmpty()) {
                    Log.i(TAG, "Resolved $hostname via $dnsServer: $addresses")
                    return addresses
                }
            } catch (e: Exception) {
                Log.w(TAG, "Public DNS $dnsServer failed for $hostname: ${e.message}")
            }
        }

        return emptyList()
    }

    /**
     * Perform a raw DNS A record query over UDP.
     */
    private fun performDnsQuery(hostname: String, dnsServer: String): List<InetAddress> {
        val socket = DatagramSocket()
        socket.soTimeout = 5000 // 5 second timeout

        socket.use { socket ->
            val queryId = (Math.random() * 65535).toInt().toShort()
            val query = buildDnsQuery(hostname, queryId)

            val serverAddress = InetAddress.getByName(dnsServer)
            val requestPacket = DatagramPacket(query, query.size, serverAddress, 53)
            socket.send(requestPacket)

            val responseBuffer = ByteArray(512)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            return parseDnsResponse(hostname, responseBuffer, responsePacket.length)
        }
    }

    /**
     * Build a DNS query packet for an A record lookup.
     */
    private fun buildDnsQuery(hostname: String, queryId: Short): ByteArray {
        val buffer = ByteBuffer.allocate(512)

        // Header
        buffer.putShort(queryId)           // ID
        buffer.putShort(0x0100.toShort())  // Flags: standard query, recursion desired
        buffer.putShort(1)                 // QDCOUNT: 1 question
        buffer.putShort(0)                 // ANCOUNT
        buffer.putShort(0)                 // NSCOUNT
        buffer.putShort(0)                 // ARCOUNT

        // Question section
        for (label in hostname.split(".")) {
            buffer.put(label.length.toByte())
            buffer.put(label.toByteArray(Charsets.US_ASCII))
        }
        buffer.put(0)                      // End of hostname
        buffer.putShort(1)                 // QTYPE: A record
        buffer.putShort(1)                 // QCLASS: IN (Internet)

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    /**
     * Parse DNS response and extract A record IP addresses.
     */
    private fun parseDnsResponse(hostname: String, response: ByteArray, length: Int): List<InetAddress> {
        if (length < 12) return emptyList()

        val buffer = ByteBuffer.wrap(response, 0, length)

        // Skip header ID
        buffer.getShort()

        // Check flags
        val flags = buffer.getShort().toInt() and 0xFFFF
        val rcode = flags and 0x000F
        if (rcode != 0) {
            Log.w(TAG, "DNS response error code: $rcode")
            return emptyList()
        }

        val qdCount = buffer.getShort().toInt() and 0xFFFF
        val anCount = buffer.getShort().toInt() and 0xFFFF
        buffer.getShort() // NSCOUNT
        buffer.getShort() // ARCOUNT

        // Skip question section
        repeat(qdCount) {
            skipDnsName(buffer)
            buffer.getShort() // QTYPE
            buffer.getShort() // QCLASS
        }

        // Parse answer section
        val addresses = mutableListOf<InetAddress>()
        repeat(anCount) {
            skipDnsName(buffer)
            val type = buffer.getShort().toInt() and 0xFFFF
            buffer.getShort() // CLASS
            buffer.getInt()   // TTL
            val rdLength = buffer.getShort().toInt() and 0xFFFF

            if (type == 1 && rdLength == 4) { // A record
                val ipBytes = ByteArray(4)
                buffer.get(ipBytes)
                try {
                    addresses.add(InetAddress.getByAddress(hostname, ipBytes))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse IP address: ${e.message}")
                }
            } else {
                // Skip this record's data
                buffer.position(buffer.position() + rdLength)
            }
        }

        return addresses
    }

    /**
     * Skip over a DNS name (handles compression pointers).
     */
    private fun skipDnsName(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val len = buffer.get().toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                // Compression pointer - skip one more byte
                buffer.get()
                break
            }
            // Skip label
            buffer.position(buffer.position() + len)
        }
    }

    private fun cacheAddresses(hostname: String, addresses: List<InetAddress>) {
        // Session cache
        sessionCache[hostname] = CachedAddresses(addresses, System.currentTimeMillis())

        // Preference cache (store up to 2 IPs for the SmartThings API)
        if (hostname == "api.smartthings.com" && addresses.isNotEmpty()) {
            try {
                val ip1 = addresses[0].hostAddress ?: ""
                val ip2 = if (addresses.size > 1) addresses[1].hostAddress ?: "" else ""

                setPreference(PREF_CACHED_IP_1, ip1, appContext)
                setPreference(PREF_CACHED_IP_2, ip2, appContext)
                setPreference(PREF_CACHED_IP_TIMESTAMP, System.currentTimeMillis(), appContext)

                Log.d(TAG, "Cached IPs for $hostname: $ip1, $ip2")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cache IPs in preferences: ${e.message}")
            }
        }
    }

    private fun getPreferenceCachedAddresses(hostname: String): List<InetAddress> {
        if (hostname != "api.smartthings.com") return emptyList()

        try {
            val timestamp = getPreference(PREF_CACHED_IP_TIMESTAMP, Long::class.java, appContext) as? Long ?: 0L

            // Check if cache is still valid (within 24 hours)
            if (System.currentTimeMillis() - timestamp > CACHED_IP_TTL_MS) {
                Log.d(TAG, "Preference cached IPs expired")
                return emptyList()
            }

            val addresses = mutableListOf<InetAddress>()

            val ip1 = getPreference(PREF_CACHED_IP_1, String::class.java, appContext) as? String
            if (!ip1.isNullOrBlank()) {
                try {
                    addresses.add(InetAddress.getByAddress(hostname, InetAddress.getByName(ip1).address))
                    Log.d(TAG, "Using preference cached IP1: $ip1")
                } catch (_: Exception) {
                    Log.w(TAG, "Invalid cached IP1: $ip1")
                }
            }

            val ip2 = getPreference(PREF_CACHED_IP_2, String::class.java, appContext) as? String
            if (!ip2.isNullOrBlank()) {
                try {
                    addresses.add(InetAddress.getByAddress(hostname, InetAddress.getByName(ip2).address))
                    Log.d(TAG, "Using preference cached IP2: $ip2")
                } catch (_: Exception) {
                    Log.w(TAG, "Invalid cached IP2: $ip2")
                }
            }

            return addresses
        } catch (e: Exception) {
            Log.w(TAG, "Error reading preference cached IPs: ${e.message}")
            return emptyList()
        }
    }
}

object SmartThingsService {
    @Volatile
    private var instance: SmartThingsApi? = null
    @Volatile
    private var currentClient: OkHttpClient? = null
    private var lastBaseUrl: String? = null
    private var lastDebugMode: Boolean? = null

    /** Grace period before an old client's dispatcher executor is actually shut down. */
    private const val EXECUTOR_SHUTDOWN_DELAY_MS = 10_000L

    private var diagnosticApi: SmartThingsApi? = null
    private var diagnosticClient: OkHttpClient? = null
    private var diagnosticUrl: HttpUrl? = null

    private fun createClient(context: Context, debugMode: Boolean, endpoint: HttpUrl, diagnostic: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // Test TLS bypass and origin-scoped authentication must not follow redirects.
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(FallbackDns(context.applicationContext))  // Use custom DNS resolver
            .retryOnConnectionFailure(true)
            // Use a very aggressive connection pool - don't keep idle connections long
            // This helps prevent stale connections when network state changes
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            // Overall timeout for entire call - prevents hanging requests
            .callTimeout(if (debugMode) 15 else 45, TimeUnit.SECONDS)
            .connectTimeout(if (debugMode) 8 else 20, TimeUnit.SECONDS)
            .readTimeout(if (debugMode) 8 else 20, TimeUnit.SECONDS)
            .writeTimeout(if (debugMode) 8 else 20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val appContext = context.applicationContext
                val originalRequest = chain.request()
                val requestUrl = originalRequest.url.toString()

                val requestBuilder = originalRequest.newBuilder().removeHeader("Authorization")
                val authorization = NetworkPolicy.authorization(
                    originalRequest.url, endpoint, debugMode, diagnostic,
                    getPreference("personal_access_token", String::class.java, appContext) as? String ?: "",
                    getPreference("debug_access_token", String::class.java, appContext) as? String ?: ""
                )
                if (authorization != null) {
                    requestBuilder.header("Authorization", authorization)
                }
                
                val request = requestBuilder.build()

                // Simple single attempt - let OkHttp's retryOnConnectionFailure handle retries
                // This ensures callTimeout is properly respected
                Log.d("SmartThingsService", "Attempting request: $requestUrl")
                chain.proceed(request)
            }

        if (debugMode) {
            configureUnsafeSsl(builder)
        }

        return builder.build()
    }

    /**
     * Force the singleton to be recreated on next getInstance() call.
     * Call this when the process transitions from background to foreground service
     * so that fresh sockets are created with the elevated process priority.
     * Android tags sockets with the process OOM-adj at creation time; a client
     * created while backgrounded may have its connections throttled even after
     * the process is promoted to foreground.
     */
    @Synchronized
    fun resetClient() {
        Log.i("SmartThingsService", "resetClient: forcing new OkHttpClient on next request")
        shutdownCurrentClient()
        instance = null
    }

    /**
     * Cleanly shut down the old OkHttpClient's thread pools and connection pool.
     *
     * Connections are evicted immediately (forces fresh sockets on the next request,
     * which is the whole point of resetClient()). The dispatcher's executor is shut
     * down on a DELAY instead of immediately: another thread may have already grabbed
     * a reference to this client via getInstance() (under a separate synchronized call)
     * and be about to enqueue a request on it. Shutting the executor down synchronously
     * here would reject that in-flight request with a RejectedExecutionException, which
     * GarageUtility.accessError() then counts as a real network failure — eventually
     * flipping the garage status to UNKNOWN even though the network is fine.
     */
    private fun shutdownCurrentClient() {
        currentClient?.let { old ->
            try {
                old.connectionPool.evictAll()
                val executor = old.dispatcher.executorService
                Thread {
                    try {
                        Thread.sleep(EXECUTOR_SHUTDOWN_DELAY_MS)
                        executor.shutdown()
                    } catch (e: Exception) {
                        Log.w("SmartThingsService", "Error shutting down old client executor: ${e.message}")
                    }
                }.start()
            } catch (e: Exception) {
                Log.w("SmartThingsService", "Error evicting old client connections: ${e.message}")
            }
        }
        currentClient = null
    }

    @Synchronized
    fun getInstance(context: Context): SmartThingsApi {
        val debugMode = getPreference("debug_switch", Boolean::class.java, context) as? Boolean == true
        val currentBaseUrl = getSmartThingsURL(context, debugMode)
        
        if (currentBaseUrl != lastBaseUrl || debugMode != lastDebugMode) {
            shutdownCurrentClient()
            instance = null
            lastBaseUrl = currentBaseUrl
            lastDebugMode = debugMode
        }

        return instance ?: run {
            val endpoint = if (debugMode) NetworkPolicy.testUrl(currentBaseUrl) else NetworkPolicy.productionUrl
            val client = createClient(context, debugMode, endpoint)
            currentClient = client
            Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build()
                .create(SmartThingsApi::class.java)
                .also { instance = it }
        }
    }

    /** Pings cannot mutate the garage client cache or carry any bearer credential. */
    @Synchronized
    fun getDiagnosticInstance(context: Context): SmartThingsApi {
        val endpoint = NetworkPolicy.testUrl(getSmartThingsURL(context, true))
        if (diagnosticUrl != endpoint) {
            diagnosticClient?.connectionPool?.evictAll()
            diagnosticClient = null
            diagnosticApi = null
            diagnosticUrl = endpoint
        }
        return diagnosticApi ?: run {
            val client = createClient(context, true, endpoint, diagnostic = true)
            diagnosticClient = client
            Retrofit.Builder().baseUrl(endpoint)
                .addConverterFactory(GsonConverterFactory.create()).client(client).build()
                .create(SmartThingsApi::class.java).also { diagnosticApi = it }
        }
    }

    private fun configureUnsafeSsl(builder: OkHttpClient.Builder) {
        try {
            val trustAllCerts = arrayOf<TrustManager>(@SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            throw RuntimeException("Failed to configure unsafe SSL", e)
        }
    }

    private fun getSmartThingsURL(context: Context, debugMode: Boolean = false): String {
        return if (debugMode) {
           getPreference("debug_server", context, "https://10.0.2.2:3000")
        } else {
            "https://api.smartthings.com"
        }
    }
}
