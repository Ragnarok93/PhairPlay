package com.phairplay.miracast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtpPacketTest {

    @Test
    fun `parses RTP v2 header and payload`() {
        val bytes = byteArrayOf(
            0x80.toByte(), 0xA1.toByte(),
            0x12, 0x34,
            0x01, 0x02, 0x03, 0x04,
            0x11, 0x22, 0x33, 0x44,
            0x47, 0x40, 0x00
        )

        val packet = RtpPacket.parse(bytes)!!

        assertEquals(0x1234, packet.sequenceNumber)
        assertEquals(0x01020304L, packet.timestamp)
        assertEquals(0x11223344L, packet.ssrc)
        assertEquals(33, packet.payloadType)
        assertEquals(true, packet.marker)
        assertArrayEquals(byteArrayOf(0x47, 0x40, 0x00), packet.payload)
    }

    @Test
    fun `skips CSRC extension and RTP padding`() {
        val bytes = byteArrayOf(
            0xB1.toByte(), 0x21,
            0x00, 0x01,
            0, 0, 0, 2,
            0, 0, 0, 3,
            1, 2, 3, 4,
            0x10, 0x00, 0x00, 0x01,
            9, 8, 7, 6,
            0x47, 0x01, 0x02,
            0, 0, 3
        )

        val packet = RtpPacket.parse(bytes)!!

        assertArrayEquals(byteArrayOf(0x47, 0x01, 0x02), packet.payload)
    }

    @Test
    fun `rejects non RTP v2 and truncated packets`() {
        assertNull(RtpPacket.parse(ByteArray(11)))
        val v1 = ByteArray(12)
        v1[0] = 0x40
        assertNull(RtpPacket.parse(v1))
    }
}
