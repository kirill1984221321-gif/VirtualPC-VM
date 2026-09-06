package com.virtualpcvm

import android.view.KeyEvent

/**
 * Keycode and Scancode mapping utility that translates:
 * - Android KeyCodes -> X11 KeySyms (for RFB protocol)
 * - Unicode Chars -> X11 KeySyms (including full Cyrillic, Latin, Symbols)
 * - Android KeyCodes -> QEMU XT / AT Scancodes (Set 1 & Set 2)
 * - Android KeyCodes -> QEMU Monitor sendkey argument names (e.g. "ctrl-alt-f1", "ret", "spc")
 */
object VncKeyMapper {

    // Common X11 KeySym Constants
    const val KEYSYM_VOID: Long = 0xFFFFFFL
    const val KEYSYM_BACKSPACE: Long = 0xFF08L
    const val KEYSYM_TAB: Long = 0xFF09L
    const val KEYSYM_RETURN: Long = 0xFF0DL
    const val KEYSYM_ESCAPE: Long = 0xFF1BL
    const val KEYSYM_DELETE: Long = 0xFFFFL
    const val KEYSYM_INSERT: Long = 0xFF63L
    const val KEYSYM_HOME: Long = 0xFF50L
    const val KEYSYM_END: Long = 0xFF57L
    const val KEYSYM_PAGE_UP: Long = 0xFF55L
    const val KEYSYM_PAGE_DOWN: Long = 0xFF56L
    const val KEYSYM_LEFT: Long = 0xFF51L
    const val KEYSYM_UP: Long = 0xFF52L
    const val KEYSYM_RIGHT: Long = 0xFF53L
    const val KEYSYM_DOWN: Long = 0xFF54L
    const val KEYSYM_CTRL_L: Long = 0xFFE3L
    const val KEYSYM_CTRL_R: Long = 0xFFE4L
    const val KEYSYM_SHIFT_L: Long = 0xFFE1L
    const val KEYSYM_SHIFT_R: Long = 0xFFE2L
    const val KEYSYM_ALT_L: Long = 0xFFE9L
    const val KEYSYM_ALT_R: Long = 0xFFEAL
    const val KEYSYM_SUPER_L: Long = 0xFFEBL // Windows / Super key
    const val KEYSYM_SUPER_R: Long = 0xFFECL
    const val KEYSYM_CAPS_LOCK: Long = 0xFFE5L
    const val KEYSYM_NUM_LOCK: Long = 0xFF7FL
    const val KEYSYM_SCROLL_LOCK: Long = 0xFF14L
    const val KEYSYM_PRINT_SCREEN: Long = 0xFF61L
    const val KEYSYM_PAUSE: Long = 0xFF6BL
    const val KEYSYM_MENU: Long = 0xFF67L

    fun getKeyName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
            KeyEvent.KEYCODE_ESCAPE -> "Escape"
            KeyEvent.KEYCODE_DPAD_UP -> "Up Arrow"
            KeyEvent.KEYCODE_DPAD_DOWN -> "Down Arrow"
            KeyEvent.KEYCODE_DPAD_LEFT -> "Left Arrow"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "Right Arrow"
            KeyEvent.KEYCODE_PAGE_UP -> "Page Up"
            KeyEvent.KEYCODE_PAGE_DOWN -> "Page Down"
            KeyEvent.KEYCODE_MOVE_HOME -> "Home"
            KeyEvent.KEYCODE_MOVE_END -> "End"
            KeyEvent.KEYCODE_INSERT -> "Insert"
            KeyEvent.KEYCODE_CTRL_LEFT -> "Ctrl (Left)"
            KeyEvent.KEYCODE_CTRL_RIGHT -> "Ctrl (Right)"
            KeyEvent.KEYCODE_ALT_LEFT -> "Alt (Left)"
            KeyEvent.KEYCODE_ALT_RIGHT -> "Alt (Right)"
            KeyEvent.KEYCODE_SHIFT_LEFT -> "Shift (Left)"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift (Right)"
            KeyEvent.KEYCODE_WINDOW -> "Win / Super"
            KeyEvent.KEYCODE_CAPS_LOCK -> "Caps Lock"
            KeyEvent.KEYCODE_NUM_LOCK -> "Num Lock"
            KeyEvent.KEYCODE_SCROLL_LOCK -> "Scroll Lock"
            KeyEvent.KEYCODE_SYSRQ -> "Print Screen"
            KeyEvent.KEYCODE_BREAK -> "Pause"
            KeyEvent.KEYCODE_SPACE -> "Space"
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }
    }

    fun getKeySymName(keySym: Long): String {
        return when (keySym) {
            KEYSYM_BACKSPACE -> "BackSpace"
            KEYSYM_TAB -> "Tab"
            KEYSYM_RETURN -> "Return"
            KEYSYM_ESCAPE -> "Escape"
            KEYSYM_DELETE -> "Delete"
            KEYSYM_INSERT -> "Insert"
            KEYSYM_HOME -> "Home"
            KEYSYM_END -> "End"
            KEYSYM_PAGE_UP -> "Page_Up"
            KEYSYM_PAGE_DOWN -> "Page_Down"
            KEYSYM_LEFT -> "Left"
            KEYSYM_UP -> "Up"
            KEYSYM_RIGHT -> "Right"
            KEYSYM_DOWN -> "Down"
            KEYSYM_CTRL_L -> "Control_L"
            KEYSYM_CTRL_R -> "Control_R"
            KEYSYM_SHIFT_L -> "Shift_L"
            KEYSYM_SHIFT_R -> "Shift_R"
            KEYSYM_ALT_L -> "Alt_L"
            KEYSYM_ALT_R -> "Alt_R"
            KEYSYM_SUPER_L -> "Super_L"
            KEYSYM_SUPER_R -> "Super_R"
            KEYSYM_CAPS_LOCK -> "Caps_Lock"
            KEYSYM_NUM_LOCK -> "Num_Lock"
            KEYSYM_SCROLL_LOCK -> "Scroll_Lock"
            KEYSYM_PRINT_SCREEN -> "Print"
            KEYSYM_PAUSE -> "Pause"
            KEYSYM_MENU -> "Menu"
            0x20L -> "Space"
            in 0x21L..0x7EL -> "'${keySym.toInt().toChar()}'"
            in 0xFFBEL..0xFFC9L -> "F${keySym - 0xFFBE + 1}"
            in 0x06A1L..0x06FFL -> "Cyrillic (0x${keySym.toString(16).uppercase()})"
            else -> if (keySym != 0L) "0x${keySym.toString(16).uppercase()}" else "None"
        }
    }

    fun getQemuScancode(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> "XT: 0x01 (esc)"
            KeyEvent.KEYCODE_1 -> "XT: 0x02 (1)"
            KeyEvent.KEYCODE_2 -> "XT: 0x03 (2)"
            KeyEvent.KEYCODE_3 -> "XT: 0x04 (3)"
            KeyEvent.KEYCODE_4 -> "XT: 0x05 (4)"
            KeyEvent.KEYCODE_5 -> "XT: 0x06 (5)"
            KeyEvent.KEYCODE_6 -> "XT: 0x07 (6)"
            KeyEvent.KEYCODE_7 -> "XT: 0x08 (7)"
            KeyEvent.KEYCODE_8 -> "XT: 0x09 (8)"
            KeyEvent.KEYCODE_9 -> "XT: 0x0A (9)"
            KeyEvent.KEYCODE_0 -> "XT: 0x0B (0)"
            KeyEvent.KEYCODE_MINUS -> "XT: 0x0C (minus)"
            KeyEvent.KEYCODE_EQUALS -> "XT: 0x0D (equal)"
            KeyEvent.KEYCODE_DEL -> "XT: 0x0E (backspace)"
            KeyEvent.KEYCODE_TAB -> "XT: 0x0F (tab)"
            KeyEvent.KEYCODE_Q -> "XT: 0x10 (q)"
            KeyEvent.KEYCODE_W -> "XT: 0x11 (w)"
            KeyEvent.KEYCODE_E -> "XT: 0x12 (e)"
            KeyEvent.KEYCODE_R -> "XT: 0x13 (r)"
            KeyEvent.KEYCODE_T -> "XT: 0x14 (t)"
            KeyEvent.KEYCODE_Y -> "XT: 0x15 (y)"
            KeyEvent.KEYCODE_U -> "XT: 0x16 (u)"
            KeyEvent.KEYCODE_I -> "XT: 0x17 (i)"
            KeyEvent.KEYCODE_O -> "XT: 0x18 (o)"
            KeyEvent.KEYCODE_P -> "XT: 0x19 (p)"
            KeyEvent.KEYCODE_ENTER -> "XT: 0x1C (ret)"
            KeyEvent.KEYCODE_CTRL_LEFT -> "XT: 0x1D (ctrl)"
            KeyEvent.KEYCODE_A -> "XT: 0x1E (a)"
            KeyEvent.KEYCODE_S -> "XT: 0x1F (s)"
            KeyEvent.KEYCODE_D -> "XT: 0x20 (d)"
            KeyEvent.KEYCODE_F -> "XT: 0x21 (f)"
            KeyEvent.KEYCODE_G -> "XT: 0x22 (g)"
            KeyEvent.KEYCODE_H -> "XT: 0x23 (h)"
            KeyEvent.KEYCODE_J -> "XT: 0x24 (j)"
            KeyEvent.KEYCODE_K -> "XT: 0x25 (k)"
            KeyEvent.KEYCODE_L -> "XT: 0x26 (l)"
            KeyEvent.KEYCODE_SHIFT_LEFT -> "XT: 0x2A (shift)"
            KeyEvent.KEYCODE_Z -> "XT: 0x2C (z)"
            KeyEvent.KEYCODE_X -> "XT: 0x2D (x)"
            KeyEvent.KEYCODE_C -> "XT: 0x2E (c)"
            KeyEvent.KEYCODE_V -> "XT: 0x2F (v)"
            KeyEvent.KEYCODE_B -> "XT: 0x30 (b)"
            KeyEvent.KEYCODE_N -> "XT: 0x31 (n)"
            KeyEvent.KEYCODE_M -> "XT: 0x32 (m)"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> "XT: 0x36 (shift_r)"
            KeyEvent.KEYCODE_ALT_LEFT -> "XT: 0x38 (alt)"
            KeyEvent.KEYCODE_SPACE -> "XT: 0x39 (spc)"
            KeyEvent.KEYCODE_CAPS_LOCK -> "XT: 0x3A (caps_lock)"
            KeyEvent.KEYCODE_F1 -> "XT: 0x3B (f1)"
            KeyEvent.KEYCODE_F2 -> "XT: 0x3C (f2)"
            KeyEvent.KEYCODE_F3 -> "XT: 0x3D (f3)"
            KeyEvent.KEYCODE_F4 -> "XT: 0x3E (f4)"
            KeyEvent.KEYCODE_F5 -> "XT: 0x3F (f5)"
            KeyEvent.KEYCODE_F6 -> "XT: 0x40 (f6)"
            KeyEvent.KEYCODE_F7 -> "XT: 0x41 (f7)"
            KeyEvent.KEYCODE_F8 -> "XT: 0x42 (f8)"
            KeyEvent.KEYCODE_F9 -> "XT: 0x43 (f9)"
            KeyEvent.KEYCODE_F10 -> "XT: 0x44 (f10)"
            KeyEvent.KEYCODE_F11 -> "XT: 0x57 (f11)"
            KeyEvent.KEYCODE_F12 -> "XT: 0x58 (f12)"
            KeyEvent.KEYCODE_DPAD_UP -> "XT: 0xE0 0x48 (up)"
            KeyEvent.KEYCODE_DPAD_LEFT -> "XT: 0xE0 0x4B (left)"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "XT: 0xE0 0x4D (right)"
            KeyEvent.KEYCODE_DPAD_DOWN -> "XT: 0xE0 0x50 (down)"
            KeyEvent.KEYCODE_PAGE_UP -> "XT: 0xE0 0x49 (pgup)"
            KeyEvent.KEYCODE_PAGE_DOWN -> "XT: 0xE0 0x51 (pgdn)"
            KeyEvent.KEYCODE_MOVE_HOME -> "XT: 0xE0 0x47 (home)"
            KeyEvent.KEYCODE_MOVE_END -> "XT: 0xE0 0x4F (end)"
            KeyEvent.KEYCODE_INSERT -> "XT: 0xE0 0x52 (insert)"
            KeyEvent.KEYCODE_FORWARD_DEL -> "XT: 0xE0 0x53 (delete)"
            KeyEvent.KEYCODE_CTRL_RIGHT -> "XT: 0xE0 0x1D (ctrl_r)"
            KeyEvent.KEYCODE_ALT_RIGHT -> "XT: 0xE0 0x38 (alt_r)"
            KeyEvent.KEYCODE_WINDOW -> "XT: 0xE0 0x5B (meta_l)"
            else -> "Set 1: 0x${Integer.toHexString(keyCode)}"
        }
    }

    /**
     * Converts a key combo description into QEMU monitor sendkey string.
     */
    fun toQemuSendkeyName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> "backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
            KeyEvent.KEYCODE_TAB -> "tab"
            KeyEvent.KEYCODE_ENTER -> "ret"
            KeyEvent.KEYCODE_ESCAPE -> "esc"
            KeyEvent.KEYCODE_SPACE -> "spc"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_PAGE_UP -> "pgup"
            KeyEvent.KEYCODE_PAGE_DOWN -> "pgdn"
            KeyEvent.KEYCODE_MOVE_HOME -> "home"
            KeyEvent.KEYCODE_MOVE_END -> "end"
            KeyEvent.KEYCODE_INSERT -> "insert"
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "ctrl"
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "alt"
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "shift"
            KeyEvent.KEYCODE_WINDOW -> "meta_l"
            KeyEvent.KEYCODE_F1 -> "f1"
            KeyEvent.KEYCODE_F2 -> "f2"
            KeyEvent.KEYCODE_F3 -> "f3"
            KeyEvent.KEYCODE_F4 -> "f4"
            KeyEvent.KEYCODE_F5 -> "f5"
            KeyEvent.KEYCODE_F6 -> "f6"
            KeyEvent.KEYCODE_F7 -> "f7"
            KeyEvent.KEYCODE_F8 -> "f8"
            KeyEvent.KEYCODE_F9 -> "f9"
            KeyEvent.KEYCODE_F10 -> "f10"
            KeyEvent.KEYCODE_F11 -> "f11"
            KeyEvent.KEYCODE_F12 -> "f12"
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('a' + (keyCode - KeyEvent.KEYCODE_A)).toString()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
            else -> ""
        }
    }
}
