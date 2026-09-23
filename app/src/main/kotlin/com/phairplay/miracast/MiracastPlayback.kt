package com.phairplay.miracast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.phairplay.airplay.VideoDecoder
import com.phairplay.util.Logger
import kotlin.math.max

/**
 * Renderer for the mandatory Miracast/WFD R1 media profile:
 * H.264 AVC video plus 48 kHz / 16-bit / stereo LPCM.
 *
 * Optional AAC/AC-3/HEVC streams are deliberately ignored until a decoder is
 * wired and runtime capability advertisement can truthfully expose them.
 */
internal class MiracastPlayback(
    private val surfaceProvider: () -> Surface?
) {
    private var videoDecoder: VideoDecoder? = null
    private var decoderSurface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var audioTrack: AudioTrack? = null
    private val presentationClock = PresentationClock()

    fun onSample(sample: MpegTsDemuxer.ElementarySample) {
        when (sample.streamType) {
            MpegTsDemuxer.STREAM_TYPE_H264 -> renderH264(sample)
            MpegTsDemuxer.STREAM_TYPE_WFD_LPCM -> renderLpcm(sample.payload)
            else -> Logger.v(
                "Miracast: ignoring unsupported TS stream type 0x" +
                    sample.streamType.toString(16)
            )
        }
    }

    fun onSurfaceChanged() {
        val liveSurface = surfaceProvider()
        if (liveSurface == null || !liveSurface.isValid) {
            releaseVideo()
            return
        }
        if (decoderSurface != null && decoderSurface !== liveSurface) {
            releaseVideo()
        }
    }

    fun release() {
        releaseVideo()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing Miracast AudioTrack (non-fatal)", e)
        } finally {
            audioTrack = null
        }
        sps = null
        pps = null
        presentationClock.reset()
    }

    private fun renderH264(sample: MpegTsDemuxer.ElementarySample) {
        val nals = extractAnnexBNalUnits(sample.payload)
        var configChanged = false
        for (nal in nals) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> if (!nal.contentEquals(sps)) {
                    sps = nal.copyOf()
                    configChanged = true
                }
                8 -> if (!nal.contentEquals(pps)) {
                    pps = nal.copyOf()
                    configChanged = true
                }
            }
        }

        if (configChanged && videoDecoder != null) {
            releaseVideo()
        }

        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            releaseVideo()
            return
        }

        if (decoderSurface != null && decoderSurface !== surface) {
            releaseVideo()
        }

        if (videoDecoder == null) {
            val spsBytes = sps ?: return
            val ppsBytes = pps ?: return
            val dimensions = VideoDecoder.parseSpsResolution(spsBytes) ?: (1920 to 1080)
            try {
                videoDecoder = VideoDecoder(
                    outputSurface = surface,
                    renderTimeNsForPresentationUs = presentationClock::renderTimeNs
                ).also {
                    it.initialize(
                        spsBytes = spsBytes,
                        ppsBytes = ppsBytes,
                        width = dimensions.first,
                        height = dimensions.second
                    )
                }
                decoderSurface = surface
                Logger.i(
                    "Miracast H.264 decoder ready: " +
                        "${dimensions.first}x${dimensions.second}"
                )
            } catch (e: Exception) {
                Logger.e("Unable to initialize Miracast H.264 decoder", e)
                releaseVideo()
                return
            }
        }

        val hasPicture = nals.any { nal ->
            nal.isNotEmpty() && (nal[0].toInt() and 0x1F) in setOf(1, 5)
        }
        if (!hasPicture) return

        val presentationUs = sample.pts90k?.let(::pts90kToUs) ?: 0L
        val decoder = videoDecoder ?: return
        decoder.decodeNalUnit(sample.payload, presentationUs)
        if (!decoder.isHealthy) {
            Logger.w("Miracast H.264 decoder became unhealthy; waiting for recovery")
            releaseVideo()
        }
    }

    private fun renderLpcm(payload: ByteArray) {
        val frame = WfdLpcmFrame.parse(payload) ?: return
        val pcm = frame.toLittleEndianPcm16()
        val track = ensureAudioTrack(frame) ?: return
        val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
        if (written < 0) {
            Logger.w("Miracast AudioTrack write failed: $written")
            releaseAudioTrack()
        }
    }

    private fun ensureAudioTrack(frame: WfdLpcmFrame): AudioTrack? {
        audioTrack?.let { return it }
        return try {
            val channelMask = when (frame.channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                else -> return null
            }
            val minimum = AudioTrack.getMinBufferSize(
                frame.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimum <= 0) return null

            // Keep enough room for scheduler jitter without introducing a large,
            // fixed latency. 100 ms is a conservative TV baseline.
            val target = frame.sampleRate * frame.channels * 2 / 10
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(frame.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(max(minimum, target))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also {
                    it.play()
                    audioTrack = it
                    Logger.i(
                        "Miracast LPCM output ready: " +
                            "${frame.sampleRate}Hz/${frame.channels}ch"
                    )
                }
        } catch (e: Exception) {
            Logger.e("Unable to initialize Miracast LPCM output", e)
            null
        }
    }

    private fun releaseVideo() {
        try {
            videoDecoder?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing Miracast video decoder (non-fatal)", e)
        } finally {
            videoDecoder = null
            decoderSurface = null
        }
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Logger.e("Error recreating Miracast AudioTrack (non-fatal)", e)
        } finally {
            audioTrack = null
        }
    }

    private fun pts90kToUs(pts: Long): Long = pts * 1_000_000L / 90_000L

    companion object {
        /**
         * Splits an Annex-B H.264 access unit into NAL payloads without start codes.
         */
        internal fun extractAnnexBNalUnits(data: ByteArray): List<ByteArray> {
            if (data.isEmpty()) return emptyList()

            data class Start(val offset: Int, val length: Int)
            val starts = mutableListOf<Start>()
            var i = 0
            while (i + 3 <= data.size) {
                if (i + 4 <= data.size &&
                    data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() &&
                    data[i + 3] == 1.toByte()
                ) {
                    starts += Start(i, 4)
                    i += 4
                    continue
                }
                if (data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte()
                ) {
                    starts += Start(i, 3)
                    i += 3
                    continue
                }
                i++
            }

            if (starts.isEmpty()) return listOf(data.copyOf())

            return starts.mapIndexedNotNull { index, start ->
                val payloadStart = start.offset + start.length
                val payloadEnd = if (index + 1 < starts.size) {
                    starts[index + 1].offset
                } else {
                    data.size
                }
                if (payloadStart >= payloadEnd) null
                else data.copyOfRange(payloadStart, payloadEnd)
            }
        }
    }
}

/**
 * Mandatory WFD LPCM PES payload. WFD uses stream type 0x83 and prepends a
 * four-byte LPCM header before big-endian PCM samples.
 */
internal data class WfdLpcmFrame(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val bigEndianSamples: ByteArray
) {
    fun toLittleEndianPcm16(): ByteArray {
        val result = bigEndianSamples.copyOf()
        var i = 0
        while (i + 1 < result.size) {
            val high = result[i]
            result[i] = result[i + 1]
            result[i + 1] = high
            i += 2
        }
        return result
    }

    companion object {
        fun parse(payload: ByteArray): WfdLpcmFrame? {
            if (payload.size <= HEADER_SIZE) return null
            if ((payload[0].toInt() and 0xFF) != 0xA0 ||
                (payload[1].toInt() and 0xFF) != 0x06
            ) {
                return null
            }

            val format = payload[3].toInt() and 0xFF
            val bitsCode = (format ushr 6) and 0x03
            val sampleRateCode = (format ushr 3) and 0x07
            val channels = (format and 0x07) + 1

            val bits = when (bitsCode) {
                0 -> 16
                1 -> 20
                2 -> 24
                else -> return null
            }
            val sampleRate = when (sampleRateCode) {
                1 -> 44_100
                2 -> 48_000
                4 -> 96_000
                else -> return null
            }

            // PhairPlay advertises only the mandatory baseline mode. Rejecting
            // other modes is safer than playing a sender negotiation bug as noise.
            if (bits != 16 || sampleRate != 48_000 || channels !in 1..2) {
                return null
            }

            val samples = payload.copyOfRange(HEADER_SIZE, payload.size)
            if (samples.size < channels * 2 || samples.size % 2 != 0) return null

            return WfdLpcmFrame(
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bits,
                bigEndianSamples = samples
            )
        }

        private const val HEADER_SIZE = 4
    }
}

/** Monotonic low-latency clock used to schedule Miracast video PTS. */
private class PresentationClock {
    private var basePresentationUs: Long? = null
    private var baseRealtimeNs: Long = 0L

    @Synchronized
    fun renderTimeNs(presentationUs: Long): Long? {
        if (presentationUs < 0L) return null

        val base = basePresentationUs
        if (base == null || presentationUs + RESET_BACKWARD_US < base) {
            basePresentationUs = presentationUs
            baseRealtimeNs = System.nanoTime() + STARTUP_BUFFER_NS
            return baseRealtimeNs
        }

        val deltaUs = presentationUs - base
        if (deltaUs > RESET_FORWARD_US) {
            basePresentationUs = presentationUs
            baseRealtimeNs = System.nanoTime() + STARTUP_BUFFER_NS
            return baseRealtimeNs
        }
        return baseRealtimeNs + deltaUs * 1_000L
    }

    @Synchronized
    fun reset() {
        basePresentationUs = null
        baseRealtimeNs = 0L
    }

    companion object {
        private const val STARTUP_BUFFER_NS = 60_000_000L
        private const val RESET_BACKWARD_US = 1_000_000L
        private const val RESET_FORWARD_US = 10_000_000L
    }
}
