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
    fun `mandatory WFD LPCM frame honors variable AU count and 80 frames per AU`() {
        val payload = wfdLpcmPayload(numAus = 1)

        val frame = WfdLpcmFrame.parse(payload)!!

        assertEquals(48_000, frame.sampleRate)
        assertEquals(2, frame.channels)
        assertEquals(16, frame.bitsPerSample)
        assertEquals(320, frame.bigEndianSamples.size)
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0xDC.toByte(), 0xFE.toByte()),
            frame.toLittleEndianPcm16().copyOfRange(0, 4)
        )
    }

    @Test
    fun `two WFD LPCM AUs produce exactly 640 bytes of stereo PCM`() {
        val payload = wfdLpcmPayload(numAus = 2)

        val frame = WfdLpcmFrame.parse(payload)!!

        assertEquals(640, frame.bigEndianSamples.size)
        assertEquals(640, frame.toLittleEndianPcm16().size)
    }

    @Test
    fun `WFD LPCM parser ignores bytes after the declared AU payload`() {
        val payload = wfdLpcmPayload(numAus = 1) + byteArrayOf(9, 8, 7, 6)

        val frame = WfdLpcmFrame.parse(payload)!!

        assertEquals(320, frame.bigEndianSamples.size)
    }

    @Test
    fun `declared WFD LPCM AUs are rejected when sample payload is truncated`() {
        val payload = wfdLpcmPayload(numAus = 2).copyOf(4 + 639)

        assertNull(WfdLpcmFrame.parse(payload))
    }

    @Test
    fun `zero WFD LPCM AU count is rejected`() {
        val payload = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x11)

        assertNull(WfdLpcmFrame.parse(payload))
    }

    @Test
    fun `unsupported WFD LPCM mode is rejected instead of misdecoded`() {
        val payload = wfdLpcmPayload(numAus = 1, format = 0x21)
        assertNull(WfdLpcmFrame.parse(payload))
    }

    @Test
    fun `truncated LPCM header is rejected`() {
        assertTrue(WfdLpcmFrame.parse(byteArrayOf(0xA0.toByte(), 0x01)) == null)
    }

    private fun wfdLpcmPayload(numAus: Int, format: Int = 0x11): ByteArray {
        val sampleBytes = numAus * 80 * 2 * 2
        return ByteArray(4 + sampleBytes).apply {
            this[0] = 0xA0.toByte()
            this[1] = numAus.toByte()
            this[2] = 0
            this[3] = format.toByte()
            if (sampleBytes >= 4) {
                this[4] = 0x12
                this[5] = 0x34
                this[6] = 0xFE.toByte()
                this[7] = 0xDC.toByte()
            }
        }
    }
}
