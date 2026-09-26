package org.omarchy.flux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @Test
    fun parsesTheOmarchyPalette() {
        val text = """
            mode = "dark"
            background = "#121212"
            dark_background = "#0d0d0d"
            lighter_background = "#1e1e1e"
            foreground = "#bebebe"
            accent = "#e68e0d"
            selection = "#2a2a2a"
            muted = "#333333"
            green = "#FFC107"
            cyan = "#bebebe"
            blue = "#e68e0d"
            magenta = "#D35F5F"
            red = "#D35F5F"
            yellow = "#b91c1c"
            orange = "#c63d3d"
        """.trimIndent()
        val c = parseColors(text)
        assertEquals(0xFF121212L, c.bg)
        assertEquals(0xFF0D0D0DL, c.offTile)
        assertEquals(0xFF1E1E1EL, c.tile)
        assertEquals(0xFFBEBEBEL, c.text)
        assertEquals(0xFFE68E0DL, c.blue) // the accent becomes the primary
        assertEquals(0xFFFFC107L, c.green)
        assertEquals(0xFF333333L, c.dim)
        assertEquals(0xFF2A2A2AL, c.line)
    }

    @Test
    fun fallsBackToTokyoNight() {
        assertEquals(TokyoNight, parseColors(""))
        assertEquals(TokyoNight, parseColors("background = \"#121212\"\n"))
    }

    @Test
    fun blendsTheHighlightFromTheBase() {
        val c = parseColors("background = \"#000000\"\nforeground = \"#ffffff\"\n")
        assertTrue("tile is lighter than the background", (c.tile and 0xFF) > (c.bg and 0xFF))
        assertTrue("tileHi is lighter than tile", (c.tileHi and 0xFF) > (c.tile and 0xFF))
        assertTrue("sub sits between the text and the background", (c.sub and 0xFF) in (c.bg and 0xFF)..(c.text and 0xFF))
    }

    @Test
    fun missingAccentsUseTheAccent() {
        val c = parseColors("background = \"#101010\"\nforeground = \"#eeeeee\"\naccent = \"#abcdef\"\n")
        assertEquals(0xFFABCDEFL, c.blue)
        assertEquals(0xFFABCDEFL, c.cyan)
        assertEquals(0xFFABCDEFL, c.magenta)
    }
}
