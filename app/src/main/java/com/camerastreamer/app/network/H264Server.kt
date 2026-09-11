package com.camerastreamer.app.network

import android.util.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TCP Server that listens on a specified port, accepts connections from players
 * (like ffplay, VLC, mpv, or custom TCP clients), and streams raw H.264 Annex-B packets.
 */
class H264Server(
    val port: Int = 8080,
    private val onNewClientConnected: () -> Unit
) {
    companion object {
        private const val TAG = "H264Server"
    }

    interface ServerListener {
        fun onServerStarted(port: Int)
        fun onClientCountChanged(count: Int)
        fun onStatsUpdated(fps: Int, bitrateBps: Long, totalBytes: Long)
        fun onServerError(error: String)
        fun onServerStopped()
    }

    var listener: ServerListener? = null

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    // Map of client sockets to their output streams
    private val clients = ConcurrentHashMap<Socket, OutputStream>()

    // Cached SPS/PPS to send immediately to newly connected clients
    @Volatile
    private var cachedSpsPps: ByteArray? = null

    // Statistics
    private val totalBytesSent = AtomicLong(0)
    private val bytesInLastSecond = AtomicLong(0)
    private val framesInLastSecond = AtomicLong(0)
    private var statsThread: Thread? = null

    fun setSpsPps(spsPps: ByteArray) {
        cachedSpsPps = spsPps
    }

    fun start() {
        if (isRunning.get()) return

        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket
            isRunning.set(true)

            acceptThread = Thread({ acceptLoop() }, "H264Server-Accept").apply {
                start()
            }

            statsThread = Thread({ statsLoop() }, "H264Server-Stats").apply {
                start()
            }

            listener?.onServerStarted(port)
            listener?.onClientCountChanged(0)
            Log.i(TAG, "H264Server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H264Server on port $port", e)
            listener?.onServerError("Failed to bind port $port: ${e.message}")
            stop()
        }
    }

    private fun acceptLoop() {
        while (isRunning.get()) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                clientSocket.tcpNoDelay = true
                clientSocket.sendBufferSize = 256 * 1024 // 256KB buffer

                Log.i(TAG, "New client connected: ${clientSocket.remoteSocketAddress}")

                val outputStream = clientSocket.getOutputStream()

                // Immediately send cached SPS/PPS header so decoder can initialize
                cachedSpsPps?.let { spsPps ->
                    try {
                        outputStream.write(spsPps)
                        outputStream.flush()
                        totalBytesSent.addAndGet(spsPps.size.toLong())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to write SPS/PPS to new client", e)
                    }
                }

                clients[clientSocket] = outputStream
                listener?.onClientCountChanged(clients.size)

                // Request keyframe so the new client can display immediately
                onNewClientConnected()
            } catch (e: SocketException) {
                if (isRunning.get()) {
                    Log.w(TAG, "SocketException in accept loop", e)
                }
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Exception in accept loop", e)
                }
            }
        }
    }

    /**
     * Broadcasts an encoded H.264 frame to all connected clients.
     */
    fun sendFrame(frameData: ByteArray) {
        if (!isRunning.get() || clients.isEmpty()) return

        framesInLastSecond.incrementAndGet()
        val frameSize = frameData.size

        val deadSockets = mutableListOf<Socket>()

        for ((socket, outStream) in clients) {
            try {
                outStream.write(frameData)
                totalBytesSent.addAndGet(frameSize.toLong())
                bytesInLastSecond.addAndGet(frameSize.toLong())
            } catch (e: Exception) {
                Log.w(TAG, "Client disconnected or write failed: ${socket.remoteSocketAddress}", e)
                deadSockets.add(socket)
            }
        }

        if (deadSockets.isNotEmpty()) {
            for (dead in deadSockets) {
                clients.remove(dead)
                try {
                    dead.close()
                } catch (_: Exception) {
                }
            }
            listener?.onClientCountChanged(clients.size)
        }
    }

    private fun statsLoop() {
        while (isRunning.get()) {
            try {
                Thread.sleep(1000)
                val fps = framesInLastSecond.getAndSet(0).toInt()
                val bytesPerSec = bytesInLastSecond.getAndSet(0)
                val total = totalBytesSent.get()
                listener?.onStatsUpdated(fps, bytesPerSec, total)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        acceptThread?.interrupt()
        acceptThread = null

        statsThread?.interrupt()
        statsThread = null

        for ((socket, _) in clients) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        clients.clear()

        listener?.onClientCountChanged(0)
        listener?.onServerStopped()
        Log.i(TAG, "H264Server stopped")
    }

    fun getClientCount(): Int = clients.size
}
