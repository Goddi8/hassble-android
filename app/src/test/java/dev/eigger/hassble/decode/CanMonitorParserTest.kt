package dev.eigger.hassble.decode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CanMonitorParserTest {
    @Test
    fun `parses spaced 29 bit frame and strips dlc`() {
        val frame = CanMonitorParser.parse("0C10A040 05 79 A2 4B 48 40").single()
        assertEquals("0C10A040", frame.canId)
        assertArrayEquals(byteArrayOf(0x79, 0xA2.toByte(), 0x4B, 0x48, 0x40), frame.data)
    }

    @Test
    fun `parses compact frame`() {
        val frame = CanMonitorParser.parse("0C10A0400579A24B4840").single()
        assertEquals("0C10A040", frame.canId)
        assertArrayEquals(byteArrayOf(0x79, 0xA2.toByte(), 0x4B, 0x48, 0x40), frame.data)
    }

    @Test
    fun `parses multiple lines and ignores adapter text`() {
        val frames = CanMonitorParser.parse("SEARCHING...\r0C10A040 05 79 A2 4B 48 40\r7E8 8 03 41 0C 1A F8 00 00 00")
        assertEquals(2, frames.size)
        assertEquals("7E8", frames[1].canId)
        assertEquals(8, frames[1].data.size)
    }
}
