package com.shantanu.shield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EarnedTaskCodecTest {

    @Test
    fun `tasks round-trip`() {
        val tasks = listOf(
            EarnedTask("1", "Homework", 20),
            EarnedTask("2", "Tidy room", 15)
        )
        assertEquals(tasks, EarnedTaskCodec.decode(EarnedTaskCodec.encode(tasks)))
    }

    @Test
    fun `empty decodes to empty`() {
        assertTrue(EarnedTaskCodec.decode("").isEmpty())
    }

    @Test
    fun `title with delimiters is sanitized`() {
        val decoded = EarnedTaskCodec.decode(EarnedTaskCodec.encode(listOf(EarnedTask("1", "do|this\nnow", 10))))
        assertEquals(1, decoded.size)
        assertEquals("do this now", decoded[0].title)
    }

    @Test
    fun `malformed lines are skipped`() {
        val good = EarnedTaskCodec.encode(listOf(EarnedTask("1", "Read", 15)))
        assertEquals(1, EarnedTaskCodec.decode("bad-line\n$good\nx|y").size)
    }
}
