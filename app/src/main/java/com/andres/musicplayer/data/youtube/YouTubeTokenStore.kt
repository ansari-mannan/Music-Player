package com.andres.musicplayer.data.youtube

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds the YouTube OAuth access token, encrypted at rest.
 *
 * Only the short-lived access token is ever kept here — there is no refresh token to
 * store, because Play Services owns the long-lived grant and hands back a fresh access
 * token silently (see [YouTubeAuth]). The token is still treated as a secret: it is
 * written through [EncryptedSharedPreferences], never plain prefs.
 *
 * If the encrypted store can't be opened (a corrupted keystore entry, e.g. after a
 * restore-from-backup), the file is dropped and rebuilt once; if that also fails the
 * store degrades to memory-only. Nothing is ever written in plaintext as a fallback —
 * losing the cached token only costs one silent re-authorization.
 */
class YouTubeTokenStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences? = openEncrypted(appContext)

    /** Used only when [prefs] could not be opened — cleared when the process dies. */
    private val fallback = HashMap<String, String?>()

    /** True once the user has completed the consent screen at least once. */
    val isConnected: Boolean
        get() = read(KEY_CONNECTED) == "true"

    /** The email (or channel name) shown in Settings as "Connected as …". */
    val accountLabel: String?
        get() = read(KEY_ACCOUNT_LABEL)

    /** The cached access token, or null if absent or old enough to be worth refreshing. */
    val accessToken: String?
        get() {
            val token = read(KEY_ACCESS_TOKEN) ?: return null
            val obtainedAt = read(KEY_OBTAINED_AT)?.toLongOrNull() ?: return null
            val age = System.currentTimeMillis() - obtainedAt
            return if (age in 0..TOKEN_TTL_MS) token else null
        }

    fun saveToken(token: String) = write(
        KEY_CONNECTED to "true",
        KEY_ACCESS_TOKEN to token,
        KEY_OBTAINED_AT to System.currentTimeMillis().toString(),
    )

    fun saveAccountLabel(label: String?) = write(KEY_ACCOUNT_LABEL to label)

    /** Forgets the cached token but stays connected, so the next call re-authorizes. */
    fun clearToken() = write(KEY_ACCESS_TOKEN to null, KEY_OBTAINED_AT to null)

    fun clearAll() = write(
        KEY_CONNECTED to null,
        KEY_ACCESS_TOKEN to null,
        KEY_OBTAINED_AT to null,
        KEY_ACCOUNT_LABEL to null,
    )

    private fun read(key: String): String? =
        if (prefs != null) prefs.getString(key, null) else fallback[key]

    private fun write(vararg entries: Pair<String, String?>) {
        if (prefs == null) {
            entries.forEach { (key, value) -> fallback[key] = value }
            return
        }
        prefs.edit().apply {
            entries.forEach { (key, value) ->
                if (value == null) remove(key) else putString(key, value)
            }
        }.apply()
    }

    private companion object {
        const val TAG = "YouTubeTokenStore"
        const val PREFS_NAME = "youtube_auth"

        const val KEY_CONNECTED = "connected"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_OBTAINED_AT = "obtained_at"
        const val KEY_ACCOUNT_LABEL = "account_label"

        /**
         * Google access tokens last an hour; refresh at 45 minutes so a request never
         * goes out with a token that expires mid-flight.
         */
        const val TOKEN_TTL_MS = 45L * 60L * 1000L

        fun openEncrypted(context: Context): SharedPreferences? =
            runCatching { build(context) }.getOrElse { first ->
                Log.w(TAG, "Encrypted prefs unreadable, rebuilding", first)
                context.deleteSharedPreferences(PREFS_NAME)
                runCatching { build(context) }.getOrElse { second ->
                    Log.e(TAG, "Encrypted prefs unavailable; token kept in memory only", second)
                    null
                }
            }

        fun build(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
