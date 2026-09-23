package com.phairplay.miracast

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import com.phairplay.airplay.RtspRequest
import com.phairplay.airplay.RtspRequestReader
import com.phairplay.airplay.RtspResponse
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * MiracastReceiver — Miracast (Wi-Fi Display / WFD) receiver service advertiser.
 *
 * WHY: Miracast allows Windows 10+ and Android devices to wirelessly mirror
 * their screen without being on the same Wi-Fi network. It uses Wi-Fi Direct
 * (P2P) to create a direct device-to-device connection.
 *
 * HOW: Implementation proceeds in phases:
 * - Phase 1: Architecture defined, P2P manager initialized
 * - Phase 2: Wi-Fi P2P service discovery advertised
 * - WFD RTSP session negotiation
 * - Phase 4 (M6): H.264 video decode + audio playback
 *
 * Miracast protocol stack:
 *   Wi-Fi Direct (P2P) → WFD RTSP → RTP/H.264 → MediaCodec → SurfaceView
 *
 * Key Android APIs used:
 *   - [WifiP2pManager]: for discovering peers and accepting connections
 *   - [WifiP2pManager.Channel]: communication channel to the P2P framework
 *   - Custom WFD RTSP: similar to AirPlay RTSP but with WFD-specific methods
 *
 * IMPORTANT LIMITATIONS (see ADR-001):
 * - Miracast requires Wi-Fi Direct, which some Android TV devices disable
 * - The WFD stack on Android TV is partly hidden (system APIs)
 * - Real-world compatibility must be tested on actual hardware
 * - Miracast is NOT available on Fire TV with standard APIs
 *
 * Example:
 *   val receiver = MiracastReceiver(context) { state -> updateUI(state) }
 *   receiver.start()  // begins P2P service advertisement
 *   receiver.stop()   // stops advertisement and closes session
 */
internal class MiracastReceiver(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val onMediaSample: (MpegTsDemuxer.ElementarySample) -> Unit = {},
    private val onStateChanged: (ProtocolState) -> Unit
) {

    // Android's Wi-Fi P2P manager — the entry point for all Wi-Fi Direct operations
    private var wifiP2pManager: WifiP2pManager? = null

    // The communication channel between the app and the Wi-Fi P2P framework
    private var channel: Channel? = null

    // The local DNS-SD service record advertised through Wi-Fi Direct.
    private var serviceInfo: WifiP2pDnsSdServiceInfo? = null

    // Whether the P2P service advertisement is currently active
    @Volatile
    private var isAdvertising = false

    @Volatile
    private var isListening = false

    @Volatile
    private var p2pConnectionReceiverRegistered = false

    private var lastOutboundEndpoint: WfdControlEndpoint? = null

    private val p2pConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                refreshP2pControlConnection()
            }
        }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val rtspServer = WfdRtspServer(
        onSessionStarted = {
            Logger.i("Miracast WFD session connected")
            onStateChanged(ProtocolState.CONNECTED)
        },
        onSessionStopped = {
            Logger.i("Miracast WFD session stopped")
            if (isAdvertising) onStateChanged(ProtocolState.ADVERTISING)
        },
        onMediaSample = onMediaSample
    )

    /**
     * Starts the Miracast receiver.
     *
     * Current implementation:
     * - Initializes the WifiP2pManager and Channel
     * - Logs availability of Wi-Fi Direct on this device
     * - Registers a local Wi-Fi Direct DNS-SD WFD service
     * - Opens the WFD RTSP control server on port 7236
     */
    fun start() {
        Logger.i("MiracastReceiver starting")
        initializeWifiP2p()
    }

    /**
     * Stops the Miracast receiver.
     *
     * Unregisters P2P service, disconnects any active WFD session,
     * and releases the WifiP2pManager channel.
     */
    fun stop() {
        Logger.i("MiracastReceiver stopping")
        try {
            unregisterP2pConnectionReceiver()
            stopP2pListening()
            stopP2pAdvertisement()
            rtspServer.stop()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                channel?.close()
            }
        } catch (e: Exception) {
            Logger.e("Error stopping MiracastReceiver (non-fatal)", e)
        } finally {
            wifiP2pManager = null
            channel = null
            isAdvertising = false
            job.cancel()
            onStateChanged(ProtocolState.DISABLED)
        }
    }

    /**
     * Initializes the WifiP2pManager and Channel.
     *
     * The [WifiP2pManager] is retrieved from Android's system services.
     * The [Channel] is the app's communication link to the P2P framework.
     *
     * If Wi-Fi Direct is not available on this device (some Android TV boxes
     * don't support it), [wifiP2pManager] will be null and we emit an ERROR state.
     */
    private fun initializeWifiP2p() {
        if (!WifiDirectCompat.isWifiDirectAvailable(context)) {
            Logger.w("Wi-Fi Direct is not available on this device — Miracast disabled")
            onStateChanged(ProtocolState.DISABLED)
            return
        }
        if (!WifiDirectCompat.hasRequiredPermission(context, sdkInt)) {
            Logger.w(
                "Missing Wi-Fi Direct runtime permission for API $sdkInt: " +
                    WifiDirectCompat.requiredRuntimePermissions(sdkInt).joinToString()
            )
            onStateChanged(ProtocolState.ERROR)
            return
        }

        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Logger.w("WifiP2pManager disappeared after capability check")
            onStateChanged(ProtocolState.ERROR)
            return
        }

        // Initialize the channel: connects the app to the Wi-Fi P2P framework
        // The looper parameter specifies which thread receives P2P framework callbacks
        channel = wifiP2pManager!!.initialize(
            context,
            context.mainLooper,
            object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    // P2P framework disconnected — this can happen if Wi-Fi is turned off
                    Logger.w("WifiP2p channel disconnected")
                    onStateChanged(ProtocolState.ERROR)
                }
            }
        )

        registerP2pConnectionReceiver()
        Logger.i("WifiP2pManager initialized — registering P2P service")
        registerP2pService()
    }

    /**
     * Tracks P2P group formation so the sink can open the WFD RTSP control
     * connection to the source. This uses APIs present since API 14, preserving
     * the Fire OS 6 / API 25 floor.
     */
    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun registerP2pConnectionReceiver() {
        if (p2pConnectionReceiverRegistered) return
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (sdkInt >= 33) {
            context.registerReceiver(
                p2pConnectionReceiver,
                filter,
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(p2pConnectionReceiver, filter)
        }
        p2pConnectionReceiverRegistered = true

        // Cover an already-formed group when the receiver is restarted while
        // Wi-Fi Direct remains connected.
        refreshP2pControlConnection()
    }

    private fun unregisterP2pConnectionReceiver() {
        if (!p2pConnectionReceiverRegistered) return
        try {
            context.unregisterReceiver(p2pConnectionReceiver)
        } catch (e: IllegalArgumentException) {
            Logger.w("Miracast P2P connection receiver was already unregistered")
        } finally {
            p2pConnectionReceiverRegistered = false
            lastOutboundEndpoint = null
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun refreshP2pControlConnection() {
        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return
        if (!WifiDirectCompat.hasRequiredPermission(context, sdkInt)) return

        try {
            manager.requestConnectionInfo(activeChannel) { info ->
                if (info == null || !info.groupFormed) {
                    lastOutboundEndpoint = null
                    rtspServer.disconnectActiveSession()
                    return@requestConnectionInfo
                }

                if (info.isGroupOwner) {
                    // Public API 25 does not expose the P2P client's IP to the
                    // group owner. Keep the inbound RTSP listener as a
                    // compatibility fallback instead of using hidden APIs.
                    lastOutboundEndpoint = null
                    rtspServer.disconnectActiveSession()
                    Logger.d(
                        "Miracast sink is P2P group owner; retaining inbound RTSP fallback"
                    )
                    return@requestConnectionInfo
                }

                val groupOwnerHost = info.groupOwnerAddress?.hostAddress
                manager.requestGroupInfo(activeChannel) { group ->
                    val advertisedPort = if (sdkInt >= 30) {
                        runCatching { group?.owner?.wfdInfo?.controlPort }.getOrNull()
                    } else {
                        // WifiP2pDevice.getWfdInfo() is public only from API 30.
                        // Older Fire TV / Android releases use the WFD default.
                        null
                    }

                    val endpoint = WfdControlEndpointResolver.resolve(
                        groupFormed = true,
                        sinkIsGroupOwner = false,
                        groupOwnerHost = groupOwnerHost,
                        advertisedControlPort = advertisedPort
                    ) ?: return@requestGroupInfo

                    if (endpoint == lastOutboundEndpoint && rtspServer.hasActiveSession()) {
                        return@requestGroupInfo
                    }

                    lastOutboundEndpoint = endpoint
                    Logger.i(
                        "Miracast P2P source resolved at " +
                            "${endpoint.host}:${endpoint.port}; opening WFD RTSP control"
                    )
                    rtspServer.connectToSource(endpoint, scope)
                }
            }
        } catch (e: SecurityException) {
            Logger.e("Missing permission while resolving Miracast P2P connection", e)
        }
    }


    /**
     * Stops the P2P service advertisement.
     */
    private fun stopP2pAdvertisement() {
        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return
        val activeService = serviceInfo ?: return
        if (!isAdvertising) return

        try {
            manager.removeLocalService(
                activeChannel,
                activeService,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Logger.d("P2P service advertisement stopped")
                    }

                    override fun onFailure(reason: Int) {
                        Logger.w("P2P service removal failed, reason=$reason (non-fatal)")
                    }
                }
            )
        } catch (e: SecurityException) {
            Logger.e("Missing Wi-Fi P2P permission while removing Miracast service", e)
        }
        serviceInfo = null
        isAdvertising = false
    }

    /**
     * Registers the WFD local service record used by Wi-Fi Direct discovery.
     *
     * Android exposes Wi-Fi Direct service discovery through DNS-SD TXT records.
     * Miracast senders look for `_wfd._tcp` and then continue with WFD capability
     * negotiation over RTSP after the P2P group is formed.
     */
    private fun registerP2pService() {
        val manager = wifiP2pManager
        val activeChannel = channel
        if (manager == null || activeChannel == null) {
            Logger.w("Cannot register Miracast P2P service before Wi-Fi P2P initialization")
            onStateChanged(ProtocolState.ERROR)
            return
        }
        if (!WifiDirectCompat.hasRequiredPermission(context, sdkInt)) {
            Logger.w("Cannot register Miracast P2P service: missing Wi-Fi Direct permission")
            onStateChanged(ProtocolState.ERROR)
            return
        }

        val txtRecord = mapOf(
            "wfd_device_type" to "primary_sink",
            "wfd_session_available" to "1",
            "wfd_rtsp_port" to WFD_RTSP_PORT.toString(),
            "wfd_video_formats" to "h264-chp,h264-cbp",
            "wfd_audio_codecs" to "lpcm"
        )
        val localService = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE_NAME,
            SERVICE_TYPE_WFD,
            txtRecord
        )

        try {
            manager.addLocalService(
                activeChannel,
                localService,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        serviceInfo = localService
                        isAdvertising = true
                        startP2pListening()
                        rtspServer.start(scope)
                        // Re-resolve after RTSP becomes active so a receiver
                        // restart can reconnect to an already-formed P2P group.
                        refreshP2pControlConnection()
                        Logger.i("Miracast WFD P2P service advertised")
                        onStateChanged(ProtocolState.ADVERTISING)
                    }

                    override fun onFailure(reason: Int) {
                        serviceInfo = null
                        isAdvertising = false
                        Logger.e("Miracast WFD P2P service registration failed, reason=$reason")
                        onStateChanged(ProtocolState.ERROR)
                    }
                }
            )
        } catch (e: SecurityException) {
            serviceInfo = null
            isAdvertising = false
            Logger.e("Missing Wi-Fi P2P permission while registering Miracast service", e)
            onStateChanged(ProtocolState.ERROR)
        }
    }


    /**
     * Keeps the device discoverable to incoming Wi-Fi Direct probes using only
     * APIs available at the running SDK level.
     */
    @SuppressLint("NewApi", "MissingPermission")
    private fun startP2pListening() {
        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isListening = true
                Logger.d("Wi-Fi Direct listen/discovery mode active")
            }

            override fun onFailure(reason: Int) {
                isListening = false
                Logger.w("Wi-Fi Direct listen/discovery failed, reason=$reason")
            }
        }

        try {
            when (WifiDirectCompat.listenStrategy(sdkInt)) {
                WifiDirectCompat.ListenStrategy.EXPLICIT_LISTEN -> {
                    if (sdkInt >= 33) {
                        manager.startListening(activeChannel, listener)
                    }
                }
                WifiDirectCompat.ListenStrategy.LEGACY_DISCOVERY ->
                    manager.discoverPeers(activeChannel, listener)
            }
        } catch (e: SecurityException) {
            isListening = false
            Logger.e("Missing permission while entering Wi-Fi Direct listen mode", e)
        }
    }

    @SuppressLint("NewApi", "MissingPermission")
    private fun stopP2pListening() {
        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return
        if (!isListening) return

        try {
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isListening = false
                }

                override fun onFailure(reason: Int) {
                    Logger.w("Wi-Fi Direct listen stop failed, reason=$reason")
                    isListening = false
                }
            }
            if (sdkInt >= 33) {
                manager.stopListening(activeChannel, listener)
            } else {
                manager.stopPeerDiscovery(activeChannel, listener)
            }
        } catch (e: SecurityException) {
            Logger.e("Missing permission while stopping Wi-Fi Direct listen mode", e)
            isListening = false
        }
    }

    companion object {
        const val WFD_RTSP_PORT = 7236
        private const val SERVICE_INSTANCE_NAME = "PhairPlay"
        private const val SERVICE_TYPE_WFD = "_wfd._tcp"
    }
}

internal class WfdRtspServer(
    private val onSessionStarted: () -> Unit,
    private val onSessionStopped: () -> Unit,
    private val onMediaSample: (MpegTsDemuxer.ElementarySample) -> Unit = {}
) {
    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = MAX_MESSAGE_BYTES
    )

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeClient: Socket? = null
    @Volatile private var outboundConnecting = false

    // A deterministic non-zero session is available to direct unit tests of routeRequest().
    // A real client connection replaces it with ports allocated by MiracastMediaReceiver.
    private var wfdSession: WfdSession = WfdSession(TEST_RTP_PORT)
    private var mediaReceiver: MiracastMediaReceiver? = null

    private var currentCSeq = 0
    private var outboundCSeq = 1
    private var sessionStarted = false
    private var sentSinkOptions = false
    private val pendingOutboundMethods = mutableMapOf<Int, String>()
    private var deferredAction: WfdSession.Action = WfdSession.Action.None

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) {
            runServer(this)
        }
    }

    fun hasActiveSession(): Boolean =
        activeClient?.let { it.isConnected && !it.isClosed } == true

    fun connectToSource(endpoint: WfdControlEndpoint, scope: CoroutineScope) {
        if (!running || hasActiveSession() || outboundConnecting) return

        synchronized(this) {
            if (!running || hasActiveSession() || outboundConnecting) return
            outboundConnecting = true
        }

        scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(
                    InetSocketAddress(endpoint.host, endpoint.port),
                    CONTROL_CONNECT_TIMEOUT_MS
                )

                if (!tryClaimClient(socket)) {
                    socket.close()
                    return@launch
                }

                Logger.i(
                    "WFD RTSP outbound control connected to " +
                        "${endpoint.host}:${endpoint.port}"
                )
                handleClient(socket, scope)
            } catch (e: Exception) {
                if (running) {
                    Logger.w(
                        "Unable to connect WFD RTSP control to " +
                            "${endpoint.host}:${endpoint.port}: ${e.message}"
                    )
                }
                try {
                    socket?.close()
                } catch (_: Exception) {
                    // Best-effort cleanup.
                }
            } finally {
                outboundConnecting = false
            }
        }
    }

    fun disconnectActiveSession() {
        try {
            activeClient?.close()
        } catch (e: Exception) {
            Logger.e("Error closing active WFD RTSP session (non-fatal)", e)
        }
    }

    fun stop() {
        running = false
        try {
            activeClient?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing WFD RTSP sockets (non-fatal)", e)
        }
        mediaReceiver?.stop()
        mediaReceiver = null
        activeClient = null
        serverSocket = null
        pendingOutboundMethods.clear()
        if (sessionStarted) {
            sessionStarted = false
            onSessionStopped()
        }
    }

    private fun runServer(scope: CoroutineScope) {
        try {
            serverSocket = ServerSocket(MiracastReceiver.WFD_RTSP_PORT)
            Logger.i("WFD RTSP server listening on port ${MiracastReceiver.WFD_RTSP_PORT}")
            while (running && scope.isActive) {
                val client = serverSocket!!.accept()
                if (!tryClaimClient(client)) {
                    sendServiceUnavailable(client)
                    client.close()
                    continue
                }
                handleClient(client, scope)
            }
        } catch (e: Exception) {
            if (running) Logger.e("WFD RTSP server error", e)
        }
    }

    private fun handleClient(socket: Socket, scope: CoroutineScope) {
        val media = try {
            MiracastMediaReceiver(onMediaSample).also { it.start(scope) }
        } catch (e: Exception) {
            Logger.e("Unable to allocate Miracast RTP/RTCP sockets", e)
            try { socket.close() } catch (_: Exception) {}
            return
        }

        mediaReceiver = media
        wfdSession = WfdSession(media.rtpPort, media.rtcpPort)
        outboundCSeq = 1
        pendingOutboundMethods.clear()
        sentSinkOptions = false

        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            while (running && !socket.isClosed) {
                val message = requestReader.read(input) ?: break

                // RtspRequestReader is deliberately shared with AirPlay. For a response line
                // ("RTSP/1.0 200 OK") it yields method=RTSP/1.0 and uri=200, which is enough
                // to dispatch our outstanding sink-originated M2/M6/M7 transactions.
                if (message.method.startsWith("RTSP/", ignoreCase = true)) {
                    handleSourceResponse(message, output)
                    continue
                }

                currentCSeq = header(message.headers, "CSeq")?.toIntOrNull() ?: 0
                val response = routeRequest(message)
                sendResponse(output, response)

                if (message.method == "OPTIONS" && !sentSinkOptions) {
                    sentSinkOptions = true
                    sendRequest(
                        output = output,
                        method = "OPTIONS",
                        uri = "*",
                        headers = mapOf("Require" to "org.wfa.wfd1.0")
                    )
                }

                val action = deferredAction
                deferredAction = WfdSession.Action.None
                performAction(action, output)

                if (message.method == "TEARDOWN") break
            }
        } catch (e: Exception) {
            if (running) Logger.e("Error handling WFD RTSP client", e)
        } finally {
            media.stop()
            if (mediaReceiver === media) mediaReceiver = null
            try {
                socket.close()
            } catch (e: Exception) {
                Logger.e("Error closing WFD RTSP client socket (non-fatal)", e)
            }
            synchronized(this) {
                if (activeClient === socket) {
                    activeClient = null
                }
            }
            pendingOutboundMethods.clear()
            wfdSession.close()
            if (sessionStarted) {
                sessionStarted = false
                onSessionStopped()
            }
        }
    }

    @Synchronized
    private fun tryClaimClient(socket: Socket): Boolean {
        val current = activeClient
        if (current != null && current.isConnected && !current.isClosed) {
            return false
        }
        activeClient = socket
        return true
    }


    internal fun routeRequest(request: RtspRequest): RtspResponse {
        Logger.d("WFD RTSP ${request.method} ${request.uri}")
        return when (request.method) {
            "OPTIONS" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf(
                    "Public" to "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER"
                )
            )

            "GET_PARAMETER" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Content-Type" to "text/parameters"),
                body = wfdSession.capabilityResponse(request.body)
            )

            "SET_PARAMETER" -> {
                when (val action = wfdSession.applySourceParameters(request.body)) {
                    is WfdSession.Action.ProtocolError -> RtspResponse(
                        statusCode = action.statusCode,
                        statusMessage = action.message
                    )
                    else -> {
                        deferredAction = action
                        RtspResponse(statusCode = 200, statusMessage = "OK")
                    }
                }
            }

            // Compatibility fallback for senders that initiate M6/M7 themselves.
            "SETUP" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf(
                    "Session" to FALLBACK_SESSION_ID,
                    "Transport" to wfdSession.setupTransportHeader()
                )
            )

            "PLAY" -> {
                markSessionStarted()
                RtspResponse(
                    statusCode = 200,
                    statusMessage = "OK",
                    headers = mapOf("Session" to (wfdSession.sessionId ?: FALLBACK_SESSION_ID))
                )
            }

            "PAUSE" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Session" to (wfdSession.sessionId ?: FALLBACK_SESSION_ID))
            )

            "TEARDOWN" -> {
                deferredAction = wfdSession.close()
                RtspResponse(
                    statusCode = 200,
                    statusMessage = "OK",
                    headers = mapOf("Session" to (wfdSession.sessionId ?: FALLBACK_SESSION_ID))
                )
            }

            else -> RtspResponse(statusCode = 501, statusMessage = "Not Implemented")
        }
    }

    private fun handleSourceResponse(response: RtspRequest, output: OutputStream) {
        val statusCode = response.uri.toIntOrNull() ?: run {
            Logger.w("Malformed WFD RTSP response status: ${response.uri}")
            return
        }
        val cSeq = header(response.headers, "CSeq")?.toIntOrNull() ?: return
        val method = pendingOutboundMethods.remove(cSeq) ?: run {
            Logger.w("Unexpected WFD RTSP response CSeq=$cSeq")
            return
        }

        val action = when (method) {
            "SETUP" -> wfdSession.onSetupResponse(statusCode, response.headers)
            "PLAY" -> wfdSession.onPlayResponse(statusCode)
            else -> WfdSession.Action.None
        }
        performAction(action, output)
    }

    private fun performAction(action: WfdSession.Action, output: OutputStream) {
        when (action) {
            WfdSession.Action.None -> Unit
            is WfdSession.Action.SendSetup -> sendRequest(
                output = output,
                method = "SETUP",
                uri = action.presentationUrl,
                headers = mapOf("Transport" to wfdSession.setupTransportHeader())
            )
            is WfdSession.Action.SendPlay -> sendRequest(
                output = output,
                method = "PLAY",
                uri = action.presentationUrl,
                headers = action.sessionId?.let { mapOf("Session" to it) } ?: emptyMap()
            )
            WfdSession.Action.StreamStarted -> markSessionStarted()
            WfdSession.Action.StreamPaused -> Unit
            WfdSession.Action.SessionClosed -> {
                if (sessionStarted) {
                    sessionStarted = false
                    onSessionStopped()
                }
            }
            is WfdSession.Action.ProtocolError ->
                Logger.w("WFD state error ${action.statusCode}: ${action.message}")
        }
    }

    private fun markSessionStarted() {
        if (!sessionStarted) {
            sessionStarted = true
            onSessionStarted()
        }
    }

    private fun sendRequest(
        output: OutputStream,
        method: String,
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: String = ""
    ) {
        val cSeq = outboundCSeq++
        pendingOutboundMethods[cSeq] = method

        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: $cSeq\r\n")
        sb.append("User-Agent: PhairPlay/1.0\r\n")
        headers.forEach { (key, value) -> sb.append("$key: $value\r\n") }
        if (bytes.isNotEmpty()) {
            sb.append("Content-Type: text/parameters\r\n")
            sb.append("Content-Length: ${bytes.size}\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
        Logger.d("WFD RTSP -> $method $uri CSeq=$cSeq")
    }

    private fun sendResponse(outputStream: OutputStream, response: RtspResponse) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
        sb.append("CSeq: $currentCSeq\r\n")
        sb.append("Server: PhairPlay/1.0\r\n")
        response.headers.forEach { (key, value) -> sb.append("$key: $value\r\n") }
        if (body.isNotEmpty()) {
            sb.append("Content-Length: ${body.size}\r\n")
        }
        sb.append("\r\n")
        outputStream.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) outputStream.write(body)
        outputStream.flush()
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun sendServiceUnavailable(socket: Socket) {
        val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
        socket.outputStream.write(response.toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 65_536
        private const val FALLBACK_SESSION_ID = "PhairPlayWfdSession"
        private const val TEST_RTP_PORT = 19_000
        private const val CONTROL_CONNECT_TIMEOUT_MS = 5_000
    }
}
