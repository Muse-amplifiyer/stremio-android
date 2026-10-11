package com.stremio.mobile.tvreceiver

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView

/**
 * Minimal TV UI: fullscreen video when playing, "waiting for phone" otherwise.
 * All control comes from the phone; the TV remote is not needed.
 */
class ReceiverActivity : Activity() {

    private var playerView: PlayerView? = null
    private var statusView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }
        playerView = PlayerView(this).apply {
            useController = false // phone is the remote
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        statusView = TextView(this).apply {
            text = "Stremio TV Receiver\n\nWaiting for your phone…"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(playerView)
        root.addView(statusView)
        setContentView(root)

        // Make sure the control service is running.
        startService(Intent(this, ReceiverService::class.java))
    }

    override fun onResume() {
        super.onResume()
        attachPlayer()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        attachPlayer()
    }

    private fun attachPlayer() {
        val p = PlayerHolder.player
        playerView?.player = p
        val playing = p != null && (p.isPlaying || p.playbackState != androidx.media3.common.Player.STATE_IDLE)
        statusView?.visibility = if (playing) View.GONE else View.VISIBLE
        playerView?.visibility = if (playing) View.VISIBLE else View.GONE
    }
}
