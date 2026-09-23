package com.phairplay.miracast

/** Parsed RTP v2 packet. Miracast uses payload type 33 for MPEG-TS in RTP. */
internal data class RtpPacket(
    val marker: Boolean,
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray
) {
    companion object {
        fun parse(data: ByteArray, length: Int = data.size): RtpPacket? {
            if (length < 12 || length > data.size) return null

            val b0 = data[0].toInt() and 0xFF
            if ((b0 ushr 6) != 2) return null

            val hasPadding = (b0 and 0x20) != 0
            val hasExtension = (b0 and 0x10) != 0
            val csrcCount = b0 and 0x0F

            var offset = 12 + csrcCount * 4
            if (offset > length) return null

            if (hasExtension) {
                if (offset + 4 > length) return null
                val words = ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
                offset += 4 + words * 4
                if (offset > length) return null
            }

            val paddingBytes = if (hasPadding) data[length - 1].toInt() and 0xFF else 0
            if (paddingBytes < 0 || paddingBytes > length - offset) return null
            val payloadEnd = length - paddingBytes
            if (payloadEnd < offset) return null

            val b1 = data[1].toInt() and 0xFF
            return RtpPacket(
                marker = (b1 and 0x80) != 0,
                payloadType = b1 and 0x7F,
                sequenceNumber = u16(data, 2),
                timestamp = u32(data, 4),
                ssrc = u32(data, 8),
                payload = data.copyOfRange(offset, payloadEnd)
            )
        }

        private fun u16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or
                (bytes[offset + 1].toInt() and 0xFF)

        private fun u32(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)
    }
}
