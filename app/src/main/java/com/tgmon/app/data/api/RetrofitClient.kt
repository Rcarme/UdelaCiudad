package com.tgmon.app.data.api

import android.util.Log
import okhttp3.Authenticator
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.tgmon.app.data.models.ContainerListResponse
import com.tgmon.app.data.models.ContainerListResponseDeserializer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private val clients = ConcurrentHashMap<String, OkHttpClient>()
    private val retrofits = ConcurrentHashMap<String, Retrofit>()
    private var tokenProvider: () -> String? = { null }
    private var tokenInvalidationCallback: (() -> Unit)? = null
    private var serverUrlProvider: () -> String? = { null }
    private const val TAG = "RetrofitClient"
    // Default base URL points to Android Emulator host loopback (10.0.2.2)
    // Synchronized with PreferencesManager & SetupViewModel.
    private const val DEFAULT_BASE_URL = "http://10.0.2.2:8000/"
    private const val TAILSCALE_BASE_URL = "http://10.0.2.2:8000/"
    private const val LOCAL_BASE_URL = "http://10.0.2.2:8000/"

    // Single Gson instance for N97 low-memory: reuse everywhere, registers ContainerListResponse deserializer
    // that handles both object {"containers": [...]} and bare [...] for backward compat without extra allocations.
    val tgmonGson: Gson by lazy {
        GsonBuilder()
            .registerTypeAdapter(ContainerListResponse::class.java, ContainerListResponseDeserializer())
            .create()
    }

    fun setTokenProvider(provider: () -> String?) {
        tokenProvider = provider
    }

    fun setTokenInvalidationCallback(callback: () -> Unit) {
        val previous = tokenInvalidationCallback
        tokenInvalidationCallback = {
            try {
                previous?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Previous tokenInvalidationCallback failed: ${e.message}")
            }
            try {
                callback()
            } catch (e: Exception) {
                Log.w(TAG, "New tokenInvalidationCallback failed: ${e.message}")
            }
        }
    }

    fun setServerUrlProvider(provider: () -> String?) {
        serverUrlProvider = provider
    }

    fun getBaseUrl(): String {
        return try {
            val preferred = serverUrlProvider()
            if (!preferred.isNullOrBlank()) {
                preferred
            } else {
                DEFAULT_BASE_URL
            }
        } catch (e: Exception) {
            DEFAULT_BASE_URL
        }
    }

    fun getAvailableBaseUrls(): List<String> {
        return listOf(TAILSCALE_BASE_URL, LOCAL_BASE_URL)
    }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val token = tokenProvider()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No token available for ${original.url}, proceeding without auth")
            return@Interceptor chain.proceed(original)
        }
        val request = original.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        chain.proceed(request)
    }

    private val authAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.code == 401) {
                tokenInvalidationCallback?.invoke()
                Log.w(TAG, "Authenticator: 401 received, token invalidated")
                return null
            }
            val token = tokenProvider() ?: return null
            return response.request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
    }

    private val loggingInterceptor = Interceptor { chain ->
        val request = chain.request()
        val startTime = System.nanoTime()
        val response = chain.proceed(request)
        val endTime = System.nanoTime()
        val durationMs = (endTime - startTime) / 1_000_000
        Log.d(TAG, "HTTP ${request.method} ${request.url} - ${response.code} (${durationMs}ms)")
        response
    }

    private val retryInterceptor = Interceptor { chain ->
        val request = chain.request()
        var response = chain.proceed(request)
        var tryCount = 0
        val maxRetries = 2
        while (!response.isSuccessful && tryCount < maxRetries && response.code in 500..599 && request.method == "GET") {
            tryCount++
            val delayMs = minOf(500L * tryCount, 1000L)
            Thread.sleep(delayMs)
            response.close()
            response = chain.proceed(request)
        }
        response
    }

    private val errorResponseInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isSuccessful && response.code in 400..499) {
            val responseBody = response.peekBody(1024 * 1024)
            try {
                val errorBody = responseBody.string()
                Log.w(TAG, "HTTP error ${response.code}: $errorBody")
            } catch (e: Exception) {
                Log.w(TAG, "HTTP error ${response.code}: unable to read body")
            }
        }
        response
    }

    private val cacheInterceptor = Interceptor { chain ->
        val request = chain.request()
        val cacheControl = CacheControl.Builder()
            .maxAge(5, TimeUnit.MINUTES)
            .build()
        val response = chain.proceed(request.newBuilder()
            .cacheControl(cacheControl)
            .build())
        response
    }

    private fun getOkHttpClient(
        baseUrl: String,
        connectTimeout: Long,
        readTimeout: Long,
        writeTimeout: Long,
        useCache: Boolean = false
    ): OkHttpClient {
        val key = "${baseUrl}_${connectTimeout}_${readTimeout}_${writeTimeout}_$useCache"
        return clients.computeIfAbsent(key) {
            val builder = OkHttpClient.Builder()
                .connectTimeout(connectTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .writeTimeout(writeTimeout, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(authInterceptor)
                .addInterceptor(loggingInterceptor)
                .addInterceptor(retryInterceptor)
                .addInterceptor(errorResponseInterceptor)
                .authenticator(authAuthenticator)
            if (useCache) {
                builder.addNetworkInterceptor(cacheInterceptor)
            }
            builder.build()
        }
    }

    fun createApi(
        baseUrl: String = getBaseUrl(),
        connectTimeoutSeconds: Long = 10,
        readTimeoutSeconds: Long = 30,
        writeTimeoutSeconds: Long = 30,
        useCache: Boolean = false
    ): TgMonApi {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val key = "${normalizedUrl}_${connectTimeoutSeconds}_${readTimeoutSeconds}_${writeTimeoutSeconds}_$useCache"
        return retrofits.computeIfAbsent(key) {
            Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(getOkHttpClient(normalizedUrl, connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds, useCache))
                // Use tgmonGson with ContainerListResponse deserializer for wrapper/bare-list compat
                .addConverterFactory(GsonConverterFactory.create(tgmonGson))
                .build()
        }.create(TgMonApi::class.java)
    }

    fun createLogsApi(baseUrl: String = getBaseUrl()): TgMonApi {
        return createApi(baseUrl, connectTimeoutSeconds = 10, readTimeoutSeconds = 120, writeTimeoutSeconds = 60)
    }

    fun createHealthApi(baseUrl: String = getBaseUrl()): TgMonApi {
        return createApi(baseUrl, connectTimeoutSeconds = 5, readTimeoutSeconds = 10, writeTimeoutSeconds = 10)
    }

    fun createHistoryApi(baseUrl: String = getBaseUrl()): TgMonApi {
        return createApi(baseUrl, connectTimeoutSeconds = 10, readTimeoutSeconds = 60, writeTimeoutSeconds = 30)
    }

    fun resetClient() {
        clients.values.forEach { client ->
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        clients.clear()
        retrofits.clear()
    }
}