package com.shantanu.shield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleCodecTest {

    @Test
    fun `schedules round-trip`() {
        val schedules = listOf(
            Schedule("1", "School", 30, 0, setOf("com.duolingo")),
            Schedule("2", "Weekend", 120, 2, setOf("com.whatsapp", "com.android.chrome"))
        )
        assertEquals(schedules, ScheduleCodec.decode(ScheduleCodec.encode(schedules)))
    }

    @Test
    fun `empty decodes to empty`() {
        assertTrue(ScheduleCodec.decode("").isEmpty())
    }

    @Test
    fun `name with delimiters is sanitized`() {
        val decoded = ScheduleCodec.decode(ScheduleCodec.encode(listOf(Schedule("1", "School|day\nmode", 30, 0))))
        assertEquals(1, decoded.size)
        assertEquals("School day mode", decoded[0].name)
    }

    @Test
    fun `malformed lines skipped`() {
        val good = ScheduleCodec.encode(listOf(Schedule("1", "Exam", 0, 0)))
        assertEquals(1, ScheduleCodec.decode("bad\n$good\nx|y").size)
    }
}
