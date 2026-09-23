package com.phairplay.media

import com.phairplay.service.Protocol

/**
 * Serializes ownership of the shared TV playback resources.
 *
 * Receiver protocols may advertise concurrently, but only one connected
 * protocol may own the Surface/audio playback path at a time.
 */
internal class PlaybackCoordinator {
    @Volatile
    private var currentOwner: Protocol? = null

    val owner: Protocol?
        @Synchronized get() = currentOwner

    @Synchronized
    fun tryAcquire(protocol: Protocol): Boolean {
        val existing = currentOwner
        if (existing == null || existing == protocol) {
            currentOwner = protocol
            return true
        }
        return false
    }

    @Synchronized
    fun canUse(protocol: Protocol): Boolean {
        val existing = currentOwner
        return existing == null || existing == protocol
    }

    /**
     * Releases the lease only when [protocol] currently owns it.
     *
     * @return true when ownership was actually released.
     */
    @Synchronized
    fun release(protocol: Protocol): Boolean {
        if (currentOwner != protocol) return false
        currentOwner = null
        return true
    }

    @Synchronized
    fun reset() {
        currentOwner = null
    }
}
