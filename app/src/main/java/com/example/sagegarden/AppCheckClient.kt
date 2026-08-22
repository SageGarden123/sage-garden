package com.example.sagegarden

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.initialize
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Attests that Sage Cloud Function calls are genuinely coming from this app (not a copy of the
 * request replayed against the bare Functions URL, which is otherwise unauthenticated beyond a
 * client-supplied deviceId). [init] must run once before the first network call — see
 * GardenMapperApp's startup effect. [token] fetches a short-lived attestation token to attach as
 * the "X-Firebase-AppCheck" header on every SageClient request.
 */
object AppCheckClient {
    fun init(context: Context) {
        Firebase.initialize(context)
        Firebase.appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }

    /** Null on failure (e.g. Play Integrity unavailable, no network) — callers should still send the request rather than block the user, since the ceiling/entitlement checks server-side are the real backstop, not this. */
    suspend fun token(): String? = suspendCancellableCoroutine { cont ->
        Firebase.appCheck.getAppCheckToken(false)
            .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result.token) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }
}
