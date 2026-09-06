package com.virtualpcvm

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SystemMonitorDialog {

    @SuppressLint("SetJavaScriptEnabled")
    fun show(
        context: Context,
        scope: CoroutineScope,
        vmConfig: VmConfig
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_system_monitor, null)

        val tvName = view.findViewById<TextView>(R.id.tvMonitorVmName)
        val tvSub = view.findViewById<TextView>(R.id.tvMonitorVmSub)
        val chipStatus = view.findViewById<Chip>(R.id.chipMonitorStatus)
        val tvCpu = view.findViewById<TextView>(R.id.tvMonitorCpuVal)
        val tvRam = view.findViewById<TextView>(R.id.tvMonitorRamVal)
        val tvNet = view.findViewById<TextView>(R.id.tvMonitorNetVal)
        val tvDisk = view.findViewById<TextView>(R.id.tvMonitorDiskVal)
        val tvDetails = view.findViewById<TextView>(R.id.tvMonitorDetails)
        val webView = view.findViewById<WebView>(R.id.wvMetricsChart)

        tvName.text = vmConfig.name
        tvSub.text = "${vmConfig.architecture.label} · ${vmConfig.ramMb} MB RAM · ${vmConfig.cpuCores} Cores"

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.setBackgroundColor(0xFF121418.toInt())

        var isWebReady = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isWebReady = true
            }
        }
        webView.loadUrl("file:///android_asset/system_monitor.html")

        var job: Job? = null

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { d, _ ->
                d.dismiss()
            }
            .setOnDismissListener {
                job?.cancel()
                webView.destroy()
            }
            .create()

        dialog.show()

        fun formatSpeed(kbps: Float): String {
            return if (kbps >= 1024f) {
                String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1024f)
            } else {
                String.format(java.util.Locale.US, "%.1f KB/s", kbps)
            }
        }

        job = scope.launch {
            QemuMonitorService.metricsMap.collect { map ->
                val m = map[vmConfig.id]
                withContext(Dispatchers.Main) {
                    val isRunning = m?.isRunning ?: QemuManager.isRunning(vmConfig.id)
                    chipStatus.text = if (isRunning) context.getString(R.string.status_running) else context.getString(R.string.status_stopped)
                    chipStatus.setChipBackgroundColorResource(if (isRunning) R.color.chip_running else R.color.chip_stopped)

                    val cpu = m?.cpuPercent ?: 0.0f
                    val ramUsed = m?.ramUsedMb ?: 0L
                    val ramTotal = vmConfig.ramMb
                    val rx = m?.netRxKbps ?: 0.0f
                    val tx = m?.netTxKbps ?: 0.0f
                    val dr = m?.diskReadKbps ?: 0.0f
                    val dw = m?.diskWriteKbps ?: 0.0f

                    tvCpu.text = String.format(java.util.Locale.US, "%.1f%%", cpu)
                    tvRam.text = "$ramUsed / $ramTotal MB"
                    tvNet?.text = "⬇ ${formatSpeed(rx)} · ⬆ ${formatSpeed(tx)}"
                    tvDisk?.text = "R: ${formatSpeed(dr)} · W: ${formatSpeed(dw)}"

                    val pid = if (m != null && m.pid > 0) m.pid.toString() else "--"
                    val threads = if (m != null && m.threads > 0) m.threads.toString() else "--"
                    tvDetails.text = "PID: $pid · Threads: $threads · VNC: 127.0.0.1:${vmConfig.vncPort} · Monitor: ${vmConfig.monitorPort}"

                    if (isWebReady) {
                        webView.evaluateJavascript(
                            String.format(
                                java.util.Locale.US,
                                "updateMetrics(%.1f, %d, %d, %.1f, %.1f, %.1f, %.1f);",
                                cpu, ramUsed, ramTotal, rx, tx, dr, dw
                            ),
                            null
                        )
                    }
                }
            }
        }
    }
}
