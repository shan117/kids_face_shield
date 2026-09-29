package com.shantanu.shield.remote

import com.google.firebase.firestore.ListenerRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant 5 of MULTI_DEVICE_PAIRING_PLAN.md: every live listener has a matching device entry.
 * These tests are the reason the sync logic lives in its own class — a leak here is invisible at
 * runtime until the Firestore bill or the duplicate updates show up.
 */
class ListenerBagTest {

    /** Counts removals so a leak or a double-remove is observable. */
    private class FakeRegistration(val id: String) : ListenerRegistration {
        var removeCount = 0
        override fun remove() { removeCount++ }
    }

    private class Attacher {
        val created = mutableListOf<FakeRegistration>()
        var attachCount = 0
        fun attach(id: String): ListenerRegistration {
            attachCount++
            return FakeRegistration(id).also { created.add(it) }
        }
        fun forId(id: String) = created.filter { it.id == id }
    }

    @Test
    fun `sync attaches one listener per id`() {
        val bag = ListenerBag()
        val a = Attacher()

        bag.sync(setOf("x", "y"), a::attach)

        assertEquals(2, a.attachCount)
        assertEquals(setOf("x", "y"), bag.activeIds)
    }

    @Test
    fun `an unchanged set does not re-register anything`() {
        val bag = ListenerBag()
        val a = Attacher()

        bag.sync(setOf("x", "y"), a::attach)
        bag.sync(setOf("x", "y"), a::attach)
        bag.sync(setOf("y", "x"), a::attach)   // different iteration order, same set

        assertEquals("no re-attach on an unchanged set", 2, a.attachCount)
        assertTrue("nothing torn down", a.created.all { it.removeCount == 0 })
    }

    @Test
    fun `a dropped id is removed exactly once and not re-attached`() {
        val bag = ListenerBag()
        val a = Attacher()

        bag.sync(setOf("x", "y"), a::attach)
        bag.sync(setOf("x"), a::attach)

        assertEquals(setOf("x"), bag.activeIds)
        assertEquals("dropped listener removed once", 1, a.forId("y").single().removeCount)
        assertEquals("surviving listener untouched", 0, a.forId("x").single().removeCount)
        assertEquals("no new attaches", 2, a.attachCount)
    }

    @Test
    fun `adding an id keeps the existing listeners in place`() {
        val bag = ListenerBag()
        val a = Attacher()

        bag.sync(setOf("x"), a::attach)
        bag.sync(setOf("x", "y"), a::attach)

        assertEquals(setOf("x", "y"), bag.activeIds)
        assertEquals(2, a.attachCount)
        assertEquals("existing listener not rebuilt", 1, a.forId("x").size)
        assertEquals(0, a.forId("x").single().removeCount)
    }

    @Test
    fun `syncing to an empty set releases everything`() {
        val bag = ListenerBag()
        val a = Attacher()

        bag.sync(setOf("x", "y"), a::attach)
        bag.sync(emptySet(), a::attach)

        assertTrue(bag.activeIds.isEmpty())
        assertTrue("all released", a.created.all { it.removeCount == 1 })
    }

    @Test
    fun `clear removes every listener exactly once`() {
        val bag = ListenerBag()
        val a = Attacher()

        bag.sync(setOf("x", "y", "z"), a::attach)
        bag.clear()

        assertTrue(bag.activeIds.isEmpty())
        assertTrue(a.created.all { it.removeCount == 1 })
    }

    @Test
    fun `clear is idempotent - a second call cannot double-remove`() {
        val bag = ListenerBag()
        val a = Attacher()

        bag.sync(setOf("x"), a::attach)
        bag.clear()
        bag.clear()

        assertEquals(1, a.forId("x").single().removeCount)
    }

    @Test
    fun `a full swap releases the old ids and attaches the new`() {
        val bag = ListenerBag()
        val a = Attacher()

        bag.sync(setOf("x", "y"), a::attach)
        bag.sync(setOf("p", "q"), a::attach)

        assertEquals(setOf("p", "q"), bag.activeIds)
        assertEquals(1, a.forId("x").single().removeCount)
        assertEquals(1, a.forId("y").single().removeCount)
        assertEquals(4, a.attachCount)
    }
}
