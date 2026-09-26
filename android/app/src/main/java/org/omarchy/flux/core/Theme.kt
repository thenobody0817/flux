package org.omarchy.flux.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.omarchy.flux.protocol.Packet
import org.omarchy.flux.protocol.Types
import org.omarchy.flux.protocol.bodyOf

/**
 * The colors of the Tiled design, as 0xAARRGGBB values. Flux builds them
 * from the active Omarchy theme of the computer (a colors.toml), so the
 * phone follows the desktop theme.
 */
data class FluxColors(
    val bg: Long,
    val offTile: Long,
    val tile: Long,
    val tileHi: Long,
    val line: Long,
    val lineHi: Long,
    val text: Long,
    val sub: Long,
    val dim: Long,
    val blue: Long,
    val cyan: Long,
    val green: Long,
    val magenta: Long,
    val orange: Long,
    val red: Long,
    val yellow: Long,
)

/** The Tokyo Night colors that the Tiled design uses before a theme arrives. */
val TokyoNight = FluxColors(
    bg = 0xFF16161E, offTile = 0xFF1A1B26, tile = 0xFF1F2335, tileHi = 0xFF24283B,
    line = 0xFF292E42, lineHi = 0xFF3B4261,
    text = 0xFFC0CAF5, sub = 0xFFA9B1D6, dim = 0xFF565F89,
    blue = 0xFF7AA2F7, cyan = 0xFF7DCFFF, green = 0xFF9ECE6A, magenta = 0xFFBB9AF7,
    orange = 0xFFFF9E64, red = 0xFFF7768E, yellow = 0xFFE0AF68,
)

private const val BLACK = 0xFF000000L

private val colorLine = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"?(#[0-9A-Fa-f]{6,8})"?""")

/**
 * Parses the colors.toml of an Omarchy theme into the Tiled roles. The keys
 * follow the semantic Omarchy palette (background, foreground, accent,
 * green, ...). Missing keys blend from the ones that are present. An empty
 * or partial theme gives the Tokyo Night colors.
 */
fun parseColors(text: String): FluxColors {
    val v = HashMap<String, Long>()
    for (line in text.lineSequence()) {
        val m = colorLine.find(line) ?: continue
        val value = parseHex(m.groupValues[2]) ?: continue
        v[m.groupValues[1].lowercase()] = value
    }
    val bg = v["background"] ?: return TokyoNight
    val fg = v["foreground"] ?: return TokyoNight
    val accent = v["accent"] ?: v["blue"] ?: fg
    val tile = v["lighter_background"] ?: mix(bg, fg, 0.055)
    val line = v["selection"] ?: mix(bg, fg, 0.14)
    return FluxColors(
        bg = bg,
        offTile = v["dark_background"] ?: mix(bg, BLACK, 0.35),
        tile = tile,
        tileHi = mix(tile, fg, 0.08),
        line = line,
        lineHi = mix(line, fg, 0.22),
        text = fg,
        sub = mix(fg, bg, 0.30),
        dim = v["muted"] ?: mix(bg, fg, 0.5),
        blue = accent,
        cyan = v["cyan"] ?: accent,
        green = v["green"] ?: accent,
        magenta = v["magenta"] ?: accent,
        orange = v["orange"] ?: accent,
        red = v["red"] ?: accent,
        yellow = v["yellow"] ?: accent,
    )
}

private fun parseHex(s: String): Long? {
    val h = s.removePrefix("#")
    return when (h.length) {
        6 -> runCatching { 0xFF000000L or h.toLong(16) }.getOrNull()
        8 -> runCatching { 0xFF000000L or h.substring(0, 6).toLong(16) }.getOrNull()
        else -> null
    }
}

/** Blends two ARGB colors. t = 0 gives a, t = 1 gives b. */
private fun mix(a: Long, b: Long, t: Double): Long {
    fun part(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toLong().coerceIn(0, 255)
    return 0xFF000000L or (part(16) shl 16) or (part(8) shl 8) or part(0)
}

/**
 * The Omarchy theme of the connected computer. The app follows it globally:
 * the colors of the last theme packet that arrived. The last theme is kept
 * on the phone, so the app starts in it and survives an offline computer.
 */
object ThemeSync {
    private val _colors = MutableStateFlow(TokyoNight)
    val colors: StateFlow<FluxColors> = _colors

    private val _themes = MutableStateFlow<List<String>>(emptyList())
    val themes: StateFlow<List<String>> = _themes

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    /** Loads the theme that the phone kept from the last session. */
    fun start(core: FluxCore) {
        val text = core.settings.themeColors
        if (text.isNotEmpty()) _colors.value = parseColors(text)
        _name.value = core.settings.themeName
        _themes.value = core.settings.themeList
    }

    /** Handles flux.theme from a computer. The core lock is held. */
    fun onPacket(core: FluxCore, p: Packet) {
        val text = p.string("colors") ?: return
        val name = p.string("name").orEmpty()
        val themes = p.strings("themes")
        _colors.value = parseColors(text)
        _name.value = name
        if (themes.isNotEmpty()) _themes.value = themes
        core.settings.themeColors = text
        core.settings.themeName = name
        if (themes.isNotEmpty()) core.settings.themeList = themes
    }

    /** Reports whether the computer accepts flux.theme.request. */
    fun canControl(d: Device): Boolean = Types.FLUX_THEME_REQUEST in d.identity.incoming

    /** Asks the computer for its installed themes and its active theme. */
    fun requestList(core: FluxCore, id: String) {
        val d = core.device(id) ?: return
        if (!canControl(d)) return
        d.send(Packet(Types.FLUX_THEME_REQUEST, bodyOf("action" to "list")))
    }

    /** Applies a theme on the computer. It answers with the new flux.theme. */
    fun setTheme(core: FluxCore, id: String, name: String) {
        val d = core.device(id) ?: return
        if (!canControl(d)) return
        d.send(Packet(Types.FLUX_THEME_REQUEST, bodyOf("set" to name)))
    }
}
