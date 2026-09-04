package com.virtualpcvm

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virtualpcvm.databinding.ActivityVncBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full-screen VNC viewer activity with on-screen PC keyboard bar,
 * real-time process monitoring, and connection diagnostics.
 */
class VNCActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST    = "vnc_host"
        const val EXTRA_PORT    = "vnc_port"
        const val EXTRA_VM_NAME = "vm_name"
        const val EXTRA_VM_ID   = "vm_id"
    }

    private lateinit var binding: ActivityVncBinding
    private var client: VncClient? = null
    private var vmId: Long = -1L
    private var host: String = "127.0.0.1"
    private var port: Int = 5901

    // Modifier keys state for on-screen PC keyboard
    private var isCtrlActive = false
    private var isAltActive = false
    private var isShiftActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive sticky mode
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        binding = ActivityVncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        host   = intent.getStringExtra(EXTRA_HOST)   ?: "127.0.0.1"
        port   = intent.getIntExtra(EXTRA_PORT, 5901)
        val vmName = intent.getStringExtra(EXTRA_VM_NAME) ?: "ВМ"
        vmId   = intent.getLongExtra(EXTRA_VM_ID, -1L)

        binding.tvTitle.text = vmName

        // Toolbar navigation & controls
        binding.btnBack.setOnClickListener { finish() }
        binding.btnStop.setOnClickListener { stopVm() }
        binding.btnLogs.setOnClickListener { toggleLogs() }
        binding.btnCloseLogs.setOnClickListener { binding.layoutLogs.visibility = View.GONE }
        binding.btnRetryConnect.setOnClickListener { connect(host, port) }

        // Mouse mode toggle (Touch vs Trackpad)
        binding.btnMouseMode.setOnClickListener {
            val view = binding.vncView
            view.isTouchMode = !view.isTouchMode
            binding.btnMouseMode.text = if (view.isTouchMode) "🖱 Тач" else "🖱 Мышь"
            Toast.makeText(
                this,
                if (view.isTouchMode) "Режим: Сенсорный экран" else "Режим: Мышь / Курсор",
                Toast.LENGTH_SHORT
            ).show()
        }

        // PC Keyboard bar toggle
        binding.btnToggleKeyboardBar.setOnClickListener {
            val isVisible = binding.layoutPcKeyboardBar.visibility == View.VISIBLE
            binding.layoutPcKeyboardBar.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        // Setup bottom PC Keyboard controls
        setupPcKeyboard()

        // Start connection & real-time monitoring
        connect(host, port)
        startResourceMonitor()
    }

    private fun setupPcKeyboard() {
        // Special single-press keys
        binding.keyEsc.setOnClickListener { sendKey(0xFF1B) }
        binding.keyTab.setOnClickListener { sendKey(0xFF09) }
        binding.keyWin.setOnClickListener { sendKey(0xFFEB) }
        binding.keyEnter.setOnClickListener { sendKey(0xFF0D) }
        binding.keyBackspace.setOnClickListener { sendKey(0xFF08) }

        // Navigation arrows
        binding.keyUp.setOnClickListener { sendKey(0xFF52) }
        binding.keyDown.setOnClickListener { sendKey(0xFF54) }
        binding.keyLeft.setOnClickListener { sendKey(0xFF51) }
        binding.keyRight.setOnClickListener { sendKey(0xFF53) }

        // Modifier toggles: Ctrl
        binding.keyCtrl.setOnClickListener {
            isCtrlActive = !isCtrlActive
            client?.sendKeyEvent(0xFFE3, isCtrlActive)
            updateModifierButtonState(binding.keyCtrl, isCtrlActive, "Ctrl")
        }

        // Modifier toggles: Alt
        binding.keyAlt.setOnClickListener {
            isAltActive = !isAltActive
            client?.sendKeyEvent(0xFFE9, isAltActive)
            updateModifierButtonState(binding.keyAlt, isAltActive, "Alt")
        }

        // Modifier toggles: Shift
        binding.keyShift.setOnClickListener {
            isShiftActive = !isShiftActive
            client?.sendKeyEvent(0xFFE1, isShiftActive)
            updateModifierButtonState(binding.keyShift, isShiftActive, "Shift")
        }

        // F1-F12 popup menu
        binding.keyFKeys.setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            for (i in 1..12) {
                popup.menu.add("F$i")
            }
            popup.setOnMenuItemClickListener { item ->
                val fNum = item.title.toString().removePrefix("F").toIntOrNull() ?: 1
                val keySym = 0xFFBE + (fNum - 1)
                sendKey(keySym.toLong())
                true
            }
            popup.show()
        }

        // Common shortcut combos
        binding.keyCombos.setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add("Ctrl + Alt + Del")
            popup.menu.add("Alt + F4")
            popup.menu.add("Alt + Tab")
            popup.menu.add("Ctrl + Esc (Пуск)")
            popup.menu.add("Ctrl + C (Копировать)")
            popup.menu.add("Ctrl + V (Вставить)")
            popup.menu.add("Ctrl + Z (Отмена)")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Ctrl + Alt + Del" -> sendCtrlAltDel()
                    "Alt + F4" -> sendCombo(listOf(0xFFE9, 0xFFC1))
                    "Alt + Tab" -> sendCombo(listOf(0xFFE9, 0xFF09))
                    "Ctrl + Esc (Пуск)" -> sendCombo(listOf(0xFFE3, 0xFF1B))
                    "Ctrl + C (Копировать)" -> sendCombo(listOf(0xFFE3, 0x63))
                    "Ctrl + V (Вставить)" -> sendCombo(listOf(0xFFE3, 0x76))
                    "Ctrl + Z (Отмена)" -> sendCombo(listOf(0xFFE3, 0x7A))
                }
                true
            }
            popup.show()
        }

        // Soft keyboard input button
        binding.keySoftKeyboard.setOnClickListener {
            toggleSoftKeyboard()
        }
    }

    private fun updateModifierButtonState(button: MaterialButton, active: Boolean, label: String) {
        if (active) {
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.chip_running))
            button.text = "● $label"
        } else {
            button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
            button.text = label
        }
    }

    private fun sendKey(keySym: Long) {
        val c = client ?: return
        c.sendKeyEvent(keySym, true)
        c.sendKeyEvent(keySym, false)
        // Auto-release single-shot modifiers if desired
        if (isShiftActive) {
            isShiftActive = false
            c.sendKeyEvent(0xFFE1, false)
            updateModifierButtonState(binding.keyShift, false, "Shift")
        }
    }

    private fun sendCombo(keys: List<Long>) {
        val c = client ?: return
        for (k in keys) c.sendKeyEvent(k, true)
        for (k in keys.reversed()) c.sendKeyEvent(k, false)
    }

    private fun sendCtrlAltDel() {
        val c = client ?: return
        c.sendKeyEvent(0xFFE3, true)   // Ctrl_L
        c.sendKeyEvent(0xFFE9, true)   // Alt_L
        c.sendKeyEvent(0xFFFF, true)   // Delete
        c.sendKeyEvent(0xFFFF, false)
        c.sendKeyEvent(0xFFE9, false)
        c.sendKeyEvent(0xFFE3, false)
    }

    private fun connect(host: String, port: Int) {
        client?.disconnect()

        binding.progressConnecting.visibility = View.VISIBLE
        binding.btnRetryConnect.visibility = View.GONE
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "Подключение к $host:$port..."

        val c = VncClient(host, port)
        c.isProcessAliveCheck = {
            if (vmId != -1L) QemuManager.isRunning(vmId) else true
        }
        client = c

        c.onConnectingProgress = { attempt, maxAttempts ->
            runOnUiThread {
                binding.tvStatus.text = "Подключение к $host:$port ($attempt/$maxAttempts)..."
            }
        }

        c.onConnected = { w, h, name ->
            runOnUiThread {
                binding.layoutConnecting.visibility = View.GONE
                binding.vncView.visibility = View.VISIBLE
                Toast.makeText(this, "VNC: ${w}×${h} «$name»", Toast.LENGTH_SHORT).show()
            }
        }

        c.onDisconnected = { reason ->
            runOnUiThread {
                binding.progressConnecting.visibility = View.GONE
                binding.layoutConnecting.visibility = View.VISIBLE
                binding.btnRetryConnect.visibility = View.VISIBLE

                if (reason.startsWith("DIAGNOSTICS_FAILED:")) {
                    val msg = reason.substringAfter(":")
                    binding.tvStatus.text = "Ошибка подключения к ВМ"
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Диагностика соединения")
                        .setMessage(msg)
                        .setPositiveButton("Посмотреть логи") { _, _ ->
                            toggleLogs()
                        }
                        .setNegativeButton("Закрыть", null)
                        .show()
                } else {
                    binding.tvStatus.text = "Отключено: $reason"
                }
            }
        }

        binding.vncView.attach(c)
        c.connect(lifecycleScope)
    }

    private fun startResourceMonitor() {
        lifecycleScope.launch {
            while (isActive) {
                val runtime = Runtime.getRuntime()
                val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                val maxMemMb = runtime.maxMemory() / (1024 * 1024)

                val isRunning = if (vmId != -1L) QemuManager.isRunning(vmId) else true
                val statusText = if (isRunning) "ВМ активна" else "Остановлена"
                binding.tvResourceMonitor.text = "ОЗУ: ${usedMemMb}MB / ${maxMemMb}MB [$statusText]"

                delay(2000)
            }
        }
    }

    private fun stopVm() {
        if (vmId != -1L) {
            QemuManager.stop(vmId)
            Toast.makeText(this, "ВМ остановлена", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun toggleLogs() {
        if (binding.layoutLogs.visibility == View.VISIBLE) {
            binding.layoutLogs.visibility = View.GONE
        } else {
            binding.layoutLogs.visibility = View.VISIBLE
            updateLogs()
        }
    }

    private fun updateLogs() {
        if (vmId == -1L || binding.layoutLogs.visibility != View.VISIBLE) return
        val logs = QemuManager.getLogs(vmId).joinToString("<br>")
        binding.tvLogs.text = android.text.Html.fromHtml(
            if (logs.isBlank()) "Логи пусты. Процесс ещё не вывел сообщений." else logs,
            android.text.Html.FROM_HTML_MODE_COMPACT
        )
        binding.scrollLogs.post {
            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
        }
        binding.tvLogs.postDelayed({ updateLogs() }, 1000)
    }

    private fun toggleSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        binding.vncView.requestFocus()
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val keySym = androidKeyToX11(keyCode) ?: return super.onKeyDown(keyCode, event)
        client?.sendKeyEvent(keySym, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val keySym = androidKeyToX11(keyCode) ?: return super.onKeyUp(keyCode, event)
        client?.sendKeyEvent(keySym, false)
        return true
    }

    private fun androidKeyToX11(code: Int): Long? = when (code) {
        KeyEvent.KEYCODE_A -> 0x61; KeyEvent.KEYCODE_B -> 0x62
        KeyEvent.KEYCODE_C -> 0x63; KeyEvent.KEYCODE_D -> 0x64
        KeyEvent.KEYCODE_E -> 0x65; KeyEvent.KEYCODE_F -> 0x66
        KeyEvent.KEYCODE_G -> 0x67; KeyEvent.KEYCODE_H -> 0x68
        KeyEvent.KEYCODE_I -> 0x69; KeyEvent.KEYCODE_J -> 0x6A
        KeyEvent.KEYCODE_K -> 0x6B; KeyEvent.KEYCODE_L -> 0x6C
        KeyEvent.KEYCODE_M -> 0x6D; KeyEvent.KEYCODE_N -> 0x6E
        KeyEvent.KEYCODE_O -> 0x6F; KeyEvent.KEYCODE_P -> 0x70
        KeyEvent.KEYCODE_Q -> 0x71; KeyEvent.KEYCODE_R -> 0x72
        KeyEvent.KEYCODE_S -> 0x73; KeyEvent.KEYCODE_T -> 0x74
        KeyEvent.KEYCODE_U -> 0x75; KeyEvent.KEYCODE_V -> 0x76
        KeyEvent.KEYCODE_W -> 0x77; KeyEvent.KEYCODE_X -> 0x78
        KeyEvent.KEYCODE_Y -> 0x79; KeyEvent.KEYCODE_Z -> 0x7A
        KeyEvent.KEYCODE_0 -> 0x30; KeyEvent.KEYCODE_1 -> 0x31
        KeyEvent.KEYCODE_2 -> 0x32; KeyEvent.KEYCODE_3 -> 0x33
        KeyEvent.KEYCODE_4 -> 0x34; KeyEvent.KEYCODE_5 -> 0x35
        KeyEvent.KEYCODE_6 -> 0x36; KeyEvent.KEYCODE_7 -> 0x37
        KeyEvent.KEYCODE_8 -> 0x38; KeyEvent.KEYCODE_9 -> 0x39
        KeyEvent.KEYCODE_SPACE   -> 0x20
        KeyEvent.KEYCODE_ENTER   -> 0xFF0D
        KeyEvent.KEYCODE_DEL     -> 0xFF08
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF
        KeyEvent.KEYCODE_ESCAPE  -> 0xFF1B
        KeyEvent.KEYCODE_TAB     -> 0xFF09
        KeyEvent.KEYCODE_DPAD_LEFT  -> 0xFF51; KeyEvent.KEYCODE_DPAD_UP    -> 0xFF52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53; KeyEvent.KEYCODE_DPAD_DOWN  -> 0xFF54
        KeyEvent.KEYCODE_F1  -> 0xFFBE; KeyEvent.KEYCODE_F2  -> 0xFFBF
        KeyEvent.KEYCODE_F3  -> 0xFFC0; KeyEvent.KEYCODE_F4  -> 0xFFC1
        KeyEvent.KEYCODE_F5  -> 0xFFC2; KeyEvent.KEYCODE_F6  -> 0xFFC3
        KeyEvent.KEYCODE_F7  -> 0xFFC4; KeyEvent.KEYCODE_F8  -> 0xFFC5
        KeyEvent.KEYCODE_F9  -> 0xFFC6; KeyEvent.KEYCODE_F10 -> 0xFFC7
        KeyEvent.KEYCODE_F11 -> 0xFFC8; KeyEvent.KEYCODE_F12 -> 0xFFC9
        KeyEvent.KEYCODE_CTRL_LEFT  -> 0xFFE3; KeyEvent.KEYCODE_CTRL_RIGHT  -> 0xFFE4
        KeyEvent.KEYCODE_ALT_LEFT   -> 0xFFE9; KeyEvent.KEYCODE_ALT_RIGHT   -> 0xFFEA
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xFFE1; KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xFFE2
        else -> null
    }?.toLong()

    override fun onDestroy() {
        client?.disconnect()
        super.onDestroy()
    }
}
