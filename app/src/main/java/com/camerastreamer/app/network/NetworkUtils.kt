package com.camerastreamer.app.network

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object NetworkUtils {

    /**
     * Finds the local IPv4 address (e.g. Wi-Fi or Ethernet).
     * Excludes loopback addresses.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Priority 1: wlan (Wi-Fi)
            for (intf in interfaces) {
                if (intf.name.contains("wlan", ignoreCase = true) || intf.name.contains("eth", ignoreCase = true)) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: ""
                        }
                    }
                }
            }
            // Fallback: any non-loopback IPv4 address
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    /**
     * Formats bytes per second into human-readable bitrate string (e.g. 2.4 Mbps).
     */
    fun formatBitrate(bps: Long): String {
        val bitsPerSec = bps * 8
        return when {
            bitsPerSec >= 1_000_000 -> String.format(Locale.US, "%.2f Mbps", bitsPerSec / 1_000_000.0)
            bitsPerSec >= 1_000 -> String.format(Locale.US, "%.1f Kbps", bitsPerSec / 1_000.0)
            else -> "$bitsPerSec bps"
        }
    }

    /**
     * Formats total transferred bytes (e.g. 15.4 MB).
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
