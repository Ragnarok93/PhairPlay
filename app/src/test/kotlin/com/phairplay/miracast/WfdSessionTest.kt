package com.phairplay.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WfdSessionTest {

    @Test
    fun `capability response advertises allocated non-zero RTP port`() {
        val session = WfdSession(rtpPort = 19000)

        val body = session.capabilityResponse(
            "wfd_audio_codecs\r\nwfd_video_formats\r\nwfd_client_rtp_ports\r\n"
        )

        assertTrue(body.contains("wfd_audio_codecs: LPCM"))
        assertTrue(body.contains("wfd_video_formats:"))
        assertTrue(body.contains("wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play"))
    }


    @Test
    fun `capability response does not claim unimplemented IDR or RTCP capability`() {
        val session = WfdSession(rtpPort = 19000, rtcpPort = 19001)

        val body = session.capabilityResponse(
            "wfd_client_rtp_ports\r\nwfd_idr_request_capability\r\n"
        )

        assertTrue(
            body.contains("wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play")
        )
        assertTrue(body.contains("wfd_idr_request_capability: 0"))
    }

    @Test
    fun `SETUP trigger requires presentation URL and asks sink to send SETUP`() {
        val session = WfdSession(rtpPort = 19000)

        session.applySourceParameters(
            "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n"
        )
        val action = session.applySourceParameters("wfd_trigger_method: SETUP\r\n")

        assertEquals(
            WfdSession.Action.SendSetup("rtsp://192.168.49.1/wfd1.0/streamid=0"),
            action
        )
        assertEquals(WfdSession.State.SETUP_REQUESTED, session.state)
    }

    @Test
    fun `SETUP trigger without presentation URL is rejected`() {
        val session = WfdSession(rtpPort = 19000)

        assertEquals(
            WfdSession.Action.ProtocolError(400, "Missing wfd_presentation_URL"),
            session.applySourceParameters("wfd_trigger_method: SETUP\r\n")
        )
    }

    @Test
    fun `successful SETUP response captures session and asks sink to PLAY`() {
        val session = WfdSession(rtpPort = 19000)
        session.applySourceParameters(
            "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n"
        )
        session.applySourceParameters("wfd_trigger_method: SETUP\r\n")

        val action = session.onSetupResponse(
            200,
            mapOf(
                "Session" to "12345678;timeout=30",
                "Transport" to "RTP/AVP/UDP;unicast;client_port=19000"
            )
        )

        assertEquals(
            WfdSession.Action.SendPlay(
                presentationUrl = "rtsp://192.168.49.1/wfd1.0/streamid=0",
                sessionId = "12345678"
            ),
            action
        )
        assertEquals("12345678", session.sessionId)
    }


    @Test
    fun `SETUP response without Session still advances to PLAY for legacy source`() {
        val session = WfdSession(rtpPort = 19000)
        session.applySourceParameters(
            "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n"
        )
        session.applySourceParameters("wfd_trigger_method: SETUP\r\n")

        assertEquals(
            WfdSession.Action.SendPlay(
                presentationUrl = "rtsp://192.168.49.1/wfd1.0/streamid=0",
                sessionId = null
            ),
            session.onSetupResponse(200, emptyMap())
        )
        assertEquals(WfdSession.State.PLAY_REQUESTED, session.state)
    }

    @Test
    fun `successful PLAY response enters streaming state`() {
        val session = WfdSession(rtpPort = 19000)
        session.applySourceParameters(
            "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n"
        )
        session.applySourceParameters("wfd_trigger_method: SETUP\r\n")
        session.onSetupResponse(200, mapOf("Session" to "12345678"))

        assertEquals(WfdSession.Action.StreamStarted, session.onPlayResponse(200))
        assertEquals(WfdSession.State.STREAMING, session.state)
    }

    @Test
    fun `PAUSE trigger asks sink to send RTSP PAUSE and waits for response`() {
        val session = streamingSession()

        assertEquals(
            WfdSession.Action.SendPause(
                presentationUrl = "rtsp://192.168.49.1/wfd1.0/streamid=0",
                sessionId = "12345678"
            ),
            session.applySourceParameters("wfd_trigger_method: PAUSE\r\n")
        )
        assertEquals(WfdSession.State.PAUSE_REQUESTED, session.state)

        assertEquals(WfdSession.Action.StreamPaused, session.onPauseResponse(200))
        assertEquals(WfdSession.State.PAUSED, session.state)
    }

    @Test
    fun `TEARDOWN trigger asks sink to send RTSP TEARDOWN before closing`() {
        val session = streamingSession()

        assertEquals(
            WfdSession.Action.SendTeardown(
                presentationUrl = "rtsp://192.168.49.1/wfd1.0/streamid=0",
                sessionId = "12345678"
            ),
            session.applySourceParameters("wfd_trigger_method: TEARDOWN\r\n")
        )
        assertEquals(WfdSession.State.TEARDOWN_REQUESTED, session.state)

        assertEquals(WfdSession.Action.SessionClosed, session.onTeardownResponse(200))
        assertEquals(WfdSession.State.CLOSED, session.state)
    }

    @Test
    fun `failed PAUSE keeps streaming and failed TEARDOWN keeps prior state`() {
        val pauseSession = streamingSession()
        pauseSession.applySourceParameters("wfd_trigger_method: PAUSE\r\n")
        assertTrue(pauseSession.onPauseResponse(500) is WfdSession.Action.ProtocolError)
        assertEquals(WfdSession.State.STREAMING, pauseSession.state)

        val teardownSession = streamingSession()
        teardownSession.applySourceParameters("wfd_trigger_method: TEARDOWN\r\n")
        assertTrue(teardownSession.onTeardownResponse(500) is WfdSession.Action.ProtocolError)
        assertEquals(WfdSession.State.STREAMING, teardownSession.state)
    }

    private fun streamingSession(): WfdSession =
        WfdSession(rtpPort = 19000).also { session ->
            session.applySourceParameters(
                "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n"
            )
            session.applySourceParameters("wfd_trigger_method: SETUP\r\n")
            session.onSetupResponse(200, mapOf("Session" to "12345678"))
            session.onPlayResponse(200)
        }
}
