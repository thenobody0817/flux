package org.omarchy.flux.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.omarchy.flux.core.DeviceUi
import org.omarchy.flux.core.Eyec
import org.omarchy.flux.core.EyecEntry
import org.omarchy.flux.core.FluxCore

/** The curated actions, matching the allowlist in fluxd. */
private val eyecMenu = listOf(
    "status" to "Status",
    "last" to "Last answer",
    "dock.toggle" to "Toggle dock",
    "dock.show" to "Show dock",
    "dock.hide" to "Hide dock",
    "shutter.on" to "Shutter on",
    "shutter.off" to "Shutter off",
    "redact.on" to "Redact on",
    "redact.off" to "Redact off",
    "yolo.on" to "YOLO on",
    "yolo.off" to "YOLO off",
)

/**
 * The eyec chat: ask the assistant on the computer, see the answer and its
 * choices, and run curated actions.
 */
@Composable
fun EyecScreen(d: DeviceUi, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val entries by Eyec.chat.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("Ask eyec", onBack, subtitle = "On ${d.name}")
        if (!d.online) {
            Text(
                "${d.name} is not reachable. The chat needs a connection.",
                Modifier.padding(horizontal = Gutter, vertical = 16.dp),
                color = scheme.onSurfaceVariant,
            )
            return@Column
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = Gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries) { e -> EyecBubble(e) { choice -> Eyec.ask(FluxCore, d.id, choice) } }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(onClick = { Eyec.peek(FluxCore, d.id, "") }, label = { Text("Peek screen") })
            for ((id, label) in eyecMenu) {
                AssistChip(onClick = { Eyec.trigger(FluxCore, d.id, id) }, label = { Text(label) })
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask eyec…") },
                maxLines = 4,
            )
            FilledIconButton(
                onClick = { Eyec.ask(FluxCore, d.id, input); input = "" },
                enabled = input.isNotBlank(),
            ) { Sym(Ic.send, "Send") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EyecBubble(e: EyecEntry, onChoice: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bitmap = remember(e.image) {
        e.image?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Column(Modifier.fillMaxWidth()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Screen from ${e.text}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (e.mine) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (e.mine) scheme.primaryContainer else scheme.surfaceContainerHighest,
                contentColor = if (e.mine) scheme.onPrimaryContainer else scheme.onSurface,
            ) {
                if (e.pending) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("thinking…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(
                        e.text,
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (e.choices.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (c in e.choices) AssistChip(onClick = { onChoice(c) }, label = { Text(c) })
            }
        }
    }
}
