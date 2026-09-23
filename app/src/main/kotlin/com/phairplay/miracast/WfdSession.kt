package com.phairplay.miracast

/**
 * Pure Wi-Fi Display RTSP session model.
 *
 * The source sends WFD parameter messages over the control connection. When it
 * sends `wfd_trigger_method: SETUP`, the primary sink becomes the RTSP client
 * for M6/M7 and sends SETUP then PLAY to the advertised presentation URL.
 */
internal class WfdSession(
    val rtpPort: Int,
    val rtcpPort: Int = 0
) {
    init {
        require(rtpPort in 1..65535) { "RTP port must be allocated before WFD negotiation" }
        require(rtcpPort in 0..65535) { "RTCP port must be zero or a valid allocated port" }
    }

    fun setupTransportHeader(): String =
        if (rtcpPort > 0) {
            "RTP/AVP/UDP;unicast;client_port=$rtpPort-$rtcpPort"
        } else {
            "RTP/AVP/UDP;unicast;client_port=$rtpPort"
        }

    enum class State {
        NEGOTIATING,
        CONFIGURED,
        SETUP_REQUESTED,
        PLAY_REQUESTED,
        STREAMING,
        PAUSED,
        CLOSED
    }

    sealed class Action {
        object None : Action()
        data class SendSetup(val presentationUrl: String) : Action()
        data class SendPlay(val presentationUrl: String, val sessionId: String) : Action()
        object StreamStarted : Action()
        object StreamPaused : Action()
        object SessionClosed : Action()
        data class ProtocolError(val statusCode: Int, val message: String) : Action()
    }

    var state: State = State.NEGOTIATING
        private set

    var presentationUrl: String? = null
        private set

    var sessionId: String? = null
        private set

    /**
     * Returns only parameters explicitly requested by M3 GET_PARAMETER. A blank
     * request is a keepalive and therefore has an empty response body.
     */
    fun capabilityResponse(requestBody: String): String {
        val requested = requestBody
            .lineSequence()
            .map { it.trim().substringBefore(':') }
            .filter { it.isNotEmpty() }
            .toSet()

        if (requested.isEmpty()) return ""

        return CAPABILITIES
            .filterKeys { it in requested }
            .entries
            .joinToString(separator = "\r\n", postfix = if (requested.isEmpty()) "" else "\r\n") {
                (name, value) ->
                val resolved = if (name == "wfd_client_rtp_ports") {
                    "RTP/AVP/UDP;unicast $rtpPort $rtcpPort mode=play"
                } else {
                    value
                }
                "$name: $resolved"
            }
    }

    /**
     * Applies one M4/M5 SET_PARAMETER body and returns any outbound RTSP action
     * required from the sink.
     */
    fun applySourceParameters(body: String): Action {
        val parameters = parseParameters(body)

        parameters["wfd_presentation_URL"]?.let { value ->
            val url = value.split(Regex("\\s+")).firstOrNull().orEmpty()
            if (url.startsWith("rtsp://", ignoreCase = true)) {
                presentationUrl = url
                if (state == State.NEGOTIATING) state = State.CONFIGURED
            }
        }

        val trigger = parameters["wfd_trigger_method"]?.trim()?.uppercase() ?: return Action.None
        return when (trigger) {
            "SETUP" -> {
                val url = presentationUrl
                    ?: return Action.ProtocolError(400, "Missing wfd_presentation_URL")
                if (state == State.CLOSED || state == State.STREAMING) {
                    Action.ProtocolError(455, "SETUP invalid in state $state")
                } else {
                    state = State.SETUP_REQUESTED
                    Action.SendSetup(url)
                }
            }
            "PLAY" -> {
                val url = presentationUrl
                    ?: return Action.ProtocolError(400, "Missing wfd_presentation_URL")
                val id = sessionId
                    ?: return Action.ProtocolError(455, "PLAY before SETUP")
                state = State.PLAY_REQUESTED
                Action.SendPlay(url, id)
            }
            "PAUSE" -> {
                if (state != State.STREAMING) {
                    Action.ProtocolError(455, "PAUSE before streaming")
                } else {
                    state = State.PAUSED
                    Action.StreamPaused
                }
            }
            "TEARDOWN" -> {
                state = State.CLOSED
                Action.SessionClosed
            }
            else -> Action.ProtocolError(400, "Unsupported WFD trigger: $trigger")
        }
    }

    fun onSetupResponse(statusCode: Int, headers: Map<String, String>): Action {
        if (state != State.SETUP_REQUESTED) {
            return Action.ProtocolError(455, "Unexpected SETUP response in state $state")
        }
        if (statusCode !in 200..299) {
            state = State.CONFIGURED
            return Action.ProtocolError(statusCode, "Source rejected SETUP")
        }

        val rawSession = header(headers, "Session")
            ?: return Action.ProtocolError(500, "SETUP response missing Session")
        val id = rawSession.substringBefore(';').trim()
        if (id.isEmpty()) {
            return Action.ProtocolError(500, "SETUP response has empty Session")
        }

        sessionId = id
        state = State.PLAY_REQUESTED
        val url = presentationUrl
            ?: return Action.ProtocolError(500, "Presentation URL lost before PLAY")
        return Action.SendPlay(url, id)
    }

    fun onPlayResponse(statusCode: Int): Action {
        if (state != State.PLAY_REQUESTED) {
            return Action.ProtocolError(455, "Unexpected PLAY response in state $state")
        }
        if (statusCode !in 200..299) {
            state = State.CONFIGURED
            return Action.ProtocolError(statusCode, "Source rejected PLAY")
        }

        state = State.STREAMING
        return Action.StreamStarted
    }

    fun close(): Action {
        state = State.CLOSED
        return Action.SessionClosed
    }

    private fun parseParameters(body: String): Map<String, String> =
        buildMap {
            body.lineSequence().forEach { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@forEach
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                if (key.isNotEmpty()) put(key, value)
            }
        }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    companion object {
        private val CAPABILITIES = linkedMapOf(
            "wfd_audio_codecs" to "LPCM 00000002 00",
            "wfd_video_formats" to
                "00 00 02 10 0001FFFF 00000000 00000000 00 0000 0000 00 none none",
            "wfd_client_rtp_ports" to "",
            "wfd_content_protection" to "none",
            "wfd_display_edid" to "none",
            "wfd_coupled_sink" to "none",
            "wfd_connector_type" to "05"
        )
    }
}
