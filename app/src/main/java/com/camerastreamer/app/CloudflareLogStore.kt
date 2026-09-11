package com.camerastreamer.app

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * App-level singleton that bridges [CloudflareTunnelManager]'s log output
 * to [CloudflareLogsActivity] without creating a tight coupling between them.
 *
 * The manager pushes lines here; the activity subscribes for live tail.
 */
object CloudflareLogStore {

    private const val MAX_LINES = 500

    interface LogListener {
        fun onNewLogLine(line: String)
    }

    private val buffer: ConcurrentLinkedDeque<String> = ConcurrentLinkedDeque()

    /** Set by [CloudflareLogsActivity] to receive live log lines. */
    @Volatile
    var logListener: LogListener? = null

    /** True while the Cloudflare tunnel process is active. */
    @Volatile
    var isTunnelRunning: Boolean = false

    fun addLine(line: String) {
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.pollFirst()
        logListener?.onNewLogLine(line)
    }

    fun getLines(): List<String> = buffer.toList()

    fun clear() {
        buffer.clear()
    }
}
