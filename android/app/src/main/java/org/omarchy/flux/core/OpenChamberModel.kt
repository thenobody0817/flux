package org.omarchy.flux.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.omarchy.flux.protocol.bool
import org.omarchy.flux.protocol.str
import org.omarchy.flux.protocol.strings

/** How the phone draws the messages of a session. */
enum class OutputMode(val key: String) {
    /** Cards for text, reasoning, and tool calls. */
    Rich("rich"),

    /** The plain lines that the computer rendered. */
    Plain("plain");

    companion object {
        fun fromKey(key: String?): OutputMode = entries.firstOrNull { it.key == key } ?: Rich
    }
}

/**
 * One OpenChamber session. [waiting] is "form" or "permission" while the
 * session waits for an answer, and it is empty otherwise.
 */
data class OpenChamberSession(
    val id: String,
    val title: String = "",
    val agent: String = "",
    val status: AgentStatus = AgentStatus.Unknown,
    val project: String = "",
    val model: String = "",
    val waiting: String = "",
)

/** One agent that OpenChamber can start a session with. */
data class OpenChamberKind(val id: String, val name: String)

/**
 * What a computer reports about OpenChamber. [enabled] is false when the
 * computer has `openchamber = false` in its config.toml. [running] is true
 * when fluxd reaches OpenChamber. [control] is true when the computer
 * accepts replies from this phone and new sessions. [dirs] are the folders
 * for a new session, as ~ paths.
 */
data class OpenChamberState(
    val enabled: Boolean,
    val running: Boolean,
    val sessions: List<OpenChamberSession>,
    val control: Boolean = false,
    val kinds: List<OpenChamberKind> = emptyList(),
    val dirs: List<String> = emptyList(),
) {
    /** The sessions with [AgentStatus.Blocked] first, then working, idle, and unknown. */
    val sorted: List<OpenChamberSession> get() = sortSessions(sessions)

    val blocked: Int get() = sessions.count { it.status == AgentStatus.Blocked }

    fun session(id: String): OpenChamberSession? = sessions.firstOrNull { it.id == id }
}

/** Sorts by status in the order of [AgentStatus], keeping the computer order inside a group. */
fun sortSessions(sessions: List<OpenChamberSession>): List<OpenChamberSession> = sessions.sortedBy { it.status.ordinal }

/** One choice of a form field. */
data class FormOption(val value: String, val label: String)

/**
 * One input of a question form. [type] is "string", "number", "integer",
 * "boolean", "multiselect", or "external". A string field with [options] is
 * a choice, and a field without them is free text. An external field is an
 * acknowledgement.
 */
data class FormField(
    val key: String,
    val type: String = "string",
    val label: String = "",
    val options: List<FormOption> = emptyList(),
    val required: Boolean = false,
)

/** Something that a session waits for. */
sealed interface Pending {
    val id: String
    val title: String

    /** A question with its fields. */
    data class Form(override val id: String, override val title: String, val fields: List<FormField> = emptyList()) : Pending

    /** A permission prompt for an action on some resources. */
    data class Permission(override val id: String, val action: String, val resources: List<String> = emptyList()) : Pending {
        override val title: String get() = action.ifEmpty { "permission" }
    }
}

/** One part of the messages of a session, for the rich view. */
sealed interface Entry {
    /** A message that the user sent. */
    data class User(val text: String) : Entry

    /** The header of an assistant message. */
    data class Assistant(val name: String) : Entry

    /** A paragraph of an assistant message. */
    data class Text(val text: String) : Entry

    /** The reasoning of an assistant message. */
    data class Reasoning(val text: String) : Entry

    /** A tool call with its input and its output. */
    data class Tool(val name: String, val status: String = "", val input: String = "", val output: String = "", val error: String = "") : Entry
}

/**
 * The messages of one session. The computer sends either the rich entries
 * or the plain text, and [plain] tells which one [entries] holds. [loading]
 * is true while a read waits for its answer; the old messages stay on
 * screen until the new ones come.
 */
data class OpenChamberOutput(
    val session: String,
    val loading: Boolean = true,
    val rich: Boolean = false,
    val plain: String = "",
    val entries: List<Entry> = emptyList(),
    val pending: List<Pending> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
)

/**
 * The last reply to a session. [action] is "prompt", "interrupt", "form",
 * or "permission". [sending] is true until the computer answers. [seq] is
 * different for each reply, so the UI sees each answer.
 */
data class OpenChamberReply(
    val session: String,
    val action: String,
    val seq: Long,
    val sending: Boolean = true,
    val error: String? = null,
)

/** The answer of the computer to a reply: `{"kind":"sent"}`. [error] is null on success. */
data class OpenChamberSent(val session: String, val action: String, val error: String?)

/**
 * The last new session or close from this phone. [action] is "create" or
 * "close". [sending] is true until the computer answers.
 */
data class OpenChamberAction(
    val action: String,
    val seq: Long,
    val sending: Boolean = true,
    val session: String? = null,
    val error: String? = null,
)

/** The answer of the computer to a create or a close: `{"kind":"created"}` or `{"kind":"closed"}`. */
data class OpenChamberDone(val action: String, val session: String?, val error: String?)

/** Parses the body of a state packet. It returns null for a body that is not a state. */
fun parseOpenChamberState(body: JsonObject): OpenChamberState? {
    if (body.str("kind") != "state") return null
    val sessions = (body["agents"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        OpenChamberSession(
            id = id,
            title = o.str("title").orEmpty(),
            agent = o.str("agent").orEmpty().ifEmpty { "Agent" },
            status = AgentStatus.from(o.str("status")),
            project = o.str("project").orEmpty(),
            model = o.str("model").orEmpty(),
            waiting = o.str("waiting").orEmpty(),
        )
    }
    val kinds = (body["kinds"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        OpenChamberKind(id, o.str("name").orEmpty().ifEmpty { id })
    }
    val enabled = body.bool("enabled") ?: true
    val control = enabled && (body.bool("control") ?: false)
    return OpenChamberState(
        enabled = enabled,
        running = enabled && (body.bool("running") ?: false),
        sessions = sessions,
        control = control,
        kinds = if (control) kinds else emptyList(),
        dirs = if (control) body.strings("dirs") else emptyList(),
    )
}

/** Parses the body of an output packet. It returns null for a body that is not an output. */
fun parseOpenChamberOutput(body: JsonObject): OpenChamberOutput? {
    if (body.str("kind") != "output") return null
    val session = body.str("session")?.takeIf { it.isNotEmpty() } ?: return null
    val error = body.str("error")?.takeIf { it.isNotEmpty() }
    val rich = body.str("format") == "rich"
    val text = body.str("text").orEmpty()
    return OpenChamberOutput(
        session = session,
        loading = false,
        rich = rich,
        plain = if (rich) "" else text,
        entries = if (rich && error == null) parseEntries(text) else emptyList(),
        pending = parsePending(body["pending"] as? JsonArray),
        truncated = body.bool("truncated") ?: false,
        error = error,
    )
}

/** Parses the entries of a rich output. A broken entry is skipped. */
private fun parseEntries(text: String): List<Entry> {
    if (text.isBlank()) return emptyList()
    val array = runCatching { org.omarchy.flux.protocol.json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val body = o.str("t").orEmpty()
        when (o.str("r")) {
            "u" -> if (body.isEmpty()) null else Entry.User(body)
            "a" -> if (o.containsKey("t")) Entry.Text(body) else Entry.Assistant(o.str("n").orEmpty().ifEmpty { "Agent" })
            "r" -> if (body.isEmpty()) null else Entry.Reasoning(body)
            "t" -> Entry.Tool(
                name = o.str("n").orEmpty().ifEmpty { "tool" },
                status = o.str("s").orEmpty(),
                input = o.str("i").orEmpty(),
                output = o.str("o").orEmpty(),
                error = o.str("e").orEmpty(),
            )
            else -> null
        }
    }
}

/** Parses the pending questions and permissions of an output. */
private fun parsePending(array: JsonArray?): List<Pending> {
    if (array == null) return emptyList()
    return array.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        when (o.str("kind")) {
            "form" -> Pending.Form(id, o.str("title").orEmpty(), parseFields(o["fields"] as? JsonArray))
            "permission" -> Pending.Permission(id, o.str("action").orEmpty(), o.strings("resources"))
            else -> null
        }
    }
}

private fun parseFields(array: JsonArray?): List<FormField> {
    if (array == null) return emptyList()
    return array.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val key = o.str("key")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val options = (o["options"] as? JsonArray).orEmpty().mapNotNull { eo ->
            val oo = eo as? JsonObject ?: return@mapNotNull null
            val value = oo.str("value") ?: return@mapNotNull null
            FormOption(value, oo.str("label").orEmpty().ifEmpty { value })
        }
        FormField(
            key = key,
            type = o.str("type").orEmpty().ifEmpty { "string" },
            label = o.str("label").orEmpty(),
            options = options,
            required = o.bool("required") ?: false,
        )
    }
}

/** Parses the body of a created or closed packet. It returns null for another body. */
fun parseOpenChamberDone(body: JsonObject): OpenChamberDone? {
    val action = when (body.str("kind")) {
        "created" -> "create"
        "closed" -> "close"
        else -> return null
    }
    return OpenChamberDone(action, body.str("session")?.takeIf { it.isNotEmpty() }, body.str("error")?.takeIf { it.isNotEmpty() })
}

/** Parses the body of a sent packet. It returns null for a body that is not a sent answer. */
fun parseOpenChamberSent(body: JsonObject): OpenChamberSent? {
    if (body.str("kind") != "sent") return null
    val session = body.str("session")?.takeIf { it.isNotEmpty() } ?: return null
    return OpenChamberSent(session, body.str("action").orEmpty(), body.str("error")?.takeIf { it.isNotEmpty() })
}

/** The longest prompt for a session, in UTF-8 bytes. The computer allows 16 KB. */
const val OPENCHAMBER_MAX_PROMPT = 16 * 1024

/** The most messages that a read asks for. */
const val OPENCHAMBER_READ_MESSAGES = 80

/**
 * Builds the answer object of a form. [values] maps each field key to the
 * value that the user chose or typed: an option value or text for a string,
 * "true" or "false" for a boolean, and digits for a number. A field that
 * the user left empty is left out, except an external field, which is an
 * acknowledgement.
 */
fun formAnswer(fields: List<FormField>, values: Map<String, String>): JsonObject = buildJsonObject {
    for (field in fields) {
        val raw = values[field.key]
        when (field.type) {
            "external" -> put(field.key, JsonPrimitive(true))
            "boolean" -> if (raw != null) put(field.key, JsonPrimitive(raw == "true"))
            "number" -> raw?.trim()?.toDoubleOrNull()?.let { put(field.key, JsonPrimitive(it)) }
            "integer" -> raw?.trim()?.toLongOrNull()?.let { put(field.key, JsonPrimitive(it)) }
            else -> raw?.takeIf { it.isNotBlank() }?.let { put(field.key, JsonPrimitive(it)) }
        }
    }
}

/** A notification change for one session that [OpenChamberTracker] finds. */
sealed interface SessionAlert {
    val id: String

    /** The session waits for a question or a permission. */
    data class NeedsInput(val session: OpenChamberSession) : SessionAlert {
        override val id: String get() = session.id
    }

    /** The session stopped working and is ready for input. The phone waits a moment before it posts this. */
    data class Finished(val session: OpenChamberSession) : SessionAlert {
        override val id: String get() = session.id
    }

    /** The notification of the session is no longer true. */
    data class Clear(override val id: String) : SessionAlert
}

/**
 * Finds the status changes of the sessions on one computer. The first state
 * after a connection only sets the start values, so it posts nothing. The
 * core lock guards it.
 */
class OpenChamberTracker {
    private val last = LinkedHashMap<String, AgentStatus>()
    private var fresh = true

    /** Makes the next state set the start values. Call it when the computer connects. */
    fun restart() {
        fresh = true
    }

    /** Takes a new session list and returns the notification changes. */
    fun update(sessions: List<OpenChamberSession>): List<SessionAlert> {
        val out = ArrayList<SessionAlert>()
        val seen = HashSet<String>()
        for (s in sessions) {
            seen += s.id
            val prev = last[s.id]
            // An unknown status gives no information. The last known status stays.
            if (s.status == AgentStatus.Unknown) continue
            last[s.id] = s.status
            if (fresh) {
                if (s.status == AgentStatus.Working) out += SessionAlert.Clear(s.id)
                continue
            }
            when {
                prev == s.status -> Unit
                s.status == AgentStatus.Blocked -> if (prev != null) out += SessionAlert.NeedsInput(s)
                s.status.ready && prev == AgentStatus.Working -> out += SessionAlert.Finished(s)
                prev != null -> out += SessionAlert.Clear(s.id)
            }
        }
        val gone = last.keys.filter { it !in seen }
        for (id in gone) {
            last.remove(id)
            out += SessionAlert.Clear(id)
        }
        fresh = false
        return out
    }
}
