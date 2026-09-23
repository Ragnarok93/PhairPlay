package com.phairplay.media

import com.phairplay.service.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {

    @Test
    fun `first protocol acquires playback and same protocol can reenter`() {
        val coordinator = PlaybackCoordinator()

        assertTrue(coordinator.tryAcquire(Protocol.AIRPLAY))
        assertTrue(coordinator.tryAcquire(Protocol.AIRPLAY))
        assertEquals(Protocol.AIRPLAY, coordinator.owner)
    }

    @Test
    fun `second protocol cannot use playback while another owns it`() {
        val coordinator = PlaybackCoordinator()

        assertTrue(coordinator.tryAcquire(Protocol.MIRACAST))
        assertFalse(coordinator.tryAcquire(Protocol.CAST))
        assertFalse(coordinator.canUse(Protocol.CAST))
        assertTrue(coordinator.canUse(Protocol.MIRACAST))
        assertEquals(Protocol.MIRACAST, coordinator.owner)
    }

    @Test
    fun `non owner release does not disturb active protocol`() {
        val coordinator = PlaybackCoordinator()
        coordinator.tryAcquire(Protocol.CAST)

        assertFalse(coordinator.release(Protocol.AIRPLAY))
        assertEquals(Protocol.CAST, coordinator.owner)
    }

    @Test
    fun `owner release makes playback available again`() {
        val coordinator = PlaybackCoordinator()
        coordinator.tryAcquire(Protocol.AIRPLAY)

        assertTrue(coordinator.release(Protocol.AIRPLAY))
        assertNull(coordinator.owner)
        assertTrue(coordinator.canUse(Protocol.MIRACAST))
        assertTrue(coordinator.tryAcquire(Protocol.MIRACAST))
    }

    @Test
    fun `reset clears owner for service shutdown`() {
        val coordinator = PlaybackCoordinator()
        coordinator.tryAcquire(Protocol.CAST)

        coordinator.reset()

        assertNull(coordinator.owner)
        assertTrue(coordinator.canUse(Protocol.AIRPLAY))
    }
}
