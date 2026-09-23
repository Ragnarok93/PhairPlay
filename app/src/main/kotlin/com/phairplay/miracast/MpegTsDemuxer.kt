package com.phairplay.miracast

import java.io.ByteArrayOutputStream

/**
 * Minimal streaming MPEG-TS demuxer for the Miracast RTP/MP2T path.
 *
 * It discovers the program map through PAT/PMT, tracks continuity counters,
 * reassembles PES payloads per elementary PID and emits a sample when the next
 * PES starts (or [flush] is called). CRC validation is intentionally left to
 * transport integrity; malformed sections are ignored rather than fatal.
 */
internal class MpegTsDemuxer {

    data class ElementarySample(
        val pid: Int,
        val streamType: Int,
        val pts90k: Long?,
        val payload: ByteArray
    )

    private data class PesAssembly(
        val streamType: Int,
        val pts90k: Long?,
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream()
    )

    private var pmtPid: Int? = null
    private val streamTypes = mutableMapOf<Int, Int>()
    private val continuity = mutableMapOf<Int, Int>()
    private val assemblies = mutableMapOf<Int, PesAssembly>()

    fun consume(data: ByteArray): List<ElementarySample> {
        if (data.size < TS_PACKET_SIZE) return emptyList()
        val output = mutableListOf<ElementarySample>()
        val syncOffset = findSyncOffset(data)
        if (syncOffset < 0) return emptyList()

        var offset = syncOffset
        while (offset + TS_PACKET_SIZE <= data.size) {
            if ((data[offset].toInt() and 0xFF) != SYNC_BYTE) {
                offset++
                continue
            }
            processPacket(data, offset, output)
            offset += TS_PACKET_SIZE
        }
        return output
    }

    fun flush(): List<ElementarySample> {
        val output = assemblies.mapNotNull { (pid, assembly) ->
            if (assembly.bytes.size() == 0) null
            else ElementarySample(pid, assembly.streamType, assembly.pts90k, assembly.bytes.toByteArray())
        }
        assemblies.clear()
        return output
    }

    private fun processPacket(
        packet: ByteArray,
        offset: Int,
        output: MutableList<ElementarySample>
    ) {
        val b1 = packet[offset + 1].toInt() and 0xFF
        val b3 = packet[offset + 3].toInt() and 0xFF
        val transportError = (b1 and 0x80) != 0
        if (transportError) return

        val payloadUnitStart = (b1 and 0x40) != 0
        val pid = ((b1 and 0x1F) shl 8) or (packet[offset + 2].toInt() and 0xFF)
        val adaptationControl = (b3 ushr 4) and 0x03
        val continuityCounter = b3 and 0x0F
        val hasPayload = adaptationControl == 1 || adaptationControl == 3
        if (!hasPayload) return

        var payloadOffset = offset + 4
        if (adaptationControl == 3) {
            if (payloadOffset >= offset + TS_PACKET_SIZE) return
            val adaptationLength = packet[payloadOffset].toInt() and 0xFF
            payloadOffset += 1 + adaptationLength
        }
        val packetEnd = offset + TS_PACKET_SIZE
        if (payloadOffset >= packetEnd) return

        val previous = continuity[pid]
        if (previous != null && continuityCounter != ((previous + 1) and 0x0F)) {
            assemblies.remove(pid)
        }
        continuity[pid] = continuityCounter

        when {
            pid == PAT_PID -> parsePat(packet, payloadOffset, packetEnd, payloadUnitStart)
            pid == pmtPid -> parsePmt(packet, payloadOffset, packetEnd, payloadUnitStart)
            streamTypes.containsKey(pid) ->
                parsePes(pid, packet, payloadOffset, packetEnd, payloadUnitStart, output)
        }
    }

    private fun parsePat(
        data: ByteArray,
        start: Int,
        end: Int,
        payloadUnitStart: Boolean
    ) {
        if (!payloadUnitStart) return
        val sectionStart = sectionStart(data, start, end) ?: return
        if (sectionStart + 8 > end || (data[sectionStart].toInt() and 0xFF) != 0x00) return

        val sectionLength =
            ((data[sectionStart + 1].toInt() and 0x0F) shl 8) or
                (data[sectionStart + 2].toInt() and 0xFF)
        val sectionEnd = (sectionStart + 3 + sectionLength).coerceAtMost(end)
        var pos = sectionStart + 8
        val entriesEnd = sectionEnd - 4
        while (pos + 4 <= entriesEnd) {
            val programNumber =
                ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val pid =
                ((data[pos + 2].toInt() and 0x1F) shl 8) or (data[pos + 3].toInt() and 0xFF)
            if (programNumber != 0) {
                pmtPid = pid
                return
            }
            pos += 4
        }
    }

    private fun parsePmt(
        data: ByteArray,
        start: Int,
        end: Int,
        payloadUnitStart: Boolean
    ) {
        if (!payloadUnitStart) return
        val sectionStart = sectionStart(data, start, end) ?: return
        if (sectionStart + 12 > end || (data[sectionStart].toInt() and 0xFF) != 0x02) return

        val sectionLength =
            ((data[sectionStart + 1].toInt() and 0x0F) shl 8) or
                (data[sectionStart + 2].toInt() and 0xFF)
        val sectionEnd = (sectionStart + 3 + sectionLength).coerceAtMost(end)
        val programInfoLength =
            ((data[sectionStart + 10].toInt() and 0x0F) shl 8) or
                (data[sectionStart + 11].toInt() and 0xFF)
        var pos = sectionStart + 12 + programInfoLength
        val entriesEnd = sectionEnd - 4

        while (pos + 5 <= entriesEnd) {
            val streamType = data[pos].toInt() and 0xFF
            val elementaryPid =
                ((data[pos + 1].toInt() and 0x1F) shl 8) or (data[pos + 2].toInt() and 0xFF)
            val esInfoLength =
                ((data[pos + 3].toInt() and 0x0F) shl 8) or (data[pos + 4].toInt() and 0xFF)
            streamTypes[elementaryPid] = streamType
            pos += 5 + esInfoLength
        }
    }

    private fun parsePes(
        pid: Int,
        data: ByteArray,
        start: Int,
        end: Int,
        payloadUnitStart: Boolean,
        output: MutableList<ElementarySample>
    ) {
        val streamType = streamTypes[pid] ?: return

        if (payloadUnitStart) {
            assemblies.remove(pid)?.let { previous ->
                if (previous.bytes.size() > 0) {
                    output += ElementarySample(
                        pid = pid,
                        streamType = previous.streamType,
                        pts90k = previous.pts90k,
                        payload = previous.bytes.toByteArray()
                    )
                }
            }

            val parsed = parsePesHeader(data, start, end) ?: return
            val assembly = PesAssembly(streamType = streamType, pts90k = parsed.first)
            if (parsed.second < end) {
                assembly.bytes.write(data, parsed.second, end - parsed.second)
            }
            assemblies[pid] = assembly
        } else {
            val assembly = assemblies[pid] ?: return
            assembly.bytes.write(data, start, end - start)
        }
    }

    private fun parsePesHeader(data: ByteArray, start: Int, end: Int): Pair<Long?, Int>? {
        if (start + 9 > end) return null
        if (data[start].toInt() != 0 || data[start + 1].toInt() != 0 ||
            (data[start + 2].toInt() and 0xFF) != 1
        ) return null

        val flags = data[start + 7].toInt() and 0xFF
        val headerLength = data[start + 8].toInt() and 0xFF
        val dataStart = start + 9 + headerLength
        if (dataStart > end) return null

        val pts = if ((flags and 0x80) != 0 && headerLength >= 5 && start + 14 <= end) {
            decodePts(data, start + 9)
        } else {
            null
        }
        return pts to dataStart
    }

    private fun decodePts(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0x0E) shl 29) or
            ((data[offset + 1].toLong() and 0xFF) shl 22) or
            ((data[offset + 2].toLong() and 0xFE) shl 14) or
            ((data[offset + 3].toLong() and 0xFF) shl 7) or
            ((data[offset + 4].toLong() and 0xFE) ushr 1)

    private fun sectionStart(data: ByteArray, start: Int, end: Int): Int? {
        if (start >= end) return null
        val pointer = data[start].toInt() and 0xFF
        val sectionStart = start + 1 + pointer
        return sectionStart.takeIf { it < end }
    }

    private fun findSyncOffset(data: ByteArray): Int {
        val max = minOf(TS_PACKET_SIZE - 1, data.size - 1)
        for (candidate in 0..max) {
            if ((data[candidate].toInt() and 0xFF) != SYNC_BYTE) continue
            val next = candidate + TS_PACKET_SIZE
            if (next >= data.size || (data[next].toInt() and 0xFF) == SYNC_BYTE) {
                return candidate
            }
        }
        return -1
    }

    companion object {
        const val STREAM_TYPE_H264 = 0x1B
        const val STREAM_TYPE_AAC = 0x0F
        const val STREAM_TYPE_AAC_LATM = 0x11
        const val STREAM_TYPE_WFD_LPCM = 0x83

        private const val TS_PACKET_SIZE = 188
        private const val SYNC_BYTE = 0x47
        private const val PAT_PID = 0x0000
    }
}
