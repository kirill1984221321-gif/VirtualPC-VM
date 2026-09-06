package com.virtualpcvm

import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

data class InputEventRecord(
    val id: Long,
    val timestamp: Long,
    val eventType: String,       // "KEYDOWN", "KEYUP", "POINTER_DOWN", "POINTER_UP", "POINTER_MOVE", "SCROLL", "CMD"
    val keyCode: Int = 0,
    val keyName: String = "",
    val char: Char? = null,
    val keySym: Long = 0L,
    val keySymHex: String = "",
    val keySymName: String = "",
    val scancode: String = "",
    val modifiers: String = "",
    val details: String = "",
    val isSuccess: Boolean = true
) {
    val timeFormatted: String get() {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date(timestamp))
    }

    val isKey: Boolean get() = eventType.startsWith("KEY")
    val isMouse: Boolean get() = eventType.startsWith("POINTER") || eventType == "SCROLL"
}

object InputDebugger {

    private val idCounter = AtomicLong(1)
    private val eventHistory = CopyOnWriteArrayList<InputEventRecord>()
    private val listeners = CopyOnWriteArrayList<(InputEventRecord) -> Unit>()
    private const val MAX_HISTORY = 300

    fun addListener(listener: (InputEventRecord) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: (InputEventRecord) -> Unit) {
        listeners.remove(listener)
    }

    fun logKeyEvent(
        isDown: Boolean,
        keyCode: Int,
        event: KeyEvent? = null,
        keySym: Long = 0L,
        customKeyName: String? = null,
        scancodeOverride: String? = null,
        details: String = ""
    ) {
        val sym = if (keySym != 0L) keySym else VncKeyUtils.keyCodeToKeySym(keyCode, event)
        val symHex = if (sym != 0L) String.format("0x%04X", sym) else "-"
        val name = customKeyName ?: VncKeyMapper.getKeyName(keyCode)
        val symName = VncKeyMapper.getKeySymName(sym)
        val scan = scancodeOverride ?: VncKeyMapper.getQemuScancode(keyCode)
        
        val mods = buildString {
            if (event?.isCtrlPressed == true) append("[Ctrl] ")
            if (event?.isAltPressed == true) append("[Alt] ")
            if (event?.isShiftPressed == true) append("[Shift] ")
            if (event?.isMetaPressed == true) append("[Win] ")
        }.trim()

        val record = InputEventRecord(
            id = idCounter.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            eventType = if (isDown) "KEYDOWN" else "KEYUP",
            keyCode = keyCode,
            keyName = name,
            char = event?.unicodeChar?.toChar(),
            keySym = sym,
            keySymHex = symHex,
            keySymName = symName,
            scancode = scan,
            modifiers = mods,
            details = details.ifEmpty { "RFB 0x04 -> VNC Server ($symHex)" }
        )

        pushRecord(record)
    }

    fun logPointerEvent(
        action: String,
        vncX: Int,
        vncY: Int,
        buttons: Int,
        mode: String,
        details: String = ""
    ) {
        val btnDesc = buildString {
            if (buttons and 0x01 != 0) append("LMB ")
            if (buttons and 0x02 != 0) append("MMB ")
            if (buttons and 0x04 != 0) append("RMB ")
            if (buttons and 0x08 != 0) append("WHEEL_UP ")
            if (buttons and 0x10 != 0) append("WHEEL_DN ")
            if (isEmpty()) append("RELEASE")
        }.trim()

        val record = InputEventRecord(
            id = idCounter.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            eventType = action,
            keyName = "MOUSE ($mode)",
            keySym = 0L,
            keySymHex = String.format("Btn: 0x%02X", buttons),
            keySymName = btnDesc,
            details = "Coord: ($vncX, $vncY) | Mode: $mode ${if (details.isNotEmpty()) "[$details]" else ""}"
        )

        pushRecord(record)
    }

    fun logCustomMessage(title: String, details: String) {
        val record = InputEventRecord(
            id = idCounter.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            eventType = "CMD",
            keyName = title,
            details = details
        )
        pushRecord(record)
    }

    private fun pushRecord(record: InputEventRecord) {
        eventHistory.add(record)
        while (eventHistory.size > MAX_HISTORY) {
            eventHistory.removeAt(0)
        }
        for (l in listeners) {
            try {
                l(record)
            } catch (_: Exception) {}
        }
    }

    fun getRecords(filter: String = "ALL"): List<InputEventRecord> {
        val list = eventHistory.toList()
        return when (filter) {
            "KEYS" -> list.filter { it.isKey }
            "MOUSE" -> list.filter { it.isMouse }
            else -> list
        }
    }

    fun clear() {
        eventHistory.clear()
    }

    fun exportLogAsText(): String {
        return buildString {
            appendLine("=== Virtual PC VM - VNC Input Debug Log ===")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Total Events: ${eventHistory.size}")
            appendLine("------------------------------------------------------------")
            for (r in eventHistory) {
                appendLine("[${r.timeFormatted}] ${r.eventType} | Key: ${r.keyName} | KeySym: ${r.keySymHex} (${r.keySymName}) | Scan: ${r.scancode} | Mods: ${r.modifiers} | ${r.details}")
            }
        }
    }
}
