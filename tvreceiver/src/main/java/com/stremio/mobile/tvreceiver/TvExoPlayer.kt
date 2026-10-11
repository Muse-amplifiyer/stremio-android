package com.stremio.mobile.tvreceiver

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * ExoPlayer implementation with audio track fallback.
 * On audio codec errors, tries alternative audio tracks before giving up.
 */
class TvExoPlayer(
    private val context: Context,
    private val callback: TvPlayerCallback
) : TvPlayer {
    override val name = "ExoPlayer"
    override val isSoftwareDecoder = false

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var triedAudioTracks = mutableSetOf<Int>()
    private var currentUrl: String? = null
    private var currentPositionMs: Long = 0L

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error: ${error.message}")
            val isAudio = error.message?.contains("audio", ignoreCase = true) == true ||
                         error.message?.contains("MediaCodecAudioRenderer", ignoreCase = true) == true
            val mimeType = extractMimeType(error.message)

            // Try audio track fallback first
            if (isAudio && tryNextAudioTrack()) {
                Log.i(TAG, "Switched to alternative audio track")
                return
            }

            // No more tracks to try, report codec error for VLC fallback
            callback.onCodecError(TvCodecError(
                message = error.message ?: "Unknown error",
                isAudioCodec = isAudio,
                mimeType = mimeType
            ))
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                callback.onPlaybackEnded()
            }
        }
    }

    private fun extractMimeType(message: String?): String? {
        if (message == null) return null
        // Look for patterns like "audio/true-hd" in the error message
        val regex = Regex("""audio/[\w-]+""")
        return regex.find(message)?.value
    }

    /**
     * Try the next available audio track. Returns true if switched.
     */
    private fun tryNextAudioTrack(): Boolean {
        val p = player ?: return false
        val trackSelector = trackSelector ?: return false

        try {
            val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: return false

            for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                if (p.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) continue

                val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                for (groupIndex in 0 until trackGroups.length) {
                    val trackGroup = trackGroups.get(groupIndex)
                    for (trackIndex in 0 until trackGroup.length) {
                        val trackId = groupIndex * 100 + trackIndex
                        if (trackId in triedAudioTracks) continue

                        val format = trackGroup.getFormat(trackIndex)
                        val mimeType = format.sampleMimeType ?: continue

                        // Skip TrueHD/DTS-HD which are likely unsupported
                        if (mimeType.contains("true-hd", ignoreCase = true) ||
                            mimeType.contains("dts-hd", ignoreCase = true)) {
                            continue
                        }

                        // Try this track
                        triedAudioTracks.add(trackId)
                        val params = trackSelector.buildUponParameters()
                            .setRendererDisabled(rendererIndex, false)
                            .setSelectionOverride(
                                rendererIndex,
                                trackGroups,
                                DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex)
                            )
                        trackSelector.setParameters(params)
                        Log.i(TAG, "Trying audio track: ${format.label} (${mimeType})")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio track fallback failed: ${e.message}")
        }
        return false
    }

    override fun play(url: String, positionMs: Long, title: String?) {
        currentUrl = url
        currentPositionMs = positionMs
        triedAudioTracks.clear()

        ensurePlayer()
        val p = player ?: return

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(guessVideoMime(url))
            .build()
        p.setMediaItem(mediaItem)
        p.prepare()
        if (positionMs > 0) p.seekTo(positionMs)
        p.play()

        PlayerHolder.exoPlayer = p
    }

    override fun pause() {
        player?.pause()
    }

    override fun resume() {
        player?.play()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    override fun stop() {
        player?.stop()
        player?.clearMediaItems()
    }

    override fun release() {
        player?.removeListener(listener)
        player?.release()
        player = null
        trackSelector = null
        PlayerHolder.exoPlayer = null
    }

    override fun getPositionMs(): Long = player?.currentPosition ?: 0
    override fun getDurationMs(): Long = player?.duration?.takeIf { it > 0 } ?: 0
    override fun isPlaying(): Boolean = player?.isPlaying == true

    private fun ensurePlayer() {
        if (player != null) return
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("StremioTvReceiver/1.0")
            .setAllowCrossProtocolRedirects(true)
        trackSelector = DefaultTrackSelector(context)
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setTrackSelector(trackSelector!!)
            .build()
            .also {
                it.addListener(listener)
                PlayerHolder.exoPlayer = it
            }
    }

    private fun guessVideoMime(url: String): String? {
        val lower = url.lowercase().substringBefore("?")
        return when {
            lower.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            lower.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            lower.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            lower.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            else -> null
        }
    }

    companion object {
        private const val TAG = "TvExoPlayer"
    }
}
