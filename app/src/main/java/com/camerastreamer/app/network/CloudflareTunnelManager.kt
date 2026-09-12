package com.camerastreamer.app.network

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages Cloudflare Quick Tunnel (cloudflared) on Android.
 *
 * Runs non-root using the pre-bundled native library (libcloudflared.so).
 *
 * DNS on Android is broken for Go binaries — Go reads /etc/resolv.conf which
 * doesn't exist on Android, so it falls back to [::1]:53 and fails.
 * The fix: do ALL DNS work from Java (which uses Android's native resolver),
 * then pass pre-resolved IPs to cloudflared via --edge flags.
 */
class CloudflareTunnelManager(private val context: Context) {

    companion object {
        private const val TAG = "CloudflareTunnel"
        private const val MAX_LOG_LINES = 500
        private const val API_URL = "https://api.trycloudflare.com/tunnel"
        private const val EDGE_PORT = 7844
        private val EDGE_HOSTS = listOf(
            "region1.v2.argotunnel.com",
            "region2.v2.argotunnel.com"
        )
    }

    interface TunnelListener {
        fun onTunnelStarting()
        fun onTunnelUrlAvailable(publicUrl: String)
        fun onTunnelStatusUpdate(status: String)
        fun onTunnelError(error: String)
        fun onTunnelStopped()
    }

    var listener: TunnelListener? = null

    /** Thread-safe ring buffer holding the last [MAX_LOG_LINES] log lines. */
    val logBuffer: ConcurrentLinkedDeque<String> = ConcurrentLinkedDeque()

    private fun appendLog(line: String) {
        logBuffer.addLast(line)
        while (logBuffer.size > MAX_LOG_LINES) logBuffer.pollFirst()
        com.camerastreamer.app.CloudflareLogStore.addLine(line)
    }

    private var tunnelProcess: Process? = null
    private val isRunning = AtomicBoolean(false)
    private var scope = CoroutineScope(Dispatchers.IO + Job())
    private var wakeLock: PowerManager.WakeLock? = null

    var publicUrl: String? = null
        private set

    /**
     * Resolves the executable path of cloudflared.
     */
    fun getBinaryFile(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativeLib = File(nativeDir, "libcloudflared.so")
        if (nativeLib.exists() && nativeLib.canExecute()) {
            return nativeLib
        }

        val downloadedBin = File(context.filesDir, "cloudflared")
        if (downloadedBin.exists() && downloadedBin.canExecute()) {
            return downloadedBin
        }

        return null
    }

    fun isBinaryAvailable(): Boolean {
        return getBinaryFile() != null
    }

    /**
     * Downloads cloudflared if not already available in the APK.
     */
    suspend fun ensureBinaryAvailable(onProgress: (Int) -> Unit): Boolean {
        if (isBinaryAvailable()) return true

        return withContext(Dispatchers.IO) {
            try {
                listener?.onTunnelStatusUpdate("Downloading cloudflared engine...")
                val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val binName = when {
                    arch.contains("arm64") -> "cloudflared-linux-arm64"
                    arch.contains("x86_64") -> "cloudflared-linux-amd64"
                    arch.contains("arm") -> "cloudflared-linux-arm"
                    else -> "cloudflared-linux-arm64"
                }

                val downloadUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/$binName"
                val destFile = File(context.filesDir, "cloudflared")

                val connection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()

                if (connection.responseCode in 200..299) {
                    val totalSize = connection.contentLength
                    var downloaded = 0

                    connection.inputStream.use { input ->
                        FileOutputStream(destFile).use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (totalSize > 0) {
                                    val pct = ((downloaded.toLong() * 100) / totalSize).toInt()
                                    onProgress(pct)
                                }
                            }
                        }
                    }

                    destFile.setExecutable(true, false)
                    true
                } else {
                    Log.e(TAG, "Download failed with HTTP ${connection.responseCode}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading cloudflared", e)
                false
            }
        }
    }

    // ── Java-side DNS helpers ────────────────────────────────────────────

    /**
     * Registers a Quick Tunnel via Java HTTP (bypasses Go DNS entirely).
     * Returns Triple(tunnelId, hostname, credentialsJson).
     */
    private fun registerTunnel(): Triple<String, String, String> {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.doOutput = true

        // Empty POST body — trycloudflare.com Quick Tunnel API needs no payload
        connection.outputStream.use { it.write(ByteArray(0)) }

        val responseCode = connection.responseCode
        val body = connection.inputStream.bufferedReader().readText()

        if (responseCode !in 200..299) {
            throw Exception("Quick Tunnel API returned HTTP $responseCode: $body")
        }

        val json = JSONObject(body)
        val result = json.getJSONObject("result")

        val tunnelId = result.getString("id")
        val hostname = result.getString("hostname")
        val accountTag = result.getString("account_tag")
        val secret = result.getString("secret")

        val credsJson = JSONObject().apply {
            put("AccountTag", accountTag)
            put("TunnelID", tunnelId)
            put("TunnelSecret", secret)
        }.toString(2)

        Log.i(TAG, "Tunnel registered: id=$tunnelId hostname=$hostname")
        return Triple(tunnelId, hostname, credsJson)
    }

    /**
     * Resolves Cloudflare edge server IPs using Java's DNS resolver.
     * Returns a list of "ip:port" strings.
     */
    private fun resolveEdgeIps(): List<String> {
        val ips = mutableListOf<String>()
        for (host in EDGE_HOSTS) {
            try {
                val allAddrs = InetAddress.getAllByName(host)
                for (addr in allAddrs) {
                    if (addr is Inet4Address) {
                        ips.add("${addr.hostAddress}:$EDGE_PORT")
                    }
                }
                Log.i(TAG, "Resolved $host -> ${ips.size} edge IPs")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve $host: ${e.message}")
            }
        }
        if (ips.isEmpty()) {
            throw Exception("Could not resolve any Cloudflare edge IPs from $EDGE_HOSTS")
        }
        return ips
    }

    // ── Tunnel lifecycle ─────────────────────────────────────────────────

    /**
     * Starts the Cloudflare Quick Tunnel forwarding traffic to the local port.
     *
     * Flow:
     *  1. Register tunnel from Java (GET tunnel ID + hostname + credentials)
     *  2. Resolve edge IPs from Java (InetAddress — uses Android native DNS)
     *  3. Write credentials JSON + config YAML to cacheDir
     *  4. Launch cloudflared with --edge flags (ZERO DNS needed from Go)
     *  5. Parse stdout for tunnel connection confirmation
     */
    fun startTunnel(localPort: Int) {
        if (isRunning.get()) return

        scope.launch {
            try {
                listener?.onTunnelStarting()
                listener?.onTunnelStatusUpdate("Preparing Cloudflare Tunnel...")

                if (!isBinaryAvailable()) {
                    val downloaded = ensureBinaryAvailable { pct ->
                        listener?.onTunnelStatusUpdate("Downloading engine: $pct%")
                    }
                    if (!downloaded) {
                        listener?.onTunnelError("Failed to obtain cloudflared binary")
                        return@launch
                    }
                }

                val binary = getBinaryFile() ?: run {
                    listener?.onTunnelError("cloudflared binary not executable")
                    return@launch
                }

                // Phase 1: Register tunnel from Java (bypass Go DNS)
                listener?.onTunnelStatusUpdate("Registering tunnel...")
                val (tunnelId, hostname, credsJson) = withContext(Dispatchers.IO) {
                    registerTunnel()
                }

                // The URL is known instantly from Phase 1 — no stdout parsing needed
                publicUrl = "https://$hostname"
                listener?.onTunnelUrlAvailable(publicUrl!!)

                // Phase 2: Write credentials file
                val credsFile = File(context.cacheDir, "tunnel_creds.json")
                credsFile.writeText(credsJson)
                Log.i(TAG, "Credentials written to ${credsFile.absolutePath}")

                // Phase 3: Write config file
                val configFile = File(context.cacheDir, "tunnel_config.yml")
                configFile.writeText("""
                    |tunnel: $tunnelId
                    |credentials-file: ${credsFile.absolutePath}
                    |protocol: http2
                    |ingress:
                    |  - service: http://localhost:$localPort
                    |    originRequest:
                    |      noTLSVerify: true
                    |  - service: http_status:404
                """.trimMargin())
                Log.i(TAG, "Config written to ${configFile.absolutePath}")

                // Phase 4: Resolve edge IPs from Java (bypass Go DNS)
                listener?.onTunnelStatusUpdate("Resolving edge servers...")
                val edgeIps = withContext(Dispatchers.IO) {
                    resolveEdgeIps()
                }
                Log.i(TAG, "Edge IPs: $edgeIps")

                // Phase 5: Acquire wake lock
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CameraStreamer:CloudflareWakeLock"
                ).apply {
                    acquire(10 * 60 * 1000L)
                }

                // Phase 6: Build command — cloudflared does ZERO DNS
                val command = mutableListOf(
                    binary.absolutePath,
                    "tunnel",
                    "--config", configFile.absolutePath,
                    "--edge-ip-version", "4",
                    "--no-autoupdate"
                )
                // One --edge flag per resolved IP (NOT comma-separated)
                for (ip in edgeIps.take(4)) {
                    command.addAll(listOf("--edge", ip))
                }
                command.addAll(listOf("run", tunnelId))

                Log.i(TAG, "Executing: ${command.joinToString(" ")}")
                val processBuilder = ProcessBuilder(command)
                processBuilder.redirectErrorStream(true)

                // Phase 7: Launch cloudflared
                val proc = processBuilder.start()
                tunnelProcess = proc
                isRunning.set(true)
                com.camerastreamer.app.CloudflareLogStore.isTunnelRunning = true
                listener?.onTunnelStatusUpdate("Connecting to Cloudflare edge...")

                // Phase 8: Parse stdout for connection status
                proc.inputStream.bufferedReader().use { reader ->
                    while (isRunning.get()) {
                        val currentLine = reader.readLine() ?: break
                        Log.d(TAG, currentLine)
                        appendLog(currentLine)

                        when {
                            currentLine.contains("Registered tunnel connection") ||
                            currentLine.contains("Connection registered") -> {
                                listener?.onTunnelStatusUpdate("Tunnel Active")
                            }
                            currentLine.contains("error") ||
                            currentLine.contains("ERR") -> {
                                listener?.onTunnelError(currentLine)
                            }
                        }
                    }
                }

                val exitCode = proc.waitFor()
                Log.i(TAG, "cloudflared exited with code $exitCode")
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Cloudflare Tunnel error", e)
                    listener?.onTunnelError(e.message ?: "Tunnel execution error")
                }
            } finally {
                stopTunnel()
            }
        }
    }

    fun stopTunnel() {
        if (!isRunning.getAndSet(false)) return

        try {
            tunnelProcess?.destroy()
            tunnelProcess = null
        } catch (_: Exception) {
        }

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null

        publicUrl = null
        com.camerastreamer.app.CloudflareLogStore.isTunnelRunning = false
        listener?.onTunnelStopped()
        Log.i(TAG, "Cloudflare Tunnel stopped")
    }

    fun isTunnelActive(): Boolean = isRunning.get() && publicUrl != null
}
