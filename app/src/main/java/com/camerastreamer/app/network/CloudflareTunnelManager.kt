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

    /** Log to both Android logcat and the in-app log buffer. */
    private fun logInfo(msg: String) {
        Log.i(TAG, msg)
        appendLog("[INFO] $msg")
    }

    private fun logDebug(msg: String) {
        Log.d(TAG, msg)
        appendLog("[DEBUG] $msg")
    }

    private fun logWarn(msg: String) {
        Log.w(TAG, msg)
        appendLog("[WARN] $msg")
    }

    private fun logError(msg: String) {
        Log.e(TAG, msg)
        appendLog("[ERROR] $msg")
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
            logDebug("Binary found at nativeLibraryDir: ${nativeLib.absolutePath} (${nativeLib.length()} bytes)")
            return nativeLib
        }

        val downloadedBin = File(context.filesDir, "cloudflared")
        if (downloadedBin.exists() && downloadedBin.canExecute()) {
            logDebug("Binary found at filesDir: ${downloadedBin.absolutePath} (${downloadedBin.length()} bytes)")
            return downloadedBin
        }

        logWarn("No cloudflared binary found (nativeLib exists=${nativeLib.exists()}, " +
                "canExecute=${nativeLib.canExecute()}, downloadedBin exists=${downloadedBin.exists()}, " +
                "canExecute=${downloadedBin.canExecute()})")
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
                logInfo("Downloading cloudflared: arch=$arch bin=$binName")
                logInfo("Download URL: $downloadUrl")

                val destFile = File(context.filesDir, "cloudflared")

                val connection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()

                logInfo("HTTP ${connection.responseCode} | Content-Length: ${connection.contentLength}")

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
                                    if (pct % 25 == 0 && pct > 0) {
                                        logDebug("Download progress: $pct% ($downloaded/$totalSize bytes)")
                                    }
                                }
                            }
                        }
                    }

                    destFile.setExecutable(true, false)
                    logInfo("Download complete: ${destFile.absolutePath} (${destFile.length()} bytes)")
                    true
                } else {
                    logError("Download failed with HTTP ${connection.responseCode}")
                    false
                }
            } catch (e: Exception) {
                logError("Error downloading cloudflared: ${e.message}")
                Log.e(TAG, "Download exception", e)
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
        logInfo("--- Phase 1: Register Quick Tunnel ---")
        logInfo("POST $API_URL")

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

        logInfo("API response: HTTP $responseCode (${body.length} bytes)")

        if (responseCode !in 200..299) {
            logError("Quick Tunnel API returned HTTP $responseCode: $body")
            throw Exception("Quick Tunnel API returned HTTP $responseCode: $body")
        }

        val json = JSONObject(body)
        val result = json.getJSONObject("result")

        val tunnelId = result.getString("id")
        val hostname = result.getString("hostname")
        val accountTag = result.getString("account_tag")
        val secret = result.getString("secret")

        logInfo("Tunnel registered successfully:")
        logInfo("  Tunnel ID:  $tunnelId")
        logInfo("  Hostname:   $hostname")
        logInfo("  Account:    $accountTag")

        val credsJson = JSONObject().apply {
            put("AccountTag", accountTag)
            put("TunnelID", tunnelId)
            put("TunnelSecret", secret)
        }.toString(2)

        return Triple(tunnelId, hostname, credsJson)
    }

    /**
     * Resolves Cloudflare edge server IPs using Java's DNS resolver.
     * Returns a list of "ip:port" strings.
     */
    private fun resolveEdgeIps(): List<String> {
        logInfo("--- Phase 3: Resolve Edge Server IPs ---")
        logInfo("Using Java InetAddress (Android native DNS resolver)")

        val ips = mutableListOf<String>()
        for (host in EDGE_HOSTS) {
            try {
                logDebug("Resolving $host ...")
                val allAddrs = InetAddress.getAllByName(host)
                logDebug("  ${host} -> ${allAddrs.size} addresses returned")
                for (addr in allAddrs) {
                    if (addr is Inet4Address) {
                        val entry = "${addr.hostAddress}:$EDGE_PORT"
                        ips.add(entry)
                        logDebug("  IPv4: $entry")
                    } else {
                        logDebug("  IPv6 (skipped): ${addr.hostAddress}")
                    }
                }
            } catch (e: Exception) {
                logWarn("Failed to resolve $host: ${e.message}")
            }
        }
        if (ips.isEmpty()) {
            logError("Could not resolve any Cloudflare edge IPs")
            throw Exception("Could not resolve any Cloudflare edge IPs from $EDGE_HOSTS")
        }
        logInfo("Resolved ${ips.size} edge IPs: ${ips.joinToString(", ")}")
        return ips
    }

    // ── Tunnel lifecycle ─────────────────────────────────────────────────

    /**
     * Starts the Cloudflare Quick Tunnel forwarding traffic to the local port.
     *
     * Flow:
     *  1. Register tunnel from Java (GET tunnel ID + hostname + credentials)
     *  2. Write credentials + config files
     *  3. Resolve edge IPs from Java (InetAddress — uses Android native DNS)
     *  4. Launch cloudflared with --edge flags (ZERO DNS needed from Go)
     *  5. Parse stdout for tunnel connection confirmation
     */
    fun startTunnel(localPort: Int) {
        if (isRunning.get()) {
            logWarn("startTunnel called but tunnel is already running")
            return
        }

        scope.launch {
            try {
                listener?.onTunnelStarting()
                listener?.onTunnelStatusUpdate("Preparing Cloudflare Tunnel...")
                logInfo("========================================")
                logInfo("Starting Cloudflare Tunnel for port $localPort")
                logInfo("========================================")

                // ── Binary check ──
                logInfo("--- Phase 0: Check Binary ---")
                if (!isBinaryAvailable()) {
                    logInfo("Binary not found, attempting download...")
                    val downloaded = ensureBinaryAvailable { pct ->
                        listener?.onTunnelStatusUpdate("Downloading engine: $pct%")
                    }
                    if (!downloaded) {
                        logError("Failed to obtain cloudflared binary")
                        listener?.onTunnelError("Failed to obtain cloudflared binary")
                        return@launch
                    }
                }

                val binary = getBinaryFile() ?: run {
                    logError("cloudflared binary not executable after download")
                    listener?.onTunnelError("cloudflared binary not executable")
                    return@launch
                }
                logInfo("Binary ready: ${binary.absolutePath}")

                // ── Register tunnel ──
                listener?.onTunnelStatusUpdate("Registering tunnel...")
                val (tunnelId, hostname, credsJson) = withContext(Dispatchers.IO) {
                    registerTunnel()
                }

                // The URL is known instantly from registration — no stdout parsing needed
                publicUrl = "https://$hostname"
                logInfo("Public URL: $publicUrl")
                listener?.onTunnelUrlAvailable(publicUrl!!)

                // ── Write credentials file ──
                logInfo("--- Phase 2: Write Config Files ---")
                val credsFile = File(context.cacheDir, "tunnel_creds.json")
                credsFile.writeText(credsJson)
                logInfo("Credentials written: ${credsFile.absolutePath}")
                logDebug("Credentials content:\n$credsJson")

                // ── Write config file ──
                val configFile = File(context.cacheDir, "tunnel_config.yml")
                val configContent = """
                    |tunnel: $tunnelId
                    |credentials-file: ${credsFile.absolutePath}
                    |protocol: http2
                    |ingress:
                    |  - service: http://localhost:$localPort
                """.trimMargin()
                configFile.writeText(configContent)
                logInfo("Config written: ${configFile.absolutePath}")
                logDebug("Config content:\n$configContent")

                // ── Resolve edge IPs ──
                listener?.onTunnelStatusUpdate("Resolving edge servers...")
                val edgeIps = withContext(Dispatchers.IO) {
                    resolveEdgeIps()
                }

                // ── Acquire wake lock ──
                logInfo("--- Phase 4: Acquire Wake Lock ---")
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CameraStreamer:CloudflareWakeLock"
                ).apply {
                    acquire(10 * 60 * 1000L)
                    logInfo("Wake lock acquired (10 min timeout)")
                }

                // ── Build command ──
                logInfo("--- Phase 5: Build Command ---")
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

                val cmdStr = command.joinToString(" ")
                logInfo("Command: $cmdStr")
                logInfo("Edge IPs passed via --edge flags: ${edgeIps.take(4).joinToString(", ")}")

                // ── Launch process ──
                logInfo("--- Phase 6: Launch cloudflared ---")
                val processBuilder = ProcessBuilder(command)
                processBuilder.redirectErrorStream(true)
                processBuilder.environment()["GODEBUG"] = "netdns=go"

                val startTime = System.currentTimeMillis()
                val proc = processBuilder.start()
                tunnelProcess = proc
                isRunning.set(true)
                com.camerastreamer.app.CloudflareLogStore.isTunnelRunning = true
                logInfo("Process started (PID unknown, started at ${startTime}ms)")
                listener?.onTunnelStatusUpdate("Connecting to Cloudflare edge...")

                // ── Parse stdout ──
                logInfo("--- Phase 7: Reading cloudflared output ---")
                proc.inputStream.bufferedReader().use { reader ->
                    var lineCount = 0
                    while (isRunning.get()) {
                        val currentLine = reader.readLine() ?: break
                        lineCount++
                        logDebug("[cloudflared] $currentLine")

                        when {
                            currentLine.contains("Registered tunnel connection") ||
                            currentLine.contains("Connection registered") -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                logInfo("Tunnel connection registered! (took ${elapsed}ms)")
                                listener?.onTunnelStatusUpdate("Tunnel Active")
                            }
                            currentLine.contains("error") ||
                            currentLine.contains("ERR") -> {
                                logError("cloudflared error: $currentLine")
                                listener?.onTunnelError(currentLine)
                            }
                            currentLine.contains("INF") -> {
                                logInfo("[cloudflared] $currentLine")
                            }
                        }
                    }
                    logInfo("Output stream ended ($lineCount lines read)")
                }

                val exitCode = proc.waitFor()
                val totalTime = System.currentTimeMillis() - startTime
                logInfo("cloudflared exited with code $exitCode (ran for ${totalTime}ms)")
            } catch (e: Exception) {
                if (isRunning.get()) {
                    logError("Cloudflare Tunnel exception: ${e.message}")
                    Log.e(TAG, "Tunnel exception", e)
                    listener?.onTunnelError(e.message ?: "Tunnel execution error")
                }
            } finally {
                stopTunnel()
            }
        }
    }

    fun stopTunnel() {
        if (!isRunning.getAndSet(false)) return

        logInfo("Stopping Cloudflare Tunnel...")

        try {
            tunnelProcess?.destroy()
            tunnelProcess = null
            logInfo("Process destroyed")
        } catch (e: Exception) {
            logWarn("Error destroying process: ${e.message}")
        }

        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    logInfo("Wake lock released")
                }
            }
        } catch (e: Exception) {
            logWarn("Error releasing wake lock: ${e.message}")
        }
        wakeLock = null

        publicUrl = null
        com.camerastreamer.app.CloudflareLogStore.isTunnelRunning = false
        listener?.onTunnelStopped()
        logInfo("Cloudflare Tunnel stopped")
        logInfo("========================================")
    }

    fun isTunnelActive(): Boolean = isRunning.get() && publicUrl != null
}
