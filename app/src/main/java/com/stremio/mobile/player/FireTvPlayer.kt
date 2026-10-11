package com.stremio.mobile.player

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.stremio.mobile.firetv.FireTvManager
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
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * A [Player] backend that renders on a Fire TV Stick running the Stremio TV Receiver.
 * Only direct HTTP(S) streams are supported in v1 (same constraint as Chromecast).
 */
class FireTvPlayer(
    private val appContext: Context,
    private val fireTvManager: FireTvManager,
) : Player {
    override val engine: PlayerEngine = PlayerEngine.FIRETV

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(PlayerRuntimeState())
    override val runtimeState: StateFlow<PlayerRuntimeState> = mutableState

    private var currentUri: Uri? = null
    private var pendingSubtitles: List<ExternalSubtitle> = emptyList()
    private var selectedSubtitleId: String? = null
    private var pollJob: Job? = null

    override fun createView(context: Context): View {
        return FrameLayout(context).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(
                TextView(context).apply {
                    text = "Playing on Fire TV"
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
        if (!fireTvManager.isConnected) {
            mutableState.value = mutableState.value.copy(error = "Not connected to Fire TV")
            return
        }
        selectedSubtitleId = preferredSubtitleLang?.let { lang ->
            subtitles.firstOrNull { it.lang.equals(lang, ignoreCase = true) }?.id
        }
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
        scope.launch(Dispatchers.IO) {
            val subsArray = JSONArray()
            subtitles.forEach { sub ->
                subsArray.put(
                    JSONObject()
                        .put("id", sub.id)
                        .put("url", sub.fallbackUrl ?: sub.url)
                        .put("lang", sub.lang)
                        .put("label", sub.label ?: sub.lang)
                )
            }
            val body = JSONObject()
                .put("url", uri.toString())
                .put("positionMs", startPositionMs)
                .put("subtitles", subsArray)
            val result = fireTvManager.post("/play", body)
            if (result == null) {
                mutableState.value = mutableState.value.copy(
                    isBuffering = false,
                    error = "Could not reach Fire TV",
                )
            } else {
                startPolling()
            }
        }
        Timber.i("FireTvPlayer loading $uri at ${startPositionMs}ms")
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                syncStatus()
                delay(1000)
            }
        }
    }

    private suspend fun syncStatus() {
        val status = fireTvManager.get("/status") ?: return
        val previous = mutableState.value
        mutableState.value = previous.copy(
            isPlaying = status.optBoolean("isPlaying", false),
            isBuffering = false,
            positionMs = status.optLong("positionMs", previous.positionMs),
            durationMs = status.optLong("durationMs", 0).takeIf { it > 0 } ?: previous.durationMs,
            bufferedPositionMs = status.optLong("positionMs", previous.positionMs),
            error = null,
        )
    }

    override fun retry() {
        val uri = currentUri ?: return
        load(uri, mutableState.value.positionMs, pendingSubtitles, null, null)
    }

    override fun play() {
        scope.launch(Dispatchers.IO) { fireTvManager.post("/resume") }
        mutableState.value = mutableState.value.copy(isPlaying = true)
    }

    override fun pause() {
        scope.launch(Dispatchers.IO) { fireTvManager.post("/pause") }
        mutableState.value = mutableState.value.copy(isPlaying = false)
    }

    override fun seekTo(positionMs: Long) {
        scope.launch(Dispatchers.IO) {
            fireTvManager.post("/seek", JSONObject().put("positionMs", positionMs))
        }
        mutableState.value = mutableState.value.copy(positionMs = positionMs)
    }

    override fun setPlaybackSpeed(speed: Float) {
        mutableState.value = mutableState.value.copy(speed = speed)
    }

    override fun setResizeMode(mode: PlayerResizeMode) {
        // No-op: the TV handles its own output.
    }

    override fun selectAudioTrack(id: String) {
        // v1: not wired up.
    }

    override fun selectSubtitleTrack(id: String) {
        selectedSubtitleId = id
        scope.launch(Dispatchers.IO) {
            fireTvManager.post("/subtitle", JSONObject().put("trackId", id))
        }
        mutableState.value = mutableState.value.copy(subtitlesDisabled = false)
    }

    override fun disableSubtitles() {
        selectedSubtitleId = null
        scope.launch(Dispatchers.IO) {
            fireTvManager.post("/subtitle", JSONObject())
        }
        mutableState.value = mutableState.value.copy(subtitlesDisabled = true)
    }

    override fun setSubtitleStyle(style: PlayerSubtitleStyle) {
        // Subtitles are rendered by the TV receiver.
    }

    override fun addExternalSubtitleTracks(tracks: List<ExternalSubtitle>) {
        val uri = currentUri ?: return
        val merged = (pendingSubtitles + tracks).distinctBy { it.id }
        val preferredLang = selectedSubtitleId?.let { sid ->
            pendingSubtitles.firstOrNull { it.id == sid }?.lang
        }
        load(uri, mutableState.value.positionMs, merged, preferredLang, null)
    }

    override fun addLocalSubtitle(track: ExternalSubtitle) {
        Timber.w("Ignoring local subtitle track for Fire TV")
    }

    override fun release() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
        currentUri = null
        // Note: intentionally does NOT disconnect the Fire TV.
    }
}
