package com.phairplay.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WfdControlEndpointResolverTest {

    @Test
    fun `client sink connects to P2P group owner using advertised WFD control port`() {
        assertEquals(
            WfdControlEndpoint("192.168.49.1", 8554),
            WfdControlEndpointResolver.resolve(
                groupFormed = true,
                sinkIsGroupOwner = false,
                groupOwnerHost = "192.168.49.1",
                advertisedControlPort = 8554
            )
        )
    }

    @Test
    fun `missing WFD control port falls back to standard 7236`() {
        assertEquals(
            WfdControlEndpoint("192.168.49.1", 7236),
            WfdControlEndpointResolver.resolve(
                groupFormed = true,
                sinkIsGroupOwner = false,
                groupOwnerHost = "192.168.49.1",
                advertisedControlPort = 0
            )
        )
    }

    @Test
    fun `sink group owner retains inbound compatibility listener`() {
        assertNull(
            WfdControlEndpointResolver.resolve(
                groupFormed = true,
                sinkIsGroupOwner = true,
                groupOwnerHost = "192.168.49.1",
                advertisedControlPort = 7236
            )
        )
    }

    @Test
    fun `disconnected or unresolved P2P group has no outbound endpoint`() {
        assertNull(
            WfdControlEndpointResolver.resolve(
                groupFormed = false,
                sinkIsGroupOwner = false,
                groupOwnerHost = "192.168.49.1",
                advertisedControlPort = 7236
            )
        )
        assertNull(
            WfdControlEndpointResolver.resolve(
                groupFormed = true,
                sinkIsGroupOwner = false,
                groupOwnerHost = null,
                advertisedControlPort = 7236
            )
        )
    }
}
