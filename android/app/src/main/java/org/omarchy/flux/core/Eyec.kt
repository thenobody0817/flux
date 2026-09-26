package org.omarchy.flux.core

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.omarchy.flux.R
import org.omarchy.flux.protocol.Packet
import org.omarchy.flux.service.FluxService
import org.omarchy.flux.ui.EyecActivity

/**
 * The eyec permission prompts from the computers. The phone shows 1 request
 * at a time and answers allow, deny, or yolo.
 */
object Eyec {
    private const val TAG = "FluxEyec"
    private val main = Handler(Looper.getMainLooper())

    private val _current = MutableStateFlow<EyecRequest?>(null)

    /** The request that the Eyec screen shows, or null. */
    val current: StateFlow<EyecRequest?> = _current

    private val _chat = MutableStateFlow<List<EyecEntry>>(emptyList())

    /** The chat that the eyec screen shows. */
    val chat: StateFlow<List<EyecEntry>> = _chat
    private var nextId = 0

    /** Handles flux.eyec from a paired computer. The core lock is held. */
    fun onPacket(core: FluxCore, d: Device, p: Packet) {
        when (p.string("kind")) {
            "cancel" -> {
                val id = p.string("id")
                if (id != null && _current.value?.id == id) main.post { clear(core.app, id) }
            }
            "permit" -> receive(core, d, p)
            "answer" -> answer(core, p)
            "trigger" -> triggerResult(p)
        }
    }

    /** Sends a question to eyec and adds it to the chat. */
    fun ask(core: FluxCore, deviceId: String, prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty()) return
        val d = core.device(deviceId) ?: return
        _chat.value = _chat.value +
            EyecEntry(mine = true, text = text) +
            EyecEntry(mine = false, text = "", pending = true)
        d.send(EyecMessage.ask("ask" + (++nextId), text))
    }

    /** Asks eyec to look at the whole screen and explain it. */
    fun peek(core: FluxCore, deviceId: String, prompt: String) {
        val d = core.device(deviceId) ?: return
        val text = prompt.trim()
        _chat.value = _chat.value +
            EyecEntry(mine = true, text = text.ifEmpty { "Peek at my screen" }) +
            EyecEntry(mine = false, text = "", pending = true)
        d.send(EyecMessage.peek("peek" + (++nextId), text))
    }

    /** Asks eyec to run one of the curated actions. */
    fun trigger(core: FluxCore, deviceId: String, action: String) {
        val d = core.device(deviceId) ?: return
        d.send(EyecMessage.trigger("act" + (++nextId), action))
    }

    fun clearChat() {
        _chat.value = emptyList()
    }

    // The chat runs on the packet thread, so the state flow is updated there.
    private fun answer(core: FluxCore, p: Packet) {
        val a = EyecMessage.parseAnswer(p) ?: return
        val text = a.error?.let { "\u26a0 $it" } ?: a.text
        val image = a.image?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
        val list = _chat.value.toMutableList()
        val idx = list.indexOfLast { !it.mine && it.pending }
        val entry = EyecEntry(mine = false, text = text, choices = a.choices, image = image, ocr = a.ocr)
        if (idx >= 0) list[idx] = entry else list.add(entry)
        _chat.value = list
    }

    private fun triggerResult(p: Packet) {
        val t = EyecMessage.parseTrigger(p) ?: return
        val text = (if (t.ok) "\u2713 " else "\u26a0 ") + t.detail.ifEmpty { t.id }
        _chat.value = _chat.value + EyecEntry(mine = false, text = text)
    }

    private fun receive(core: FluxCore, d: Device, p: Packet) {
        val id = p.string("id") ?: return
        val r = EyecMessage.parse(p, d.id, d.identity.deviceName)
        if (r == null) {
            Log.i(TAG, "refused a request from ${d.identity.deviceName}: the request is not valid")
            return
        }
        if (_current.value != null && _current.value?.id != r.id) {
            Log.i(TAG, "refused a request from ${d.identity.deviceName}: another request is open")
            return
        }
        _current.value = r
        main.post {
            show(core.app, r)
            // The computer stops waiting at its timeout, so the screen closes too.
            main.postDelayed({ clear(core.app, r.id) }, r.timeoutSeconds * 1000L)
        }
    }

    /** Sends the answer of the phone for the current request. */
    fun answer(core: FluxCore, r: EyecRequest, decision: String): Boolean {
        val d = core.device(r.computerId) ?: return false
        val ok = d.send(EyecMessage.answer(r.id, decision))
        clear(core.app, r.id)
        return ok
    }

    fun deny(core: FluxCore, r: EyecRequest? = _current.value) {
        val req = r ?: return
        answer(core, req, "deny")
    }

    /** Closes the request [id], and its notification. */
    fun clear(context: Context, id: String) {
        if (_current.value?.id != id) return
        _current.value = null
        NotificationManagerCompat.from(context).cancel(Android.ID_EYEC)
    }

    @SuppressLint("MissingPermission")
    private fun show(context: Context, r: EyecRequest) {
        val open = PendingIntent.getActivity(
            context, 20, Intent(context, EyecActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deny = PendingIntent.getService(
            context, 21, Intent(context, FluxService::class.java).setAction(FluxService.ACTION_EYEC_DENY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Android.CHANNEL_EYEC)
            .setSmallIcon(R.drawable.ic_stat_flux)
            .setContentTitle("eyec: " + r.title.ifEmpty { "Permission needed" })
            .setContentText(r.pattern.ifEmpty { EyecMessage.question(r) })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setOngoing(true)
            .setTimeoutAfter(r.timeoutSeconds * 1000L)
            .addAction(0, "Deny", deny)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(Android.ID_EYEC, n) }
        if (FluxCore.foreground) {
            runCatching { context.startActivity(Intent(context, EyecActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

/** One line of the eyec chat. */
data class EyecEntry(
    val mine: Boolean,
    val text: String,
    val pending: Boolean = false,
    val choices: List<String> = emptyList(),
    val image: ByteArray? = null,
    val ocr: String? = null,
)
