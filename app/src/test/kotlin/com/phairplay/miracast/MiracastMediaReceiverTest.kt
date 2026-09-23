package com.phairplay.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiracastMediaReceiverTest {

    @Test
    fun `RTP and RTCP bind as an even consecutive UDP port pair`() {
        val receiver = MiracastMediaReceiver { }
        try {
            assertTrue(receiver.rtpPort > 0)
            assertEquals(0, receiver.rtpPort and 1)
            assertEquals(receiver.rtpPort + 1, receiver.rtcpPort)
        } finally {
            receiver.stop()
        }
    }
}
