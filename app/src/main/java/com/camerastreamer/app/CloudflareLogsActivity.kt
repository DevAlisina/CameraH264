package com.camerastreamer.app

import android.os.Bundle
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen activity that shows the raw cloudflared log output.
 * It reads from the static log buffer held by [CloudflareLogStore] and subscribes
 * to new lines via [CloudflareLogStore.LogListener] for live tail.
 */
class CloudflareLogsActivity : AppCompatActivity() {

    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView
    private lateinit var tvLogStatus: TextView

    private val logListener = object : CloudflareLogStore.LogListener {
        override fun onNewLogLine(line: String) {
            runOnUiThread { appendLine(line) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloudflare_logs)

        tvLogs = findViewById(R.id.tvLogs)
        scrollLogs = findViewById(R.id.scrollLogs)
        tvLogStatus = findViewById(R.id.tvLogStatus)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<ImageButton>(R.id.btnClearLogs).setOnClickListener {
            CloudflareLogStore.clear()
            tvLogs.text = ""
        }

        findViewById<ImageButton>(R.id.btnScrollBottom).setOnClickListener {
            scrollToBottom()
        }

        // Load existing buffered lines
        val existing = CloudflareLogStore.getLines()
        if (existing.isNotEmpty()) {
            tvLogs.text = existing.joinToString("\n")
            scrollToBottom()
        }

        // Reflect tunnel active state
        if (CloudflareLogStore.isTunnelRunning) {
            tvLogStatus.text = "● Tunnel running – live tail active"
        } else {
            tvLogStatus.text = "○ Tunnel not running – showing cached logs"
        }

        // Subscribe for live lines
        CloudflareLogStore.logListener = logListener
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only clear listener if it is still ours (avoid clearing a newly set one)
        if (CloudflareLogStore.logListener === logListener) {
            CloudflareLogStore.logListener = null
        }
    }

    private fun appendLine(line: String) {
        val current = tvLogs.text
        tvLogs.text = if (current.isNullOrEmpty()) line else "$current\n$line"
        scrollToBottom()
    }

    private fun scrollToBottom() {
        scrollLogs.post { scrollLogs.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
