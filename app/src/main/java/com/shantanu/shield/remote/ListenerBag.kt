package com.shantanu.shield.remote

import com.google.firebase.firestore.ListenerRegistration

/**
 * Keeps a set of Firestore subscriptions in step with a set of ids — one per linked child device.
 *
 * This exists as its own class because listener lifecycle is where a multi-device parent screen leaks:
 * a stray `addSnapshotListener` that is never removed keeps firing (and billing) forever, and a
 * double-registered id delivers every update twice. Concentrating the add/remove decision in one place
 * makes invariant 5 of MULTI_DEVICE_PAIRING_PLAN.md ("every live listener has a matching list entry")
 * a property of ten lines that are unit-tested, rather than of scattered `remove()` calls in a ViewModel.
 *
 * Not thread-safe by design: call it from a single coroutine (the ViewModel's collector).
 */
class ListenerBag {

    private val live = LinkedHashMap<String, ListenerRegistration>()

    /** The ids currently subscribed. Exposed for assertions. */
    val activeIds: Set<String> get() = live.keys.toSet()

    /**
     * Attach listeners for ids in [ids] that are not yet live, remove those that are live but no longer
     * wanted, and — crucially — leave untouched the ones already correct. That last part is what stops
     * an unrelated recomposition from tearing down and rebuilding every subscription.
     *
     * [attach] is called only for genuinely new ids.
     */
    fun sync(ids: Set<String>, attach: (String) -> ListenerRegistration) {
        // Remove first, so a shrinking set releases before a growing one allocates.
        val gone = live.keys - ids
        for (id in gone) live.remove(id)?.remove()

        for (id in ids) {
            if (live.containsKey(id)) continue      // already correct — do not re-register
            live[id] = attach(id)
        }
    }

    /** Detach everything. Must be called from the owner's teardown (e.g. ViewModel.onCleared). */
    fun clear() {
        for (registration in live.values) registration.remove()
        live.clear()
    }
}
