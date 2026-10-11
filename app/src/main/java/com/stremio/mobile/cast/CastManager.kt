package com.stremio.mobile.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Process-wide holder for the current Cast session. Initialized once from
 * MainApplication.onCreate. Safe to use when Play Services / Cast is absent:
 * it simply never reports a session.
 */
object CastManager {
    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName

    @Volatile
    var remoteMediaClient: RemoteMediaClient? = null
        private set

    private var initialized = false

    /** True if the Cast SDK initialized successfully. The cast button is hidden when false. */
    @Volatile
    var isCastAvailable: Boolean = false
        private set

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        // Use the async API: the synchronous getSharedInstance() can throw Errors
        // (not just Exceptions) on devices where Play Services/Cast is unavailable,
        // which would crash the app. The async version reports failure via listener.
        try {
            CastContext.getSharedInstance(
                context.applicationContext,
                java.util.concurrent.Executors.newSingleThreadExecutor()
            ).addOnSuccessListener { castContext ->
                try {
                    castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
                    val current = castContext.sessionManager.currentCastSession
                    if (current != null && current.isConnected) {
                        onSessionStarted(current)
                    }
                    isCastAvailable = true
                } catch (e: Exception) {
                    Timber.w("Cast session setup failed: ${e.message}")
                    isCastAvailable = false
                }
            }.addOnFailureListener { e ->
                Timber.w("Cast not available: ${e.message}")
                isCastAvailable = false
            }
        } catch (t: Throwable) {
            Timber.w("Cast init failed: ${t.message}")
            isCastAvailable = false
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) = onSessionStarted(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) = onSessionEnded()
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onSessionStarted(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) = onSessionEnded()
    }

    private fun onSessionStarted(session: CastSession) {
        remoteMediaClient = session.remoteMediaClient
        _deviceName.value = session.castDevice?.friendlyName
        _isCasting.value = true
        Timber.i("Cast session started on ${session.castDevice?.friendlyName}")
    }

    private fun onSessionEnded() {
        remoteMediaClient = null
        _deviceName.value = null
        _isCasting.value = false
        Timber.i("Cast session ended")
    }
}
