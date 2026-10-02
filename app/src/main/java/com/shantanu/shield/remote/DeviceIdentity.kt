package com.shantanu.shield.remote

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * A stable per-install identifier, used to record which two devices a pairing belongs to.
 *
 * An interface rather than a direct Firebase call so the membership logic stays testable without
 * Firebase, and so the rest of Phase 7 could be written before the dependency existed.
 * See PHASE7_AUTH_PLAN.md.
 */
interface DeviceIdentity {
    /**
     * This install's UID, or null when no identity is available.
     *
     * Null is a normal, expected answer — not an error. Callers must treat it as "record no membership"
     * and carry on: the child's budget, night lock, app locking and web filtering never touch the relay,
     * and a device that cannot reach Firebase must still protect the child.
     */
    suspend fun uid(): String?
}

/**
 * Anonymous Firebase identity — the whole of the app's authentication.
 *
 * No account, login, email or password is ever shown to a user. The UID exists only so the Firestore
 * rules can restrict a pairing's documents to the two devices that actually paired, instead of to
 * anyone who learns the 128-bit pairing id.
 *
 * Persisted by Firebase locally, so after the first successful sign-in this needs no network and
 * survives restarts. It does NOT survive uninstall or clearing app data — a fresh UID then locks the
 * device out of its own documents and the pair must be re-made. That is the accepted cost of Phase 7
 * (plan §1), and the reason it was done before launch rather than after.
 */
@Singleton
class FirebaseAnonymousIdentity @Inject constructor() : DeviceIdentity {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    /**
     * Serialises sign-in.
     *
     * Several callers can ask at once — the report upload, a grant renewal and a location response can
     * easily coincide — and without this they would each fire their own `signInAnonymously()`. Firebase
     * tolerates that, but it is wasted round trips at exactly the moment the network is busy.
     */
    private val signInLock = Mutex()

    override suspend fun uid(): String? = runCatching {
        // Fast path: already signed in, no I/O. True for every call after the first launch.
        auth.currentUser?.uid?.let { return@runCatching it }

        signInLock.withLock {
            // Re-check inside the lock: another caller may have signed in while this one waited.
            auth.currentUser?.uid ?: signInAnonymously()
        }
    }.getOrElse {
        // Never propagate. An unavailable identity means "no membership recorded", not a broken app.
        Log.w(TAG, "anonymous sign-in unavailable", it)
        null
    }

    /**
     * Listener-based rather than `.await()` — `kotlinx-coroutines-play-services` is not a dependency,
     * and this matches how the repositories in this package already wrap Play Services tasks.
     */
    private suspend fun signInAnonymously(): String? =
        suspendCancellableCoroutine { cont ->
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid
                    Log.i(TAG, "anonymous sign-in ok (${uid?.take(6)}…)")
                    if (cont.isActive) cont.resume(uid)
                }
                .addOnFailureListener { error ->
                    // Offline at first launch, or Anonymous sign-in not enabled in the Firebase console.
                    Log.w(TAG, "anonymous sign-in failed", error)
                    if (cont.isActive) cont.resume(null)
                }
        }

    private companion object { const val TAG = "ShieldAuth" }
}
