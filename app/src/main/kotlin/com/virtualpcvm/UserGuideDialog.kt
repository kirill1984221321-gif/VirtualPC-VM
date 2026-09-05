package com.virtualpcvm

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

object UserGuideDialog {

    fun show(context: Context) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_user_guide, null)
        val isRu = Locale.getDefault().language.equals("ru", ignoreCase = true)

        if (!isRu) {
            view.findViewById<TextView>(R.id.tvGuideTitle)?.text = "User Guide & Help"
            view.findViewById<TextView>(R.id.tvGuideSub)?.text = "Comprehensive setup and usage guide for Virtual PC"

            view.findViewById<TextView>(R.id.tvGuideSec1Title)?.text = "🚀 1. Quick Start"
            view.findViewById<TextView>(R.id.tvGuideSec1Body)?.text =
                "• Tap «+ Create VM» on the dashboard.\n• Select a configuration preset (Windows XP, Windows 7, Alpine Linux) or customize settings.\n• Set your virtual disk size (qcow2) and choose an ISO installer image.\n• Press «Start» to boot QEMU and enter the VNC screen."

            view.findViewById<TextView>(R.id.tvGuideSec2Title)?.text = "⚙ 2. Hardware Settings & Sliders"
            view.findViewById<TextView>(R.id.tvGuideSec2Body)?.text =
                "• RAM Memory Slider: Adjust allocated RAM. 512-1024 MB for Windows XP, 2048-4096 MB for Windows 7/10.\n• CPU Cores Slider: 1-8 cores with MTTCG multi-threading enabled.\n• Network Adapters: rtl8139 (universal for Win XP), virtio-net-pci (maximum speed for Linux), e1000 (Intel Gigabit).\n• Input Devices: USB Tablet provides pixel-perfect touch alignment without cursor drift."

            view.findViewById<TextView>(R.id.tvGuideSec3Title)?.text = "🖱 3. Mouse & Touch Controls"
            view.findViewById<TextView>(R.id.tvGuideSec3Body)?.text =
                "• Touch Mode: Taps directly where your finger touches. Tap = Left Click, Long Press = Right Click (context menu), 2-Finger Tap = Middle Click.\n• Trackpad Mode: Functions as a laptop touchpad with arrow cursor. Drag to move, 2-finger vertical slide to scroll the mouse wheel."

            view.findViewById<TextView>(R.id.tvGuideSec4Title)?.text = "⌨ 4. Keyboard Shortcuts Helper"
            view.findViewById<TextView>(R.id.tvGuideSec4Body)?.text =
                "• In VNC view, tap the floating «⌨ Shortcuts» button at the bottom.\n• Quick one-tap commands: Ctrl+Alt+Del, Alt+Tab, Alt+F4, Win key, Ctrl+Esc, Copy/Paste, and F1-F12 keys.\n• Tap «⌨ IME» to bring up your phone's native on-screen keyboard."

            view.findViewById<TextView>(R.id.tvGuideSec5Title)?.text = "📸 5. Snapshots Management"
            view.findViewById<TextView>(R.id.tvGuideSec5Body)?.text =
                "• Create instant checkpoints of your VM and disk state.\n• In the VM menu select «Snapshots» or tap the disk icon in VNC.\n• Create named checkpoints (e.g. «clean_install») and revert anytime with a single tap."

            view.findViewById<TextView>(R.id.tvGuideSec6Title)?.text = "📊 6. Real-time System Monitor"
            view.findViewById<TextView>(R.id.tvGuideSec6Body)?.text =
                "• Tap «System Monitor» on any running VM or in the top menu.\n• View real-time animated CPU and RAM utilization graphs, active QEMU PID, and thread counts."
        }

        MaterialAlertDialogBuilder(context)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
