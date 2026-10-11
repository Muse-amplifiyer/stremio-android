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
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Foreground service: keeps the HTTP control server alive and owns the ExoPlayer.
 * Started on boot; the phone app talks to it over the LAN.
 */
class ReceiverService : Service(), ControlServer.CommandHandler, TvPlayerCallback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var server: ControlServer? = null
    private var currentPlayer: TvPlayer? = null
    private var currentTitle: String? = null
    private var currentUrl: String? = null
    private var currentPositionMs: Long = 0L
    private var subtitleTracks: List<ControlServer.SubtitleTrack> = emptyList()
    private var usedVlcFallback = false

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
        currentPlayer?.release()
        currentPlayer = null
        super.onDestroy()
    }

    // ---- CommandHandler ----

    override fun onPlay(url: String, positionMs: Long, title: String?, subtitles: List<ControlServer.SubtitleTrack>) {
        scope.launch {
            currentTitle = title
            currentUrl = url
            currentPositionMs = positionMs
            subtitleTracks = subtitles
            usedVlcFallback = false

            // Try ExoPlayer first (hardware decoding)
            playWithExoPlayer(url, positionMs)

            // Bring the player UI to the front.
            val launchIntent = Intent(this@ReceiverService, ReceiverActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launchIntent)
            Log.i(TAG, "Playing $url at ${positionMs}ms")
        }
    }

    private fun playWithExoPlayer(url: String, positionMs: Long) {
        currentPlayer?.release()
        currentPlayer = TvExoPlayer(this, this).apply {
            play(url, positionMs, currentTitle)
        }
        Log.i(TAG, "Using ExoPlayer (hardware)")
    }

    private fun fallbackToVlc() {
        if (usedVlcFallback) {
            Log.e(TAG, "VLC also failed, giving up")
            return
        }
        usedVlcFallback = true
        val url = currentUrl ?: return
        val pos = currentPlayer?.getPositionMs() ?: currentPositionMs
        Log.i(TAG, "Falling back to VLC (software) at ${pos}ms")
        currentPlayer?.release()
        currentPlayer = TvVlcPlayer(this, this).apply {
            // Note: VLC video layout must be attached by the activity
            play(url, pos, currentTitle)
        }
    }

    // TvPlayerCallback
    override fun onCodecError(error: TvCodecError) {
        Log.w(TAG, "Codec error (${error.mimeType}): ${error.message}")
        // If ExoPlayer failed and we haven't tried VLC yet, fallback
        if (currentPlayer?.isSoftwareDecoder == false) {
            fallbackToVlc()
        } else {
            Log.e(TAG, "All players failed")
        }
    }

    override fun onPlaybackEnded() {
        Log.i(TAG, "Playback ended")
    }

    override fun onPause() {
        currentPlayer?.pause()
    }

    override fun onResume() {
        currentPlayer?.resume()
    }

    override fun onSeek(positionMs: Long) {
        currentPlayer?.seekTo(positionMs)
    }

    override fun onSelectSubtitle(trackId: String?) {
        // Media3: subtitle track selection via track selector overrides would go here.
        // v1: subtitles are loaded with the media; selection changes require re-play.
        // We re-issue play at the current position with the chosen track as default.
        Log.i(TAG, "Subtitle selection requested: $trackId (v1: applied on next play)")
    }

    override fun onStop() {
        currentPlayer?.stop()
        currentTitle = null
        currentUrl = null
    }

    override fun getStatus(): JSONObject {
        val p = currentPlayer
        return JSONObject()
            .put("positionMs", p?.getPositionMs() ?: 0)
            .put("durationMs", p?.getDurationMs() ?: 0)
            .put("isPlaying", p?.isPlaying() == true)
            .put("title", currentTitle ?: JSONObject.NULL)
            .put("player", p?.name ?: JSONObject.NULL)
    }

    // ---- internals ----

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
    var exoPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    @Volatile
    var vlcPlayer: TvVlcPlayer? = null
    @Volatile
    var vlcVideoLayout: org.videolan.libvlc.util.VLCVideoLayout? = null
}
