package com.example.sagegarden

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Event-driven replacement for polling syncGarden every minute while the app is open. The server
 * bumps gardenSignals/{gardenId}.rev whenever a sync actually changes a garden (see
 * firebase/functions/src/gardenSignals.ts); this listens to that tiny doc for every known garden
 * while the app is in the foreground and calls GardenSyncClient.sync only when the rev moves past
 * the rev this device last synced to — so another member's edit (including a new photo thumbnail)
 * shows up within a second or two, and an idle garden costs nothing.
 *
 * Reading gardenSignals needs a Firebase Auth uid that syncGarden has granted access to, after its
 * usual memberToken check. Every sync sends this device's anonymous ID token, so access is granted
 * on the first sync after an upgrade, and a garden is only listened to once a sync has confirmed
 * the grant. If anything is missing (Anonymous Auth disabled in the console, functions not yet
 * deployed, access revoked, no Google Play services), the listener simply isn't live and
 * MainActivity's fallback loop keeps polling every 60s exactly as before — see [isLive].
 */
object RealtimeGardenSync {
    private const val TAG = "RealtimeGardenSync"

    private val listeners = ConcurrentHashMap<String, ListenerRegistration>()
    private val liveGardens = ConcurrentHashMap.newKeySet<String>()
    // A garden with no confirmed grant gets ONE sync per foreground session to try to obtain one —
    // never repeatedly, or a device whose grant can't succeed would re-sync every garden every loop.
    private val grantAttempted = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var scope: CoroutineScope? = null

    /** True once [gardenId]'s listener has delivered a snapshot and hasn't errored since — MainActivity polls slowly while this holds and every 60s otherwise. */
    fun isLive(gardenId: String): Boolean = gardenId in liveGardens

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            val e = task.exception
            if (e != null) cont.resumeWithException(e) else cont.resume(task.result)
        }
    }

    private suspend fun ensureSignedIn(): Boolean = try {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser != null || auth.signInAnonymously().await().user != null
    } catch (e: Exception) {
        Log.w(TAG, "anonymous sign-in failed — staying on polling", e)
        false
    }

    /** Sent with every sync so the server can grant this device's uid access to the change signal. Never signs in itself — background workers shouldn't block on that — so it's null until the foreground app has signed in once (the uid then persists across restarts). */
    suspend fun idTokenOrNull(): String? = try {
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
    } catch (e: Exception) {
        null
    }

    /** Called from MainActivity on ON_START. */
    fun start(context: Context, scope: CoroutineScope) {
        this.scope = scope
        scope.launch { if (ensureSignedIn()) refreshListeners(context) }
    }

    /** Called from MainActivity on ON_STOP — no open connection while the app isn't visible; background work keeps using plain syncs. */
    fun stop() {
        scope = null
        listeners.values.forEach { it.remove() }
        listeners.clear()
        liveGardens.clear()
        grantAttempted.clear()
    }

    /** Aligns the set of listened-to gardens with the known-gardens list — call after it's refreshed, since gardens can be joined, left, or removed at any time. */
    fun refreshListeners(context: Context) {
        val scope = scope ?: return
        if (FirebaseAuth.getInstance().currentUser == null) return
        val wanted = allKnownGardenIds(context).toSet()
        (listeners.keys - wanted).forEach { detach(it) }
        for (gardenId in wanted) {
            if (listeners.containsKey(gardenId)) continue
            if (GardenSyncStore.isSignalGranted(context, gardenId)) attach(context, gardenId)
            else if (grantAttempted.add(gardenId)) {
                // The sync sends our ID token, and its onSynced callback attaches the listener once granted.
                scope.launch { GardenSyncClient.sync(context, getOrCreateInstallId(context), gardenId) }
            }
        }
    }

    /** Called by GardenSyncClient after every successful sync. [granted] is true when the request carried an ID token AND the server understood it (returned a signalRev). */
    fun onSynced(context: Context, gardenId: String, granted: Boolean) {
        if (!granted) return
        GardenSyncStore.setSignalGranted(context, gardenId, true)
        if (scope != null && !listeners.containsKey(gardenId)) attach(context, gardenId)
    }

    private fun detach(gardenId: String) {
        listeners.remove(gardenId)?.remove()
        liveGardens.remove(gardenId)
    }

    private fun attach(context: Context, gardenId: String) {
        val registration = FirebaseFirestore.getInstance().collection("gardenSignals").document(gardenId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Most likely PERMISSION_DENIED: access revoked (removed from the garden) or the
                    // grant was lost. Drop back to polling for this garden; the next sync re-grants
                    // it if this device still legitimately has access.
                    Log.w(TAG, "listener for $gardenId failed — falling back to polling", error)
                    detach(gardenId)
                    GardenSyncStore.setSignalGranted(context, gardenId, false)
                    return@addSnapshotListener
                }
                liveGardens.add(gardenId)
                val scope = scope ?: return@addSnapshotListener
                val rev = snapshot?.getLong("rev") ?: 0L
                if (rev > GardenSyncStore.getSignalRev(context, gardenId)) {
                    scope.launch { GardenSyncClient.sync(context, getOrCreateInstallId(context), gardenId) }
                }
                val membershipRev = snapshot?.getLong("membershipRev") ?: 0L
                if (membershipRev > GardenSyncStore.getMembershipRev(context, gardenId)) {
                    GardenSyncStore.setMembershipRev(context, gardenId, membershipRev)
                    scope.launch {
                        GardenMembershipClient.refreshKnownGardens(context)
                        refreshListeners(context)
                    }
                }
            }
        listeners.put(gardenId, registration)?.remove()
    }
}
