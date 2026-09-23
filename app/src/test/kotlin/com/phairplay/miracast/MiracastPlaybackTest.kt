package com.phairplay.miracast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiracastPlaybackTest {

    @Test
    fun `extractAnnexBNalUnits finds SPS PPS and IDR`() {
        val bytes = byteArrayOf(
            0, 0, 0, 1, 0x67, 1, 2,
            0, 0, 1, 0x68, 3,
            0, 0, 0, 1, 0x65, 4, 5
        )

        val nals = MiracastPlayback.extractAnnexBNalUnits(bytes)

        assertEquals(3, nals.size)
        assertEquals(7, nals[0][0].toInt() and 0x1F)
        assertEquals(8, nals[1][0].toInt() and 0x1F)
        assertEquals(5, nals[2][0].toInt() and 0x1F)
    }

    @Test
    fun `mandatory WFD LPCM header parses 48k stereo 16-bit big endian samples`() {
        val payload = byteArrayOf(
            0xA0.toByte(), 0x06, 0x00, 0x11,
            0x12, 0x34, 0xFE.toByte(), 0xDC.toByte()
        )

        val frame = WfdLpcmFrame.parse(payload)!!

        assertEquals(48_000, frame.sampleRate)
        assertEquals(2, frame.channels)
        assertEquals(16, frame.bitsPerSample)
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0xDC.toByte(), 0xFE.toByte()),
            frame.toLittleEndianPcm16()
        )
    }

    @Test
    fun `unsupported WFD LPCM mode is rejected instead of misdecoded`() {
        // 96 kHz code with otherwise-valid header.
        val payload = byteArrayOf(0xA0.toByte(), 0x06, 0x00, 0x21, 0, 0)
        assertNull(WfdLpcmFrame.parse(payload))
    }

    @Test
    fun `truncated LPCM frame is rejected`() {
        assertTrue(WfdLpcmFrame.parse(byteArrayOf(0xA0.toByte(), 0x06)) == null)
    }
}
