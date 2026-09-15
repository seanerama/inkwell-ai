package com.inkwell.net

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds a [DeviceApi] bound to a server base URL, wiring the contract header, the
 * bearer [AuthInterceptor], and kotlinx.serialization (contract device-api). The base
 * URL is normalized to end with `/` so Retrofit resolves the `v1/...` paths correctly.
 */
object ApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    fun create(baseUrl: String, tokenStore: TokenStore): DeviceApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(ContractInterceptor())
            .addInterceptor(AuthInterceptor(tokenStore))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeviceApi::class.java)
    }
}
