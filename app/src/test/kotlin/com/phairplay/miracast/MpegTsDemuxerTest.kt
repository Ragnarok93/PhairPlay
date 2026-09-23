package com.phairplay.miracast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpegTsDemuxerTest {

    @Test
    fun `PAT PMT and PES expose H264 sample with PTS`() {
        val demuxer = MpegTsDemuxer()
        val input = concat(
            tsPacket(0x0000, true, 0, patSection(0x0100)),
            tsPacket(0x0100, true, 0, pmtSection(0x0101, 0x1B, 0x0101)),
            tsPacket(0x0101, true, 0, pesPacket(0xE0, 90_000L, byteArrayOf(0, 0, 0, 1, 0x65, 1, 2))),
            tsPacket(0x0101, true, 1, pesPacket(0xE0, 180_000L, byteArrayOf(0, 0, 0, 1, 0x41, 3, 4)))
        )

        val samples = demuxer.consume(input)

        assertEquals(1, samples.size)
        assertEquals(0x0101, samples[0].pid)
        assertEquals(0x1B, samples[0].streamType)
        assertEquals(90_000L, samples[0].pts90k)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2), samples[0].payload)
    }

    @Test
    fun `continuity gap discards partial PES until next payload-unit boundary`() {
        val demuxer = MpegTsDemuxer()
        demuxer.consume(
            concat(
                tsPacket(0x0000, true, 0, patSection(0x0100)),
                tsPacket(0x0100, true, 0, pmtSection(0x0101, 0x1B, 0x0101)),
                tsPacket(0x0101, true, 0, pesPacket(0xE0, 90_000L, byteArrayOf(1, 2, 3)))
            )
        )

        // Expected continuity=1. A packet carrying continuation data arrives as 3.
        demuxer.consume(tsPacket(0x0101, false, 3, byteArrayOf(9, 9, 9)))
        val samples = demuxer.consume(
            tsPacket(0x0101, true, 4, pesPacket(0xE0, 180_000L, byteArrayOf(4, 5, 6)))
        )

        assertTrue(samples.isEmpty())
        val flushed = demuxer.flush()
        assertEquals(1, flushed.size)
        assertArrayEquals(byteArrayOf(4, 5, 6), flushed.single().payload)
    }

    private fun patSection(pmtPid: Int): ByteArray {
        val section = byteArrayOf(
            0x00,
            0xB0.toByte(), 0x0D,
            0x00, 0x01,
            0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01,
            (0xE0 or ((pmtPid shr 8) and 0x1F)).toByte(), (pmtPid and 0xFF).toByte(),
            0, 0, 0, 0
        )
        return byteArrayOf(0) + section
    }

    private fun pmtSection(pcrPid: Int, streamType: Int, elementaryPid: Int): ByteArray {
        val sectionLength = 18
        val section = byteArrayOf(
            0x02,
            (0xB0 or ((sectionLength shr 8) and 0x0F)).toByte(), (sectionLength and 0xFF).toByte(),
            0x00, 0x01,
            0xC1.toByte(), 0x00, 0x00,
            (0xE0 or ((pcrPid shr 8) and 0x1F)).toByte(), (pcrPid and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
            streamType.toByte(),
            (0xE0 or ((elementaryPid shr 8) and 0x1F)).toByte(), (elementaryPid and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
            0, 0, 0, 0
        )
        return byteArrayOf(0) + section
    }

    private fun pesPacket(streamId: Int, pts90k: Long, payload: ByteArray): ByteArray {
        val pts = encodePts(pts90k)
        val pesLength = 3 + 5 + payload.size
        return byteArrayOf(
            0, 0, 1, streamId.toByte(),
            ((pesLength shr 8) and 0xFF).toByte(), (pesLength and 0xFF).toByte(),
            0x80.toByte(), 0x80.toByte(), 0x05
        ) + pts + payload
    }

    private fun encodePts(value: Long): ByteArray = byteArrayOf(
        (((value shr 29) and 0x0E) or 0x21).toByte(),
        ((value shr 22) and 0xFF).toByte(),
        (((value shr 14) and 0xFE) or 1).toByte(),
        ((value shr 7) and 0xFF).toByte(),
        (((value shl 1) and 0xFE) or 1).toByte()
    )

    private fun tsPacket(pid: Int, pusi: Boolean, continuity: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 184)
        val packet = ByteArray(188) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (((if (pusi) 0x40 else 0) or ((pid shr 8) and 0x1F))).toByte()
        packet[2] = (pid and 0xFF).toByte()

        if (payload.size == 184) {
            packet[3] = (0x10 or (continuity and 0x0F)).toByte()
            payload.copyInto(packet, 4)
        } else {
            packet[3] = (0x30 or (continuity and 0x0F)).toByte()
            val adaptationLength = 183 - payload.size
            packet[4] = adaptationLength.toByte()
            if (adaptationLength > 0) {
                packet[5] = 0x00
            }
            payload.copyInto(packet, 5 + adaptationLength)
        }
        return packet
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val result = ByteArray(parts.sumOf { it.size })
        var offset = 0
        parts.forEach {
            it.copyInto(result, offset)
            offset += it.size
        }
        return result
    }
}
