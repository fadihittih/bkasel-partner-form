package com.fadi.healthsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads batches to the backend: POST {serverUrl}/api/v1/ingest with a bearer token.
 * Never logs request or response bodies.
 */
class BackendClient(serverUrl: String, private val token: String) {

    private val endpoint: HttpUrl

    init {
        val base = serverUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalStateException("Server URL is not set or invalid")
        // HTTPS only. Plain HTTP is allowed to the phone itself (adb reverse testing).
        val local = base.host == "127.0.0.1" || base.host == "localhost"
        if (!base.isHttps && !local) throw IllegalStateException("Server URL must use https://")
        if (token.isBlank()) throw IllegalStateException("API token is not set")
        endpoint = base.newBuilder().addPathSegments("api/v1/ingest").build()
    }

    suspend fun ingest(payload: IngestPayload) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(IngestPayload.serializer(), payload)
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw IllegalStateException("Server rejected the API token (HTTP ${response.code})")
            }
            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")
        }
    }

    companion object {
        private val json = Json { encodeDefaults = true }
        private val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
