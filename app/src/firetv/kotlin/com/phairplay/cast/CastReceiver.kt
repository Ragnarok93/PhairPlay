package com.phairplay.cast

import android.content.Context
import android.content.Intent
import android.view.Surface
import com.phairplay.BuildConfig
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger

/**
 * Fire TV Cast receiver compatibility surface.
 *
 * Fire OS 6+ has no Google Play Services, so Google Cast Connect cannot run.
 * Keep the same public methods as the Google TV source set without referencing
 * any GMS or Media3 classes, ensuring the Fire TV flavor remains API-25-safe.
 */
class CastReceiver(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") surfaceProvider: () -> Surface? = { null },
    private val onStateChanged: (ProtocolState) -> Unit
) {
    fun start() {
        Logger.w("Google Cast disabled on Fire TV flavor")
        onStateChanged(ProtocolState.DISABLED)
    }

    fun handleIntent(@Suppress("UNUSED_PARAMETER") intent: Intent): Boolean = false

    fun updateVideoSurface(@Suppress("UNUSED_PARAMETER") surface: Surface?) = Unit

    fun stop() {
        onStateChanged(ProtocolState.DISABLED)
    }

    companion object {
        fun isAvailable(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

        fun isConfigured(appId: String = CAST_APP_ID): Boolean {
            val normalized = appId.trim()
            return normalized.isNotEmpty() &&
                normalized != "TODO_REGISTER_YOUR_CAST_APP_ID" &&
                normalized != "00000000"
        }

        const val CAST_APP_ID = BuildConfig.CAST_APP_ID
    }
}
