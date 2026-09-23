package com.phairplay.miracast

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiDirectCompatTest {

    @Test
    fun `API 25 through 32 require fine location for Wi-Fi Direct operations`() {
        for (api in 25..32) {
            assertEquals(
                setOf("android.permission.ACCESS_FINE_LOCATION"),
                WifiDirectCompat.requiredRuntimePermissions(api)
            )
        }
    }

    @Test
    fun `API 33 and newer require nearby Wi-Fi devices instead of location`() {
        for (api in 33..35) {
            assertEquals(
                setOf("android.permission.NEARBY_WIFI_DEVICES"),
                WifiDirectCompat.requiredRuntimePermissions(api)
            )
        }
    }

    @Test
    fun `API below supported Fire TV floor reports no runtime permission contract`() {
        assertEquals(emptySet<String>(), WifiDirectCompat.requiredRuntimePermissions(24))
    }

    @Test
    fun `API 25 through 32 use legacy discovery and API 33 plus explicit listen`() {
        assertEquals(
            WifiDirectCompat.ListenStrategy.LEGACY_DISCOVERY,
            WifiDirectCompat.listenStrategy(25)
        )
        assertEquals(
            WifiDirectCompat.ListenStrategy.LEGACY_DISCOVERY,
            WifiDirectCompat.listenStrategy(32)
        )
        assertEquals(
            WifiDirectCompat.ListenStrategy.EXPLICIT_LISTEN,
            WifiDirectCompat.listenStrategy(33)
        )
        assertEquals(
            WifiDirectCompat.ListenStrategy.EXPLICIT_LISTEN,
            WifiDirectCompat.listenStrategy(35)
        )
    }
}
