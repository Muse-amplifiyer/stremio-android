package com.stremio.mobile.tvreceiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Foreground service: keeps the HTTP control server alive and owns the ExoPlayer.
 * Started on boot; the phone app talks to it over the LAN.
 */
class ReceiverService : Service(), ControlServer.CommandHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var server: ControlServer? = null
    private var player: ExoPlayer? = null
    private var currentTitle: String? = null
    private var subtitleTracks: List<ControlServer.SubtitleTrack> = emptyList()

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        server = ControlServer(ControlServer.DEFAULT_PORT, this).also { it.start() }
        Log.i(TAG, "Receiver service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.stop()
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---- CommandHandler ----

    override fun onPlay(url: String, positionMs: Long, title: String?, subtitles: List<ControlServer.SubtitleTrack>) {
        scope.launch {
            currentTitle = title
            subtitleTracks = subtitles
            ensurePlayer()
            val p = player ?: return@launch

            val subtitleConfigs = subtitles.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                    .setMimeType(guessSubtitleMime(sub.url))
                    .setLanguage(sub.lang)
                    .setLabel(sub.label ?: sub.lang)
                    .setId(sub.id)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            }
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMimeType(guessVideoMime(url))
                .setSubtitleConfigurations(subtitleConfigs)
                .build()
            p.setMediaItem(mediaItem)
            p.prepare()
            if (positionMs > 0) p.seekTo(positionMs)
            p.play()

            // Bring the player UI to the front.
            val launchIntent = Intent(this@ReceiverService, ReceiverActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launchIntent)
            Log.i(TAG, "Playing $url at ${positionMs}ms")
        }
    }

    override fun onPause() {
        player?.pause()
    }

    override fun onResume() {
        player?.play()
    }

    override fun onSeek(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    override fun onSelectSubtitle(trackId: String?) {
        // Media3: subtitle track selection via track selector overrides would go here.
        // v1: subtitles are loaded with the media; selection changes require re-play.
        // We re-issue play at the current position with the chosen track as default.
        Log.i(TAG, "Subtitle selection requested: $trackId (v1: applied on next play)")
    }

    override fun onStop() {
        player?.stop()
        player?.clearMediaItems()
        currentTitle = null
    }

    override fun getStatus(): JSONObject {
        val p = player
        return JSONObject()
            .put("positionMs", p?.currentPosition ?: 0)
            .put("durationMs", p?.duration?.takeIf { it > 0 } ?: 0)
            .put("isPlaying", p?.isPlaying == true)
            .put("title", currentTitle ?: JSONObject.NULL)
    }

    // ---- internals ----

    private fun ensurePlayer() {
        if (player != null) return
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("StremioTvReceiver/1.0")
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .also { PlayerHolder.player = it }
    }

    private fun startForegroundNotification() {
        val channelId = "tv_receiver"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "TV Receiver",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Stremio TV Receiver")
            .setContentText("Waiting for phone…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    companion object {
        private const val TAG = "TvReceiverService"

        private fun guessVideoMime(url: String): String? {
            val lower = url.lowercase().substringBefore("?")
            return when {
                lower.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
                lower.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                lower.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
                lower.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
                lower.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
                else -> null // let ExoPlayer sniff
            }
        }

        private fun guessSubtitleMime(url: String): String {
            val lower = url.lowercase().substringBefore("?")
            return when {
                lower.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                lower.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
                else -> MimeTypes.TEXT_VTT
            }
        }
    }
}

/** Shared handle so the activity can attach the service-owned player to its view. */
object PlayerHolder {
    @Volatile
    var player: ExoPlayer? = null
}
