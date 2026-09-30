package org.omarchy.flux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.omarchy.flux.core.AgentStatus
import org.omarchy.flux.core.DebugDemo
import org.omarchy.flux.core.DeviceUi
import org.omarchy.flux.core.Entry
import org.omarchy.flux.core.FluxCore
import org.omarchy.flux.core.FormField
import org.omarchy.flux.core.OpenChamberOutput
import org.omarchy.flux.core.OpenChamberReply
import org.omarchy.flux.core.OpenChamberSession
import org.omarchy.flux.core.OpenChamberSync
import org.omarchy.flux.core.Pending
import org.omarchy.flux.core.formAnswer
import org.omarchy.flux.voice.Dictation
import org.omarchy.flux.voice.DictationBar
import org.omarchy.flux.voice.DictationText
import org.omarchy.flux.voice.rememberDictation

/** How often the session screen reads the messages again while the session works. */
private const val WORKING_REFRESH_MS = 5_000L

// ───────────────────────── Sessions ─────────────────────────

/**
 * The OpenChamber sessions of a computer. The sessions that need input come
 * first. A tap opens the recent messages of the session. When the computer
 * allows control, the add button starts a new session.
 */
@Composable
fun TiledSessionsScreen(d: DeviceUi, onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit = {}) {
    LaunchedEffect(d.id, d.online) { if (d.online) OpenChamberSync.request(FluxCore, d.id) }
    val state = d.openChamber
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = TiledGutter)) {
        TiledTopBar("OpenChamber · ${d.name}", onBack) {
            if (d.online && state?.running == true && state.control) SquareButton(Ic.add, "New session", onNew)
            if (d.online) SquareButton(Ic.refresh, "Refresh", { OpenChamberSync.request(FluxCore, d.id) })
        }
        when {
            !d.online -> NotReachable(d, "The sessions")
            state == null -> Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Tn.magenta)
                T("Loading the sessions of ${d.name}", color = Tn.sub)
            }
            !state.enabled -> EmptyState(
                Ic.agent,
                "OpenChamber is off",
                "On ${d.name}, set openchamber = true in ~/.config/flux/config.toml. Then run systemctl --user reload fluxd.",
                Modifier.padding(top = 48.dp),
            )
            !state.running -> EmptyState(
                Ic.agent,
                "OpenChamber is not running",
                "Start OpenChamber on ${d.name}. Its sessions show here.",
                Modifier.padding(top = 48.dp),
            )
            state.sessions.isEmpty() -> EmptyState(
                Ic.agent,
                "No sessions yet",
                if (state.control) {
                    "Select + to start a session on ${d.name}, or start one in OpenChamber there."
                } else {
                    "Start a session in OpenChamber on ${d.name}. It shows here."
                },
                Modifier.padding(top = 48.dp),
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
                for (s in state.sorted) SessionTile(s) { onOpen(s.id) }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun SessionTile(s: OpenChamberSession, onClick: () -> Unit) {
    val blocked = s.status == AgentStatus.Blocked
    Tile(
        Modifier.fillMaxWidth().height(96.dp), onClick,
        accent = statusColor(s.status),
        container = if (blocked) Tn.tileHi else Tn.tile,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (blocked) Tn.red else Tn.line),
        padding = PaddingValues(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusLine(s.status)
            Spacer(Modifier.weight(1f))
            T(s.model.ifEmpty { s.agent }, size = 10, color = Tn.dim, family = Mono, maxLines = 1)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            T(s.project.ifEmpty { s.agent }, size = 15, weight = FontWeight.SemiBold, maxLines = 1)
            T(s.title.ifEmpty { s.id }, size = 11, color = Tn.sub, maxLines = 1)
        }
    }
}

// ───────────────────────── One session ─────────────────────────

/**
 * The recent messages of one OpenChamber session, with the newest at the
 * bottom, and the reply controls. The screen reads the messages again when
 * the status changes, and every few seconds while the session works and the
 * screen is visible.
 */
@Composable
fun TiledSessionScreen(d: DeviceUi, session: String, onBack: () -> Unit) {
    val oc = d.openChamber
    val s = oc?.session(session)
    val status = s?.status
    val demo = DebugDemo.isDemo(d.id)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(d.id, session, d.online, status) {
        if (!d.online || demo) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            OpenChamberSync.read(FluxCore, d.id, session)
            while (status == AgentStatus.Working) {
                delay(WORKING_REFRESH_MS)
                OpenChamberSync.read(FluxCore, d.id, session)
            }
        }
    }
    DisposableEffect(d.id, session) { onDispose { OpenChamberSync.closeOutput(FluxCore, d.id, session) } }

    val out = d.openChamberOutput?.takeIf { it.session == session }
    val closer = rememberSessionCloser(d, session, onBack)
    val label = if (s != null) "${s.agent} · ${s.project.ifEmpty { session }}" else session
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = TiledGutter)) {
        TiledTopBar(label, onBack) {
            if (out?.loading == true) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Tn.magenta)
                }
            } else if (d.online && s != null && !demo) {
                SquareButton(Ic.refresh, "Refresh", { OpenChamberSync.read(FluxCore, d.id, session) })
            }
        }
        when {
            !d.online -> NotReachable(d, "The messages")
            s == null && oc != null -> EmptyState(
                Ic.agent,
                "The session is gone",
                "The session $session on ${d.name} was closed.",
                Modifier.padding(top = 48.dp),
            )
            else -> {
                if (s != null) SessionHeader(s, closer)
                Spacer(Modifier.height(TileGap))
                SessionOutput(out, Modifier.weight(1f))
                if (s != null) {
                    Spacer(Modifier.height(TileGap))
                    if (oc.control) {
                        SessionReplies(d, s, out, d.openChamberReply?.takeIf { it.session == session })
                    } else {
                        T(
                            "To answer from this phone, set openchamber_control = true on ${d.name}.",
                            Modifier.padding(horizontal = 4.dp), size = 11, color = Tn.dim,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    closer.Dialog()
}

@Composable
private fun SessionHeader(s: OpenChamberSession, closer: SessionCloser) {
    Tile(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusLine(s.status)
            Spacer(Modifier.weight(1f))
            if (s.model.isNotEmpty()) T(s.model, size = 10, color = Tn.dim, family = Mono, maxLines = 1)
            closer.Button()
        }
        if (s.title.isNotEmpty()) T(s.title, size = 13, weight = FontWeight.SemiBold, maxLines = 2)
        closer.error?.let { T(it, size = 11, color = Tn.red) }
    }
}

/** How close to the end the messages must be, in pixels, to follow new ones. */
private const val FOLLOW_SLACK_PX = 48

/**
 * The messages of a session. The rich mode draws a card per text, reasoning,
 * and tool entry; the plain mode shows the lines that the computer
 * rendered. The view follows new messages at the end and stays where the
 * user scrolled.
 */
@Composable
private fun SessionOutput(out: OpenChamberOutput?, modifier: Modifier) {
    val scroll = rememberScrollState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(scroll) {
        androidx.compose.runtime.snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            if (!moving) follow = scroll.value >= scroll.maxValue - FOLLOW_SLACK_PX
        }
    }
    LaunchedEffect(out?.entries, out?.plain) {
        if (!follow) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { scroll.maxValue }.first { it > 0 && it < Int.MAX_VALUE }
        scroll.scrollTo(scroll.maxValue)
    }
    Box(modifier.fillMaxWidth()) {
        when {
            out == null || (out.loading && out.entries.isEmpty() && out.plain.isEmpty()) -> Row(
                Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Tn.magenta)
                T("Reading the messages", color = Tn.sub)
            }
            out.error != null && out.entries.isEmpty() && out.plain.isEmpty() ->
                EmptyState(Ic.error, "No messages", out.error, Modifier.padding(top = 32.dp))
            else -> Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(TileGap)) {
                if (out.truncated) T("Older messages are cut.", Modifier.padding(horizontal = 4.dp), size = 10, color = Tn.dim, family = Mono)
                out.error?.let { T(it, Modifier.padding(horizontal = 4.dp), size = 11, color = Tn.red) }
                if (out.rich) {
                    if (out.entries.isEmpty()) T("No messages yet.", Modifier.padding(4.dp), size = 11, color = Tn.dim, family = Mono)
                    for (e in out.entries) EntryCard(e)
                } else {
                    PlainMessages(out.plain)
                }
            }
        }
    }
}

@Composable
private fun PlainMessages(text: String) {
    if (text.isBlank()) {
        T("No messages yet.", Modifier.padding(4.dp), size = 11, color = Tn.dim, family = Mono)
        return
    }
    Box(Modifier.fillMaxWidth().clip(TileShape).background(TermBg).border(1.dp, Tn.line, TileShape)) {
        SelectionContainer {
            T(text, Modifier.fillMaxWidth().padding(TermPad), size = 11, color = Tn.text, family = Mono, lineHeight = 1.35f)
        }
    }
}

@Composable
private fun EntryCard(e: Entry) {
    when (e) {
        is Entry.User -> Tile(
            Modifier.fillMaxWidth(), accent = Tn.blue, container = Tn.tileHi,
            padding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TileLabel("you", color = Tn.blue)
            T(e.text, size = 13)
        }
        is Entry.Assistant -> T(e.name, Modifier.padding(start = 4.dp, top = 4.dp), size = 11, color = Tn.dim, weight = FontWeight.SemiBold, family = Mono)
        is Entry.Text -> Tile(Modifier.fillMaxWidth(), accent = Tn.blue, padding = PaddingValues(12.dp)) {
            T(e.text, size = 13)
        }
        is Entry.Reasoning -> Tile(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
            TileLabel("thinking")
            Spacer(Modifier.height(4.dp))
            T(e.text, size = 11, color = Tn.dim, lineHeight = 1.3f)
        }
        is Entry.Tool -> Tile(
            Modifier.fillMaxWidth(), accent = Tn.green,
            padding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TileLabel(e.name, color = if (e.error.isNotEmpty()) Tn.red else Tn.green)
                Spacer(Modifier.weight(1f))
                if (e.status.isNotEmpty()) T(e.status, size = 10, color = Tn.dim, family = Mono)
            }
            if (e.input.isNotEmpty()) T(e.input, size = 11, color = Tn.sub, family = Mono, maxLines = 3)
            if (e.error.isNotEmpty()) T(e.error, size = 11, color = Tn.red, family = Mono, maxLines = 6)
            if (e.output.isNotEmpty()) T(e.output, size = 11, color = Tn.text, family = Mono, maxLines = 12, lineHeight = 1.3f)
        }
    }
}

// ───────────────────────── Close ─────────────────────────

/**
 * The close action of a session: a Close key, a confirmation dialog, and the
 * phone lock. The screen goes back when the computer closed the session.
 */
class SessionCloser(
    private val onAsk: () -> Unit,
    val error: String?,
) {
    @Composable
    fun Button() {
        T(
            "Close",
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Close the session", onClick = onAsk)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            size = 12, color = Tn.red, weight = FontWeight.SemiBold,
        )
    }

    @Composable
    fun Dialog() = Unit
}

@Composable
private fun rememberSessionCloser(d: DeviceUi, session: String, onClosed: () -> Unit): SessionCloser {
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    var lockError by remember { mutableStateOf<String?>(null) }
    var after by rememberSaveable(d.id, session) { mutableLongStateOf(Long.MAX_VALUE) }
    val action = d.openChamberAction?.takeIf { it.action == "close" && it.seq > after && it.session == session }
    LaunchedEffect(action) {
        if (action != null && !action.sending && action.error == null) {
            OpenChamberSync.clearAction(FluxCore, d.id, action.seq)
            onClosed()
        }
    }
    val lastSeq = d.openChamberAction?.seq ?: 0L
    if (asking) {
        ConfirmDialog(
            "Close the session?", "OpenChamber stops the session $session on ${d.name} and files it away.",
            "Close",
            onCancel = { asking = false },
            onConfirm = {
                asking = false
                ReplyLock.run(
                    context,
                    {
                        after = lastSeq
                        OpenChamberSync.close(FluxCore, d.id, session)
                    },
                    title = "Close a session",
                    purpose = "close sessions",
                ) { lockError = it }
            },
            destructive = true,
        )
    }
    return SessionCloser(onAsk = { lockError = null; asking = true }, error = lockError ?: action?.error)
}

// ───────────────────────── Replies ─────────────────────────

/** The highest part of the screen that the pending cards can take. More cards scroll. */
private val PendingMaxHeight = 300.dp

/**
 * The reply controls of a session: the questions and permissions that wait
 * for an answer, a text field, a mic key for dictation, and a stop button.
 * Each reply asks for the phone lock first, see [ReplyLock].
 */
@Composable
private fun SessionReplies(d: DeviceUi, s: OpenChamberSession, out: OpenChamberOutput?, reply: OpenChamberReply?) {
    val context = LocalContext.current
    var field by rememberSaveable(d.id, s.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var lockError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reply) {
        if (reply != null && reply.action == "prompt" && !reply.sending && reply.error == null) field = TextFieldValue()
    }
    fun guarded(action: () -> Unit) {
        lockError = null
        ReplyLock.run(context, action, title = "Answer a session", purpose = "answer sessions") { lockError = it }
    }

    val dictation = rememberDictation()
    val demo = DebugDemo.isDemo(d.id)
    val canDictate = demo || remember { Dictation.available(context) }
    val dictating = dictation.phase != Dictation.Phase.Idle
    var voiceError by remember { mutableStateOf<String?>(null) }
    fun dictate(): Boolean {
        voiceError = null
        lockError = null
        if (!demo && !canDictate) {
            voiceError = "This phone has no speech recognizer"
            return false
        }
        val hints = listOf(s.agent, s.project).filter { it.isNotBlank() }.distinct()
        return dictation.start(hints, demo) { spoken ->
            val e = DictationText.insert(field.text, field.selection.start, field.selection.end, spoken)
            field = TextFieldValue(e.text, TextRange(e.cursor))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
        val pending = out?.pending.orEmpty()
        if (pending.isNotEmpty()) {
            Column(
                Modifier.heightIn(max = PendingMaxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TileGap),
            ) {
                for (p in pending) PendingCard(d.id, s.id, p) { answer -> guarded(answer) }
            }
        }
        val sendingPrompt = reply?.sending == true && reply.action == "prompt"
        DictationBar(
            dictation,
            canDictate = canDictate,
            onStart = { dictate() },
            onLanguage = { dictation.stopNow() },
            field = { m ->
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    modifier = m,
                    placeholder = { T("Write to ${s.agent}", color = Tn.dim) },
                    textStyle = TextStyle(color = Tn.text, fontSize = 14.sp),
                    shape = TileShape,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            },
            send = {
                val canSend = field.text.isNotBlank() && !sendingPrompt
                Box(
                    Modifier.size(56.dp).clip(TileShape).background(if (canSend) Tn.blue else Tn.tile)
                        .clickable(enabled = canSend, onClickLabel = "Send") {
                            val t = field.text
                            guarded { OpenChamberSync.sendPrompt(FluxCore, d.id, s.id, t) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (sendingPrompt) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Tn.magenta)
                    } else {
                        Sym(Ic.send, "Send", tint = if (canSend) Tn.onAccent else Tn.dim, size = 22.dp)
                    }
                }
            },
        )
        if (s.status == AgentStatus.Working && reply?.action != "interrupt") {
            Box(
                Modifier.fillMaxWidth().height(40.dp).clip(TileShape).background(Tn.tile)
                    .border(1.dp, Tn.red, TileShape)
                    .clickable(onClickLabel = "Stop the session") { guarded { OpenChamberSync.interrupt(FluxCore, d.id, s.id) } },
                contentAlignment = Alignment.Center,
            ) {
                T("Stop the run", size = 12, color = Tn.red, weight = FontWeight.SemiBold)
            }
        }
        val problem = lockError ?: voiceError ?: dictation.error ?: reply?.error
        if (problem != null) T(problem, Modifier.padding(horizontal = 4.dp), size = 11, color = Tn.red)
    }
}

/** One question or permission that waits for an answer. */
@Composable
private fun PendingCard(deviceId: String, sessionId: String, p: Pending, onAnswer: (() -> Unit) -> Unit) {
    when (p) {
        is Pending.Permission -> Tile(
            Modifier.fillMaxWidth(), accent = Tn.orange, container = Tn.tileHi,
            padding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TileLabel("permission", color = Tn.orange)
            T(p.action.ifEmpty { "An action" }, size = 13, weight = FontWeight.SemiBold)
            if (p.resources.isNotEmpty()) T(p.resources.joinToString("\n"), size = 11, color = Tn.sub, family = Mono, maxLines = 5)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                SessionButton("Allow", Tn.green, Modifier.weight(1f)) {
                    onAnswer { OpenChamberSync.answerPermission(FluxCore, deviceId, sessionId, p.id, "allow") }
                }
                SessionButton("Deny", Tn.red, Modifier.weight(1f)) {
                    onAnswer { OpenChamberSync.answerPermission(FluxCore, deviceId, sessionId, p.id, "deny") }
                }
            }
        }
        is Pending.Form -> FormCard(deviceId, sessionId, p, onAnswer)
    }
}

/** A question with its fields. The answer waits for the values of every required field. */
@Composable
private fun FormCard(deviceId: String, sessionId: String, form: Pending.Form, onAnswer: (() -> Unit) -> Unit) {
    var values by remember(form.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    fun set(key: String, value: String) { values = values + (key to value) }
    val ready = form.fields.none { it.required && !it.options.isNotEmpty() && values[it.key].isNullOrBlank() }
    Tile(
        Modifier.fillMaxWidth(), accent = Tn.magenta, container = Tn.tileHi,
        padding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TileLabel("question", color = Tn.magenta)
        if (form.title.isNotEmpty()) T(form.title, size = 13, weight = FontWeight.SemiBold)
        for (f in form.fields) FieldInput(f, values[f.key].orEmpty()) { set(f.key, it) }
        SessionButton("Answer", Tn.magenta, Modifier.fillMaxWidth(), enabled = ready) {
            onAnswer { OpenChamberSync.answerForm(FluxCore, deviceId, sessionId, form.id, formAnswer(form.fields, values)) }
        }
    }
}

/** One input of a question: choices, a yes or no, an acknowledgement, or free text. */
@Composable
private fun FieldInput(f: FormField, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (f.label.isNotEmpty()) T(f.label, size = 12, color = Tn.sub)
        when {
            f.type == "external" -> SessionButton("Acknowledge", Tn.blue, Modifier.fillMaxWidth(), enabled = value != "true") { onChange("true") }
            f.options.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (o in f.options) {
                    val selected = value == o.value
                    Tile(
                        Modifier.fillMaxWidth(), { onChange(o.value) },
                        accent = Tn.blue,
                        container = if (selected) Tn.tileHi else Tn.tile,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Tn.blue else Tn.line),
                        padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        T(o.label, size = 13, maxLines = 2)
                    }
                }
            }
            f.type == "boolean" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                SessionButton("Yes", Tn.green, Modifier.weight(1f), enabled = value != "true") { onChange("true") }
                SessionButton("No", Tn.red, Modifier.weight(1f), enabled = value != "false") { onChange("false") }
            }
            else -> OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { T("Your answer", color = Tn.dim) },
                textStyle = TextStyle(color = Tn.text, fontSize = 14.sp),
                shape = TileShape,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
    }
}

/** A filled button of a pending card. */
@Composable
private fun SessionButton(label: String, accent: Color, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 38.dp).clip(TileShape).background(if (enabled) accent else Tn.tile)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        T(label, size = 12, color = if (enabled) Tn.onAccent else Tn.dim, weight = FontWeight.SemiBold)
    }
}

// ───────────────────────── New session ─────────────────────────

/**
 * Starts a new OpenChamber session on the computer: an agent, an optional
 * title, and a folder. The folder list holds the projects of OpenChamber.
 */
@Composable
fun TiledNewSessionScreen(d: DeviceUi, onBack: () -> Unit, onStarted: (String) -> Unit) {
    val state = d.openChamber
    val context = LocalContext.current
    var kind by rememberSaveable(d.id) { mutableStateOf("") }
    var folder by rememberSaveable(d.id) { mutableStateOf("") }
    var title by rememberSaveable(d.id) { mutableStateOf("") }
    var lockError by remember { mutableStateOf<String?>(null) }
    var after by rememberSaveable(d.id) { mutableLongStateOf(Long.MAX_VALUE) }
    val action = d.openChamberAction?.takeIf { it.action == "create" && it.seq > after }
    LaunchedEffect(action) {
        val session = action?.session
        if (action != null && !action.sending && action.error == null && session != null) {
            OpenChamberSync.clearAction(FluxCore, d.id, action.seq)
            onStarted(session)
        }
    }
    val kinds = state?.kinds.orEmpty()
    val selected = kind.ifEmpty { kinds.firstOrNull()?.id.orEmpty() }
    val starting = action?.sending == true

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = TiledGutter)) {
        TiledTopBar("New session · ${d.name}", onBack)
        when {
            !d.online -> NotReachable(d, "The agents")
            state == null -> Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Tn.magenta)
                T("Loading the agents of ${d.name}", color = Tn.sub)
            }
            !state.control -> EmptyState(
                Ic.agent,
                "Starting a session is off",
                "On ${d.name}, set openchamber_control = true in ~/.config/flux/config.toml. Then run systemctl --user reload fluxd.",
                Modifier.padding(top = 48.dp),
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
                SectionLabel("Run")
                Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
                    for (k in kinds) {
                        val on = k.id == selected
                        Tile(
                            Modifier.fillMaxWidth().height(56.dp), { kind = k.id },
                            accent = Tn.magenta,
                            container = if (on) Tn.tileHi else Tn.tile,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Tn.magenta else Tn.line),
                            padding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.Center,
                        ) {
                            T(k.name, size = 14, weight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
                SectionLabel("Folder")
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { T("~ for the home folder, or ~/Code/app", color = Tn.dim) },
                    textStyle = TextStyle(color = Tn.text, fontSize = 14.sp),
                    shape = TileShape,
                    maxLines = 2,
                )
                if (state.dirs.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (dir in state.dirs) {
                            Tile(
                                Modifier.fillMaxWidth().height(44.dp), { folder = dir },
                                accent = Tn.cyan,
                                container = if (folder == dir) Tn.tileHi else Tn.tile,
                                padding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.Center,
                            ) {
                                T(dir, size = 12, color = Tn.sub, family = Mono, maxLines = 1)
                            }
                        }
                    }
                }
                SectionLabel("Title")
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { T("Optional", color = Tn.dim) },
                    textStyle = TextStyle(color = Tn.text, fontSize = 14.sp),
                    shape = TileShape,
                    maxLines = 2,
                )
                val problem = lockError ?: action?.error
                if (problem != null) T(problem, Modifier.padding(horizontal = 4.dp), size = 11, color = Tn.red)
                SessionButton("Start", Tn.magenta, Modifier.fillMaxWidth().height(46.dp), enabled = selected.isNotEmpty() && !starting) {
                    ReplyLock.run(
                        context,
                        {
                            after = d.openChamberAction?.seq ?: 0L
                            OpenChamberSync.create(FluxCore, d.id, selected, folder, title)
                        },
                        title = "Start a session",
                        purpose = "start sessions",
                    ) { lockError = it }
                }
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}
