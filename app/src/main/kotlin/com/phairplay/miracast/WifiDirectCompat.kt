package com.phairplay.miracast

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Centralizes Wi-Fi Direct API-level compatibility.
 *
 * Fire TV OS 6 is Android 7.1 / API 25, so shared Miracast code must remain
 * loadable and executable from API 25 upward. Android 13 (API 33) moved Wi-Fi
 * Direct runtime authorization from location to NEARBY_WIFI_DEVICES.
 */
internal object WifiDirectCompat {
    enum class ListenStrategy { LEGACY_DISCOVERY, EXPLICIT_LISTEN }
    const val PERMISSION_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val PERMISSION_NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"

    fun requiredRuntimePermissions(apiLevel: Int = Build.VERSION.SDK_INT): Set<String> =
        when {
            apiLevel < 25 -> emptySet()
            apiLevel >= 33 -> setOf(PERMISSION_NEARBY_WIFI_DEVICES)
            else -> setOf(PERMISSION_FINE_LOCATION)
        }

    fun listenStrategy(apiLevel: Int = Build.VERSION.SDK_INT): ListenStrategy =
        if (apiLevel >= 33) ListenStrategy.EXPLICIT_LISTEN
        else ListenStrategy.LEGACY_DISCOVERY

    fun hasRequiredPermission(
        context: Context,
        apiLevel: Int = Build.VERSION.SDK_INT
    ): Boolean {
        val permissions = requiredRuntimePermissions(apiLevel)
        if (permissions.isEmpty()) return false
        return permissions.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isWifiDirectAvailable(context: Context): Boolean =
        context.getSystemService(Context.WIFI_P2P_SERVICE) != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
}
