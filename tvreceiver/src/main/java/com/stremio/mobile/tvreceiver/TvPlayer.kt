package com.stremio.mobile.tvreceiver

import android.net.Uri

/**
 * Simplified player interface for the TV receiver.
 * Supports fallback chain: ExoPlayer (hardware) -> VLC (software).
 */
interface TvPlayer {
    /** Human-readable name for logging. */
    val name: String

    /** True if this player uses software decoding (fallback). */
    val isSoftwareDecoder: Boolean get() = false

    fun play(url: String, positionMs: Long, title: String?)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()
    fun release()

    fun getPositionMs(): Long
    fun getDurationMs(): Long
    fun isPlaying(): Boolean

    /** Called when a codec error occurs. Returns true if the player handled it (e.g. switched audio track). */
    fun onCodecError(error: TvCodecError): Boolean = false
}

/** Details about a codec failure. */
data class TvCodecError(
    val message: String,
    val isAudioCodec: Boolean,
    val mimeType: String?
)

/** Callback for player events. */
interface TvPlayerCallback {
    fun onCodecError(error: TvCodecError)
    fun onPlaybackEnded()
}
