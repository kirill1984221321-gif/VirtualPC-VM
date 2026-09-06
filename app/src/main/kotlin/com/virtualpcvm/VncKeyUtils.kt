package com.virtualpcvm

import android.view.KeyEvent

object VncKeyUtils {

    /**
     * Converts an Android Char to an RFB / X11 KeySym.
     * Supports ASCII, Latin-1, Cyrillic (Russian alphabet), and full Unicode.
     */
    fun charToKeySym(ch: Char): Long {
        return when (ch) {
            '\n', '\r' -> 0xFF0DL // Return
            '\t' -> 0xFF09L       // Tab
            '\b' -> 0xFF08L       // Backspace
            '\u001B' -> 0xFF1BL   // Escape
            ' ' -> 0x0020L

            // Standard Cyrillic letters to X11 Cyrillic Keysyms (0x06A1 .. 0x06FA)
            'Ё' -> 0x06B3L
            'ё' -> 0x06A3L
            'А' -> 0x06E1L; 'а' -> 0x06C1L
            'Б' -> 0x06E2L; 'б' -> 0x06C2L
            'В' -> 0x06F7L; 'в' -> 0x06D7L
            'Г' -> 0x06E7L; 'г' -> 0x06C7L
            'Д' -> 0x06E4L; 'д' -> 0x06C4L
            'Е' -> 0x06E5L; 'е' -> 0x06C5L
            'Ж' -> 0x06F6L; 'ж' -> 0x06D6L
            'З' -> 0x06FAL; 'з' -> 0x06DAL
            'И' -> 0x06E9L; 'и' -> 0x06C9L
            'Й' -> 0x06EAL; 'й' -> 0x06CAL
            'К' -> 0x06EBL; 'к' -> 0x06CBL
            'Л' -> 0x06ECL; 'л' -> 0x06CCL
            'М' -> 0x06EDL; 'м' -> 0x06CDL
            'Н' -> 0x06EEL; 'н' -> 0x06CEL
            'О' -> 0x06EFL; 'о' -> 0x06CFL
            'П' -> 0x06F0L; 'п' -> 0x06D0L
            'Р' -> 0x06F2L; 'р' -> 0x06D2L
            'С' -> 0x06F3L; 'с' -> 0x06D3L
            'Т' -> 0x06F4L; 'т' -> 0x06D4L
            'У' -> 0x06F5L; 'у' -> 0x06D5L
            'Ф' -> 0x06E6L; 'ф' -> 0x06C6L
            'Х' -> 0x06E8L; 'х' -> 0x06C8L
            'Ц' -> 0x06E3L; 'ц' -> 0x06C3L
            'Ч' -> 0x06FEL; 'ч' -> 0x06DEL
            'Ш' -> 0x06FBL; 'ш' -> 0x06DBL
            'Щ' -> 0x06FDL; 'щ' -> 0x06DDL
            'Ъ' -> 0x06FFL; 'ъ' -> 0x06DFL
            'Ы' -> 0x06F9L; 'ы' -> 0x06D9L
            'Ь' -> 0x06F8L; 'ь' -> 0x06D8L
            'Э' -> 0x06FCL; 'э' -> 0x06DCL
            'Ю' -> 0x06E0L; 'ю' -> 0x06C0L
            'Я' -> 0x06F1L; 'я' -> 0x06D1L

            else -> {
                val code = ch.code
                if (code in 32..126 || code in 160..255) {
                    code.toLong()
                } else {
                    // Standard X11 Unicode Keysym: 0x01000000 | codepoint
                    0x01000000L or code.toLong()
                }
            }
        }
    }

    /**
     * Converts an Android KeyEvent keyCode to an X11 / RFB KeySym.
     */
    fun keyCodeToKeySym(keyCode: Int, event: KeyEvent? = null): Long {
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> 0xFF08L       // Backspace
            KeyEvent.KEYCODE_TAB -> 0xFF09L       // Tab
            KeyEvent.KEYCODE_ENTER -> 0xFF0DL     // Return / Enter
            KeyEvent.KEYCODE_ESCAPE -> 0xFF1BL    // Escape
            KeyEvent.KEYCODE_DPAD_UP -> 0xFF52L   // Up
            KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54L // Down
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51L // Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53L// Right
            KeyEvent.KEYCODE_PAGE_UP -> 0xFF55L   // Page Up
            KeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56L // Page Down
            KeyEvent.KEYCODE_MOVE_HOME -> 0xFF50L // Home
            KeyEvent.KEYCODE_MOVE_END -> 0xFF57L  // End
            KeyEvent.KEYCODE_INSERT -> 0xFF63L    // Insert
            KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFFL // Delete
            KeyEvent.KEYCODE_CTRL_LEFT -> 0xFFE3L // Control_L
            KeyEvent.KEYCODE_CTRL_RIGHT -> 0xFFE4L// Control_R
            KeyEvent.KEYCODE_ALT_LEFT -> 0xFFE9L  // Alt_L
            KeyEvent.KEYCODE_ALT_RIGHT -> 0xFFEAL // Alt_R
            KeyEvent.KEYCODE_SHIFT_LEFT -> 0xFFE1L// Shift_L
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xFFE2L// Shift_R
            KeyEvent.KEYCODE_WINDOW -> 0xFFEBL    // Super_L (Windows key)
            KeyEvent.KEYCODE_CAPS_LOCK -> 0xFFE5L // Caps_Lock
            KeyEvent.KEYCODE_NUM_LOCK -> 0xFF7FL  // Num_Lock
            KeyEvent.KEYCODE_SCROLL_LOCK -> 0xFF14L // Scroll_Lock
            KeyEvent.KEYCODE_SYSRQ -> 0xFF61L     // PrintScreen / SysRq
            KeyEvent.KEYCODE_BREAK -> 0xFF6BL     // Pause / Break
            KeyEvent.KEYCODE_MENU -> 0xFF67L      // Menu

            // Function Keys F1..F12
            KeyEvent.KEYCODE_F1 -> 0xFFBEL
            KeyEvent.KEYCODE_F2 -> 0xFFBFL
            KeyEvent.KEYCODE_F3 -> 0xFFC0L
            KeyEvent.KEYCODE_F4 -> 0xFFC1L
            KeyEvent.KEYCODE_F5 -> 0xFFC2L
            KeyEvent.KEYCODE_F6 -> 0xFFC3L
            KeyEvent.KEYCODE_F7 -> 0xFFC4L
            KeyEvent.KEYCODE_F8 -> 0xFFC5L
            KeyEvent.KEYCODE_F9 -> 0xFFC6L
            KeyEvent.KEYCODE_F10 -> 0xFFC7L
            KeyEvent.KEYCODE_F11 -> 0xFFC8L
            KeyEvent.KEYCODE_F12 -> 0xFFC9L

            // Standard alphanumeric mapping from event if available
            else -> {
                if (event != null) {
                    val unicode = event.getUnicodeChar(event.metaState)
                    if (unicode != 0) {
                        return charToKeySym(unicode.toChar())
                    }
                }
                0L
            }
        }
    }
}
