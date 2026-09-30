package org.omarchy.flux.core

import android.util.Log
import kotlinx.serialization.json.JsonObject
import org.omarchy.flux.protocol.Packet
import org.omarchy.flux.protocol.Types
import org.omarchy.flux.protocol.bodyOf
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val TAG = "FluxOpenChamber"

/** How long a finished session must stay ready before the phone posts it. Status can flap between tool calls. */
private const val FINISH_HOLD_MS = 2_000L

/** How long a read waits for the messages. */
private const val READ_TIMEOUT_MS = 15_000L

/** How long a new session waits for the answer of the computer. */
private const val CREATE_TIMEOUT_MS = 30_000L

/** How long a reply waits for the answer of the computer. */
private const val REPLY_TIMEOUT_MS = 10_000L

/** How long the phone waits after a reply before it reads the messages again. */
private const val REREAD_DELAY_MS = 700L

/**
 * Shows the OpenChamber sessions of a computer with flux.openchamber. The
 * computer sends the session list and their status, and this phone asks for
 * the recent messages of a session. When the computer allows it, the phone
 * also sends prompts, answers the questions and the permissions of a
 * session, stops it, starts a session, and closes it. The UI asks for the
 * phone lock before the first reply, see [org.omarchy.flux.ui.ReplyLock].
 */
object OpenChamberSync {
    /**
     * The finished notifications that wait for [FINISH_HOLD_MS], by device
     * ID and session. Only the thread of [FluxCore.scheduler] uses it.
     */
    private val pending = HashMap<String, ScheduledFuture<*>>()

    /** Counts the reads, so that a late timeout does not replace a newer read. The core lock guards it. */
    private var reads = 0L

    /** Counts the replies, so that a late timeout does not replace a newer reply. The core lock guards it. */
    private var replies = 0L

    /** Counts the creates and closes, so that a late timeout does not replace a newer one. The core lock guards it. */
    private var actions = 0L

    private fun key(deviceId: String, session: String) = "$deviceId|oc|$session"

    /** Makes the next session list set the start values. The core lock is held. */
    fun onConnected(d: Device) {
        d.openChamberTracker.restart()
    }

    /** Handles flux.openchamber from a computer. The core lock is held. */
    fun onPacket(core: FluxCore, d: Device, p: Packet) {
        when (p.string("kind")) {
            "state" -> {
                val state = parseOpenChamberState(p.body) ?: return
                d.openChamber = state
                val alerts = d.openChamberTracker.update(state.sessions)
                if (alerts.isNotEmpty()) {
                    val name = d.identity.deviceName
                    val id = d.id
                    // The scheduler has 1 thread, so the alerts keep their order.
                    core.scheduler.execute { alert(core, id, name, alerts) }
                }
            }
            "output" -> onOutput(d, parseOpenChamberOutput(p.body) ?: return)
            "sent" -> {
                val sent = parseOpenChamberSent(p.body) ?: return
                val reply = d.openChamberReply
                if (reply == null || reply.session != sent.session || !reply.sending) return
                d.openChamberReply = reply.copy(sending = false, error = sent.error)
                // An answer changes the messages, so the screen reads them again.
                if (sent.error == null && (sent.action == "prompt" || sent.action == "form" || sent.action == "permission")) {
                    val id = d.id
                    core.scheduler.schedule({ read(core, id, sent.session) }, REREAD_DELAY_MS, TimeUnit.MILLISECONDS)
                }
            }
            "created", "closed" -> {
                val done = parseOpenChamberDone(p.body) ?: return
                val action = d.openChamberAction
                if (action == null || action.action != done.action || !action.sending) return
                if (done.action == "close" && action.session != done.session) return
                d.openChamberAction = action.copy(sending = false, session = done.session ?: action.session, error = done.error)
            }
            else -> Log.d(TAG, "ignored flux.openchamber kind ${p.string("kind")}")
        }
    }

    /**
     * Keeps the messages of the session that [parseOpenChamberOutput] read.
     * The core lock is held. The core parses the output before it takes the
     * lock.
     */
    fun onOutput(d: Device, out: OpenChamberOutput) {
        // Only the session on screen keeps its messages.
        if (d.openChamberOutput?.session == out.session) d.openChamberOutput = out
    }

    /** Asks the computer for its session list now. */
    fun request(core: FluxCore, id: String) {
        core.device(id)?.send(Packet(Types.FLUX_OPENCHAMBER, bodyOf("kind" to "request")))
    }

    /**
     * Asks the computer for the recent messages of [session]. The messages
     * of the last read stay on screen until the answer comes. The output
     * mode of the settings decides whether the computer sends plain lines
     * or the rich entries.
     */
    fun read(core: FluxCore, id: String, session: String) {
        val token = core.locked {
            val d = core.device(id) ?: return@locked null
            val rich = core.settings.openChamberRich
            val old = d.openChamberOutput?.takeIf { it.session == session }
            d.openChamberOutput = (old ?: OpenChamberOutput(session)).copy(loading = true, error = null, rich = rich)
            val sent = d.send(
                Packet(
                    Types.FLUX_OPENCHAMBER,
                    bodyOf(
                        "kind" to "read", "session" to session,
                        "messages" to OPENCHAMBER_READ_MESSAGES,
                        "format" to if (rich) "rich" else "plain",
                    ),
                ),
            )
            if (!sent) {
                d.openChamberOutput = d.openChamberOutput?.copy(loading = false, error = "${d.identity.deviceName} is not reachable")
                return@locked null
            }
            ++reads
        } ?: return
        core.scheduler.schedule({
            core.locked {
                val d = core.device(id) ?: return@locked
                val out = d.openChamberOutput
                if (token == reads && out != null && out.session == session && out.loading) {
                    d.openChamberOutput = out.copy(loading = false, error = "${d.identity.deviceName} did not answer")
                }
            }
        }, READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Forgets the messages of a session when the session screen closes. */
    fun closeOutput(core: FluxCore, id: String, session: String) {
        core.locked {
            val d = core.device(id) ?: return@locked
            if (d.openChamberOutput?.session == session) d.openChamberOutput = null
            if (d.openChamberReply?.session == session) d.openChamberReply = null
        }
    }

    /** Sends [text] to [session] as a new prompt. */
    fun sendPrompt(core: FluxCore, id: String, session: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (t.toByteArray(Charsets.UTF_8).size > OPENCHAMBER_MAX_PROMPT) {
            core.locked {
                val d = core.device(id) ?: return@locked
                d.openChamberReply = OpenChamberReply(session, "prompt", ++replies, sending = false, error = "The text is too long. The limit is 16 KB.")
            }
            return
        }
        reply(core, id, session, "prompt", bodyOf("kind" to "prompt", "session" to session, "text" to t))
    }

    /** Stops the run of [session]. The session stays in the list. */
    fun interrupt(core: FluxCore, id: String, session: String) {
        reply(core, id, session, "interrupt", bodyOf("kind" to "interrupt", "session" to session))
    }

    /** Answers the question [form] of [session] with the [answer] object of field values. */
    fun answerForm(core: FluxCore, id: String, session: String, form: String, answer: JsonObject) {
        reply(core, id, session, "form", bodyOf("kind" to "form", "session" to session, "form" to form, "answer" to answer))
    }

    /** Answers the permission [permission] of [session]. [decision] is "allow" or "deny". */
    fun answerPermission(core: FluxCore, id: String, session: String, permission: String, decision: String) {
        if (decision != "allow" && decision != "deny") return
        reply(core, id, session, "permission", bodyOf("kind" to "permission", "session" to session, "permission" to permission, "decision" to decision))
    }

    /** Asks the computer to start a session of [kind] in [cwd]. */
    fun create(core: FluxCore, id: String, kind: String, cwd: String, title: String) {
        action(
            core, id, OpenChamberAction("create", 0), CREATE_TIMEOUT_MS,
            bodyOf("kind" to "create", "agent" to kind, "cwd" to cwd.trim(), "title" to title.trim()),
        )
    }

    /** Stops [session] on the computer and files it away. */
    fun close(core: FluxCore, id: String, session: String) {
        action(core, id, OpenChamberAction("close", 0, session = session), REPLY_TIMEOUT_MS, bodyOf("kind" to "close", "session" to session))
    }

    /** Forgets the last create or close, after the UI used its answer. */
    fun clearAction(core: FluxCore, id: String, seq: Long) {
        core.locked {
            val d = core.device(id) ?: return@locked
            if (d.openChamberAction?.seq == seq) d.openChamberAction = null
        }
    }

    private fun action(core: FluxCore, id: String, start: OpenChamberAction, timeout: Long, body: JsonObject) {
        val token = core.locked {
            val d = core.device(id) ?: return@locked null
            val seq = ++actions
            d.openChamberAction = start.copy(seq = seq)
            if (!d.send(Packet(Types.FLUX_OPENCHAMBER, body))) {
                d.openChamberAction = start.copy(seq = seq, sending = false, error = "${d.identity.deviceName} is not reachable")
                return@locked null
            }
            seq
        } ?: return
        core.scheduler.schedule({
            core.locked {
                val d = core.device(id) ?: return@locked
                val a = d.openChamberAction
                if (a != null && a.seq == token && a.sending) {
                    d.openChamberAction = a.copy(sending = false, error = "${d.identity.deviceName} did not answer")
                }
            }
        }, timeout, TimeUnit.MILLISECONDS)
    }

    private fun reply(core: FluxCore, id: String, session: String, action: String, body: JsonObject) {
        val token = core.locked {
            val d = core.device(id) ?: return@locked null
            val seq = ++replies
            d.openChamberReply = OpenChamberReply(session, action, seq)
            if (!d.send(Packet(Types.FLUX_OPENCHAMBER, body))) {
                d.openChamberReply = OpenChamberReply(session, action, seq, sending = false, error = "${d.identity.deviceName} is not reachable")
                return@locked null
            }
            seq
        } ?: return
        core.scheduler.schedule({
            core.locked {
                val d = core.device(id) ?: return@locked
                val r = d.openChamberReply
                if (r != null && r.seq == token && r.sending) {
                    d.openChamberReply = r.copy(sending = false, error = "${d.identity.deviceName} did not answer")
                }
            }
        }, REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Posts and removes the notifications for [alerts]. It runs on the scheduler thread. */
    private fun alert(core: FluxCore, id: String, computer: String, alerts: List<SessionAlert>) {
        for (a in alerts) {
            val k = key(id, a.id)
            pending.remove(k)?.cancel(false)
            when (a) {
                is SessionAlert.Clear -> Android.cancelSession(core.app, id, a.id)
                is SessionAlert.NeedsInput -> {
                    if (core.settings.agentInputAlerts) Android.showSession(core.app, id, computer, a.session)
                }
                is SessionAlert.Finished -> {
                    if (!core.settings.agentDoneAlerts) {
                        Android.cancelSession(core.app, id, a.id)
                        continue
                    }
                    val job = core.scheduler.schedule({ finish(core, id, computer, a.id, k) }, FINISH_HOLD_MS, TimeUnit.MILLISECONDS)
                    pending[k] = job
                }
            }
        }
    }

    /** Posts a finished notification when the session is still ready after the hold. It runs on the scheduler thread. */
    private fun finish(core: FluxCore, id: String, computer: String, session: String, k: String) {
        pending.remove(k)
        val s = core.locked { core.device(id)?.openChamber?.session(session) } ?: return
        if (!s.status.ready || !core.settings.agentDoneAlerts) return
        Android.showSession(core.app, id, computer, s)
    }
}
