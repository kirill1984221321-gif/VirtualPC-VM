package com.virtualpcvm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup

/**
 * Reusable real-time log viewer component for the VM dashboard.
 * Displays QEMU console output, highlights boot parameters, hardware initialization,
 * kernel messages, and system errors.
 */
class RealtimeLogView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tvLiveIndicator: TextView
    private val tvViewerTitle: TextView
    private val tvActiveVmBadge: TextView
    private val btnCopyLogs: MaterialButton
    private val btnClearLogs: MaterialButton
    private val btnToggleLogs: MaterialButton
    private val layoutLogBody: View
    private val scrollLogs: ScrollView
    private val tvConsoleLogs: TextView
    private val tvLogStats: TextView
    private val tvAutoScrollToggle: TextView
    private val chipGroupFilter: ChipGroup

    private var isExpanded = true
    private var isAutoScroll = true
    private var activeVmId: Long? = null
    private var activeVmName: String? = null

    private val allLogEntries = mutableListOf<QemuLogger.LogEntry>()
    private var currentFilter: FilterType = FilterType.ALL

    enum class FilterType {
        ALL, BOOT, ERRORS, STDOUT
    }

    private val logListener: (QemuLogger.LogEntry) -> Unit = { entry ->
        post {
            addLogEntry(entry)
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_realtime_log_view, this, true)

        tvLiveIndicator = findViewById(R.id.tvLiveIndicator)
        tvViewerTitle = findViewById(R.id.tvLogViewerTitle)
        tvActiveVmBadge = findViewById(R.id.tvActiveVmBadge)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnToggleLogs = findViewById(R.id.btnToggleLogs)
        layoutLogBody = findViewById(R.id.layoutLogBody)
        scrollLogs = findViewById(R.id.scrollLogs)
        tvConsoleLogs = findViewById(R.id.tvConsoleLogs)
        tvLogStats = findViewById(R.id.tvLogStats)
        tvAutoScrollToggle = findViewById(R.id.tvAutoScrollToggle)
        chipGroupFilter = findViewById(R.id.chipGroupLogFilter)

        btnToggleLogs.setOnClickListener {
            toggleExpanded()
        }

        findViewById<View>(R.id.headerLogBar).setOnClickListener {
            toggleExpanded()
        }

        btnClearLogs.setOnClickListener {
            clearLogs()
        }

        btnCopyLogs.setOnClickListener {
            copyLogsToClipboard()
        }

        tvAutoScrollToggle.setOnClickListener {
            isAutoScroll = !isAutoScroll
            tvAutoScrollToggle.text = if (isAutoScroll) "Автопрокрутка: ВКЛ" else "Автопрокрутка: ВЫКЛ"
            tvAutoScrollToggle.setTextColor(if (isAutoScroll) Color.parseColor("#80D8FF") else Color.parseColor("#9E9E9E"))
        }

        setupFilterChips()
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        layoutLogBody.visibility = if (isExpanded) View.VISIBLE else View.GONE
        btnToggleLogs.text = if (isExpanded) "▼ Свернуть" else "▲ Развернуть"
    }

    private fun setupFilterChips() {
        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipFilterBoot) -> FilterType.BOOT
                checkedIds.contains(R.id.chipFilterErrors) -> FilterType.ERRORS
                checkedIds.contains(R.id.chipFilterStdout) -> FilterType.STDOUT
                else -> FilterType.ALL
            }
            renderLogs()
        }
    }

    fun attachVm(vmId: Long, vmName: String, isRunning: Boolean) {
        if (activeVmId != vmId) {
            activeVmId?.let { QemuLogger.removeListener(it, logListener) }
            activeVmId = vmId
            activeVmName = vmName
            QemuLogger.addListener(vmId, logListener)
        }

        tvActiveVmBadge.text = "$vmName (ID: $vmId)"
        updateRunningStatus(isRunning)

        // Preload memory logs
        allLogEntries.clear()
        val initialLogs = QemuLogger.getLogs(context, vmId)
        if (initialLogs.isNotEmpty()) {
            initialLogs.forEach { line ->
                val level = when {
                    line.contains("[ERROR]") || line.contains("[STDERR]") -> QemuLogger.LogEntry.Level.ERROR
                    line.contains("[WARNING]") -> QemuLogger.LogEntry.Level.WARNING
                    line.contains("[STARTUP]") -> QemuLogger.LogEntry.Level.STARTUP
                    line.contains("[INFO]") -> QemuLogger.LogEntry.Level.INFO
                    else -> QemuLogger.LogEntry.Level.STDOUT
                }
                allLogEntries.add(QemuLogger.LogEntry(level = level, message = line))
            }
        }
        renderLogs()
    }

    fun updateRunningStatus(isRunning: Boolean) {
        if (isRunning) {
            tvLiveIndicator.text = "●"
            tvLiveIndicator.setTextColor(Color.parseColor("#00E676"))
            tvActiveVmBadge.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvLiveIndicator.text = "○"
            tvLiveIndicator.setTextColor(Color.parseColor("#9E9E9E"))
            tvActiveVmBadge.setTextColor(Color.parseColor("#9E9E9E"))
        }
    }

    private fun addLogEntry(entry: QemuLogger.LogEntry) {
        allLogEntries.add(entry)
        if (allLogEntries.size > 2500) {
            allLogEntries.removeAt(0)
        }

        if (matchesFilter(entry, currentFilter)) {
            appendFormattedLine(entry)
            updateStats()
            if (isAutoScroll) {
                scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun matchesFilter(entry: QemuLogger.LogEntry, filter: FilterType): Boolean {
        val msgLower = entry.message.lowercase()
        return when (filter) {
            FilterType.ALL -> true
            FilterType.BOOT -> entry.level == QemuLogger.LogEntry.Level.STARTUP ||
                    msgLower.contains("boot") || msgLower.contains("bios") ||
                    msgLower.contains("init") || msgLower.contains("kernel") ||
                    msgLower.contains("cpu") || msgLower.contains("machine")
            FilterType.ERRORS -> entry.level == QemuLogger.LogEntry.Level.ERROR ||
                    entry.level == QemuLogger.LogEntry.Level.STDERR ||
                    msgLower.contains("error") || msgLower.contains("fail") ||
                    msgLower.contains("denied") || msgLower.contains("abort")
            FilterType.STDOUT -> entry.level == QemuLogger.LogEntry.Level.STDOUT
        }
    }

    private fun renderLogs() {
        if (allLogEntries.isEmpty()) {
            tvConsoleLogs.text = "[Логи QEMU отсутствуют. Ожидание запуска виртуальной машины...]"
            tvLogStats.text = "Строк: 0"
            return
        }

        val filtered = allLogEntries.filter { matchesFilter(it, currentFilter) }
        val ssb = SpannableStringBuilder()

        filtered.takeLast(600).forEach { entry ->
            val start = ssb.length
            val formatted = if (entry.message.startsWith("[")) entry.message else entry.format()
            ssb.append(formatted).append("\n")
            val end = ssb.length

            val color = when (entry.level) {
                QemuLogger.LogEntry.Level.ERROR, QemuLogger.LogEntry.Level.STDERR -> Color.parseColor("#FF5252") // Red
                QemuLogger.LogEntry.Level.WARNING -> Color.parseColor("#FFA726") // Orange
                QemuLogger.LogEntry.Level.STARTUP -> Color.parseColor("#00E5FF") // Cyan
                QemuLogger.LogEntry.Level.INFO -> Color.parseColor("#69F0AE") // Light Green
                QemuLogger.LogEntry.Level.STDOUT -> Color.parseColor("#CFD8DC") // Gray-white
            }

            ssb.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        tvConsoleLogs.text = ssb
        updateStats()

        if (isAutoScroll) {
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun appendFormattedLine(entry: QemuLogger.LogEntry) {
        val ssb = SpannableStringBuilder(tvConsoleLogs.text)
        val start = ssb.length
        val formatted = if (entry.message.startsWith("[")) entry.message else entry.format()
        ssb.append(formatted).append("\n")
        val end = ssb.length

        val color = when (entry.level) {
            QemuLogger.LogEntry.Level.ERROR, QemuLogger.LogEntry.Level.STDERR -> Color.parseColor("#FF5252")
            QemuLogger.LogEntry.Level.WARNING -> Color.parseColor("#FFA726")
            QemuLogger.LogEntry.Level.STARTUP -> Color.parseColor("#00E5FF")
            QemuLogger.LogEntry.Level.INFO -> Color.parseColor("#69F0AE")
            QemuLogger.LogEntry.Level.STDOUT -> Color.parseColor("#CFD8DC")
        }

        ssb.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvConsoleLogs.text = ssb
    }

    private fun updateStats() {
        val total = allLogEntries.size
        val errors = allLogEntries.count { it.level == QemuLogger.LogEntry.Level.ERROR || it.level == QemuLogger.LogEntry.Level.STDERR }
        tvLogStats.text = "Строк: $total | Ошибок: $errors"
    }

    fun clearLogs() {
        allLogEntries.clear()
        tvConsoleLogs.text = "[Логи консоли очищены]"
        tvLogStats.text = "Строк: 0"
        activeVmId?.let { QemuLogger.clearLogs(context, it) }
    }

    private fun copyLogsToClipboard() {
        val text = tvConsoleLogs.text.toString()
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("QEMU Logs", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Логи QEMU скопированы в буфер обмена", Toast.LENGTH_SHORT).show()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        activeVmId?.let { QemuLogger.removeListener(it, logListener) }
    }
}
