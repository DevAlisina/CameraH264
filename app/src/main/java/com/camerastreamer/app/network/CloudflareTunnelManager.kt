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
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Manages Cloudflare Quick Tunnel (cloudflared) on Android.
 * Runs non-root using either the pre-bundled native library (libcloudflared.so)
 * or dynamically downloaded binary.
 */
class CloudflareTunnelManager(private val context: Context) {

    companion object {
        private const val TAG = "CloudflareTunnel"
        private val URL_PATTERN = Pattern.compile("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")
    }

    interface TunnelListener {
        fun onTunnelStarting()
        fun onTunnelUrlAvailable(publicUrl: String)
        fun onTunnelStatusUpdate(status: String)
        fun onTunnelError(error: String)
        fun onTunnelStopped()
    }

    var listener: TunnelListener? = null

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
        // 1. Native library directory (preferred and fastest)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativeLib = File(nativeDir, "libcloudflared.so")
        if (nativeLib.exists() && nativeLib.canExecute()) {
            return nativeLib
        }

        // 2. Downloaded / cached binary in files directory
        val downloadedBin = File(context.filesDir, "cloudflared")
        if (downloadedBin.exists() && downloadedBin.canExecute()) {
            return downloadedBin
        }

        return null
    }

    /**
     * Checks if the binary is ready to run or needs to be downloaded.
     */
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

    /**
     * Starts the Cloudflare Quick Tunnel forwarding traffic to the local port.
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

                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraStreamer:CloudflareWakeLock").apply {
                    acquire(10 * 60 * 1000L) // 10 minutes lock
                }

                // Write custom DNS for Go's pure resolver
                val dnsFile = File(context.filesDir, "resolv.conf")
                dnsFile.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")

                val command = arrayOf(
                    binary.absolutePath,
                    "tunnel",
                    "--no-autoupdate",
                    "--edge-ip-version", "4",
                    "--protocol", "http2",
                    "--url", "http://127.0.0.1:$localPort"
                )

                Log.i(TAG, "Executing: ${command.joinToString(" ")}")
                val processBuilder = ProcessBuilder(*command)
                val env = processBuilder.environment()
                env["GODEBUG"] = "netdns=go"
                processBuilder.redirectInput(dnsFile)
                processBuilder.redirectErrorStream(true)

                val proc = processBuilder.start()
                tunnelProcess = proc
                isRunning.set(true)
                listener?.onTunnelStatusUpdate("Connecting to Cloudflare edge...")

                proc.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (isRunning.get() && reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue
                        Log.d(TAG, currentLine)

                        val matcher = URL_PATTERN.matcher(currentLine)
                        if (matcher.find()) {
                            val url = matcher.group()
                            publicUrl = url
                            Log.i(TAG, "Discovered Cloudflare Tunnel URL: $url")
                            listener?.onTunnelUrlAvailable(url)
                            listener?.onTunnelStatusUpdate("Tunnel Active")
                        } else if (currentLine.contains("Registered tunnel connection") || currentLine.contains("Connection registered")) {
                            listener?.onTunnelStatusUpdate("Tunnel Active")
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

    /**
     * Stops the running Cloudflare tunnel process.
     */
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
        listener?.onTunnelStopped()
        Log.i(TAG, "Cloudflare Tunnel stopped")
    }

    fun isTunnelActive(): Boolean = isRunning.get() && publicUrl != null
}
