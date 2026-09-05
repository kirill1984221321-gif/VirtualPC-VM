package com.virtualpcvm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        const val EXTRA_MONITOR_PORT = "monitor_port"
    }

    private lateinit var binding: ActivityVncBinding
    private var client: VncClient? = null
    private var vmId: Long = -1L
    private var host: String = "127.0.0.1"
    private var port: Int = 5901
    private var monitorPort: Int = -1

    // Modifier keys state for on-screen PC keyboard
    private var isCtrlActive = false
    private var isAltActive = false
    private var isShiftActive = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.applySavedLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive sticky mode using modern WindowInsetsControllerCompat
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        binding = ActivityVncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        host   = intent.getStringExtra(EXTRA_HOST)   ?: "127.0.0.1"
        port   = intent.getIntExtra(EXTRA_PORT, 5901)
        monitorPort = intent.getIntExtra(EXTRA_MONITOR_PORT, -1)
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
        binding.btnMouseMode.text = if (binding.vncView.isTouchMode) getString(R.string.vnc_touch_mode) else getString(R.string.vnc_mouse_mode)
        binding.btnMouseMode.setOnClickListener {
            val view = binding.vncView
            view.isTouchMode = !view.isTouchMode
            binding.btnMouseMode.text = if (view.isTouchMode) getString(R.string.vnc_touch_mode) else getString(R.string.vnc_mouse_mode)
            Toast.makeText(
                this,
                if (view.isTouchMode) getString(R.string.vnc_mode_touch_toast) else getString(R.string.vnc_mode_mouse_toast),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Zoom and keyboard controls
        binding.btnZoomIn.setOnClickListener { binding.vncView.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.vncView.zoomOut() }
        binding.btnFitScreen.setOnClickListener { binding.vncView.fitToScreen() }
        binding.btnSoftKeyboard.setOnClickListener { binding.vncView.toggleSoftKeyboard() }
        binding.btnExternalVnc.setOnClickListener { openExternalVncOrStore() }

        // PC Keyboard / Shortcuts helper toggle
        binding.btnToggleKeyboardBar.setOnClickListener {
            toggleShortcutsHelper()
        }

        binding.btnExpandShortcutsPill.setOnClickListener {
            expandShortcutsHelper()
        }

        binding.btnCollapseShortcuts.setOnClickListener {
            collapseShortcutsHelper()
        }

        binding.btnSaveSnapshot.setOnClickListener {
            showSnapshotDialog()
        }

        binding.btnLoadSnapshot.setOnClickListener {
            showSnapshotDialog()
        }

        // Setup bottom PC Keyboard & Shortcuts controls
        setupPcKeyboard()

        // Start connection & real-time monitoring
        connect(host, port)
        startResourceMonitor()
    }

    private fun showSnapshotDialog() {
        val vmConfig = VmRepository.getVm(this, vmId) ?: return
        SnapshotDialogHelper.show(this, lifecycleScope, vmConfig)
    }

    private fun expandShortcutsHelper() {
        binding.btnExpandShortcutsPill.visibility = View.GONE
        binding.layoutShortcutsHelper.visibility = View.VISIBLE
    }

    private fun collapseShortcutsHelper() {
        binding.layoutShortcutsHelper.visibility = View.GONE
        binding.btnExpandShortcutsPill.visibility = View.VISIBLE
    }

    private fun toggleShortcutsHelper() {
        if (binding.layoutShortcutsHelper.visibility == View.VISIBLE) {
            collapseShortcutsHelper()
        } else {
            expandShortcutsHelper()
        }
    }

    private fun updateMouseModeUI(isRelative: Boolean) {
        val modeLabel = if (isRelative) getString(R.string.vnc_mouse_mode) else getString(R.string.vnc_touch_mode)
        binding.btnMouseMode.text = modeLabel
        binding.btnHelperMouseMode.text = modeLabel
        binding.tvGesturesGuide.text = if (isRelative) {
            getString(R.string.sc_mode_trackpad_desc)
        } else {
            getString(R.string.sc_mode_touch_desc)
        }
    }

    private fun setupPcKeyboard() {
        // Mode switch buttons
        binding.btnMouseMode.setOnClickListener {
            val newMode = !binding.vncView.isRelativeMouseMode
            binding.vncView.setMouseMode(newMode)
            updateMouseModeUI(newMode)
            Toast.makeText(this, if (newMode) R.string.vnc_mode_mouse_toast else R.string.vnc_mode_touch_toast, Toast.LENGTH_SHORT).show()
        }

        binding.btnHelperMouseMode.setOnClickListener {
            val newMode = !binding.vncView.isRelativeMouseMode
            binding.vncView.setMouseMode(newMode)
            updateMouseModeUI(newMode)
            Toast.makeText(this, if (newMode) R.string.vnc_mode_mouse_toast else R.string.vnc_mode_touch_toast, Toast.LENGTH_SHORT).show()
        }

        // Shortcut command buttons
        binding.btnShortcutCad.setOnClickListener { sendCtrlAltDel() }
        binding.btnShortcutAltTab.setOnClickListener { sendCombo(listOf(0xFFE9, 0xFF09)) }
        binding.btnShortcutAltF4.setOnClickListener { sendCombo(listOf(0xFFE9, 0xFFC1)) }
        binding.btnShortcutWin.setOnClickListener { sendKey(0xFFEB) }
        binding.btnShortcutCtrlEsc.setOnClickListener { sendCombo(listOf(0xFFE3, 0xFF1B)) }
        binding.btnShortcutCopy.setOnClickListener { sendCombo(listOf(0xFFE3, 0x63)) }
        binding.btnShortcutPaste.setOnClickListener { sendCombo(listOf(0xFFE3, 0x76)) }
        binding.btnShortcutF11.setOnClickListener { sendKey(0xFFC8) }

        // Special single-press keys
        binding.keyEsc.setOnClickListener { sendKey(0xFF1B) }
        binding.keyTab.setOnClickListener { sendKey(0xFF09) }
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

        // Soft keyboard input button
        binding.keySoftKeyboard.setOnClickListener {
            toggleSoftKeyboard()
        }

        // External VNC button in top bar
        binding.btnExternalVnc.setOnClickListener {
            openExternalVncOrStore()
        }

        // Connection failure overlay actions
        binding.btnOpenExternalVnc.setOnClickListener {
            openExternalVncOrStore()
        }

        binding.btnRetryConnect.setOnClickListener {
            connect(host, port)
        }

        binding.btnCopyVncAddress.setOnClickListener {
            val addr = "127.0.0.1:$port"
            val clip = ClipData.newPlainText("VNC Address", addr)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(this, "Адрес $addr скопирован", Toast.LENGTH_SHORT).show()
        }

        binding.btnConnectionLogs.setOnClickListener {
            toggleLogs()
        }
    }

    private fun getInstalledVncPackage(): String? {
        val pm = packageManager
        val vncIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnc://127.0.0.1:$port"))
        val resolved = pm.queryIntentActivities(vncIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolved.isNotEmpty()) {
            return resolved.first().activityInfo.packageName
        }
        val knownPackages = listOf(
            "com.realvnc.viewer.android",
            "org.freedesktop.avnc",
            "com.iiordanov.freebVNC",
            "com.iiordanov.bVNC",
            "com.undatech.opaque"
        )
        for (pkg in knownPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        return null
    }

    private fun openExternalVncOrStore() {
        val pkg = getInstalledVncPackage()
        val address = "127.0.0.1:$port"
        val clip = ClipData.newPlainText("VNC Address", address)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)

        if (pkg != null) {
            Toast.makeText(this, "Адрес скопирован ($address), запуск VNC Viewer...", Toast.LENGTH_SHORT).show()
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vnc://$address")))
            }
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Внешний VNC Viewer")
                .setMessage("На устройстве не обнаружен сторонний VNC-клиент.\n\nАдрес сервера скопирован в буфер: $address\n\nЖелаете установить RealVNC Viewer из Google Play?")
                .setPositiveButton("Установить RealVNC") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.realvnc.viewer.android")))
                    } catch (_: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.realvnc.viewer.android")))
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun updateModifierButtonState(button: MaterialButton, active: Boolean, label: String) {
        button.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
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
        
        // Haptic feedback for key press
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        
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
        
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        
        for (k in keys) c.sendKeyEvent(k, true)
        for (k in keys.reversed()) c.sendKeyEvent(k, false)
    }

    private fun sendCtrlAltDel() {
        val c = client ?: return
        
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        
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
        binding.layoutConnectionActions.visibility = View.GONE
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "Подключение к $host:$port..."

        // Pre-configure external VNC button label
        val hasExternalVnc = getInstalledVncPackage() != null
        binding.btnOpenExternalVnc.text = if (hasExternalVnc) {
            "Открыть в установленном VNC (RealVNC/bVNC)"
        } else {
            "Установить RealVNC Viewer (Google Play)"
        }
        binding.btnCopyVncAddress.text = "Скопировать $host:$port"

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
                binding.layoutConnectionActions.visibility = View.VISIBLE

                if (reason.startsWith("DIAGNOSTICS_FAILED:")) {
                    val msg = reason.substringAfter(":")
                    binding.tvStatus.text = "Не удалось подключиться к VNC"
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Диагностика соединения")
                        .setMessage("$msg\n\nВы можете открыть внешний VNC Viewer или проверить лог консоли.")
                        .setPositiveButton("Внешний VNC") { _, _ ->
                            openExternalVncOrStore()
                        }
                        .setNeutralButton("Посмотреть логи") { _, _ ->
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
        // Start background service monitor loop if not already running
        QemuMonitorService.startMonitoring(lifecycleScope)

        lifecycleScope.launch {
            QemuMonitorService.metricsMap.collect { map ->
                val m = map[vmId]
                if (m != null && m.isRunning) {
                    binding.tvResourceMonitor.text = String.format("CPU: %.1f%% | RAM: %dMB", m.cpuPercent, m.ramUsedMb)
                } else {
                    val isRunning = if (vmId != -1L) QemuManager.isRunning(vmId) else true
                    val statusText = if (isRunning) "Active" else "Stopped"
                    binding.tvResourceMonitor.text = "State: $statusText"
                }
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
        val rawLogs = QemuLogger.getLogs(this, vmId)
        val logs = if (rawLogs.isNotEmpty()) {
            rawLogs.joinToString("<br>")
        } else {
            QemuManager.getLogs(vmId).joinToString("<br>")
        }
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
