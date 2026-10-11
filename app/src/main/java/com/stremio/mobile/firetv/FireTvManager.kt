package com.stremio.mobile.firetv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/**
 * Finds and talks to the Stremio TV Receiver on the LAN.
 * Discovery: scans the local subnet for the receiver's /ping endpoint.
 * The chosen IP is persisted; control goes through simple HTTP JSON.
 *
 * Singleton (like CastManager); call [init] once from MainApplication.
 */
object FireTvManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    private val prefs: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences("firetv", Context.MODE_PRIVATE)
    }

    private val _devices = MutableStateFlow<List<FireTvDevice>>(emptyList())
    val devices: StateFlow<List<FireTvDevice>> = _devices

    private val _connectedDevice = MutableStateFlow<FireTvDevice?>(null)
    val connectedDevice: StateFlow<FireTvDevice?> = _connectedDevice

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    val isConnected: Boolean get() = _connectedDevice.value != null

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs.getString("device_ip", null)?.let { ip ->
            val name = prefs.getString("device_name", null) ?: "Fire TV"
            _connectedDevice.value = FireTvDevice(ip, name)
        }
    }

    data class FireTvDevice(val ip: String, val name: String) {
        val baseUrl: String get() = "http://$ip:8080"
    }

    /** Scan the LAN for receivers. Returns the found devices. */
    suspend fun scan(): List<FireTvDevice> = withContext(Dispatchers.IO) {
        _isScanning.value = true
        try {
            val subnet = localSubnetPrefix() ?: return@withContext emptyList()
            val found = (1..254).map { i ->
                async {
                    val ip = "$subnet.$i"
                    pingDevice(ip)?.let { FireTvDevice(ip, "Fire TV ($ip)") }
                }
            }.awaitAll().filterNotNull()
            _devices.value = found
            // Auto-connect if we have a saved IP that responded.
            val savedIp = prefs.getString("device_ip", null)
            if (savedIp != null && found.any { it.ip == savedIp } && _connectedDevice.value == null) {
                _connectedDevice.value = found.first { it.ip == savedIp }
            }
            found
        } finally {
            _isScanning.value = false
        }
    }

    /** Manually add/connect a receiver by IP. */
    suspend fun connectManual(ip: String): Boolean = withContext(Dispatchers.IO) {
        val cleanIp = ip.trim()
        val ok = pingDevice(cleanIp) != null
        if (ok) {
            val device = FireTvDevice(cleanIp, "Fire TV ($cleanIp)")
            prefs.edit()
                .putString("device_ip", cleanIp)
                .putString("device_name", device.name)
                .apply()
            _connectedDevice.value = device
        }
        ok
    }

    fun connect(device: FireTvDevice) {
        prefs.edit()
            .putString("device_ip", device.ip)
            .putString("device_name", device.name)
            .apply()
        _connectedDevice.value = device
    }

    fun disconnect() {
        _connectedDevice.value = null
        prefs.edit().remove("device_ip").remove("device_name").apply()
    }

    // ---- low-level HTTP ----

    suspend fun post(path: String, body: JSONObject? = null): JSONObject? =
        withContext(Dispatchers.IO) {
            val device = _connectedDevice.value ?: return@withContext null
            try {
                val url = URL(device.baseUrl + path)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val bytes = (body ?: JSONObject()).toString().toByteArray()
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                if (code in 200..299) {
                    JSONObject(conn.inputStream.bufferedReader().readText())
                } else null
            } catch (e: Exception) {
                Timber.w("FireTV POST $path failed: ${e.message}")
                null
            }
        }

    suspend fun get(path: String): JSONObject? = withContext(Dispatchers.IO) {
        val device = _connectedDevice.value ?: return@withContext null
        try {
            val url = URL(device.baseUrl + path)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code in 200..299) {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } else null
        } catch (e: Exception) {
            Timber.w("FireTV GET $path failed: ${e.message}")
            null
        }
    }

    private fun pingDevice(ip: String): JSONObject? {
        return try {
            val url = URL("http://$ip:8080/ping")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 400
            conn.readTimeout = 800
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                if (json.optString("name") == "stremio-tv-receiver") json else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun localSubnetPrefix(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        // Skip mobile data interfaces by checking wifi transport.
                        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                        val network = cm?.activeNetwork ?: continue
                        val caps = cm.getNetworkCapabilities(network) ?: continue
                        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                        return host.substringBeforeLast(".")
                    }
                }
            }
            null
        } catch (e: Exception) {
            Timber.w("Subnet detection failed: ${e.message}")
            null
        }
    }
}
