package com.inkwell.net

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the pairing credentials: the device bearer token and the server base URL.
 * The token is a secret (ADR-0008), so it never touches plain SharedPreferences.
 */
interface TokenStore {
    fun getToken(): String?
    fun setToken(token: String?)
    fun getBaseUrl(): String?
    fun setBaseUrl(url: String?)
}

/**
 * [TokenStore] backed by [EncryptedSharedPreferences] with an Android Keystore master
 * key (ADR-0008 / SPEC §11). Instantiated on-device; the pure poll/parse logic that
 * unit tests exercise depends only on the [TokenStore] interface, not this class.
 */
class EncryptedTokenStore(context: Context) : TokenStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "inkwell_pairing",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    override fun setToken(token: String?) {
        prefs.edit().apply {
            if (token.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
        }.apply()
    }

    override fun getBaseUrl(): String? = prefs.getString(KEY_BASE_URL, null)

    override fun setBaseUrl(url: String?) {
        prefs.edit().apply {
            if (url.isNullOrBlank()) remove(KEY_BASE_URL) else putString(KEY_BASE_URL, url)
        }.apply()
    }

    private companion object {
        const val KEY_TOKEN = "device_token"
        const val KEY_BASE_URL = "base_url"
    }
}
