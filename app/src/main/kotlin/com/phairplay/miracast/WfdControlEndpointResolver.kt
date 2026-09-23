package com.phairplay.miracast

/** Network endpoint for the WFD source RTSP control server. */
internal data class WfdControlEndpoint(
    val host: String,
    val port: Int
)

/**
 * Resolves whether PhairPlay can open the standards-correct outbound WFD RTSP
 * control connection using only public Wi-Fi Direct information.
 *
 * A primary sink that is a P2P client can connect directly to the group owner
 * (the source in the normal WFD topology). If the sink itself became group
 * owner, Android's API-25-era public surface does not expose the client's IP,
 * so the caller deliberately keeps the inbound compatibility listener instead
 * of reaching for hidden APIs.
 */
internal object WfdControlEndpointResolver {
    const val DEFAULT_WFD_CONTROL_PORT = 7236

    fun resolve(
        groupFormed: Boolean,
        sinkIsGroupOwner: Boolean,
        groupOwnerHost: String?,
        advertisedControlPort: Int?
    ): WfdControlEndpoint? {
        if (!groupFormed || sinkIsGroupOwner) return null

        val host = groupOwnerHost?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = advertisedControlPort
            ?.takeIf { it in 1..65535 }
            ?: DEFAULT_WFD_CONTROL_PORT

        return WfdControlEndpoint(host, port)
    }
}
