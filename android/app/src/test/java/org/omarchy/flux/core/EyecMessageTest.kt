package org.omarchy.flux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.omarchy.flux.protocol.Packet
import org.omarchy.flux.protocol.Types
import org.omarchy.flux.protocol.bodyOf

/** The wire rules of eyec permission prompts. docs/eyec.md describes them. */
class EyecMessageTest {
    private fun packet(vararg extra: Pair<String, Any?>): Packet {
        val fields = mutableMapOf<String, Any?>(
            "kind" to "permit", "id" to "e1", "title" to "curl https://x",
            "pattern" to "curl *", "service" to "bash", "timeout" to 120,
        )
        for ((k, v) in extra) fields[k] = v
        return Packet(Types.FLUX_EYEC, bodyOf(*fields.toList().toTypedArray()))
    }

    @Test
    fun parsePermit() {
        val r = EyecMessage.parse(packet(), "pc1", "omarchy-xps")!!
        assertEquals("pc1", r.computerId)
        assertEquals("omarchy-xps", r.computerName)
        assertEquals("e1", r.id)
        assertEquals("curl https://x", r.title)
        assertEquals("curl *", r.pattern)
        assertEquals("bash", r.service)
        assertEquals(120, r.timeoutSeconds)
        assertEquals("Allow \"curl https://x\" on omarchy-xps?", EyecMessage.question(r))
    }

    @Test
    fun questionWithoutTitle() {
        val r = EyecMessage.parse(packet("title" to ""), "pc1", "omarchy-xps")!!
        assertEquals("Allow this action on omarchy-xps?", EyecMessage.question(r))
    }

    @Test
    fun parseRefusesBadRequests() {
        assertNull(EyecMessage.parse(packet("kind" to "other"), "pc1", "pc"))
        assertNull(EyecMessage.parse(packet("id" to ""), "pc1", "pc"))
        assertNull(EyecMessage.parse(packet("id" to "x".repeat(65)), "pc1", "pc"))
        assertNull(EyecMessage.parse(packet("title" to "a\nb"), "pc1", "pc"))
        assertNull(EyecMessage.parse(packet("pattern" to "tab\there"), "pc1", "pc"))
        assertNull(EyecMessage.parse(packet("title" to "x".repeat(513)), "pc1", "pc"))
    }

    @Test
    fun timeoutIsClamped() {
        assertEquals(120, EyecMessage.parse(packet("timeout" to null), "pc1", "pc")!!.timeoutSeconds)
        assertEquals(5, EyecMessage.parse(packet("timeout" to 1), "pc1", "pc")!!.timeoutSeconds)
        assertEquals(600, EyecMessage.parse(packet("timeout" to 9999), "pc1", "pc")!!.timeoutSeconds)
    }

    @Test
    fun fieldRules() {
        assertTrue(EyecMessage.validField("curl https://x"))
        assertTrue(EyecMessage.validField(""))
        assertFalse(EyecMessage.validField("a\nb"))
        assertFalse(EyecMessage.validField("x".repeat(513)))
    }

    @Test
    fun answerPackets() {
        for (decision in listOf("allow", "deny", "yolo")) {
            val p = EyecMessage.answer("e1", decision)
            assertEquals(Types.FLUX_EYEC, p.type)
            assertEquals("permit", p.string("kind"))
            assertEquals("e1", p.string("id"))
            assertEquals(decision, p.string("decision"))
            // A packet survives the wire format.
            val back = Packet.parse(p.serialize())!!
            assertEquals(decision, back.string("decision"))
        }
    }

    @Test
    fun askAndTriggerPackets() {
        val a = EyecMessage.ask("a1", "hello")
        assertEquals(Types.FLUX_EYEC, a.type)
        assertEquals("ask", a.string("kind"))
        assertEquals("a1", a.string("id"))
        assertEquals("hello", a.string("prompt"))

        val t = EyecMessage.trigger("t1", "dock.toggle")
        assertEquals("trigger", t.string("kind"))
        assertEquals("t1", t.string("id"))
        assertEquals("dock.toggle", t.string("action"))
    }

    @Test
    fun parseAnswer() {
        val p = Packet(Types.FLUX_EYEC, bodyOf("kind" to "answer", "id" to "a1", "text" to "hi", "choices" to listOf("x", "y")))
        val a = EyecMessage.parseAnswer(p)!!
        assertEquals("a1", a.id)
        assertEquals("hi", a.text)
        assertEquals(listOf("x", "y"), a.choices)
        assertNull(a.error)
        assertNull(EyecMessage.parseAnswer(Packet(Types.FLUX_EYEC, bodyOf("kind" to "permit"))))

        val e = EyecMessage.parseAnswer(Packet(Types.FLUX_EYEC, bodyOf("kind" to "answer", "id" to "a2", "error" to "no")))
        assertEquals("no", e!!.error)
    }

    @Test
    fun parseTriggerResult() {
        val p = Packet(Types.FLUX_EYEC, bodyOf("kind" to "trigger", "id" to "t1", "ok" to true, "detail" to "done"))
        val t = EyecMessage.parseTrigger(p)!!
        assertEquals("t1", t.id)
        assertTrue(t.ok)
        assertEquals("done", t.detail)
        assertNull(EyecMessage.parseTrigger(Packet(Types.FLUX_EYEC, bodyOf("kind" to "answer", "id" to "x"))))
    }

    @Test
    fun peekPacketAndImageAnswer() {
        val p = EyecMessage.peek("p1", "look")
        assertEquals(Types.FLUX_EYEC, p.type)
        assertEquals("peek", p.string("kind"))
        assertEquals("p1", p.string("id"))
        assertEquals("look", p.string("prompt"))

        val a = EyecMessage.parseAnswer(
            Packet(Types.FLUX_EYEC, bodyOf("kind" to "answer", "id" to "p1", "text" to "screen", "image" to "aGk=", "mime" to "image/jpeg", "ocr" to "txt")),
        )!!
        assertEquals("screen", a.text)
        assertEquals("aGk=", a.image)
        assertEquals("image/jpeg", a.mime)
        assertEquals("txt", a.ocr)
    }
}
