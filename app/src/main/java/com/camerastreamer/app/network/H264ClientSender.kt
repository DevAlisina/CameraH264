package com.camerastreamer.app.network

import android.util.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TCP Client that connects to a target host and port (push mode),
 * streaming raw H.264 Annex-B packets.
 */
class H264ClientSender(
    val host: String,
    val port: Int,
    private val onConnectedCallback: () -> Unit
) {
    companion object {
        private const val TAG = "H264ClientSender"
    }

    interface ClientListener {
        fun onConnected(host: String, port: Int)
        fun onDisconnected()
        fun onError(error: String)
        fun onStatsUpdated(fps: Int, bitrateBps: Long, totalBytes: Long)
    }

    var listener: ClientListener? = null

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private val isRunning = AtomicBoolean(false)
    private var connectThread: Thread? = null

    @Volatile
    private var cachedSpsPps: ByteArray? = null

    private val totalBytesSent = AtomicLong(0)
    private val bytesInLastSecond = AtomicLong(0)
    private val framesInLastSecond = AtomicLong(0)
    private var statsThread: Thread? = null

    fun setSpsPps(spsPps: ByteArray) {
        cachedSpsPps = spsPps
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        connectThread = Thread({
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.sendBufferSize = 256 * 1024
                s.connect(InetSocketAddress(host, port), 5000)
                socket = s
                outputStream = s.getOutputStream()

                cachedSpsPps?.let { spsPps ->
                    outputStream?.write(spsPps)
                    outputStream?.flush()
                    totalBytesSent.addAndGet(spsPps.size.toLong())
                }

                listener?.onConnected(host, port)
                onConnectedCallback()

                statsThread = Thread({ statsLoop() }, "H264Client-Stats").apply {
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed to $host:$port", e)
                listener?.onError("Failed to connect to $host:$port: ${e.message}")
                stop()
            }
        }, "H264Client-Connect").apply {
            start()
        }
    }

    fun sendFrame(frameData: ByteArray) {
        if (!isRunning.get()) return
        val stream = outputStream ?: return

        try {
            stream.write(frameData)
            framesInLastSecond.incrementAndGet()
            totalBytesSent.addAndGet(frameData.size.toLong())
            bytesInLastSecond.addAndGet(frameData.size.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write frame to $host:$port", e)
            listener?.onError("Write failed: ${e.message}")
            stop()
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
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        outputStream = null

        statsThread?.interrupt()
        statsThread = null

        connectThread?.interrupt()
        connectThread = null

        listener?.onDisconnected()
        Log.i(TAG, "H264ClientSender stopped")
    }

    fun isConnected(): Boolean = socket?.isConnected == true && !socket!!.isClosed
}
