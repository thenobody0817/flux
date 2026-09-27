package org.omarchy.flux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.omarchy.flux.protocol.Packet
import org.omarchy.flux.protocol.Types
import org.omarchy.flux.protocol.bodyOf

class SmsTest {
    @Test
    fun messageHasTheWireShape() {
        val row = SmsRow(id = 7, thread = 42, address = "+31612345678", body = "Hi", date = 1_700_000_000_000, type = 1, read = false)
        val m = smsMessage(row)
        assertEquals(7L, m["_id"])
        assertEquals(42L, m["thread_id"])
        assertEquals("Hi", m["body"])
        assertEquals(1_700_000_000_000L, m["date"])
        assertEquals(1, m["type"])
        assertEquals(0, m["read"])
        val addresses = m["addresses"] as List<*>
        assertEquals("+31612345678", (addresses[0] as Map<*, *>)["address"])
    }

    @Test
    fun sentAndReadMapToTheProtocolNumbers() {
        val row = SmsRow(id = 8, thread = 42, address = "123", body = "Bye", date = 2, type = 2, read = true)
        assertEquals(1, smsMessage(row)["read"])
        assertEquals(2, smsMessage(row)["type"])
    }

    @Test
    fun addressesComeFromTheVersionTwoList() {
        val p = Packet(Types.SMS_REQUEST, bodyOf("version" to 2, "addresses" to listOf(mapOf("address" to "+31612345678"))))
        assertEquals(listOf("+31612345678"), smsAddresses(p))
    }

    @Test
    fun addressesFallBackToTheVersionOneField() {
        val p = Packet(Types.SMS_REQUEST, bodyOf("address" to "+31600000000"))
        assertEquals(listOf("+31600000000"), smsAddresses(p))
    }

    @Test
    fun blankAddressesAreDropped() {
        val p = Packet(Types.SMS_REQUEST, bodyOf("addresses" to listOf(mapOf("address" to "  "), mapOf<String, Any?>())))
        assertTrue(smsAddresses(p).isEmpty())
    }
}
