package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the invariants from MULTI_DEVICE_PAIRING_PLAN.md §6 that protect a parent's links:
 * no duplicate pairing ids (2), the active device is always a member or null (3, 4), and the
 * legacy migration is idempotent (§4.3).
 */
class PairedDevicesTest {

    private fun device(id: String, label: String = "Phone", key: String = "k$id", at: Long = 1L) =
        PairedDevice(pairingId = id, keyHex = key, label = label, addedAtMs = at)

    // ---- add ----

    @Test
    fun `add appends a new device`() {
        val out = PairedDevices.add(listOf(device("a")), device("b"))
        assertEquals(listOf("a", "b"), out.map { it.pairingId })
    }

    @Test
    fun `add replaces in place instead of duplicating the same pairing id`() {
        val start = listOf(device("a", "Old", key = "old", at = 100L), device("b"))
        val out = PairedDevices.add(start, device("a", "New", key = "new", at = 999L))

        assertEquals("no duplicate row", listOf("a", "b"), out.map { it.pairingId })
        assertEquals("position preserved", 0, out.indexOfFirst { it.pairingId == "a" })
        assertEquals("key updated", "new", out[0].keyHex)
        assertEquals("label updated", "New", out[0].label)
        assertEquals("original addedAt kept", 100L, out[0].addedAtMs)
    }

    @Test
    fun `add with a blank label keeps the existing label`() {
        val start = listOf(device("a", "Aarav's phone"))
        val out = PairedDevices.add(start, device("a", "", key = "rotated"))
        assertEquals("Aarav's phone", out[0].label)
        assertEquals("rotated", out[0].keyHex)
    }

    @Test
    fun `add refuses a new device beyond the cap but still allows replacing one`() {
        val full = (1..PairedDevices.MAX_DEVICES).map { device("d$it") }

        val rejected = PairedDevices.add(full, device("overflow"))
        assertEquals("cap holds", full, rejected)

        val replaced = PairedDevices.add(full, device("d1", "Renamed"))
        assertEquals(PairedDevices.MAX_DEVICES, replaced.size)
        assertEquals("Renamed", replaced[0].label)
    }

    // ---- remove / rename ----

    @Test
    fun `remove drops only the named device and tolerates a missing id`() {
        val start = listOf(device("a"), device("b"))
        assertEquals(listOf("a"), PairedDevices.remove(start, "b").map { it.pairingId })
        assertEquals(start, PairedDevices.remove(start, "nope"))
    }

    @Test
    fun `rename updates one label and ignores blank or missing`() {
        val start = listOf(device("a", "Old"), device("b", "Keep"))

        val renamed = PairedDevices.rename(start, "a", "  Aarav's phone  ")
        assertEquals("Aarav's phone", renamed[0].label)
        assertEquals("Keep", renamed[1].label)

        assertEquals(start, PairedDevices.rename(start, "a", "   "))
        assertEquals(start, PairedDevices.rename(start, "missing", "X"))
    }

    // ---- resolveActive (invariants 3 + 4) ----

    @Test
    fun `resolveActive returns the stored device when it is still linked`() {
        val list = listOf(device("a"), device("b"))
        assertEquals("b", PairedDevices.resolveActive(list, "b")?.pairingId)
    }

    @Test
    fun `resolveActive falls back to the first device when the stored id is stale`() {
        val list = listOf(device("a"), device("b"))
        assertEquals("a", PairedDevices.resolveActive(list, "removed")?.pairingId)
        assertEquals("a", PairedDevices.resolveActive(list, "")?.pairingId)
    }

    @Test
    fun `resolveActive is null only when nothing is linked`() {
        assertNull(PairedDevices.resolveActive(emptyList(), "a"))
    }

    @Test
    fun `removing the active device reassigns to the first remaining`() {
        val list = listOf(device("a"), device("b"))
        val after = PairedDevices.remove(list, "b")
        assertEquals("a", PairedDevices.resolveActive(after, "b")?.pairingId)
    }

    // ---- migrate (plan 4.3) ----

    @Test
    fun `migrate folds legacy keys into a one-element list`() {
        val out = PairedDevices.migrate("id1", "key1", emptyList(), label = "Child device", nowMs = 5L)
        assertEquals(1, out.size)
        assertEquals("id1", out[0].pairingId)
        assertEquals("key1", out[0].keyHex)
        assertEquals(5L, out[0].addedAtMs)
    }

    @Test
    fun `migrate twice equals migrate once`() {
        val once = PairedDevices.migrate("id1", "key1", emptyList(), nowMs = 5L)
        val twice = PairedDevices.migrate("id1", "key1", once, nowMs = 9_999L)
        assertEquals(once, twice)
        assertSame("second call must be a no-op, not a rebuild", once, twice)
    }

    @Test
    fun `migrate is a no-op without legacy keys or with an existing list`() {
        assertTrue(PairedDevices.migrate("", "", emptyList()).isEmpty())
        assertTrue(PairedDevices.migrate("id", "", emptyList()).isEmpty())
        assertTrue(PairedDevices.migrate("", "key", emptyList()).isEmpty())

        val existing = listOf(device("x"))
        assertSame(existing, PairedDevices.migrate("id1", "key1", existing))
    }
}
