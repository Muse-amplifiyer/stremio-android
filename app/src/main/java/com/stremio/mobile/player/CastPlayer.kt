package com.stremio.mobile.player

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.stremio.mobile.cast.CastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A [Player] backend that renders on a Chromecast instead of locally.
 *
 * Only direct HTTP(S) streams are supported: the Cast device fetches the URL
 * itself, so anything served by the phone's local streaming server (torrents)
 * cannot be cast in this version. Callers must check that before routing here.
 */
class CastPlayer(
    private val appContext: Context,
) : Player {
    override val engine: PlayerEngine = PlayerEngine.CAST

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(PlayerRuntimeState())
    override val runtimeState: StateFlow<PlayerRuntimeState> = mutableState

    private var currentUri: Uri? = null
    private var pendingSubtitles: List<ExternalSubtitle> = emptyList()
    private val trackIdBySubtitleId = mutableMapOf<String, Long>()
    private var selectedSubtitleId: String? = null
    private var pollJob: Job? = null
    private var clientCallback: RemoteMediaClient.Callback? = null

    private fun client(): RemoteMediaClient? = CastManager.remoteMediaClient

    override fun createView(context: Context): View {
        return FrameLayout(context).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(
                TextView(context).apply {
                    text = "Casting to ${CastManager.deviceName.value ?: "TV"}"
                    gravity = Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 16f
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    override fun load(
        uri: Uri,
        startPositionMs: Long,
        subtitles: List<ExternalSubtitle>,
        preferredSubtitleLang: String?,
        settings: com.stremio.core.types.profile.Profile.Settings?,
    ) {
        currentUri = uri
        pendingSubtitles = subtitles
        val client = client() ?: run {
            mutableState.value = mutableState.value.copy(error = "Not connected to a Cast device")
            return
        }

        trackIdBySubtitleId.clear()
        val tracks = subtitles.mapIndexed { index, sub ->
            val trackId = index.toLong() + 1
            trackIdBySubtitleId[sub.id] = trackId
            MediaTrack.Builder(trackId, MediaTrack.TYPE_TEXT)
                .setContentId(sub.fallbackUrl ?: sub.url)
                .setContentType(guessSubtitleMime(sub.fallbackUrl ?: sub.url))
                .setName(sub.label ?: sub.lang)
                .setLanguage(sub.lang)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .build()
        }
        selectedSubtitleId = preferredSubtitleLang?.let { lang ->
            subtitles.firstOrNull { it.lang.equals(lang, ignoreCase = true) }?.id
        }
        val activeTrackIds = selectedSubtitleId
            ?.let { trackIdBySubtitleId[it] }
            ?.let { longArrayOf(it) }

        val mediaInfo = MediaInfo.Builder(uri.toString())
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(guessVideoMime(uri.toString()))
            .setMetadata(MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE))
            .setMediaTracks(tracks)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(startPositionMs)
            .apply { activeTrackIds?.let { setActiveTrackIds(it) } }
            .build()

        mutableState.value = PlayerRuntimeState(
            isBuffering = true,
            positionMs = startPositionMs,
            subtitleTracks = subtitles.map { sub ->
                PlayerTrackOption(
                    id = sub.id,
                    type = PlayerTrackType.SUBTITLE,
                    label = sub.label ?: sub.lang,
                    language = sub.lang,
                    selected = sub.id == selectedSubtitleId,
                )
            },
            subtitlesDisabled = selectedSubtitleId == null,
        )
        attachClient(client)
        client.load(request)
        Timber.i("CastPlayer loading ${uri} at ${startPositionMs}ms with ${tracks.size} subtitle tracks")
    }

    private fun attachClient(client: RemoteMediaClient) {
        detachClient()
        val cb = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() = syncState(client)
            override fun onMediaError(error: MediaError) {
                Timber.w("Cast media error: ${error.detailedErrorCode}")
                mutableState.value = mutableState.value.copy(
                    isBuffering = false,
                    error = "Cast playback failed",
                )
            }
        }
        clientCallback = cb
        client.registerCallback(cb)
        pollJob = scope.launch {
            while (isActive) {
                syncState(client)
                delay(1000)
            }
        }
        syncState(client)
    }

    private fun detachClient() {
        clientCallback?.let { cb ->
            runCatching { client()?.unregisterCallback(cb) }
        }
        clientCallback = null
        pollJob?.cancel()
        pollJob = null
    }

    private fun syncState(client: RemoteMediaClient) {
        val previous = mutableState.value
        val position = client.approximateStreamPosition.coerceAtLeast(0)
        val duration = client.streamDuration.takeIf { it > 0 } ?: previous.durationMs
        mutableState.value = previous.copy(
            isPlaying = client.isPlaying,
            isBuffering = client.isBuffering,
            positionMs = position,
            durationMs = duration,
            bufferedPositionMs = position,
            error = null,
        )
    }

    override fun retry() {
        val uri = currentUri ?: return
        load(uri, mutableState.value.positionMs, pendingSubtitles, null, null)
    }

    override fun play() {
        runCatching { client()?.play() }
    }

    override fun pause() {
        runCatching { client()?.pause() }
    }

    override fun seekTo(positionMs: Long) {
        runCatching { client()?.seek(positionMs) }
        mutableState.value = mutableState.value.copy(positionMs = positionMs)
    }

    override fun setPlaybackSpeed(speed: Float) {
        // The default receiver does not support playback speed; keep it locally for UI consistency.
        mutableState.value = mutableState.value.copy(speed = speed)
    }

    override fun setResizeMode(mode: PlayerResizeMode) {
        // No-op: the Cast device handles its own output sizing.
    }

    override fun selectAudioTrack(id: String) {
        // v1: audio track switching on the receiver is not wired up.
    }

    override fun selectSubtitleTrack(id: String) {
        val trackId = trackIdBySubtitleId[id] ?: return
        selectedSubtitleId = id
        runCatching { client()?.setActiveMediaTracks(longArrayOf(trackId)) }
        mutableState.value = mutableState.value.copy(subtitlesDisabled = false)
    }

    override fun disableSubtitles() {
        selectedSubtitleId = null
        runCatching { client()?.setActiveMediaTracks(longArrayOf()) }
        mutableState.value = mutableState.value.copy(subtitlesDisabled = true)
    }

    override fun setSubtitleStyle(style: PlayerSubtitleStyle) {
        // Subtitles are rendered by the Cast receiver.
    }

    override fun addExternalSubtitleTracks(tracks: List<ExternalSubtitle>) {
        // The receiver cannot add tracks mid-stream; reload with the merged list.
        val uri = currentUri ?: return
        val merged = (pendingSubtitles + tracks).distinctBy { it.id }
        val preferredLang = selectedSubtitleId?.let { sid ->
            pendingSubtitles.firstOrNull { it.id == sid }?.lang
        }
        load(uri, mutableState.value.positionMs, merged, preferredLang, null)
    }

    override fun addLocalSubtitle(track: ExternalSubtitle) {
        // Device-local subtitle files cannot be sent to a Cast device.
        Timber.w("Ignoring local subtitle track while casting")
    }

    override fun release() {
        detachClient()
        scope.cancel()
        currentUri = null
        // Note: intentionally does NOT end the Cast session.
    }

    companion object {
        private fun guessVideoMime(url: String): String {
            val lower = url.lowercase().substringBefore("?")
            return when {
                lower.endsWith(".m3u8") -> "application/x-mpegurl"
                lower.endsWith(".mpd") -> "application/dash+xml"
                lower.endsWith(".mp4") -> "video/mp4"
                lower.endsWith(".mkv") -> "video/x-matroska"
                lower.endsWith(".webm") -> "video/webm"
                lower.endsWith(".avi") -> "video/x-msvideo"
                lower.endsWith(".mov") -> "video/quicktime"
                else -> "video/*"
            }
        }

        private fun guessSubtitleMime(url: String): String {
            val lower = url.lowercase().substringBefore("?")
            return when {
                lower.endsWith(".vtt") -> "text/vtt"
                lower.endsWith(".srt") -> "application/x-subrip"
                else -> "text/vtt"
            }
        }
    }
}
