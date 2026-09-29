package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Invariants 3, 4 and 9 of MULTI_DEVICE_PAIRING_PLAN.md — the rules the DataStore write applies.
 *
 * Invariant 9 (the legacy mirror tracks element 0) is the one that carries a pre-multi-device parent
 * across the migration: the old read sites — `RemoteCommandApplier`, `RemoteReportSync`, the service's
 * command listener — still read those keys. If the mirror drifts, an upgraded parent silently stops
 * working, which is exactly the failure this whole change exists to prevent.
 */
class PairingKeyStateTest {

    private fun device(id: String) =
        PairedDevice(pairingId = id, keyHex = "key-$id", label = "Phone $id", addedAtMs = 1L)

    // ---- invariant 9: the legacy mirror ----

    @Test
    fun `mirror tracks element 0`() {
        val state = PairedDevices.keyStateFor(listOf(device("a"), device("b")), storedActiveId = "b")

        assertEquals("a", state.legacyId)
        assertEquals("key-a", state.legacyKey)
    }

    @Test
    fun `mirror follows element 0 after the first device is removed`() {
        val remaining = PairedDevices.remove(listOf(device("a"), device("b")), "a")
        val state = PairedDevices.keyStateFor(remaining, storedActiveId = "b")

        assertEquals("b", state.legacyId)
        assertEquals("key-b", state.legacyKey)
    }

    @Test
    fun `mirror is CLEARED, not blanked, when the last device goes`() {
        val state = PairedDevices.keyStateFor(emptyList(), storedActiveId = "a")

        // Null means "remove the key". A blank string would read as a real-but-broken pairing to the
        // legacy call sites, and would also let becomeChild() reuse a dead id.
        assertNull(state.legacyId)
        assertNull(state.legacyKey)
        assertNull(state.activeId)
    }

    @Test
    fun `mirror is unaffected by which device is active`() {
        val list = listOf(device("a"), device("b"), device("c"))

        val viewingA = PairedDevices.keyStateFor(list, storedActiveId = "a")
        val viewingC = PairedDevices.keyStateFor(list, storedActiveId = "c")

        assertEquals("a", viewingA.legacyId)
        assertEquals("a", viewingC.legacyId)
        assertEquals("c", viewingC.activeId)
    }

    // ---- invariants 3 + 4: the active pointer stays inside the list ----

    @Test
    fun `active pointer is preserved when it is still linked`() {
        val state = PairedDevices.keyStateFor(listOf(device("a"), device("b")), storedActiveId = "b")
        assertEquals("b", state.activeId)
    }

    @Test
    fun `active pointer falls back to the first device when the active one is removed`() {
        val remaining = PairedDevices.remove(listOf(device("a"), device("b")), "b")
        val state = PairedDevices.keyStateFor(remaining, storedActiveId = "b")

        assertEquals("a", state.activeId)
    }

    @Test
    fun `active pointer is repaired when the stored id was never linked`() {
        val state = PairedDevices.keyStateFor(listOf(device("a")), storedActiveId = "ghost")
        assertEquals("a", state.activeId)
    }

    @Test
    fun `active pointer is set on the very first link, with nothing stored yet`() {
        val state = PairedDevices.keyStateFor(listOf(device("a")), storedActiveId = "")
        assertEquals("a", state.activeId)
    }

    // ---- the whole upgrade path, end to end ----

    @Test
    fun `a migrated legacy parent keeps the same mirror it had before`() {
        // Exactly what an existing paired parent holds before upgrading.
        val legacyId = "abc123"
        val legacyKey = "deadbeef"

        val migrated = PairedDevices.migrate(legacyId, legacyKey, emptyList(), nowMs = 1L)
        val state = PairedDevices.keyStateFor(migrated, storedActiveId = "")

        assertEquals("upgrade must not change the keys the child-path readers use", legacyId, state.legacyId)
        assertEquals(legacyKey, state.legacyKey)
        assertEquals("and the sole device becomes the active one", legacyId, state.activeId)
    }

    @Test
    fun `linking a second child does not disturb the migrated first one`() {
        val migrated = PairedDevices.migrate("abc123", "deadbeef", emptyList(), nowMs = 1L)
        val withSecond = PairedDevices.add(migrated, device("second"))

        val state = PairedDevices.keyStateFor(withSecond, storedActiveId = "abc123")

        assertEquals(2, state.devices.size)
        assertEquals("first child stays element 0", "abc123", state.legacyId)
        assertEquals("deadbeef", state.legacyKey)
    }
}
