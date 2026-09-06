package com.virtualpcvm

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class VncVirtualKeyboard(
    private val context: Context,
    private val vncView: VncView,
    private val rootLayout: ViewGroup,
    private val onKeySent: (Long, Boolean) -> Unit
) {

    private var keyboardView: View? = null
    private var containerContent: FrameLayout? = null

    // Modifier states
    var isCtrlLatched = false
        private set
    var isAltLatched = false
        private set
    var isShiftLatched = false
        private set
    var isWinLatched = false
        private set

    private var holdTimeMs: Long = 40L
    private var currentTab: String = "QUICK"

    private var btnLatchCtrl: MaterialButton? = null
    private var btnLatchAlt: MaterialButton? = null
    private var btnLatchShift: MaterialButton? = null
    private var btnLatchWin: MaterialButton? = null
    private var btnHoldTime: MaterialButton? = null

    fun isVisible(): Boolean = keyboardView?.visibility == View.VISIBLE

    fun show() {
        if (keyboardView == null) {
            initView()
        }
        keyboardView?.visibility = View.VISIBLE
        keyboardView?.bringToFront()
    }

    fun hide() {
        keyboardView?.visibility = View.GONE
        resetModifiers()
    }

    fun toggle() {
        if (isVisible()) hide() else show()
    }

    private fun initView() {
        val view = View.inflate(context, R.layout.layout_vnc_virtual_keyboard, null)
        keyboardView = view

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(16, 0, 16, 44)
        }
        rootLayout.addView(view, params)

        containerContent = view.findViewById(R.id.containerVkContent)
        btnLatchCtrl = view.findViewById(R.id.btnLatchCtrl)
        btnLatchAlt = view.findViewById(R.id.btnLatchAlt)
        btnLatchShift = view.findViewById(R.id.btnLatchShift)
        btnLatchWin = view.findViewById(R.id.btnLatchWin)
        btnHoldTime = view.findViewById(R.id.btnVkHoldTime)

        view.findViewById<MaterialButton>(R.id.btnVkClose).setOnClickListener {
            hide()
        }

        btnHoldTime?.setOnClickListener {
            holdTimeMs = when (holdTimeMs) {
                20L -> 40L
                40L -> 80L
                80L -> 150L
                else -> 20L
            }
            btnHoldTime?.text = "${holdTimeMs}ms"
            Toast.makeText(context, "Key Hold: ${holdTimeMs}ms", Toast.LENGTH_SHORT).show()
        }

        btnLatchCtrl?.setOnClickListener {
            isCtrlLatched = !isCtrlLatched
            updateModifierButtons()
        }

        btnLatchAlt?.setOnClickListener {
            isAltLatched = !isAltLatched
            updateModifierButtons()
        }

        btnLatchShift?.setOnClickListener {
            isShiftLatched = !isShiftLatched
            updateModifierButtons()
        }

        btnLatchWin?.setOnClickListener {
            isWinLatched = !isWinLatched
            updateModifierButtons()
        }

        val tabQuick = view.findViewById<MaterialButton>(R.id.tabVkQuick)
        val tabQwerty = view.findViewById<MaterialButton>(R.id.tabVkQwerty)
        val tabCyrillic = view.findViewById<MaterialButton>(R.id.tabVkCyrillic)
        val tabFKeys = view.findViewById<MaterialButton>(R.id.tabVkFKeys)
        val tabNav = view.findViewById<MaterialButton>(R.id.tabVkNav)

        val allTabs = listOf(tabQuick, tabQwerty, tabCyrillic, tabFKeys, tabNav)

        fun selectTab(name: String, activeBtn: MaterialButton) {
            currentTab = name
            allTabs.forEach {
                it.setBackgroundColor(if (it == activeBtn) Color.parseColor("#388BFD") else Color.parseColor("#21262D"))
                it.setTextColor(Color.WHITE)
            }
            renderTabContent(name)
        }

        tabQuick.setOnClickListener { selectTab("QUICK", tabQuick) }
        tabQwerty.setOnClickListener { selectTab("QWERTY", tabQwerty) }
        tabCyrillic.setOnClickListener { selectTab("CYRILLIC", tabCyrillic) }
        tabFKeys.setOnClickListener { selectTab("FKEYS", tabFKeys) }
        tabNav.setOnClickListener { selectTab("NAV", tabNav) }

        selectTab("QUICK", tabQuick)
        updateModifierButtons()
    }

    private fun updateModifierButtons() {
        val activeBg = Color.parseColor("#238636")
        val activeText = Color.WHITE
        val inactiveBg = Color.parseColor("#161B22")
        val inactiveText = Color.parseColor("#C9D1D9")

        btnLatchCtrl?.setBackgroundColor(if (isCtrlLatched) activeBg else inactiveBg)
        btnLatchCtrl?.setTextColor(if (isCtrlLatched) activeText else inactiveText)

        btnLatchAlt?.setBackgroundColor(if (isAltLatched) activeBg else inactiveBg)
        btnLatchAlt?.setTextColor(if (isAltLatched) activeText else inactiveText)

        btnLatchShift?.setBackgroundColor(if (isShiftLatched) activeBg else inactiveBg)
        btnLatchShift?.setTextColor(if (isShiftLatched) activeText else inactiveText)

        btnLatchWin?.setBackgroundColor(if (isWinLatched) activeBg else inactiveBg)
        btnLatchWin?.setTextColor(if (isWinLatched) activeText else inactiveText)
    }

    private fun resetModifiers() {
        isCtrlLatched = false
        isAltLatched = false
        isShiftLatched = false
        isWinLatched = false
        updateModifierButtons()
    }

    private fun renderTabContent(tab: String) {
        val container = containerContent ?: return
        container.removeAllViews()

        when (tab) {
            "QUICK" -> renderQuickTab(container)
            "QWERTY" -> renderQwertyTab(container)
            "CYRILLIC" -> renderCyrillicTab(container)
            "FKEYS" -> renderFKeysTab(container)
            "NAV" -> renderNavTab(container)
        }
    }

    private fun makeKeyButton(label: String, keySym: Long, widthDp: Int = 42, heightDp: Int = 34, isAccent: Boolean = false): MaterialButton {
        val btn = MaterialButton(context)
        val density = context.resources.displayMetrics.density
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()

        btn.layoutParams = LinearLayout.LayoutParams(w, h).apply {
            setMargins(3, 3, 3, 3)
        }
        btn.text = label
        btn.textSize = if (label.length > 3) 9.5f else 11.5f
        btn.setPadding(0, 0, 0, 0)
        btn.minWidth = 0
        btn.minHeight = 0
        btn.cornerRadius = (6 * density).toInt()
        btn.setBackgroundColor(if (isAccent) Color.parseColor("#1F6FEB") else Color.parseColor("#21262D"))
        btn.setTextColor(Color.WHITE)

        btn.setOnClickListener {
            btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendKeyWithActiveModifiers(keySym)
        }
        return btn
    }

    private fun makeActionComboButton(label: String, keys: List<Long>, isAccent: Boolean = false): MaterialButton {
        val btn = MaterialButton(context)
        val density = context.resources.displayMetrics.density
        val h = (34 * density).toInt()

        btn.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h).apply {
            setMargins(3, 3, 3, 3)
        }
        btn.text = label
        btn.textSize = 10.5f
        btn.setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        btn.minWidth = 0
        btn.minHeight = 0
        btn.cornerRadius = (6 * density).toInt()
        btn.setBackgroundColor(if (isAccent) Color.parseColor("#8957E5") else Color.parseColor("#21262D"))
        btn.setTextColor(Color.WHITE)

        btn.setOnClickListener {
            btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendCombo(keys)
        }
        return btn
    }

    private fun sendKeyWithActiveModifiers(keySym: Long) {
        val mods = mutableListOf<Long>()
        if (isCtrlLatched) mods.add(VncKeyMapper.KEYSYM_CTRL_L)
        if (isAltLatched) mods.add(VncKeyMapper.KEYSYM_ALT_L)
        if (isShiftLatched) mods.add(VncKeyMapper.KEYSYM_SHIFT_L)
        if (isWinLatched) mods.add(VncKeyMapper.KEYSYM_SUPER_L)

        // 1. Send modifier down
        for (m in mods) {
            onKeySent(m, true)
            InputDebugger.logKeyEvent(true, 0, keySym = m, customKeyName = VncKeyMapper.getKeySymName(m))
        }

        // 2. Send main key down
        onKeySent(keySym, true)
        InputDebugger.logKeyEvent(true, 0, keySym = keySym, customKeyName = VncKeyMapper.getKeySymName(keySym))

        // 3. Post key up after holdTimeMs
        vncView.postDelayed({
            onKeySent(keySym, false)
            InputDebugger.logKeyEvent(false, 0, keySym = keySym, customKeyName = VncKeyMapper.getKeySymName(keySym))

            // 4. Send modifier up
            for (m in mods) {
                onKeySent(m, false)
                InputDebugger.logKeyEvent(false, 0, keySym = m, customKeyName = VncKeyMapper.getKeySymName(m))
            }
        }, holdTimeMs)

        // Auto-unlatch single-shot modifiers unless user locked
        resetModifiers()
    }

    private fun sendCombo(keys: List<Long>) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var delay = 0L

        // Press all down in sequence
        for (k in keys) {
            handler.postDelayed({
                onKeySent(k, true)
                InputDebugger.logKeyEvent(true, 0, keySym = k, customKeyName = VncKeyMapper.getKeySymName(k))
            }, delay)
            delay += 15L
        }

        // Release all in reverse order
        delay += holdTimeMs
        for (k in keys.reversed()) {
            handler.postDelayed({
                onKeySent(k, false)
                InputDebugger.logKeyEvent(false, 0, keySym = k, customKeyName = VncKeyMapper.getKeySymName(k))
            }, delay)
            delay += 15L
        }
    }

    private fun renderQuickTab(container: ViewGroup) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Row 1
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(makeActionComboButton("Ctrl+Alt+Del", listOf(VncKeyMapper.KEYSYM_CTRL_L, VncKeyMapper.KEYSYM_ALT_L, VncKeyMapper.KEYSYM_DELETE), true))
            addView(makeActionComboButton("Alt+Tab", listOf(VncKeyMapper.KEYSYM_ALT_L, VncKeyMapper.KEYSYM_TAB)))
            addView(makeActionComboButton("Alt+F4", listOf(VncKeyMapper.KEYSYM_ALT_L, 0xFFC1L))) // F4
            addView(makeActionComboButton("Win+R", listOf(VncKeyMapper.KEYSYM_SUPER_L, 'r'.code.toLong())))
            addView(makeActionComboButton("Win+E", listOf(VncKeyMapper.KEYSYM_SUPER_L, 'e'.code.toLong())))
            addView(makeActionComboButton("Win+D", listOf(VncKeyMapper.KEYSYM_SUPER_L, 'd'.code.toLong())))
        }
        val scroll1 = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row1)
        }
        root.addView(scroll1)

        // Row 2
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(makeActionComboButton("Ctrl+Esc", listOf(VncKeyMapper.KEYSYM_CTRL_L, VncKeyMapper.KEYSYM_ESCAPE)))
            addView(makeActionComboButton("Ctrl+Shift+Esc", listOf(VncKeyMapper.KEYSYM_CTRL_L, VncKeyMapper.KEYSYM_SHIFT_L, VncKeyMapper.KEYSYM_ESCAPE)))
            addView(makeActionComboButton("Ctrl+C", listOf(VncKeyMapper.KEYSYM_CTRL_L, 'c'.code.toLong())))
            addView(makeActionComboButton("Ctrl+V", listOf(VncKeyMapper.KEYSYM_CTRL_L, 'v'.code.toLong())))
            addView(makeActionComboButton("Ctrl+Z", listOf(VncKeyMapper.KEYSYM_CTRL_L, 'z'.code.toLong())))
            addView(makeActionComboButton("Ctrl+A", listOf(VncKeyMapper.KEYSYM_CTRL_L, 'a'.code.toLong())))
            addView(makeActionComboButton("Ctrl+X", listOf(VncKeyMapper.KEYSYM_CTRL_L, 'x'.code.toLong())))
        }
        val scroll2 = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row2)
        }
        root.addView(scroll2)

        // Row 3: Essential standalone keys
        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(makeKeyButton("Esc", VncKeyMapper.KEYSYM_ESCAPE, 46))
            addView(makeKeyButton("Tab", VncKeyMapper.KEYSYM_TAB, 46))
            addView(makeKeyButton("▲", VncKeyMapper.KEYSYM_UP, 40))
            addView(makeKeyButton("▼", VncKeyMapper.KEYSYM_DOWN, 40))
            addView(makeKeyButton("◀", VncKeyMapper.KEYSYM_LEFT, 40))
            addView(makeKeyButton("▶", VncKeyMapper.KEYSYM_RIGHT, 40))
            addView(makeKeyButton("↵ Enter", VncKeyMapper.KEYSYM_RETURN, 64, isAccent = true))
            addView(makeKeyButton("⌫ Del", VncKeyMapper.KEYSYM_BACKSPACE, 52))
        }
        val scroll3 = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row3)
        }
        root.addView(scroll3)

        container.addView(root)
    }

    private fun renderQwertyTab(container: ViewGroup) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val rows = listOf(
            listOf("`" to 0x60L, "1" to 0x31L, "2" to 0x32L, "3" to 0x33L, "4" to 0x34L, "5" to 0x35L, "6" to 0x36L, "7" to 0x37L, "8" to 0x38L, "9" to 0x39L, "0" to 0x30L, "-" to 0x2DL, "=" to 0x3DL, "⌫" to VncKeyMapper.KEYSYM_BACKSPACE),
            listOf("Tab" to VncKeyMapper.KEYSYM_TAB, "q" to 0x71L, "w" to 0x77L, "e" to 0x65L, "r" to 0x72L, "t" to 0x74L, "y" to 0x79L, "u" to 0x75L, "i" to 0x69L, "o" to 0x6FL, "p" to 0x70L, "[" to 0x5BL, "]" to 0x5DL, "\\" to 0x5CL),
            listOf("Caps" to VncKeyMapper.KEYSYM_CAPS_LOCK, "a" to 0x61L, "s" to 0x73L, "d" to 0x64L, "f" to 0x66L, "g" to 0x67L, "h" to 0x68L, "j" to 0x6AL, "k" to 0x6BL, "l" to 0x6CL, ";" to 0x3BL, "'" to 0x27L, "↵ Enter" to VncKeyMapper.KEYSYM_RETURN),
            listOf("Shift" to VncKeyMapper.KEYSYM_SHIFT_L, "z" to 0x7AL, "x" to 0x78L, "c" to 0x63L, "v" to 0x76L, "b" to 0x62L, "n" to 0x6EL, "m" to 0x6DL, "," to 0x2CL, "." to 0x2EL, "/" to 0x2FL, "Space" to 0x20L)
        )

        for (row in rows) {
            val ll = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((label, sym) in row) {
                    val w = when (label) {
                        "↵ Enter" -> 64
                        "Tab", "Caps", "Shift", "⌫" -> 50
                        "Space" -> 70
                        else -> 32
                    }
                    addView(makeKeyButton(label, sym, w))
                }
            }
            val scroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(ll)
            }
            root.addView(scroll)
        }
        container.addView(root)
    }

    private fun renderCyrillicTab(container: ViewGroup) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val rows = listOf(
            listOf("Ё" to 0x06B3L, "1" to 0x31L, "2" to 0x32L, "3" to 0x33L, "4" to 0x34L, "5" to 0x35L, "6" to 0x36L, "7" to 0x37L, "8" to 0x38L, "9" to 0x39L, "0" to 0x30L, "-" to 0x2DL, "=" to 0x3DL, "⌫" to VncKeyMapper.KEYSYM_BACKSPACE),
            listOf("Tab" to VncKeyMapper.KEYSYM_TAB, "й" to 0x06CAL, "ц" to 0x06C3L, "у" to 0x06D5L, "к" to 0x06CBL, "е" to 0x06C5L, "н" to 0x06CEL, "г" to 0x06C7L, "ш" to 0x06DBL, "щ" to 0x06DDL, "з" to 0x06DAL, "х" to 0x06C8L, "ъ" to 0x06DFL),
            listOf("Caps" to VncKeyMapper.KEYSYM_CAPS_LOCK, "ф" to 0x06C6L, "ы" to 0x06D9L, "в" to 0x06D7L, "а" to 0x06C1L, "п" to 0x06D0L, "р" to 0x06D2L, "о" to 0x06CFL, "л" to 0x06CCL, "д" to 0x06C4L, "ж" to 0x06D6L, "э" to 0x06DCL, "↵" to VncKeyMapper.KEYSYM_RETURN),
            listOf("Shift" to VncKeyMapper.KEYSYM_SHIFT_L, "я" to 0x06D1L, "ч" to 0x06DEL, "с" to 0x06D3L, "м" to 0x06CDL, "и" to 0x06C9L, "т" to 0x06D4L, "ь" to 0x06D8L, "б" to 0x06C2L, "ю" to 0x06C0L, "." to 0x2EL, "Пробел" to 0x20L)
        )

        for (row in rows) {
            val ll = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((label, sym) in row) {
                    val w = when (label) {
                        "↵" -> 50
                        "Tab", "Caps", "Shift", "⌫" -> 46
                        "Пробел" -> 70
                        else -> 32
                    }
                    addView(makeKeyButton(label, sym, w))
                }
            }
            val scroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(ll)
            }
            root.addView(scroll)
        }
        container.addView(root)
    }

    private fun renderFKeysTab(container: ViewGroup) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // F1..F6
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            for (i in 1..6) {
                val sym = 0xFFBEL + (i - 1)
                addView(makeKeyButton("F$i", sym, 50, 36, isAccent = (i == 1 || i == 11)))
            }
            addView(makeKeyButton("Esc", VncKeyMapper.KEYSYM_ESCAPE, 48, 36))
        }
        val scroll1 = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row1)
        }
        root.addView(scroll1)

        // F7..F12
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            for (i in 7..12) {
                val sym = 0xFFBEL + (i - 1)
                addView(makeKeyButton("F$i", sym, 50, 36, isAccent = (i == 12)))
            }
            addView(makeKeyButton("↵ Enter", VncKeyMapper.KEYSYM_RETURN, 58, 36))
        }
        val scroll2 = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row2)
        }
        root.addView(scroll2)

        container.addView(root)
    }

    private fun renderNavTab(container: ViewGroup) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Row 1: Nav keys
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(makeKeyButton("Insert", VncKeyMapper.KEYSYM_INSERT, 52))
            addView(makeKeyButton("Delete", VncKeyMapper.KEYSYM_DELETE, 52))
            addView(makeKeyButton("Home", VncKeyMapper.KEYSYM_HOME, 50))
            addView(makeKeyButton("End", VncKeyMapper.KEYSYM_END, 50))
            addView(makeKeyButton("PgUp", VncKeyMapper.KEYSYM_PAGE_UP, 50))
            addView(makeKeyButton("PgDn", VncKeyMapper.KEYSYM_PAGE_DOWN, 50))
            addView(makeKeyButton("PrtSc", VncKeyMapper.KEYSYM_PRINT_SCREEN, 50))
            addView(makeKeyButton("Pause", VncKeyMapper.KEYSYM_PAUSE, 50))
        }
        val scroll1 = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row1)
        }
        root.addView(scroll1)

        // Row 2: Numpad numbers & DPAD
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("7" to 0x37L, "8" to 0x38L, "9" to 0x39L, "/" to 0x2FL, "*" to 0x2AL, "-" to 0x2DL).forEach {
                addView(makeKeyButton(it.first, it.second, 38))
            }
            addView(makeKeyButton("▲", VncKeyMapper.KEYSYM_UP, 44, isAccent = true))
        }
        val scroll2 = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row2)
        }
        root.addView(scroll2)

        // Row 3: Numpad bottom & Arrows
        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("4" to 0x34L, "5" to 0x35L, "6" to 0x36L, "1" to 0x31L, "2" to 0x32L, "3" to 0x33L, "0" to 0x30L, "." to 0x2EL, "+" to 0x2BL).forEach {
                addView(makeKeyButton(it.first, it.second, 34))
            }
            addView(makeKeyButton("◀", VncKeyMapper.KEYSYM_LEFT, 40, isAccent = true))
            addView(makeKeyButton("▼", VncKeyMapper.KEYSYM_DOWN, 40, isAccent = true))
            addView(makeKeyButton("▶", VncKeyMapper.KEYSYM_RIGHT, 40, isAccent = true))
        }
        val scroll3 = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row3)
        }
        root.addView(scroll3)

        container.addView(root)
    }
}
