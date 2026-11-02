package com.andres.musicplayer.data.youtube

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Raised when the account needs to be (re)connected before an API call can be made. */
class NotConnectedException(message: String) : Exception(message)

/**
 * Owns the Google account connection used for YouTube reads.
 *
 * Uses Google Identity Services' Authorization API ([AuthorizationClient][com.google.android.gms.auth.api.identity.AuthorizationClient]),
 * not the sign-in-only ID-token flow: sign-in returns who the user is, whereas
 * authorization returns an OAuth access token carrying the `youtube.readonly` scope,
 * which is what the Data API actually needs.
 *
 * There is deliberately no refresh token in the app. Play Services keeps the long-lived
 * grant, so once the user has approved the consent screen every later [authorize] call
 * comes back silently (no `pendingIntent`) with a fresh access token — that is what
 * satisfies "the account picker is only ever seen once". Access tokens are cached in
 * [YouTubeTokenStore] for their useful life so a token isn't re-fetched per request.
 *
 * Requires an **Android** OAuth 2.0 client ID in the Google Cloud project, registered
 * against this app's package name and signing-certificate SHA-1. There is no client ID
 * or secret to embed in the code — Play Services matches the caller by signature.
 */
class YouTubeAuth(context: Context) {

    private val appContext = context.applicationContext
    private val client = Identity.getAuthorizationClient(appContext)
    private val store = YouTubeTokenStore(appContext)

    val isConnected: Boolean get() = store.isConnected
    val accountLabel: String? get() = store.accountLabel

    /** Outcome of an authorization attempt. */
    sealed interface Outcome {
        /** A usable access token was obtained without showing anything to the user. */
        data class Authorized(val accessToken: String) : Outcome

        /** The user must approve the consent screen; launch [intentSender] to show it. */
        data class NeedsConsent(val intentSender: IntentSender) : Outcome
    }

    /**
     * Requests an access token, silently when possible.
     *
     * Returns [Outcome.NeedsConsent] only on the first connection (or after the user
     * revokes access in their Google account), in which case the caller must launch the
     * intent sender and feed the result back through [onConsentResult].
     */
    suspend fun authorize(): Outcome {
        val result = client.authorize(request).await()
        val pendingIntent = result.pendingIntent
        return if (result.hasResolution() && pendingIntent != null) {
            Outcome.NeedsConsent(pendingIntent.intentSender)
        } else {
            Outcome.Authorized(store(result))
        }
    }

    /**
     * Completes the consent flow from the activity result, returning the access token.
     * Returns null if the user dismissed the consent screen.
     */
    fun onConsentResult(data: Intent?): String? {
        if (data == null) return null
        val result = runCatching { client.getAuthorizationResultFromIntent(data) }
            .getOrNull() ?: return null
        return runCatching { store(result) }.getOrNull()
    }

    /**
     * The access token to put in an `Authorization: Bearer` header, using the cached one
     * when it is still fresh. Throws [NotConnectedException] if the account has never
     * been connected, or if the silent path unexpectedly wants the consent screen again.
     */
    suspend fun requireAccessToken(): String {
        if (!store.isConnected) {
            throw NotConnectedException("No YouTube account connected.")
        }
        store.accessToken?.let { return it }
        return when (val outcome = authorize()) {
            is Outcome.Authorized -> outcome.accessToken
            is Outcome.NeedsConsent ->
                throw NotConnectedException("YouTube access needs to be re-approved.")
        }
    }

    /** Drops the cached token so the next [requireAccessToken] fetches a fresh one. */
    fun invalidateToken() = store.clearToken()

    fun saveAccountLabel(label: String?) = store.saveAccountLabel(label)

    /**
     * Forgets the connection locally. This does not revoke the grant on Google's side —
     * that is done from the account's own permissions page — so reconnecting afterwards
     * is silent rather than showing the consent screen again.
     */
    fun disconnect() = store.clearAll()

    /** Persists the token from [result] and returns it. */
    private fun store(result: AuthorizationResult): String {
        val token = result.accessToken
            ?: throw NotConnectedException("Google returned no access token.")
        store.saveToken(token)
        // Available when the grant came with basic profile info; the account's YouTube
        // channel name is fetched as a fallback label by the repository.
        result.toGoogleSignInAccount()?.email?.let { store.saveAccountLabel(it) }
        return token
    }

    private companion object {
        val request: AuthorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/youtube.readonly")))
            .build()
    }
}

/** Bridges a Play Services [Task] into a coroutine without pulling in another library. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
