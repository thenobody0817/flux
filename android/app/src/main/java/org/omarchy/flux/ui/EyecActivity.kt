package org.omarchy.flux.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.omarchy.flux.core.Eyec
import org.omarchy.flux.core.EyecMessage
import org.omarchy.flux.core.EyecRequest
import org.omarchy.flux.core.FluxCore

/**
 * The eyec screen: 1 permission prompt from a computer, with Allow, Deny,
 * and YOLO. It shows over the lock screen, like the ring screen.
 */
class EyecActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FluxCore.init(this)
        setContent { TiledTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val current by Eyec.current.collectAsStateWithLifecycle()
        val shown = track(current)
        // The request ends: answered, cancelled by the computer, or timed out.
        LaunchedEffect(current) { if (current == null) finish() }
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            shown?.let { AskView(it) }
        }
    }

    // The request that the screen shows. It stays while the flow updates.
    private var last: EyecRequest? = null

    private fun track(r: EyecRequest?): EyecRequest? {
        if (r != null) last = r
        return last
    }

    @Composable
    private fun AskView(r: EyecRequest) {
        val scheme = MaterialTheme.colorScheme
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Ic.terminal, size = 80.dp)
            Spacer(Modifier.height(4.dp))
            Text(EyecMessage.question(r), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (r.pattern.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(12.dp), color = scheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        r.pattern,
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                "eyec asks on ${r.computerName}. YOLO allows every later prompt without asking.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { decide(r, "deny") }) { Text("Deny") }
                OutlinedButton(onClick = { decide(r, "yolo") }) { Text("YOLO") }
                Button(onClick = { decide(r, "allow") }) { Text("Allow") }
            }
        }
    }

    private fun decide(r: EyecRequest, decision: String) {
        Eyec.answer(FluxCore, r, decision)
        finish()
    }
}
