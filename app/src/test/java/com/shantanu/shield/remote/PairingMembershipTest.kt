package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a stolen pairing id is worth anything.
 *
 * Mirrors the server-side logic in `firestore.rules.phase7`. Tested here because the interesting cases
 * are the abuses — a third device grabbing the second slot, a claim after the window, a device
 * consuming its own partner's slot — and none of those are things to discover by trying them against
 * production.
 */
class PairingMembershipTest {

    private val child = "child-uid"
    private val parent = "parent-uid"
    private val stranger = "stranger-uid"

    private val now = 1_000_000L
    private val deadline = now + PairingMembership.CLAIM_WINDOW_MS

    // ---- membership ----

    @Test
    fun `a recorded uid is a member`() {
        assertTrue(PairingMembership.isMember(listOf(child, parent), parent))
    }

    @Test
    fun `an unrecorded uid is not a member`() {
        assertFalse(PairingMembership.isMember(listOf(child, parent), stranger))
    }

    @Test
    fun `null and blank uids are never members`() {
        assertFalse(PairingMembership.isMember(listOf(child), null))
        assertFalse(PairingMembership.isMember(listOf(child, ""), ""))
    }

    // ---- the claim window ----

    @Test
    fun `the window is open with a free slot before the deadline`() {
        assertTrue(PairingMembership.isClaimWindowOpen(listOf(child), deadline, now))
    }

    @Test
    fun `the window is shut once two devices are recorded`() {
        // The important one: after pairing completes, a photographed QR buys nothing.
        assertFalse(
            PairingMembership.isClaimWindowOpen(listOf(child, parent), deadline, now)
        )
    }

    @Test
    fun `the window is shut after the deadline`() {
        assertFalse(
            PairingMembership.isClaimWindowOpen(listOf(child), deadline, deadline + 1)
        )
    }

    @Test
    fun `the window is shut exactly at the deadline`() {
        assertFalse(PairingMembership.isClaimWindowOpen(listOf(child), deadline, deadline))
    }

    // ---- claiming ----

    @Test
    fun `the parent claims the second slot`() {
        assertEquals(
            listOf(child, parent),
            PairingMembership.claim(listOf(child), parent, deadline, now),
        )
    }

    @Test
    fun `a third device cannot claim once both slots are taken`() {
        val full = listOf(child, parent)
        assertEquals(full, PairingMembership.claim(full, stranger, deadline, now))
    }

    @Test
    fun `a stranger cannot claim after the deadline`() {
        assertEquals(
            listOf(child),
            PairingMembership.claim(listOf(child), stranger, deadline, deadline + 1),
        )
    }

    @Test
    fun `re-claiming by an existing member does not consume the free slot`() {
        // A device re-scanning its own QR must not fill the second slot and lock its real partner out.
        val after = PairingMembership.claim(listOf(child), child, deadline, now)
        assertEquals(listOf(child), after)
        assertTrue(
            "the slot is still available for the partner",
            PairingMembership.isClaimWindowOpen(after, deadline, now),
        )
    }

    @Test
    fun `a null or blank uid cannot claim`() {
        assertEquals(listOf(child), PairingMembership.claim(listOf(child), null, deadline, now))
        assertEquals(listOf(child), PairingMembership.claim(listOf(child), "  ", deadline, now))
    }

    @Test
    fun `claiming never grows past two members`() {
        var uids = emptyList<String>()
        for (uid in listOf(child, parent, stranger, "fourth")) {
            uids = PairingMembership.claim(uids, uid, deadline, now)
        }
        assertEquals(PairingMembership.MAX_MEMBERS, uids.size)
        assertEquals(listOf(child, parent), uids)
    }


    @Test
    fun `the claim window is minutes, not seconds or hours`() {
        // Seconds would fail a pairing on a slow network; hours would leave a photographed QR usable
        // long after the parent walked away. The window is also refreshed while the QR is on screen,
        // so it does not need to cover an unattended phone.
        val minutes = PairingMembership.CLAIM_WINDOW_MS / 60_000L
        assertTrue("window is $minutes min", minutes in 5..60)
    }

    // ---- the reader/writer asymmetry that broke the first design ----

    @Test
    fun `membership must cover the READER of a collection, not just its creator`() {
        // The bug this whole record exists to fix. Each relay collection has one writer and one reader:
        // the child writes reports and locations, the parent writes commands and grants. Per-document
        // membership left the reader out of the document it needed to read, and a reader cannot add
        // itself because claiming is a write.
        //
        // With one shared record, BOTH devices are members of the pairing, so either can read any of
        // the four collections.
        val shared = PairingMembership.claim(listOf(child), parent, deadline, now)

        assertTrue("child reads commands/grants the parent wrote", PairingMembership.isMember(shared, child))
        assertTrue("parent reads reports/locations the child wrote", PairingMembership.isMember(shared, parent))
        assertFalse(PairingMembership.isMember(shared, stranger))
    }

    @Test
    fun `a single-member record is not enough for the pairing to work`() {
        // What the console showed: uids held only the creator. Pinned so a regression to per-document
        // membership fails here rather than in production behind strict rules.
        val creatorOnly = listOf(child)

        assertTrue(PairingMembership.isMember(creatorOnly, child))
        assertFalse("the other device cannot read anything yet", PairingMembership.isMember(creatorOnly, parent))
        assertTrue(
            "so the window must still be open for them to claim",
            PairingMembership.isClaimWindowOpen(creatorOnly, deadline, now),
        )
    }
}
