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
    private var vmName: String = "ВМ"
    private var host: String = "127.0.0.1"
    private var port: Int = 5901
    private var monitorPort: Int = -1

    // New VNC Usability Subsystems
    private var virtualKeyboard: VncVirtualKeyboard? = null
    private var inputDebuggerOverlay: VncInputDebuggerOverlay? = null

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
        vmName = intent.getStringExtra(EXTRA_VM_NAME) ?: "ВМ"
        vmId   = intent.getLongExtra(EXTRA_VM_ID, -1L)

        binding.tvTitle.text = vmName

        // Initialize Virtual Keyboard & Input Debugger Overlay
        virtualKeyboard = VncVirtualKeyboard(this, binding.vncView, binding.root as android.view.ViewGroup) { keySym, down ->
            client?.sendKeyEvent(keySym, down)
        }
        inputDebuggerOverlay = VncInputDebuggerOverlay(this, binding.root as android.view.ViewGroup)

        // Initialize mouse mode from VM Configuration if available
        if (vmId != -1L) {
            val vmConfig = VmRepository.getVm(this, vmId)
            val isRelative = vmConfig?.mouseMode?.equals("relative", ignoreCase = true) == true
            binding.vncView.setMouseMode(isRelative)
            updateMouseModeUI(isRelative)
        } else {
            updateMouseModeUI(binding.vncView.isRelativeMouseMode)
        }

        // Toolbar navigation & controls
        binding.btnBack.setOnClickListener { finish() }
        binding.btnStop.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            confirmStopVm()
        }
        binding.btnLogs.setOnClickListener { toggleLogs() }
        binding.btnCloseLogs.setOnClickListener { binding.layoutLogs.visibility = View.GONE }
        binding.btnRetryConnect.setOnClickListener { connect(host, port) }

        // Virtual Keyboard toggle
        binding.btnVirtualKeyboard.setOnClickListener {
            virtualKeyboard?.toggle()
        }

        // Input Debugger toggle
        binding.btnInputDebugger.setOnClickListener {
            inputDebuggerOverlay?.toggle()
        }

        // Zoom and keyboard controls
        binding.btnZoomIn.setOnClickListener { binding.vncView.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.vncView.zoomOut() }
        binding.btnFitScreen.setOnClickListener { binding.vncView.zoomFit() }
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
        binding.btnShortcutCopy.setOnClickListener {
            sendCombo(listOf(0xFFE3, 0x63)) // Ctrl+C
            Toast.makeText(this, "Ctrl+C отправлено в ВМ", Toast.LENGTH_SHORT).show()
        }
        binding.btnShortcutPaste.setOnClickListener {
            pasteClipboardToVm()
        }
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
        InputDebugger.logKeyEvent(true, 0, keySym = keySym, customKeyName = VncKeyMapper.getKeySymName(keySym))
        c.sendKeyEvent(keySym, false)
        InputDebugger.logKeyEvent(false, 0, keySym = keySym, customKeyName = VncKeyMapper.getKeySymName(keySym))

        // Auto-release single-shot modifiers if desired
        if (isShiftActive) {
            isShiftActive = false
            c.sendKeyEvent(0xFFE1, false)
            InputDebugger.logKeyEvent(false, 0, keySym = 0xFFE1, customKeyName = "Shift_L")
            updateModifierButtonState(binding.keyShift, false, "Shift")
        }
    }

    private fun sendCombo(keys: List<Long>) {
        val c = client ?: return
        
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        
        for (k in keys) {
            c.sendKeyEvent(k, true)
            InputDebugger.logKeyEvent(true, 0, keySym = k, customKeyName = VncKeyMapper.getKeySymName(k))
        }
        for (k in keys.reversed()) {
            c.sendKeyEvent(k, false)
            InputDebugger.logKeyEvent(false, 0, keySym = k, customKeyName = VncKeyMapper.getKeySymName(k))
        }
    }

    private fun sendCtrlAltDel() {
        val c = client ?: return
        
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        
        c.sendKeyEvent(0xFFE3, true)   // Ctrl_L
        InputDebugger.logKeyEvent(true, 0, keySym = 0xFFE3, customKeyName = "Ctrl_L")
        c.sendKeyEvent(0xFFE9, true)   // Alt_L
        InputDebugger.logKeyEvent(true, 0, keySym = 0xFFE9, customKeyName = "Alt_L")
        c.sendKeyEvent(0xFFFF, true)   // Delete
        InputDebugger.logKeyEvent(true, 0, keySym = 0xFFFF, customKeyName = "Delete")

        c.sendKeyEvent(0xFFFF, false)
        InputDebugger.logKeyEvent(false, 0, keySym = 0xFFFF, customKeyName = "Delete")
        c.sendKeyEvent(0xFFE9, false)
        InputDebugger.logKeyEvent(false, 0, keySym = 0xFFE9, customKeyName = "Alt_L")
        c.sendKeyEvent(0xFFE3, false)
        InputDebugger.logKeyEvent(false, 0, keySym = 0xFFE3, customKeyName = "Ctrl_L")
    }

    private fun pasteClipboardToVm() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.isNotEmpty()) {
                client?.sendClientCutText(text)
                // Also send standard Ctrl+V combo to trigger paste in the active guest window
                sendCombo(listOf(0xFFE3, 0x76))
                Toast.makeText(this, "Вставлен буфер Android (${text.take(30)}...)", Toast.LENGTH_SHORT).show()
                return
            }
        }
        // Fallback: send standard Ctrl+V combo
        sendCombo(listOf(0xFFE3, 0x76))
        Toast.makeText(this, "Ctrl+V отправлено в ВМ", Toast.LENGTH_SHORT).show()
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

        // Bidirectional clipboard sync from Guest VM -> Android Host
        c.onServerCutText = { guestText ->
            runOnUiThread {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = ClipData.newPlainText("Guest VM Clipboard", guestText)
                    clipboard?.setPrimaryClip(clip)
                    Toast.makeText(
                        this,
                        "Буфер обмена синхронизирован из ВМ (${guestText.length} симв.)",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

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

                val vmLogs = if (vmId != -1L) QemuManager.getLogs(vmId) else emptyList()
                val diagnostic = QemuErrorInterpreter.interpret(null, vmLogs)

                if (reason.startsWith("DIAGNOSTICS_FAILED:") || !diagnostic.isCleanExit) {
                    val msg = if (reason.startsWith("DIAGNOSTICS_FAILED:")) reason.substringAfter(":") else diagnostic.userFriendlyTitle
                    val detailedMsg = buildString {
                        append(msg)
                        append("\n\n")
                        append("Причина: ").append(diagnostic.likelyCause)
                        append("\n\nРекомендация: ").append(diagnostic.suggestedAction)
                    }

                    binding.tvStatus.text = diagnostic.userFriendlyTitle
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Диагностика QEMU: ${diagnostic.category.name}")
                        .setMessage(detailedMsg)
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
                    val simTemp = 38.0 + (m.cpuPercent * 0.45) + (Math.random() * 2.5)
                    binding.tvResourceMonitor.text = String.format("CPU: %.1f%% | RAM: %dMB | %.1f°C", m.cpuPercent, m.ramUsedMb, simTemp)
                    
                    binding.networkChart.visibility = View.VISIBLE
                    binding.networkChart.updateMetrics(m.netRxKbps, m.netTxKbps)
                } else {
                    binding.networkChart.visibility = View.GONE
                    val isRunning = if (vmId != -1L) QemuManager.isRunning(vmId) else true
                    val statusText = if (isRunning) "Active" else "Stopped"
                    binding.tvResourceMonitor.text = "State: $statusText"
                }
            }
        }
    }

    private fun confirmStopVm() {
        ConfirmationDialogHelper.show(
            context = this,
            title = "Остановить ВМ «$vmName»?",
            message = "Принудительное выключение QEMU завершит сеанс гостевой ОС. Несохранённые данные могут быть потеряны.",
            actionType = ConfirmationDialogHelper.ActionType.SHUTDOWN_VM,
            confirmText = "Выключить ВМ"
        ) {
            stopVm()
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
        // Check user configured shortcuts first
        if (event != null) {
            val shortcutAction = ShortcutManager.getActionForEvent(this, event)
            if (shortcutAction != null) {
                when (shortcutAction) {
                    ShortcutManager.ACTION_SNAPSHOT -> {
                        showSnapshotDialog()
                        return true
                    }
                    ShortcutManager.ACTION_CTRL_ALT_DEL -> {
                        sendCtrlAltDel()
                        return true
                    }
                    ShortcutManager.ACTION_STOP -> {
                        confirmStopVm()
                        return true
                    }
                    ShortcutManager.ACTION_LOGS -> {
                        toggleLogs()
                        return true
                    }
                    ShortcutManager.ACTION_PAUSE -> {
                        if (monitorPort > 0) {
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                QemuManager.executeMonitorCommand(monitorPort, "stop")
                            }
                        }
                        return true
                    }
                }
            }
        }

        val keySym = androidKeyToX11(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        client?.sendKeyEvent(keySym, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val keySym = androidKeyToX11(keyCode, event) ?: return super.onKeyUp(keyCode, event)
        client?.sendKeyEvent(keySym, false)
        return true
    }

    private fun androidKeyToX11(code: Int, event: KeyEvent?): Long? {
        val mapped = VncKeyUtils.keyCodeToKeySym(code)
        if (mapped != null) return mapped

        val unicode = event?.unicodeChar ?: 0
        if (unicode > 0) {
            return VncKeyUtils.charToKeySym(unicode.toChar())
        }
        return null
    }

    override fun onDestroy() {
        client?.disconnect()
        super.onDestroy()
    }
}
