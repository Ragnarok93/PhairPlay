package com.phairplay.cast

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.google.android.gms.cast.tv.CastReceiverContext
import com.phairplay.BuildConfig
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger

/**
 * Google TV Cast Connect receiver lifecycle and Media3 playback owner.
 *
 * This source set is compiled only for the Google TV flavor. Fire TV uses its
 * own no-GMS implementation, preserving the API-25 / Fire OS 6 floor.
 */
class CastReceiver(
    private val context: Context,
    private val surfaceProvider: () -> Surface? = { null },
    private val onStateChanged: (ProtocolState) -> Unit
) {
    private var started = false
    private var mediaController: CastMediaController? = null

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CastReceiver.start() must run on the main looper"
        }

        if (!isConfigured()) {
            Logger.w("Google Cast is not configured: missing Cast application ID")
            onStateChanged(ProtocolState.ERROR)
            return
        }

        if (!isAvailable(context)) {
            Logger.w("Google Cast not available on this device (missing Google Play Services)")
            onStateChanged(ProtocolState.DISABLED)
            return
        }

        try {
            CastReceiverContext.initInstance(context.applicationContext)
            val receiverContext = CastReceiverContext.getInstance()
            mediaController = CastMediaController(
                context = context,
                mediaManager = receiverContext.mediaManager,
                surfaceProvider = surfaceProvider,
                onPlaybackActive = { active ->
                    onStateChanged(
                        if (active) ProtocolState.CONNECTED else ProtocolState.ADVERTISING
                    )
                }
            )
            receiverContext.start()
            started = true
            Logger.i("Cast Connect receiver + Media3 playback started")
            onStateChanged(ProtocolState.ADVERTISING)
        } catch (e: Exception) {
            Logger.e("Failed to start Cast Connect receiver", e)
            mediaController?.release()
            mediaController = null
            started = false
            onStateChanged(ProtocolState.ERROR)
        }
    }

    fun handleIntent(intent: Intent): Boolean =
        mediaController?.handleIntent(intent) ?: false

    fun updateVideoSurface(surface: Surface?) {
        mediaController?.updateSurface(surface)
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { stop() }
            return
        }
        try {
            mediaController?.release()
            mediaController = null
            if (started) {
                CastReceiverContext.getInstance().stop()
                Logger.i("Cast Connect receiver stopped")
            }
        } catch (e: Exception) {
            Logger.e("Failed to stop Cast Connect receiver", e)
        } finally {
            started = false
            onStateChanged(ProtocolState.DISABLED)
        }
    }

    companion object {
        fun isAvailable(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo("com.google.android.gms", 0)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun isConfigured(appId: String = CAST_APP_ID): Boolean {
            val normalized = appId.trim()
            return normalized.isNotEmpty() &&
                normalized != "TODO_REGISTER_YOUR_CAST_APP_ID" &&
                normalized != "00000000"
        }

        const val CAST_APP_ID = BuildConfig.CAST_APP_ID
    }
}
