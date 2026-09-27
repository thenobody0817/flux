package org.omarchy.flux.shell

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Matches the shell's per-device action registry, which it sends on `shellKeyboard`. The
 * registry is the only shortcut table: this holds what the shell last sent and nothing else,
 * so a new host action needs no change here.
 */
class ShellKeys {
    private var commands = JSONArray()

    fun register(next: JSONArray?) {
        if (next == null || next.length() > 256) return
        for (index in 0 until next.length()) {
            val row = next.optJSONObject(index) ?: return
            if (row.optString("code").length > 32 || row.optString("label").length > 160) return
        }
        commands = next
    }

    /** The action for [event], or null to let Android handle the key. */
    fun match(event: KeyEvent, editing: Boolean): JSONObject? {
        val code = code(event.keyCode) ?: return null
        for (index in 0 until commands.length()) {
            val row = commands.optJSONObject(index) ?: continue
            if (code != row.optString("code") || row.optBoolean("shift") != event.isShiftPressed) continue
            if (editing && row.optBoolean("editing")) continue
            val exact = row.optBoolean("meta") == event.isMetaPressed &&
                row.optBoolean("ctrl") == event.isCtrlPressed &&
                row.optBoolean("alt") == event.isAltPressed
            // Ctrl+Alt is the shell's alias for Meta, so a keyboard without a Meta key
            // still reaches the same action.
            val alias = row.optBoolean("meta") && !row.optBoolean("ctrl") && !row.optBoolean("alt") &&
                !event.isMetaPressed && event.isCtrlPressed && event.isAltPressed
            if (exact || alias) return row
        }
        return null
    }

    private fun code(key: Int): String? = when (key) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> "Key" + ('A' + key - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> "Digit" + (key - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F" + (key - KeyEvent.KEYCODE_F1 + 1)
        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
        KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        KeyEvent.KEYCODE_ESCAPE -> "Escape"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_ENTER -> "Enter"
        KeyEvent.KEYCODE_NUMPAD_ENTER -> "NumpadEnter"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_LEFT_BRACKET -> "BracketLeft"
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "BracketRight"
        KeyEvent.KEYCODE_SLASH -> "Slash"
        KeyEvent.KEYCODE_BACKSLASH -> "Backslash"
        KeyEvent.KEYCODE_COMMA -> "Comma"
        KeyEvent.KEYCODE_PERIOD -> "Period"
        KeyEvent.KEYCODE_SEMICOLON -> "Semicolon"
        KeyEvent.KEYCODE_APOSTROPHE -> "Quote"
        KeyEvent.KEYCODE_GRAVE -> "Backquote"
        KeyEvent.KEYCODE_EQUALS -> "Equal"
        KeyEvent.KEYCODE_MINUS -> "Minus"
        KeyEvent.KEYCODE_NUMPAD_ADD -> "NumpadAdd"
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "NumpadSubtract"
        else -> null
    }
}
