package org.omarchy.flux.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.omarchy.flux.core.FluxColors
import org.omarchy.flux.core.ThemeSync
import org.omarchy.flux.core.TokyoNight

/**
 * The Tiled design: Tokyo Night colors, 12 dp tiles with 8 dp gaps, and an
 * active-window gradient border like Hyprland on Omarchy.
 *
 * The colors follow the active Omarchy theme of the computer. [apply] takes
 * the palette that the phone received; [TiledTheme] calls it. The defaults
 * are Tokyo Night, so the app looks right before the first theme arrives.
 */
object Tn {
    var bg by mutableStateOf(Color(TokyoNight.bg)); private set
    var tile by mutableStateOf(Color(TokyoNight.tile)); private set
    var tileHi by mutableStateOf(Color(TokyoNight.tileHi)); private set
    var offTile by mutableStateOf(Color(TokyoNight.offTile)); private set
    var line by mutableStateOf(Color(TokyoNight.line)); private set
    var lineHi by mutableStateOf(Color(TokyoNight.lineHi)); private set
    var text by mutableStateOf(Color(TokyoNight.text)); private set
    var sub by mutableStateOf(Color(TokyoNight.sub)); private set
    var dim by mutableStateOf(Color(TokyoNight.dim)); private set
    var blue by mutableStateOf(Color(TokyoNight.blue)); private set
    var cyan by mutableStateOf(Color(TokyoNight.cyan)); private set
    var green by mutableStateOf(Color(TokyoNight.green)); private set
    var magenta by mutableStateOf(Color(TokyoNight.magenta)); private set
    var orange by mutableStateOf(Color(TokyoNight.orange)); private set
    var red by mutableStateOf(Color(TokyoNight.red)); private set
    var yellow by mutableStateOf(Color(TokyoNight.yellow)); private set

    fun apply(c: FluxColors) {
        bg = Color(c.bg)
        tile = Color(c.tile)
        tileHi = Color(c.tileHi)
        offTile = Color(c.offTile)
        line = Color(c.line)
        lineHi = Color(c.lineHi)
        text = Color(c.text)
        sub = Color(c.sub)
        dim = Color(c.dim)
        blue = Color(c.blue)
        cyan = Color(c.cyan)
        green = Color(c.green)
        magenta = Color(c.magenta)
        orange = Color(c.orange)
        red = Color(c.red)
        yellow = Color(c.yellow)
    }
}

val TileShape = RoundedCornerShape(12.dp)
val TileGap = 8.dp

/** The height of 1 grid row on the device home screen. 2 rows are 2 × 62 + 8. */
val TileUnit = 62.dp
val TileUnit2 = TileUnit * 2 + TileGap
val TiledGutter = 10.dp

/** The alpha of a tile whose computer is not reachable. */
private const val DimAlpha = 0.55f

fun activeBorder(from: Color = Tn.blue, to: Color = Tn.cyan) = BorderStroke(2.dp, Brush.linearGradient(listOf(from, to)))

/**
 * The theme of the app: a fixed dark Tokyo Night scheme. Material parts,
 * such as menus, sliders, dialogs, and the camera and mic screens, take the
 * same colors as the tiles.
 */
@Composable
fun TiledTheme(content: @Composable () -> Unit) {
    // Follow the active Omarchy theme of the computer. Tn holds Compose
    // state, so the tiles and this scheme rebuild when the palette changes.
    val colors by ThemeSync.colors.collectAsStateWithLifecycle()
    LaunchedEffect(colors) { Tn.apply(colors) }
    val scheme = darkColorScheme(
        primary = Tn.blue, onPrimary = Tn.bg,
        primaryContainer = Tn.tileHi, onPrimaryContainer = Tn.text,
        secondary = Tn.cyan, onSecondary = Tn.bg,
        secondaryContainer = Tn.line, onSecondaryContainer = Tn.text,
        tertiary = Tn.magenta, onTertiary = Tn.bg,
        tertiaryContainer = Tn.line, onTertiaryContainer = Tn.text,
        background = Tn.bg, onBackground = Tn.text,
        surface = Tn.bg, onSurface = Tn.text,
        surfaceVariant = Tn.tile, onSurfaceVariant = Tn.sub,
        surfaceContainerLowest = Tn.bg, surfaceContainerLow = Tn.offTile,
        surfaceContainer = Tn.tile, surfaceContainerHigh = Tn.tileHi, surfaceContainerHighest = Tn.line,
        inverseSurface = Tn.tileHi, inverseOnSurface = Tn.text, inversePrimary = Tn.blue,
        outline = Tn.dim, outlineVariant = Tn.line,
        error = Tn.red, onError = Tn.bg,
        errorContainer = Tn.red, onErrorContainer = Tn.bg,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides Tn.text, content = content)
    }
}

/**
 * A tile. The border takes [accent] while pressed. A long press runs
 * [onLongClick], for example to unpair a computer. A tile that is not
 * [enabled] shows at a lower alpha and still takes taps.
 */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    accent: Color = Tn.blue,
    container: Color = Tn.tile,
    border: BorderStroke? = BorderStroke(1.dp, Tn.line),
    enabled: Boolean = true,
    padding: PaddingValues = PaddingValues(14.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.SpaceBetween,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val stroke = if (pressed && onClick != null) BorderStroke(1.dp, accent) else border
    var m = modifier.alpha(if (enabled) 1f else DimAlpha).clip(TileShape).background(container)
    if (stroke != null) m = m.border(stroke, TileShape)
    if (onClick != null) {
        m = m.combinedClickable(
            interactionSource = source,
            indication = LocalIndication.current,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
    Column(m.padding(padding), verticalArrangement = verticalArrangement, horizontalAlignment = horizontalAlignment, content = content)
}

/** A short tile with an icon and a label on 1 line. */
@Composable
fun LineTile(
    @DrawableRes icon: Int,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: String? = null,
) {
    Tile(modifier, onClick, accent = accent, enabled = enabled, padding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Sym(icon, tint = accent, size = 22.dp)
            T(label, Modifier.weight(1f), size = 13, weight = FontWeight.SemiBold, maxLines = 1)
            if (trailing != null) T(trailing, size = 11, color = Tn.dim, family = Mono)
        }
    }
}

/** A small tile: the icon over the label, centered. */
@Composable
fun MiniTile(
    @DrawableRes icon: Int,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = Tn.tile,
) {
    Tile(
        modifier, onClick, accent = accent, container = container, enabled = enabled,
        padding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Sym(icon, tint = accent, size = 20.dp)
        T(label, size = 11, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The mono, uppercase label of a tile or a section. */
@Composable
fun TileLabel(text: String, modifier: Modifier = Modifier, color: Color = Tn.dim) {
    T(text.uppercase(), modifier, size = 11, color = color, weight = FontWeight.Medium, family = Mono, letterSpacing = 0.9f, maxLines = 1)
}

@Composable
fun SectionLabel(text: String) {
    TileLabel(text, Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
}

/** A row of tiles with the grid gap. */
@Composable
fun TileRow(height: Dp, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(TileGap), content = content)
}

/** A small square button with an icon, for top bars. */
@Composable
fun SquareButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Tn.tile).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Sym(icon, description, size = if (size < 36.dp) 18.dp else 20.dp) }
}

/** The top bar of an inner tiled screen: a square back button and a mono label. */
@Composable
fun TiledTopBar(label: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SquareButton(Ic.back, "Back", onBack)
        T(label, Modifier.weight(1f), size = 12, color = Tn.dim, weight = FontWeight.Medium, family = Mono, maxLines = 1)
        trailing()
    }
}

/** A dashed rounded border, for a computer that is available to pair. */
fun Modifier.dashedBorder(color: Color, width: Dp = 1.5.dp, radius: Dp = 12.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
    )
}

/** A status dot. */
@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
