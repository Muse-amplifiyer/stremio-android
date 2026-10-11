package com.stremio.mobile.tvreceiver

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Minimal HTTP server for phone→TV control. No dependencies beyond the stdlib.
 *
 * Endpoints:
 *   GET  /ping      -> {"name":"stremio-tv-receiver","version":"1.0.0"}
 *   GET  /status    -> {"positionMs":..,"durationMs":..,"isPlaying":..,"title":..}
 *   POST /play      -> {"url":..,"positionMs":..,"title":..,"subtitles":[{id,url,lang,label}]}
 *   POST /pause     -> {}
 *   POST /resume    -> {}
 *   POST /seek      -> {"positionMs":..}
 *   POST /subtitle  -> {"trackId":..} or {} to disable
 *   POST /stop      -> {}
 */
class ControlServer(
    private val port: Int,
    private val handler: CommandHandler,
) {
    interface CommandHandler {
        fun onPlay(url: String, positionMs: Long, title: String?, subtitles: List<SubtitleTrack>)
        fun onPause()
        fun onResume()
        fun onSeek(positionMs: Long)
        fun onSelectSubtitle(trackId: String?)
        fun onStop()
        fun getStatus(): JSONObject
    }

    data class SubtitleTrack(val id: String, val url: String, val lang: String, val label: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    fun start() {
        scope.launch {
            try {
                serverSocket = ServerSocket(port).also { it.soTimeout = 0 }
                Log.i(TAG, "Control server listening on port $port")
                while (true) {
                    val socket = try {
                        serverSocket?.accept() ?: break
                    } catch (e: Exception) {
                        break
                    }
                    scope.launch { handleConnection(socket) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Control server failed", e)
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore("?")

                var contentLength = 0
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    val header = line!!
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                val response = route(method, path, body)
                sendResponse(s.getOutputStream(), response)
            } catch (e: Exception) {
                Log.w(TAG, "Connection handling failed", e)
            }
        }
    }

    private fun route(method: String, path: String, body: String): JSONObject {
        return try {
            when {
                method == "GET" && path == "/ping" ->
                    JSONObject().put("name", "stremio-tv-receiver").put("version", "1.0.0")
                method == "GET" && path == "/status" ->
                    handler.getStatus()
                method == "POST" && path == "/play" -> {
                    val json = JSONObject(body)
                    val subs = mutableListOf<SubtitleTrack>()
                    val arr: JSONArray? = json.optJSONArray("subtitles")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            subs.add(
                                SubtitleTrack(
                                    id = o.optString("id"),
                                    url = o.optString("url"),
                                    lang = o.optString("lang"),
                                    label = o.optString("label").takeIf { it.isNotEmpty() },
                                )
                            )
                        }
                    }
                    handler.onPlay(
                        url = json.getString("url"),
                        positionMs = json.optLong("positionMs", 0),
                        title = json.optString("title").takeIf { it.isNotEmpty() },
                        subtitles = subs,
                    )
                    JSONObject().put("ok", true)
                }
                method == "POST" && path == "/pause" -> {
                    handler.onPause()
                    JSONObject().put("ok", true)
                }
                method == "POST" && path == "/resume" -> {
                    handler.onResume()
                    JSONObject().put("ok", true)
                }
                method == "POST" && path == "/seek" -> {
                    handler.onSeek(JSONObject(body).optLong("positionMs", 0))
                    JSONObject().put("ok", true)
                }
                method == "POST" && path == "/subtitle" -> {
                    val trackId = JSONObject(body).optString("trackId").takeIf { it.isNotEmpty() }
                    handler.onSelectSubtitle(trackId)
                    JSONObject().put("ok", true)
                }
                method == "POST" && path == "/stop" -> {
                    handler.onStop()
                    JSONObject().put("ok", true)
                }
                else -> JSONObject().put("error", "not found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Route $method $path failed", e)
            JSONObject().put("error", e.message ?: "internal error")
        }
    }

    private fun sendResponse(out: OutputStream, json: JSONObject) {
        val body = json.toString().toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n"
        out.write(headers.toByteArray(StandardCharsets.UTF_8))
        out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "TvControlServer"
        const val DEFAULT_PORT = 8080
    }
}
