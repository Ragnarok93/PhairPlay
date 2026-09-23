package com.phairplay.miracast

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException

/**
 * UDP RTP/RTCP endpoint for a single Miracast presentation.
 *
 * Sockets are bound before WFD capability exchange so the sink never advertises
 * port zero. RTP payload type 33 (MP2T) is demultiplexed into elementary samples.
 */
internal class MiracastMediaReceiver(
    private val onSample: (MpegTsDemuxer.ElementarySample) -> Unit
) {
    private val rtpSocket = DatagramSocket(0)
    private val rtcpSocket = DatagramSocket(0)
    private val demuxer = MpegTsDemuxer()
    private var receiveJob: Job? = null
    private var rtcpJob: Job? = null

    val rtpPort: Int get() = rtpSocket.localPort
    val rtcpPort: Int get() = rtcpSocket.localPort

    @Volatile
    private var running = false

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true

        receiveJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(MAX_DATAGRAM_BYTES)
            var previousSequence: Int? = null
            while (running && isActive) {
                try {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    rtpSocket.receive(datagram)
                    val bytes = datagram.data.copyOfRange(
                        datagram.offset,
                        datagram.offset + datagram.length
                    )
                    val packet = RtpPacket.parse(bytes) ?: continue
                    if (packet.payloadType != RTP_PAYLOAD_TYPE_MP2T) continue

                    previousSequence?.let { previous ->
                        val expected = (previous + 1) and 0xFFFF
                        if (packet.sequenceNumber != expected) {
                            Logger.w(
                                "Miracast RTP loss/reorder: expected=$expected " +
                                    "received=${packet.sequenceNumber}"
                            )
                        }
                    }
                    previousSequence = packet.sequenceNumber

                    demuxer.consume(packet.payload).forEach(onSample)
                } catch (e: SocketException) {
                    if (running) Logger.e("Miracast RTP socket failed", e)
                    break
                } catch (e: Exception) {
                    Logger.e("Miracast RTP packet rejected", e)
                }
            }
        }

        // Bind and drain RTCP even though receiver reports are not generated yet.
        // This keeps the negotiated port valid and prevents sender-side socket errors.
        rtcpJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(MAX_RTCP_BYTES)
            while (running && isActive) {
                try {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    rtcpSocket.receive(datagram)
                } catch (e: SocketException) {
                    if (running) Logger.e("Miracast RTCP socket failed", e)
                    break
                }
            }
        }
    }

    fun stop() {
        if (!running && rtpSocket.isClosed && rtcpSocket.isClosed) return
        running = false
        rtpSocket.close()
        rtcpSocket.close()
        receiveJob?.cancel()
        rtcpJob?.cancel()
        receiveJob = null
        rtcpJob = null
        demuxer.flush().forEach(onSample)
    }

    companion object {
        private const val RTP_PAYLOAD_TYPE_MP2T = 33
        private const val MAX_DATAGRAM_BYTES = 65_535
        private const val MAX_RTCP_BYTES = 4_096
    }
}
