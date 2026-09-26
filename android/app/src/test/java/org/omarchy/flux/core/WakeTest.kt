package org.omarchy.flux.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeTest {
    @Test
    fun magicPacketHasTheMacSixteenTimes() {
        val mac = "10:06:48:c0:1b:f9"
        val packet = magicPacket(mac) ?: error("no packet")
        assertEquals(6 + 16 * 6, packet.size)
        for (i in 0 until 6) assertEquals(0xFF.toByte(), packet[i])
        val bytes = parseMac(mac)!!
        for (i in 0 until 16) {
            assertArrayEquals(bytes, packet.copyOfRange(6 + i * 6, 6 + i * 6 + 6))
        }
    }

    @Test
    fun magicPacketAcceptsDashesAndRejectsBadInput() {
        assertArrayEquals(magicPacket("10:06:48:c0:1b:f9"), magicPacket("10-06-48-C0-1B-F9"))
        assertNull(magicPacket("10:06:48:c0:1b"))
        assertNull(magicPacket("no:ta:ma:c0:1b:f9"))
        assertNull(magicPacket(""))
    }

    @Test
    fun targetUsesTheConfiguredHost() {
        assertEquals("home.example.com" to 9, Wake.target("home.example.com", 0, onWifi = false))
        assertEquals("10.0.0.2" to 7, Wake.target("10.0.0.2", 7, onWifi = false))
        assertEquals("host" to 9, Wake.target("  host ", 9, onWifi = false))
    }

    @Test
    fun targetFallsBackToBroadcastOnWifi() {
        assertEquals(Wake.BROADCAST to 9, Wake.target("", 9, onWifi = true))
        assertNull("no address away from Wi-Fi", Wake.target("", 9, onWifi = false))
    }

    @Test
    fun targetsSendToHostAndBroadcastOnWifi() {
        assertEquals(listOf("home.example.com" to 9), Wake.targets("home.example.com", 9, onWifi = false))
        assertEquals(
            listOf("home.example.com" to 9, Wake.BROADCAST to 9),
            Wake.targets("home.example.com", 9, onWifi = true),
        )
        assertEquals(listOf(Wake.BROADCAST to 9), Wake.targets("", 9, onWifi = true))
        assertTrue("no target away from Wi-Fi without a host", Wake.targets("", 9, onWifi = false).isEmpty())
    }

    @Test
    fun automaticAttemptsAreRateLimitedAndReset() {
        val id = "device-under-test"
        Wake.clear(id)
        assertTrue("the first attempt is allowed", Wake.allowAuto(id))
        assertFalse("a second attempt waits", Wake.allowAuto(id))
        Wake.clear(id)
        assertTrue("a connect clears the state", Wake.allowAuto(id))
    }
}
