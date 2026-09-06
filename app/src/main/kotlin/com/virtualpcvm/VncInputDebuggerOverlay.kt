package com.virtualpcvm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class VncInputDebuggerOverlay(
    private val context: Context,
    private val rootLayout: ViewGroup
) {

    private var overlayView: View? = null
    private var containerEvents: LinearLayout? = null
    private var scrollLogs: ScrollView? = null
    private var tvLiveSummary: TextView? = null

    private var currentFilter: String = "ALL"
    private var isSubscribed = false

    private val eventListener: (InputEventRecord) -> Unit = { record ->
        overlayView?.post {
            if (isVisible()) {
                appendEventView(record)
                updateSummary()
            }
        }
    }

    fun isVisible(): Boolean = overlayView?.visibility == View.VISIBLE

    fun show() {
        if (overlayView == null) {
            initView()
        }
        overlayView?.visibility = View.VISIBLE
        overlayView?.bringToFront()
        if (!isSubscribed) {
            InputDebugger.addListener(eventListener)
            isSubscribed = true
        }
        refreshList()
    }

    fun hide() {
        overlayView?.visibility = View.GONE
        if (isSubscribed) {
            InputDebugger.removeListener(eventListener)
            isSubscribed = false
        }
    }

    fun toggle() {
        if (isVisible()) hide() else show()
    }

    private fun initView() {
        val view = View.inflate(context, R.layout.layout_vnc_input_debugger, null)
        overlayView = view

        val density = context.resources.displayMetrics.density
        val topMargin = (52 * density).toInt()
        val bottomMargin = (80 * density).toInt()

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (280 * density).toInt()
        ).apply {
            gravity = Gravity.TOP
            setMargins(16, topMargin, 16, bottomMargin)
        }
        rootLayout.addView(view, params)

        containerEvents = view.findViewById(R.id.containerDbgEvents)
        scrollLogs = view.findViewById(R.id.scrollDbgLogs)
        tvLiveSummary = view.findViewById(R.id.tvDbgLiveSummary)

        view.findViewById<MaterialButton>(R.id.btnDbgClose).setOnClickListener {
            hide()
        }

        view.findViewById<MaterialButton>(R.id.btnDbgClear).setOnClickListener {
            InputDebugger.clear()
            containerEvents?.removeAllViews()
            updateSummary()
        }

        view.findViewById<MaterialButton>(R.id.btnDbgCopy).setOnClickListener {
            val text = InputDebugger.exportLogAsText()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("VNC Input Log", text))
            Toast.makeText(context, R.string.dbg_copied_toast, Toast.LENGTH_SHORT).show()
        }

        val btnAll = view.findViewById<MaterialButton>(R.id.btnFilterAll)
        val btnKeys = view.findViewById<MaterialButton>(R.id.btnFilterKeys)
        val btnMouse = view.findViewById<MaterialButton>(R.id.btnFilterMouse)

        val filterButtons = listOf(btnAll, btnKeys, btnMouse)

        fun applyFilter(filter: String, activeBtn: MaterialButton) {
            currentFilter = filter
            filterButtons.forEach {
                it.setBackgroundColor(if (it == activeBtn) Color.parseColor("#388BFD") else Color.parseColor("#21262D"))
                it.setTextColor(Color.WHITE)
            }
            refreshList()
        }

        btnAll.setOnClickListener { applyFilter("ALL", btnAll) }
        btnKeys.setOnClickListener { applyFilter("KEYS", btnKeys) }
        btnMouse.setOnClickListener { applyFilter("MOUSE", btnMouse) }

        applyFilter("ALL", btnAll)
    }

    private fun refreshList() {
        val container = containerEvents ?: return
        container.removeAllViews()
        val records = InputDebugger.getRecords(currentFilter)
        if (records.isEmpty()) {
            val tvEmpty = TextView(context).apply {
                text = context.getString(R.string.dbg_empty)
                setTextColor(Color.parseColor("#8B949E"))
                textSize = 11f
                setPadding(8, 16, 8, 16)
            }
            container.addView(tvEmpty)
        } else {
            // Display up to the last 60 records for smooth scrolling
            val displayRecords = if (records.size > 60) records.takeLast(60) else records
            for (r in displayRecords) {
                container.addView(createEventRow(r))
            }
            scrollLogs?.post {
                scrollLogs?.fullScroll(View.FOCUS_DOWN)
            }
        }
        updateSummary()
    }

    private fun appendEventView(record: InputEventRecord) {
        val container = containerEvents ?: return
        if (currentFilter == "KEYS" && !record.isKey) return
        if (currentFilter == "MOUSE" && !record.isMouse) return

        // Remove empty placeholder if present
        if (container.childCount == 1 && container.getChildAt(0) !is LinearLayout) {
            container.removeAllViews()
        }

        container.addView(createEventRow(record))
        if (container.childCount > 80) {
            container.removeViewAt(0)
        }

        scrollLogs?.post {
            scrollLogs?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateSummary() {
        val total = InputDebugger.getRecords("ALL").size
        val keys = InputDebugger.getRecords("KEYS").size
        val mouse = InputDebugger.getRecords("MOUSE").size
        tvLiveSummary?.text = "Events: $total | Keys: $keys | Mouse: $mouse"
    }

    private fun createEventRow(r: InputEventRecord): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 2, 4, 2)
            gravity = Gravity.CENTER_VERTICAL
        }

        val badgeColor = when (r.eventType) {
            "KEYDOWN" -> "#238636"
            "KEYUP" -> "#DA3633"
            "POINTER_DOWN" -> "#1F6FEB"
            "POINTER_UP" -> "#8957E5"
            "POINTER_MOVE" -> "#30363D"
            "SCROLL" -> "#D29922"
            else -> "#58A6FF"
        }

        val tvBadge = TextView(context).apply {
            text = r.eventType
            textSize = 9f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(badgeColor))
            setPadding(8, 2, 8, 2)
        }
        row.addView(tvBadge)

        val tvTime = TextView(context).apply {
            text = " " + r.timeFormatted
            textSize = 9.5f
            setTextColor(Color.parseColor("#8B949E"))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        row.addView(tvTime)

        val tvDesc = TextView(context).apply {
            val textBuilder = StringBuilder(" ")
            if (r.keyName.isNotEmpty()) textBuilder.append(r.keyName).append(" ")
            if (r.keySymHex.isNotEmpty() && r.keySymHex != "-") textBuilder.append("[${r.keySymHex}] ")
            if (r.scancode.isNotEmpty()) textBuilder.append("<${r.scancode}> ")
            if (r.modifiers.isNotEmpty()) textBuilder.append("${r.modifiers} ")
            if (r.details.isNotEmpty()) textBuilder.append("(${r.details})")

            text = textBuilder.toString()
            textSize = 10f
            setTextColor(Color.parseColor("#E6EDF3"))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        row.addView(tvDesc)

        return row
    }
}
