package com.stremio.mobile.player

import android.content.Context
import android.net.Uri
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import timber.log.Timber

/**
 * VLC-based player using libVLC. Serves as a fallback when ExoPlayer and MPV
 * fail (e.g. exotic codecs). Software-decodes via FFmpeg.
 */
class VlcPlayer(
    context: Context,
    private val settings: com.stremio.core.types.profile.Profile.Settings? = null
) : Player {
    override val engine: PlayerEngine = PlayerEngine.VLC

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableRuntimeState = MutableStateFlow(PlayerRuntimeState())

    override val runtimeState: StateFlow<PlayerRuntimeState> = mutableRuntimeState

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private var released = false

    private var currentUri: Uri? = null
    private var currentStartPositionMs: Long = 0L

    private val eventListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                updateState(isPlaying = true, isBuffering = false, error = null)
            }
            MediaPlayer.Event.Paused -> {
                updateState(isPlaying = false)
            }
            MediaPlayer.Event.Stopped -> {
                updateState(isPlaying = false)
            }
            MediaPlayer.Event.EndReached -> {
                updateState(isPlaying = false)
                // Notify ended via state
                scope.launch {
                    val current = mutableRuntimeState.value
                    mutableRuntimeState.value = current.copy(isPlaying = false)
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                Timber.e("VLC encountered error")
                updateState(isPlaying = false, error = "VLC playback error")
            }
            MediaPlayer.Event.Buffering -> {
                val buffering = event.buffering < 100f
                updateState(isBuffering = buffering)
            }
            MediaPlayer.Event.TimeChanged -> {
                val pos = event.timeChanged
                val dur = mediaPlayer?.length ?: -1L
                scope.launch {
                    val current = mutableRuntimeState.value
                    mutableRuntimeState.value = current.copy(
                        positionMs = pos,
                        durationMs = if (dur > 0) dur else current.durationMs
                    )
                }
            }
            else -> {}
        }
    }

    private fun updateState(
        isPlaying: Boolean? = null,
        isBuffering: Boolean? = null,
        error: String? = null
    ) {
        scope.launch {
            val current = mutableRuntimeState.value
            mutableRuntimeState.value = current.copy(
                isPlaying = isPlaying ?: current.isPlaying,
                isBuffering = isBuffering ?: current.isBuffering,
                error = error ?: current.error
            )
        }
    }

    override fun createView(context: Context): View {
        if (videoLayout == null) {
            videoLayout = VLCVideoLayout(context).apply {
                // Attach to media player when created
                mediaPlayer?.attachViews(this, null, false, false)
            }
        }
        return videoLayout!!
    }

    override fun load(
        uri: Uri,
        startPositionMs: Long,
        subtitles: List<ExternalSubtitle>,
        preferredSubtitleLang: String?,
        settings: com.stremio.core.types.profile.Profile.Settings?
    ) {
        if (released) return
        currentUri = uri
        currentStartPositionMs = startPositionMs

        try {
            // Initialize libVLC if needed
            if (libVLC == null) {
                val options = arrayListOf(
                    "--no-drop-late-frames",
                    "--no-skip-frames",
                    "-vvv"
                )
                libVLC = LibVLC(appContext, options)
            }

            // Release old player
            mediaPlayer?.let {
                it.stop()
                it.detachViews()
                it.release()
            }

            // Create new player
            val lib = libVLC ?: return
            mediaPlayer = MediaPlayer(lib).apply {
                setEventListener(eventListener)
                videoLayout?.let { layout ->
                    attachViews(layout, null, false, false)
                }
            }

            // Load media
            val media = Media(lib, uri).apply {
                setHWDecoderEnabled(true, false)
                // Start position
                if (startPositionMs > 0) {
                    addOption(":start-time=${startPositionMs / 1000}")
                }
            }
            mediaPlayer?.media = media
            media.release()

            // Add external subtitles
            subtitles.forEach { sub ->
                try {
                    mediaPlayer?.addSlave(
                        Media.Slave.Type.Subtitle,
                        Uri.parse(sub.url),
                        sub.label?.let { true } ?: false
                    )
                } catch (e: Exception) {
                    Timber.w("Failed to add subtitle: ${e.message}")
                }
            }

            updateState(isBuffering = true, error = null)
            mediaPlayer?.play()

        } catch (e: Exception) {
            Timber.e("VLC load failed: ${e.message}")
            updateState(error = "VLC failed to load: ${e.message}")
        }
    }

    override fun retry() {
        val uri = currentUri ?: return
        load(uri, mutableRuntimeState.value.positionMs)
    }

    override fun play() {
        mediaPlayer?.play()
    }

    override fun pause() {
        mediaPlayer?.pause()
    }

    override fun seekTo(positionMs: Long) {
        mediaPlayer?.time = positionMs
    }

    override fun setPlaybackSpeed(speed: Float) {
        mediaPlayer?.rate = speed
    }

    override fun setResizeMode(mode: PlayerResizeMode) {
        // VLC video layout handles aspect ratio; map modes
        // FIT = default, others not directly supported via simple API
    }

    override fun selectAudioTrack(id: String) {
        try {
            val trackId = id.toIntOrNull() ?: return
            mediaPlayer?.setAudioTrack(trackId)
        } catch (e: Exception) {
            Timber.w("Failed to select audio track: ${e.message}")
        }
    }

    override fun selectSubtitleTrack(id: String) {
        try {
            val trackId = id.toIntOrNull() ?: return
            mediaPlayer?.setSpuTrack(trackId)
        } catch (e: Exception) {
            Timber.w("Failed to select subtitle track: ${e.message}")
        }
    }

    override fun disableSubtitles() {
        mediaPlayer?.setSpuTrack(-1)
    }

    override fun setSubtitleStyle(style: PlayerSubtitleStyle) {
        // libVLC subtitle styling is limited via API
    }

    override fun addExternalSubtitleTracks(tracks: List<ExternalSubtitle>) {
        tracks.forEach { sub ->
            try {
                mediaPlayer?.addSlave(
                    Media.Slave.Type.Subtitle,
                    Uri.parse(sub.url),
                    false
                )
            } catch (e: Exception) {
                Timber.w("Failed to add subtitle track: ${e.message}")
            }
        }
    }

    override fun addLocalSubtitle(track: ExternalSubtitle) {
        addExternalSubtitleTracks(listOf(track))
    }

    override fun release() {
        if (released) return
        released = true
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
            videoLayout = null
        } catch (e: Exception) {
            Timber.w("VLC release failed: ${e.message}")
        }
        scope.cancel()
    }
}
