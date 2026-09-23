package com.phairplay.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata
import com.google.android.gms.cast.tv.media.MediaException
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.cast.tv.media.MediaManager
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.phairplay.util.Logger

/**
 * Cast Connect media player for the Google TV flavor.
 *
 * ExoPlayer owns decoding/container/adaptive-streaming. MediaSessionCompat is the
 * Cast Connect bridge for transport commands and status. This class must be
 * created and released on the main looper.
 */
internal class CastMediaController(
    context: Context,
    private val mediaManager: MediaManager,
    private val surfaceProvider: () -> Surface?,
    private val onPlaybackActive: (Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val player = ExoPlayer.Builder(appContext).build()
    private val mediaSession = MediaSessionCompat(appContext, SESSION_TAG)
    private var currentMediaInfo: MediaInfo? = null
    private var released = false

    private val loadCallback = object : MediaLoadCommandCallback() {
        override fun onLoad(
            senderId: String?,
            loadRequestData: MediaLoadRequestData
        ): Task<MediaLoadRequestData> {
            val completion = TaskCompletionSource<MediaLoadRequestData>()
            mainHandler.post {
                if (released) {
                    completion.setException(loadFailure("Cast player is released"))
                    return@post
                }

                try {
                    load(loadRequestData)
                    completion.setResult(loadRequestData)
                } catch (e: MediaException) {
                    completion.setException(e)
                } catch (e: Exception) {
                    Logger.e("Cast LOAD failed", e)
                    completion.setException(loadFailure(e.message ?: "Cast load failed"))
                }
            }
            return completion.task
        }
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CastMediaController must be created on the main looper"
        }

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                attachSurface(surfaceProvider())
                player.play()
                if (currentMediaInfo != null) {
                    onPlaybackActive(true)
                }
                publishState()
            }

            override fun onPause() {
                player.pause()
                publishState()
            }

            override fun onSeekTo(pos: Long) {
                player.seekTo(pos.coerceAtLeast(0L))
                publishState()
            }

            override fun onStop() {
                player.stop()
                player.clearMediaItems()
                player.clearVideoSurface()
                currentMediaInfo = null
                onPlaybackActive(false)
                publishState()
            }
        })
        mediaSession.isActive = true

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.clearVideoSurface()
                    onPlaybackActive(false)
                }
                publishState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishState()
            }

            override fun onPlayerError(error: PlaybackException) {
                Logger.e("Cast playback error: ${error.errorCodeName}", error)
                player.clearVideoSurface()
                onPlaybackActive(false)
                publishState()
            }
        })

        // Do not claim the shared TV Surface while Cast is only advertising.
        // load()/onPlay() attach it when Cast actually has active media.
        mediaManager.setSessionCompatToken(mediaSession.sessionToken)
        mediaManager.setMediaLoadCommandCallback(loadCallback)
        publishState()
    }

    fun handleIntent(intent: Intent): Boolean {
        if (released) return false
        return mediaManager.onNewIntent(intent)
    }

    fun updateSurface(surface: Surface?) {
        val update = {
            if (!released) {
                if (currentMediaInfo != null && player.playbackState != Player.STATE_ENDED) {
                    attachSurface(surface)
                } else {
                    player.clearVideoSurface()
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            mainHandler.post(update)
        }
    }

    fun release() {
        if (released) return
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CastMediaController.release() must run on the main looper"
        }
        released = true
        mediaManager.setMediaLoadCommandCallback(null)
        mediaManager.setSessionCompatToken(null)
        player.clearVideoSurface()
        player.release()
        mediaSession.isActive = false
        mediaSession.release()
        currentMediaInfo = null
        onPlaybackActive(false)
    }

    private fun load(request: MediaLoadRequestData) {
        val mediaInfo = request.mediaInfo ?: throw loadFailure("LOAD has no MediaInfo")
        val contentUrl = resolveContentUrl(mediaInfo)
            ?: throw loadFailure("LOAD has no supported HTTP(S) content URL")

        currentMediaInfo = mediaInfo
        mediaManager.setDataFromLoad(request)

        attachSurface(surfaceProvider())
        player.setMediaItem(buildMediaItem(mediaInfo, contentUrl))

        val startPositionMs = request.currentTime
        if (startPositionMs != MediaLoadRequestData.PLAY_POSITION_UNASSIGNED &&
            startPositionMs > 0L
        ) {
            player.seekTo(startPositionMs)
        }

        val rate = request.playbackRate
        if (rate in MediaLoadRequestData.PLAYBACK_RATE_MIN..MediaLoadRequestData.PLAYBACK_RATE_MAX) {
            player.setPlaybackSpeed(rate.toFloat())
        }

        player.prepare()
        player.playWhenReady = request.autoplay != false
        onPlaybackActive(true)

        publishMetadata()
        publishState()
        // setDataFromLoad changes Cast-specific status overrides, so explicitly
        // broadcast once after loading. Subsequent MediaSession changes propagate.
        mediaManager.broadcastMediaStatus()
        Logger.i("Cast media loading: $contentUrl")
    }

    private fun attachSurface(surface: Surface?) {
        if (surface != null && surface.isValid) {
            player.setVideoSurface(surface)
        } else {
            player.clearVideoSurface()
        }
    }

    private fun publishMetadata() {
        val info = currentMediaInfo
        val castMetadata = info?.metadata
        val title = castMetadata?.getString(CastMediaMetadata.KEY_TITLE)
            ?: info?.contentId
            ?: "Cast media"
        val subtitle = castMetadata?.getString(CastMediaMetadata.KEY_SUBTITLE)

        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, resolveContentUrl(info))

        if (!subtitle.isNullOrBlank()) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
        }
        if (player.duration > 0L) {
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.duration)
        }
        mediaSession.setMetadata(builder.build())
    }

    private fun publishState() {
        if (released) return

        val state = when (player.playbackState) {
            Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            Player.STATE_READY -> if (player.isPlaying) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }
            Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            else -> if (player.mediaItemCount > 0) {
                PlaybackStateCompat.STATE_PAUSED
            } else {
                PlaybackStateCompat.STATE_NONE
            }
        }

        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setBufferedPosition(player.bufferedPosition.coerceAtLeast(0L))
                .setState(
                    state,
                    player.currentPosition.coerceAtLeast(0L),
                    player.playbackParameters.speed
                )
                .build()
        )
        publishMetadata()
    }

    private fun loadFailure(message: String): MediaException {
        Logger.w(message)
        return MediaException(
            MediaError.Builder()
                .setDetailedErrorCode(MediaError.DetailedErrorCode.LOAD_FAILED)
                .setReason(MediaError.ERROR_REASON_INVALID_REQUEST)
                .build()
        )
    }

    companion object {
        private const val SESSION_TAG = "PhairPlayCast"

        internal fun resolveContentUrl(mediaInfo: MediaInfo?): String? {
            if (mediaInfo == null) return null
            val candidate = mediaInfo.contentUrl?.takeIf { it.isNotBlank() }
                ?: mediaInfo.contentId.takeIf { it.isNotBlank() }
                ?: return null
            val scheme = Uri.parse(candidate).scheme?.lowercase()
            return candidate.takeIf { scheme == "http" || scheme == "https" }
        }

        internal fun buildMediaItem(mediaInfo: MediaInfo, contentUrl: String): MediaItem {
            val mimeType = mediaInfo.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            return MediaItem.Builder()
                .setUri(contentUrl)
                .apply {
                    if (mimeType != null) setMimeType(mimeType)
                }
                .build()
        }
    }
}
