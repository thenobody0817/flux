package org.omarchy.flux.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.omarchy.flux.core.DeviceUi
import org.omarchy.flux.core.FluxCore
import org.omarchy.flux.core.ThemeSync

/**
 * The theme picker: the installed Omarchy themes of the computer. The
 * active one is marked. A tap applies it on the computer, which sends the
 * new theme back, and the whole app follows it.
 */
@Composable
fun ThemeScreen(d: DeviceUi, onBack: () -> Unit) {
    val themes by ThemeSync.themes.collectAsState()
    val current by ThemeSync.name.collectAsState()
    var pending by remember { mutableStateOf<String?>(null) }
    // The computer answered with the new theme.
    LaunchedEffect(current) { pending = null }
    LaunchedEffect(d.id) { ThemeSync.requestList(FluxCore, d.id) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = TiledGutter)) {
        TiledTopBar("theme · ${d.name}", onBack)
        Tile(Modifier.fillMaxWidth().height(TileUnit2), border = activeBorder(), padding = PaddingValues(16.dp)) {
            TileLabel("Active on ${d.name}", color = Tn.blue)
            T(current.ifEmpty { "Unknown" }, size = 26, weight = FontWeight.SemiBold, letterSpacing = -0.5f, maxLines = 1)
            T("A tap applies the theme on ${d.name}. The phone follows the connected computer.", size = 12, color = Tn.sub, maxLines = 2)
        }
        SectionLabel("Installed themes")
        if (themes.isEmpty()) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Tn.blue)
                T("Asking ${d.name} for its themes", color = Tn.sub)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
            for (row in themes.chunked(2)) {
                TileRow(64.dp) {
                    for (name in row) {
                        val on = name == current
                        val busy = pending == name
                        Tile(
                            Modifier.weight(1f).fillMaxHeight(),
                            onClick = {
                                if (!on) {
                                    pending = name
                                    ThemeSync.setTheme(FluxCore, d.id, name)
                                }
                            },
                            container = if (on) Tn.tileHi else Tn.tile,
                            border = BorderStroke(1.dp, if (on) Tn.blue else Tn.line),
                            padding = PaddingValues(12.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Sym(if (on) Ic.checkCircle else Ic.tune, tint = if (on) Tn.blue else Tn.dim, size = 20.dp)
                                Spacer(Modifier.weight(1f))
                                if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Tn.blue)
                            }
                            T(name, size = 13, weight = FontWeight.SemiBold, maxLines = 2)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}
