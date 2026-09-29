package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec guards a preference that holds every one of a parent's links. A corrupt or hostile
 * label must never cost more than its own record (plan trap J).
 */
class PairedDeviceCodecTest {

    private fun device(id: String, label: String = "Phone") =
        PairedDevice(pairingId = id, keyHex = "key-$id", label = label, addedAtMs = 42L)

    @Test
    fun `round-trips a list`() {
        val list = listOf(device("a", "Aarav's phone"), device("b", "Diya's phone"))
        assertEquals(list, PairedDeviceCodec.decode(PairedDeviceCodec.encode(list)))
    }

    @Test
    fun `round-trips an empty list`() {
        assertTrue(PairedDeviceCodec.encode(emptyList()).isEmpty())
        assertTrue(PairedDeviceCodec.decode("").isEmpty())
        assertTrue(PairedDeviceCodec.decode("   ").isEmpty())
    }

    @Test
    fun `a label containing the delimiters cannot corrupt the following records`() {
        val hostile = "Evil${Char(31)}injected${Char(30)}record"
        val list = listOf(device("a", hostile), device("b", "Intact"))

        val out = PairedDeviceCodec.decode(PairedDeviceCodec.encode(list))

        assertEquals("still exactly two devices", 2, out.size)
        assertEquals("a", out[0].pairingId)
        assertEquals("b", out[1].pairingId)
        assertEquals("second record survives intact", "Intact", out[1].label)
        assertEquals("delimiters stripped", "Evilinjectedrecord", out[0].label)
    }

    @Test
    fun `round-trips an emoji label`() {
        val list = listOf(device("a", "Aarav 📱 phone"))
        assertEquals("Aarav 📱 phone", PairedDeviceCodec.decode(PairedDeviceCodec.encode(list))[0].label)
    }

    @Test
    fun `a malformed record is dropped without losing the others`() {
        val good = PairedDeviceCodec.encode(listOf(device("a"), device("b")))
        val corrupted = good + Char(30) + "not-a-valid-record"

        val out = PairedDeviceCodec.decode(corrupted)
        assertEquals(listOf("a", "b"), out.map { it.pairingId })
    }

    @Test
    fun `extra trailing fields still decode - forward compatible`() {
        val record = listOf("id1", "key1", "Label", "42", "future-field").joinToString(Char(31).toString())
        val out = PairedDeviceCodec.decode(record)
        assertEquals(1, out.size)
        assertEquals("id1", out[0].pairingId)
        assertEquals(42L, out[0].addedAtMs)
    }

    @Test
    fun `a record missing its secrets is dropped`() {
        val blankId = listOf("", "key1", "Label", "1").joinToString(Char(31).toString())
        val blankKey = listOf("id1", "", "Label", "1").joinToString(Char(31).toString())
        assertTrue(PairedDeviceCodec.decode(blankId).isEmpty())
        assertTrue(PairedDeviceCodec.decode(blankKey).isEmpty())
    }

    @Test
    fun `a blank label decodes to the default, and a bad timestamp to zero`() {
        val record = listOf("id1", "key1", "", "not-a-number").joinToString(Char(31).toString())
        val out = PairedDeviceCodec.decode(record)
        assertEquals(PairedDevices.DEFAULT_LABEL, out[0].label)
        assertEquals(0L, out[0].addedAtMs)
    }
}
