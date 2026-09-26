package org.omarchy.flux.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Builds the 6 + 16 * 6 byte Wake-on-LAN magic packet for a MAC. */
fun magicPacket(mac: String): ByteArray? {
    val bytes = parseMac(mac) ?: return null
    val packet = ByteArray(6 + 16 * bytes.size)
    for (i in 0 until 6) packet[i] = 0xFF.toByte()
    for (i in 0 until 16) bytes.copyInto(packet, 6 + i * bytes.size)
    return packet
}

/** Parses a colon- or dash-separated hardware address into 6 bytes. */
fun parseMac(mac: String): ByteArray? {
    val parts = mac.trim().split(':', '-')
    if (parts.size != 6) return null
    val out = ByteArray(6)
    for (i in parts.indices) {
        if (parts[i].length != 2) return null
        out[i] = parts[i].toIntOrNull(16)?.toByte() ?: return null
    }
    return out
}

/**
 * Wake-on-LAN: builds and sends magic packets and limits automatic retries.
 * It uses no Android API, so the packet and target logic has JVM tests.
 */
object Wake {
    /** The UDP port that WoL usually uses. */
    const val DEFAULT_PORT = 9

    /** The IPv4 local broadcast, used when the phone is on the computer's network. */
    const val BROADCAST = "255.255.255.255"

    /** The least time between automatic attempts for one computer. */
    const val AUTO_INTERVAL_MS = 2 * 60 * 1000L

    /** The most automatic attempts for one computer before the user tries again. */
    const val MAX_AUTO_ATTEMPTS = 6

    private val attempts = HashMap<String, Int>()
    private val lastAttempt = HashMap<String, Long>()

    /**
     * Returns the address that delivers the magic packet: [host] when set,
     * the local broadcast on Wi-Fi, or null when neither is available.
     */
    fun target(host: String, port: Int, onWifi: Boolean): Pair<String, Int>? {
        val h = host.trim()
        if (h.isNotEmpty()) return h to (port.takeIf { it in 1..65535 } ?: DEFAULT_PORT)
        if (onWifi) return BROADCAST to DEFAULT_PORT
        return null
    }

    /** Returns true when an automatic attempt is allowed now, and records it. */
    @Synchronized
    fun allowAuto(deviceId: String): Boolean {
        val now = System.currentTimeMillis()
        val count = attempts[deviceId] ?: 0
        if (count >= MAX_AUTO_ATTEMPTS) return false
        if (now - (lastAttempt[deviceId] ?: 0L) < AUTO_INTERVAL_MS) return false
        attempts[deviceId] = count + 1
        lastAttempt[deviceId] = now
        return true
    }

    /** Clears the automatic attempt state, for example when the computer connects. */
    @Synchronized
    fun clear(deviceId: String) {
        attempts.remove(deviceId)
        lastAttempt.remove(deviceId)
    }

    /** Sends one magic packet per MAC to [host]:[port]. Returns the number sent. */
    fun send(host: String, port: Int, macs: List<String>): Int {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return 0
        var sent = 0
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (mac in macs) {
                val payload = magicPacket(mac) ?: continue
                runCatching { socket.send(DatagramPacket(payload, payload.size, address, port)) }
                    .onSuccess { sent++ }
            }
        }
        return sent
    }
}
