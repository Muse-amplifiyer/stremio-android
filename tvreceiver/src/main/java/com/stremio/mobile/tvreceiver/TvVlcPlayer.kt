package com.stremio.mobile.tvreceiver

import android.content.Context
import android.net.Uri
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * VLC-based player for TV receiver. Software decoding fallback
 * when ExoPlayer fails with unsupported codecs.
 */
class TvVlcPlayer(
    private val context: Context,
    private val callback: TvPlayerCallback
) : TvPlayer {
    override val name = "VLC"
    override val isSoftwareDecoder = true

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null

    private val eventListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.EndReached -> callback.onPlaybackEnded()
            MediaPlayer.Event.EncounteredError -> {
                Log.e(TAG, "VLC encountered error")
                callback.onCodecError(TvCodecError(
                    message = "VLC playback error",
                    isAudioCodec = false,
                    mimeType = null
                ))
            }
            else -> {}
        }
    }

    /** Must be called with the activity's video layout before play(). */
    fun attachVideoLayout(layout: VLCVideoLayout) {
        videoLayout = layout
        mediaPlayer?.attachViews(layout, null, false, false)
    }

    override fun play(url: String, positionMs: Long, title: String?) {
        try {
            if (libVLC == null) {
                libVLC = LibVLC(context, arrayListOf(
                    "--no-drop-late-frames",
                    "--no-skip-frames"
                ))
            }

            mediaPlayer?.let {
                it.stop()
                it.detachViews()
                it.release()
            }

            val lib = libVLC ?: return
            mediaPlayer = MediaPlayer(lib).apply {
                setEventListener(eventListener)
                videoLayout?.let { layout ->
                    attachViews(layout, null, false, false)
                }
            }

            val media = Media(lib, Uri.parse(url)).apply {
                setHWDecoderEnabled(true, false)
                if (positionMs > 0) {
                    addOption(":start-time=${positionMs / 1000}")
                }
            }
            mediaPlayer?.media = media
            media.release()

            PlayerHolder.vlcPlayer = this
            mediaPlayer?.play()
            Log.i(TAG, "VLC playing $url")

        } catch (e: Exception) {
            Log.e(TAG, "VLC play failed: ${e.message}")
            callback.onCodecError(TvCodecError(
                message = e.message ?: "VLC init failed",
                isAudioCodec = false,
                mimeType = null
            ))
        }
    }

    override fun pause() {
        mediaPlayer?.pause()
    }

    override fun resume() {
        mediaPlayer?.play()
    }

    override fun seekTo(positionMs: Long) {
        mediaPlayer?.time = positionMs
    }

    override fun stop() {
        mediaPlayer?.stop()
    }

    override fun release() {
        try {
            mediaPlayer?.let {
                it.stop()
                it.setEventListener(null)
                it.detachViews()
                it.release()
            }
            mediaPlayer = null
            libVLC?.release()
            libVLC = null
        } catch (e: Exception) {
            Log.w(TAG, "VLC release failed: ${e.message}")
        }
    }

    override fun getPositionMs(): Long = mediaPlayer?.time ?: 0
    override fun getDurationMs(): Long = mediaPlayer?.length?.takeIf { it > 0 } ?: 0
    override fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    companion object {
        private const val TAG = "TvVlcPlayer"
    }
}
