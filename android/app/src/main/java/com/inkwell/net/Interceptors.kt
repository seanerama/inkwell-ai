package com.inkwell.net

import okhttp3.Interceptor
import okhttp3.Response

/** Contract header required on every request (contract device-api §Versioning). */
const val CONTRACT_HEADER = "X-Inkwell-Contract"
const val CONTRACT_VALUE = "device-api/v1"

class ContractInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header(CONTRACT_HEADER, CONTRACT_VALUE)
            .build()
        return chain.proceed(request)
    }
}

/**
 * Adds `Authorization: Bearer <token>` from the [TokenStore] (ADR-0008). `/health`
 * is unauthenticated (contract device-api), so the header is only added when a token
 * is present; every other route needs it. The token is read fresh per request so a
 * newly pasted token takes effect immediately.
 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.getToken()
        val builder = chain.request().newBuilder()
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return chain.proceed(builder.build())
    }
}
