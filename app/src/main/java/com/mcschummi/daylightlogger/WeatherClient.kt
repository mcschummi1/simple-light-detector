package com.mcschummi.daylightlogger

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WEATHER CONTEXT via Open-Meteo (free, NO API key).
 *
 * Fetches hourly cloud cover (%), UV index and temperature for Timisoara
 * (lat 45.7489, lon 21.2087) and keeps the latest snapshot in memory so every
 * CSV row can be annotated with the weather at the time of the sample.
 *
 * Resilience:
 *  - fetches run on a background coroutine (Dispatchers.IO): the camera loop
 *    is NEVER blocked;
 *  - failures retry with exponential backoff: 30 s, 1 m, 2 m, 4 m, 8 m
 *    (max 5 attempts), then waits for the network to come back;
 *  - a connectivity callback triggers an immediate fetch when Wi-Fi returns;
 *  - while offline, the CSV uses the LAST KNOWN values (or 'Pending').
 */
object WeatherClient {

    private const val TAG = "WeatherClient"
    private const val TIMISOARA_LAT = 45.7489
    private const val TIMISOARA_LON = 21.2087

    /**
     * hourly=... plus timezone=auto so times come back in the phone's local
     * timezone; two days so late-evening hours always resolve.
     */
    private const val API_URL =
        "https://api.open-meteo.com/v1/forecast" +
        "?latitude=$TIMISOARA_LAT&longitude=$TIMISOARA_LON" +
        "&hourly=cloud_cover,uv_index,temperature_2m" +
        "&timezone=auto&forecast_days=2"

    data class Snapshot(
        val cloudPct: Double?,   // 0-100
        val uvIndex: Double?,    // 0-11+
        val tempC: Double?,      // degrees Celsius
        val updatedMs: Long,     // when it was fetched
        val hourLabel: String    // which hour the data refers to
    )

    /** Latest known weather, or null until the very first successful fetch. */
    @Volatile var latest: Snapshot? = null
        private set

    /** Short human-readable status for the Settings screen. */
    @Volatile var statusText = "weather: not fetched yet"
        private set

    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null
    private var appCtx: Context? = null
    private val wakeNow = AtomicBoolean(false)   // set when Wi-Fi returns

    // ------------------------------------------------------------- lifecycle

    fun start(context: Context) {
        if (loopJob?.isActive == true) return
        appCtx = context.applicationContext
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        registerConnectivityWatcher()
        loopJob = s.launch { runLoop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        scope?.cancel()
        scope = null
        unregisterConnectivityWatcher()
    }

    /** Settings/save triggers can request an immediate refresh. */
    fun refreshNow() { wakeNow.set(true) }

    // --------------------------------------------------------- network watcher

    private var connectivityWatcher: ConnectivityManager.NetworkCallback? = null

    private fun registerConnectivityWatcher() {
        val ctx = appCtx ?: return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "network back -> immediate weather fetch")
                    wakeNow.set(true)   // wakes the sleeping loop instantly
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            connectivityWatcher = callback
        } catch (e: Exception) {
            Log.w(TAG, "connectivity watcher not registered", e)
        }
    }

    private fun unregisterConnectivityWatcher() {
        try {
            val ctx = appCtx ?: return
            val callback = connectivityWatcher ?: return
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {}
        connectivityWatcher = null
    }

    // ---------------------------------------------------------------- the loop

    private suspend fun runLoop() {
        val backoffsMs = longArrayOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L)
        var attempt = 0
        while (scope?.isActive == true) {
            val ok = withContext(Dispatchers.IO) { fetchOnce() }
            if (ok) {
                attempt = 0
                // Weather changes slowly: refresh every 15 minutes.
                smartDelay(15 * 60_000L)
            } else {
                attempt++
                statusText = if (attempt >= backoffsMs.size) {
                    "weather: network failed - retrying when Wi-Fi is back"
                } else {
                    "weather: fetch failed, retrying in ${backoffsMs[attempt - 1] / 1000} s"
                }
                if (attempt < backoffsMs.size) {
                    smartDelay(backoffsMs[attempt - 1])
                } else {
                    // All 5 attempts gone: sleep until the network returns
                    // (the connectivity watcher wakes us) or at most 30 min.
                    smartDelay(30 * 60_000L)
                    attempt = 0
                }
            }
        }
    }

    /**
     * Sleeps up to `ms`, but wakes immediately when the connectivity watcher
     * signals that Wi-Fi came back (that's the "trigger an immediate fetch").
     */
    private suspend fun smartDelay(ms: Long) {
        var waited = 0L
        while (waited < ms && scope?.isActive == true) {
            if (wakeNow.compareAndSet(true, false)) return
            delay(2000)
            waited += 2000
        }
    }

    // -------------------------------------------------------------- fetch+parse

    private fun fetchOnce(): Boolean = try {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        val body = conn.inputStream.use { it.readBytes().decodeToString() }
        conn.disconnect()
        parseAndStore(body)
    } catch (e: Exception) {
        Log.w(TAG, "weather fetch failed: ${e.message}")
        false
    }

    private fun parseAndStore(json: String): Boolean {
        return try {
        val root = JSONObject(json)
        val hourly = root.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val cloud = hourly.getJSONArray("cloud_cover")
        val uv = hourly.getJSONArray("uv_index")
        val temp = hourly.getJSONArray("temperature_2m")

        // Which hour are we in? Times look like "2026-11-23T14:00" (local).
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        val nowLabel = fmt.format(java.util.Date())
        var idx = -1
        for (i in 0 until times.length()) {
            if (times.getString(i) <= nowLabel) idx = i else break
        }
        if (idx < 0) return false

        fun safeDouble(arr: org.json.JSONArray, i: Int): Double? {
            if (arr.isNull(i)) return null
            val v = arr.optDouble(i, Double.NaN)
            return if (v.isNaN()) null else v
        }

        latest = Snapshot(
            cloudPct = safeDouble(cloud, idx),
            uvIndex = safeDouble(uv, idx),
            tempC = safeDouble(temp, idx),
            updatedMs = System.currentTimeMillis(),
            hourLabel = times.getString(idx)
        )
        val s = latest!!
        statusText = "weather: cloud %.0f%%, UV %.1f, %.1f C (%s)".format(
            Locale.US, s.cloudPct ?: Double.NaN, s.uvIndex ?: Double.NaN,
            s.tempC ?: Double.NaN, s.hourLabel
        )
        Log.i(TAG, statusText)
        true
        } catch (e: Exception) {
            Log.w(TAG, "weather parse failed", e)
            false
        }
    }

    /** Are the current capabilities good enough for a fetch right now? */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
