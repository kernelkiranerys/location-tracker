package com.example.locationtracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.CertificatePinner
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class ApiClientManager(
    private val context: Context,
    private val settingsStore: TrackerSettingsStore,
) {
    private var client: OkHttpClient? = null
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    fun getHost(): String = settingsStore.read().serverHost.ifBlank { "tracker.example.com" }
    fun getPort(): Int = settingsStore.read().serverPort
    fun getCertPinHash(): String = settingsStore.read().certPinHash

    private fun signedRequestBuilder(path: String, body: ByteArray): Request.Builder {
        val settings = settingsStore.read()
        val timestamp = System.currentTimeMillis()
        val nonce = SecurityManager.generateNonce()
        val signature = SecurityManager.signRequest(settings.deviceId, timestamp, nonce, body, settings.secretKeyBase64)

        return Request.Builder()
            .url("${getBaseUrl()}$path")
            .addHeader("X-Device-Id", settings.deviceId)
            .addHeader("X-Timestamp", timestamp.toString())
            .addHeader("X-Nonce", nonce)
            .addHeader("X-Signature", signature)
    }

    private fun buildSslContext(): Pair<SSLContext, X509TrustManager> {
        val certFactory = CertificateFactory.getInstance("X.509")
        val serverCert: X509Certificate = context.resources.openRawResource(R.raw.server_cert).use { stream: InputStream ->
            certFactory.generateCertificate(stream) as X509Certificate
        }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("server-cert", serverCert)
        }

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }

        val trustManager = trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().first()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }

        return sslContext to trustManager
    }

    @Synchronized
    fun getClient(): OkHttpClient {
        val cachedClient = client
        if (cachedClient != null) {
            return cachedClient
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        try {
            val (sslContext, trustManager) = buildSslContext()
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        } catch (e: Exception) {
            Log.w("ApiClientManager", "Could not load embedded server cert, falling back to system trust", e)
        }

        val configuredHost = getHost()
        builder.hostnameVerifier { hostname, _ -> hostname == configuredHost }

        builder.cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val host = url.host
                synchronized(cookieStore) {
                    cookieStore[host]?.clear()
                    cookieStore[host] = cookies.toMutableList()
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val host = url.host
                return synchronized(cookieStore) {
                    cookieStore[host]?.toList() ?: emptyList()
                }
            }
        })

        val certPinHash = getCertPinHash()
        if (certPinHash.isNotBlank()) {
            val certPinner = CertificatePinner.Builder()
                .add(getHost(), "sha256/$certPinHash")
                .build()
            builder.certificatePinner(certPinner)
        }

        val newClient = builder.build()

        client = newClient
        return newClient
    }

    fun resetClient() {
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }

    fun getBaseUrl(): String = "https://${getHost()}:${getPort()}"

    fun sendLocation(payload: String): Boolean = postJson("/api/location/", payload)

    fun sendLogs(logsJson: String): Boolean = postJson("/api/logs/", logsJson)

    fun sendPrivacyRequest(hours: Int): Boolean {
        val json = JSONObject().put("hours", hours).toString()
        return postJson("/api/privacy/", json)
    }

    fun isLocationRequestPending(): Boolean {
        val request = signedRequestBuilder("/api/pending-request/", ByteArray(0)).get().build()

        try {
            getClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return false
                }

                val responseBody = response.body?.string().orEmpty()
                if (responseBody.isBlank()) {
                    return false
                }

                return JSONObject(responseBody).optBoolean("request_location", false)
            }
        } catch (exception: javax.net.ssl.SSLPeerUnverifiedException) {
            throw ServerCertificateVerificationException(exception)
        } catch (exception: Exception) {
            Log.w("ApiClientManager", "Failed to check pending request", exception)
            return false
        }
    }

    private fun postJson(path: String, json: String): Boolean {
        val body = json.toByteArray()
        val request = signedRequestBuilder(path, body)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            getClient().newCall(request).execute().use { response ->
                Log.d("ApiClientManager", "POST $path -> HTTP ${response.code}")
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    Log.w("ApiClientManager", "Server error body: $errorBody")
                    return false
                }
                return true
            }
        } catch (exception: javax.net.ssl.SSLPeerUnverifiedException) {
            throw ServerCertificateVerificationException(exception)
        } catch (exception: Exception) {
            Log.w("ApiClientManager", "Request failed for $path", exception)
            return false
        }
    }

    fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // --- Admin API ---

    fun adminLogin(username: String, password: String): Boolean {
        val json = JSONObject().put("username", username).put("password", password).toString()
        val body = json.toByteArray()
        val request = Request.Builder()
            .url("${getBaseUrl()}/api/admin-login/")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            getClient().newCall(request).execute().use { response ->
                Log.d("ApiClientManager", "Admin login -> HTTP ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w("ApiClientManager", "Admin login failed", e)
            false
        }
    }

    fun adminGetClientLocations(): JSONArray? {
        val request = Request.Builder()
            .url("${getBaseUrl()}/api/admin/client-locations/")
            .get()
            .build()

        return try {
            getClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("ApiClientManager", "Client locations -> HTTP ${response.code}")
                    return null
                }
                JSONObject(response.body?.string() ?: "{}").optJSONArray("clients") ?: JSONArray()
            }
        } catch (e: Exception) {
            Log.w("ApiClientManager", "Failed to get client locations", e)
            null
        }
    }

    fun adminCreateDevice(name: String): JSONObject? {
        val json = JSONObject().put("name", name).toString()
        val body = json.toByteArray()
        val request = Request.Builder()
            .url("${getBaseUrl()}/api/admin/create-device/")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            getClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("ApiClientManager", "Create device -> HTTP ${response.code}")
                    return null
                }
                JSONObject(response.body?.string() ?: "{}")
            }
        } catch (e: Exception) {
            Log.w("ApiClientManager", "Failed to create device", e)
            null
        }
    }

    fun adminGetNearbyDevices(lat: Double, lng: Double, radiusM: Double): JSONArray? {
        val request = Request.Builder()
            .url("${getBaseUrl()}/api/admin/nearby-devices/?lat=$lat&lng=$lng&radius=$radiusM")
            .get()
            .build()

        return try {
            getClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("ApiClientManager", "Nearby devices -> HTTP ${response.code}")
                    return null
                }
                JSONObject(response.body?.string() ?: "{}").optJSONArray("devices") ?: JSONArray()
            }
        } catch (e: Exception) {
            Log.w("ApiClientManager", "Failed to get nearby devices", e)
            null
        }
    }

    fun adminGetDeviceHistory(mac: String, lat: Double, lng: Double): JSONArray? {
        val request = Request.Builder()
            .url("${getBaseUrl()}/api/admin/device-history/?mac=$mac&lat=$lat&lng=$lng")
            .get()
            .build()

        return try {
            getClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("ApiClientManager", "Device history -> HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                JSONObject(body).optJSONArray("locations") ?: JSONArray()
            }
        } catch (e: Exception) {
            Log.w("ApiClientManager", "Failed to get device history", e)
            null
        }
    }
}

class ServerCertificateVerificationException(cause: Throwable) : RuntimeException(
    "Server certificate verification failed. Check that the server address and cert pin are correct.",
    cause,
)
