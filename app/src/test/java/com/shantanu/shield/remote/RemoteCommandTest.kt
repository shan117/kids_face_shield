package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the command wire format, the idempotency/freshness gate, and that forged commands are rejected. */
class RemoteCommandTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun `every command type round-trips through encode then decode`() {
        for (type in CommandType.entries) {
            val cmd = RemoteCommand("c-${type.name}", 1_700_000_000_000L, type, arg = 30)
            assertEquals(cmd, RemoteCommandCodec.decode(RemoteCommandCodec.encode(cmd)))
        }
    }

    @Test
    fun `payload command round-trips (5-field)`() {
        val cmd = RemoteCommand("c1", 1_700_000_000_000L, CommandType.SET_CUSTOM_ALLOWED, arg = 0, payload = "com.a,com.b")
        assertEquals(cmd, RemoteCommandCodec.decode(RemoteCommandCodec.encode(cmd)))
        val perApp = RemoteCommand("c2", 1_700_000_000_000L, CommandType.SET_PER_APP_LIMIT, arg = 30, payload = "com.y")
        assertEquals(perApp, RemoteCommandCodec.decode(RemoteCommandCodec.encode(perApp)))
    }

    @Test
    fun `int-only command stays 4-field with empty payload (back-compat)`() {
        val cmd = RemoteCommand("c1", 1000L, CommandType.LOCK_NOW)
        val encoded = RemoteCommandCodec.encode(cmd)
        assertEquals(4, encoded.split(Char(31)).size)   // no trailing payload field
        assertEquals(cmd, RemoteCommandCodec.decode(encoded))
        assertEquals("", RemoteCommandCodec.decode(encoded)!!.payload)
    }

    @Test
    fun `decode of garbage or an unknown type returns null`() {
        val us = Char(31).toString()
        assertNull(RemoteCommandCodec.decode("not-a-command"))                              // wrong field count
        // well-formed delimiters but a type outside the bounded set → enumValueOf throws → null:
        assertNull(RemoteCommandCodec.decode(listOf("id1", "1000", "NUKE_DEVICE", "0").joinToString(us)))
        // well-formed but a non-numeric issuedAt → null:
        assertNull(RemoteCommandCodec.decode(listOf("id1", "notlong", "LOCK_NOW", "0").joinToString(us)))
    }

    @Test
    fun `gate applies a fresh, unseen, recent command`() {
        val cmd = RemoteCommand("c1", issuedAtMs = 1000L, type = CommandType.LOCK_NOW)
        assertTrue(RemoteCommandGate.shouldApply(cmd, lastAppliedId = "c0", nowMs = 2000L, maxAgeMs = hour))
    }

    @Test
    fun `gate rejects an already-applied command (idempotency)`() {
        val cmd = RemoteCommand("c1", issuedAtMs = 1000L, type = CommandType.LOCK_NOW)
        assertFalse(RemoteCommandGate.shouldApply(cmd, lastAppliedId = "c1", nowMs = 2000L, maxAgeMs = hour))
    }

    @Test
    fun `gate rejects a stale command`() {
        val cmd = RemoteCommand("c1", issuedAtMs = 0L, type = CommandType.LOCK_NOW)
        // now is well past issuedAt + maxAge
        assertFalse(RemoteCommandGate.shouldApply(cmd, lastAppliedId = "", nowMs = 10 * hour, maxAgeMs = hour))
    }

    @Test
    fun `gate rejects a blank id`() {
        val cmd = RemoteCommand("", issuedAtMs = 1000L, type = CommandType.UNLOCK)
        assertFalse(RemoteCommandGate.shouldApply(cmd, lastAppliedId = "x", nowMs = 1500L, maxAgeMs = hour))
    }

    @Test
    fun `an authentic command survives encrypt then decrypt then decode`() {
        val key = ReportCrypto.generateKey()
        val cmd = RemoteCommand("c1", 1_700_000_000_000L, CommandType.GRANT_EXTRA_TIME, arg = 45)
        val sealed = ReportCrypto.encrypt(RemoteCommandCodec.encode(cmd), key)
        val decoded = ReportCrypto.decrypt(sealed, key)?.let { RemoteCommandCodec.decode(it) }
        assertEquals(cmd, decoded)
    }

    @Test
    fun `a forged command (wrong key) is rejected — decrypt fails, nothing to apply`() {
        val cmd = RemoteCommand("c1", 1_700_000_000_000L, CommandType.SET_DAILY_LIMIT, arg = 120)
        val sealed = ReportCrypto.encrypt(RemoteCommandCodec.encode(cmd), ReportCrypto.generateKey())
        // An attacker who knows the pairingId but not the key cannot produce readable bytes:
        assertNull(ReportCrypto.decrypt(sealed, ReportCrypto.generateKey()))
    }
}
