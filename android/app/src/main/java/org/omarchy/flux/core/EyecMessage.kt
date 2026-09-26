package org.omarchy.flux.core

import org.omarchy.flux.protocol.Packet
import org.omarchy.flux.protocol.Types
import org.omarchy.flux.protocol.bodyOf

/**
 * A permission prompt from eyec on a computer, and the packets that answer
 * it. This code has no Android dependency, so the JVM tests check it.
 * docs/eyec.md describes the flow.
 */
data class EyecRequest(
    val computerId: String,
    val computerName: String,
    val id: String,
    val title: String,
    val pattern: String,
    val service: String,
    val timeoutSeconds: Int,
)

/** The answer of the computer to an ask or a peek. */
data class EyecAnswer(
    val id: String,
    val text: String,
    val choices: List<String>,
    val error: String?,
    /** Base64 JPEG of the screen, for a peek. */
    val image: String? = null,
    val mime: String? = null,
    val ocr: String? = null,
)

/** The result of a trigger action. */
data class EyecTriggerResult(val id: String, val ok: Boolean, val detail: String)

object EyecMessage {
    private const val MAX_FIELD = 512

    /** Valid UTF-8, at most MAX_FIELD bytes, and no control character. */
    fun validField(v: String): Boolean {
        val bytes = v.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FIELD || String(bytes, Charsets.UTF_8) != v) return false
        var i = 0
        while (i < v.length) {
            val c = v.codePointAt(i)
            if (c < 0x20 || c == 0x7f || c in 0x80..0x9f) return false
            i += Character.charCount(c)
        }
        return true
    }

    /** Reads a permit request. It returns null for a packet that breaks a rule. */
    fun parse(p: Packet, computerId: String, computerName: String): EyecRequest? {
        if (p.string("kind") != "permit") return null
        val id = p.string("id")?.takeIf { it.isNotEmpty() && it.length <= 64 && validField(it) } ?: return null
        val title = p.string("title") ?: ""
        val pattern = p.string("pattern") ?: ""
        val service = p.string("service") ?: ""
        if (!validField(title) || !validField(pattern) || !validField(service)) return null
        return EyecRequest(
            computerId = computerId,
            computerName = computerName,
            id = id,
            title = title,
            pattern = pattern,
            service = service,
            timeoutSeconds = (p.int("timeout") ?: 120).coerceIn(5, 600),
        )
    }

    /** The question on the phone, for example "Allow \"curl https://x\" on omarchy-xps?". */
    fun question(r: EyecRequest): String =
        if (r.title.isNotEmpty()) "Allow \"${r.title}\" on ${r.computerName}?" else "Allow this action on ${r.computerName}?"

    /** The packet that answers a request with allow, deny, or yolo. */
    fun answer(id: String, decision: String): Packet =
        Packet(Types.FLUX_EYEC, bodyOf("kind" to "permit", "id" to id, "decision" to decision))

    /** The packet that asks eyec a question. */
    fun ask(id: String, prompt: String): Packet =
        Packet(Types.FLUX_EYEC, bodyOf("kind" to "ask", "id" to id, "prompt" to prompt))

    /** The packet that asks eyec to look at the whole screen. */
    fun peek(id: String, prompt: String): Packet =
        Packet(Types.FLUX_EYEC, bodyOf("kind" to "peek", "id" to id, "prompt" to prompt))

    /** The packet that asks eyec to run a curated action. */
    fun trigger(id: String, action: String): Packet =
        Packet(Types.FLUX_EYEC, bodyOf("kind" to "trigger", "id" to id, "action" to action))

    /** Reads the answer to an ask or a peek. */
    fun parseAnswer(p: Packet): EyecAnswer? {
        if (p.string("kind") != "answer") return null
        val id = p.string("id") ?: return null
        return EyecAnswer(
            id = id,
            text = p.string("text") ?: "",
            choices = p.strings("choices"),
            error = p.string("error"),
            image = p.string("image"),
            mime = p.string("mime"),
            ocr = p.string("ocr"),
        )
    }

    /** Reads the result of a trigger action. */
    fun parseTrigger(p: Packet): EyecTriggerResult? {
        if (p.string("kind") != "trigger") return null
        val id = p.string("id") ?: return null
        return EyecTriggerResult(id, p.bool("ok") ?: false, p.string("detail") ?: "")
    }
}
