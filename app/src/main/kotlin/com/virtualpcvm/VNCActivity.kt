package com.virtualpcvm

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.virtualpcvm.databinding.ActivityVncBinding
import kotlinx.coroutines.launch

/**
 * Full-screen VNC viewer activity.
 *
 * Receives: host (String), port (Int), vmName (String), vmId (Long).
 * Implements RFB client using [VncClient] and renders via [VncView].
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // full-screen immersive
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        binding = ActivityVncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val host   = intent.getStringExtra(EXTRA_HOST)   ?: "127.0.0.1"
        val port   = intent.getIntExtra(EXTRA_PORT, 5901)
        val vmName = intent.getStringExtra(EXTRA_VM_NAME) ?: "ВМ"
        vmId       = intent.getLongExtra(EXTRA_VM_ID, -1L)

        binding.tvTitle.text = vmName

        // toolbar buttons
        binding.btnBack.setOnClickListener { finish() }
        binding.btnKeyboard.setOnClickListener { toggleKeyboard() }
        binding.btnCtrlAltDel.setOnClickListener { sendCtrlAltDel() }
        binding.btnStop.setOnClickListener { stopVm() }
        binding.btnLogs.setOnClickListener { toggleLogs() }

        // connect
        connect(host, port)
        startResourceMonitor()
    }

    private fun startResourceMonitor() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()

        lifecycleScope.launch {
            while (true) {
                am.getMemoryInfo(memInfo)
                val totalRam = memInfo.totalMem / (1024 * 1024)
                val availRam = memInfo.availMem / (1024 * 1024)
                val usedRam = totalRam - availRam
                binding.tvResourceMonitor.text = "Sys RAM: $usedRam / ${totalRam}MB"
                kotlinx.coroutines.delay(2000)
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
        val logs = QemuManager.getLogs(vmId).joinToString("\n")
        binding.tvLogs.text = logs
        binding.scrollLogs.post {
            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
        }
        binding.tvLogs.postDelayed({ updateLogs() }, 1000)
    }

    private fun connect(host: String, port: Int) {
        binding.progressConnecting.visibility = View.VISIBLE
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "Подключение к $host:$port..."

        val c = VncClient(host, port)
        client = c

        c.onConnected = { w, h, name ->
            runOnUiThread {
                binding.progressConnecting.visibility = View.GONE
                binding.tvStatus.visibility = View.GONE
                binding.vncView.visibility = View.VISIBLE
                Toast.makeText(this, "VNC: ${w}×${h} «$name»", Toast.LENGTH_SHORT).show()
            }
        }

        c.onDisconnected = { reason ->
            runOnUiThread {
                binding.progressConnecting.visibility = View.GONE
                binding.tvStatus.text = "Отключено: $reason"
                binding.tvStatus.visibility = View.VISIBLE
                Toast.makeText(this, "VNC отключился: $reason", Toast.LENGTH_LONG).show()
            }
        }

        binding.vncView.attach(c)
        c.connect(lifecycleScope)
    }

    /* ── keyboard helpers ── */

    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.isAcceptingText) {
            imm.hideSoftInputFromWindow(binding.vncView.windowToken, 0)
        } else {
            binding.vncView.requestFocus()
            imm.showSoftInput(binding.vncView, InputMethodManager.SHOW_IMPLICIT)
        }
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

    private fun sendCtrlAltDel() {
        val c = client ?: return
        // Ctrl down, Alt down, Del down, Del up, Alt up, Ctrl up
        c.sendKeyEvent(0xFFE3, true)   // Ctrl_L
        c.sendKeyEvent(0xFFE9, true)   // Alt_L
        c.sendKeyEvent(0xFFFF, true)   // Delete
        c.sendKeyEvent(0xFFFF, false)
        c.sendKeyEvent(0xFFE9, false)
        c.sendKeyEvent(0xFFE3, false)
    }

    /* ── Android keyCode → X11 keysym ── */
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
        KeyEvent.KEYCODE_DEL     -> 0xFF08 // Backspace
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
