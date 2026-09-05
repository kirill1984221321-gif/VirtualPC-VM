package com.virtualpcvm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified logging utility for QEMU processes.
 * Captures stdout/stderr, startup parameters, environment variables,
 * and exit diagnostics. Persists logs to disk and provides memory buffering.
 */
object QemuLogger {
    private const val TAG = "QemuLogger"
    private const val MAX_MEMORY_LINES = 1000

    private val memoryLogs = ConcurrentHashMap<Long, MutableList<LogEntry>>()
    private val listeners = ConcurrentHashMap<Long, MutableList<(LogEntry) -> Unit>>()

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: Level,
        val message: String
    ) {
        enum class Level {
            STARTUP,
            STDOUT,
            STDERR,
            INFO,
            WARNING,
            ERROR
        }

        private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        fun format(): String = "[${timeFormat.format(Date(timestamp))}] [${level.name}] $message"
    }

    private fun getLogDir(context: Context): File {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getLogFile(context: Context, vmId: Long): File {
        return File(getLogDir(context), "vm_$vmId.log")
    }

    fun log(context: Context, vmId: Long, level: LogEntry.Level, message: String) {
        val entry = LogEntry(level = level, message = message)

        // 1. Log to Android Logcat
        when (level) {
            LogEntry.Level.ERROR -> Log.e(TAG, "[VM-$vmId] $message")
            LogEntry.Level.WARNING -> Log.w(TAG, "[VM-$vmId] $message")
            LogEntry.Level.STDERR -> Log.w(TAG, "[VM-$vmId] [STDERR] $message")
            else -> Log.d(TAG, "[VM-$vmId] $message")
        }

        // 2. Add to in-memory buffer
        val list = memoryLogs.getOrPut(vmId) { mutableListOf() }
        synchronized(list) {
            if (list.size >= MAX_MEMORY_LINES) {
                list.removeAt(0)
            }
            list.add(entry)
        }

        // 3. Notify real-time listeners
        listeners[vmId]?.forEach { callback ->
            try {
                callback(entry)
            } catch (e: Exception) {
                Log.w(TAG, "Listener error: ${e.message}")
            }
        }

        // 4. Append to disk log asynchronously
        try {
            val file = getLogFile(context, vmId)
            FileWriter(file, true).use { fw ->
                fw.write(entry.format() + "\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write log to file: ${e.message}")
        }
    }

    fun logStartup(
        context: Context,
        cfg: VmConfig,
        cmd: List<String>,
        env: Map<String, String>,
        linker: String?
    ) {
        val file = getLogFile(context, cfg.id)
        if (file.exists() && file.length() > 2 * 1024 * 1024) {
            file.delete()
        }

        log(context, cfg.id, LogEntry.Level.STARTUP, "=".repeat(50))
        log(context, cfg.id, LogEntry.Level.STARTUP, "Запуск ВМ: ${cfg.name} (ID: ${cfg.id})")
        log(context, cfg.id, LogEntry.Level.STARTUP, "Архитектура: ${cfg.architecture.name} (${cfg.architecture.binary})")
        log(context, cfg.id, LogEntry.Level.STARTUP, "Тип машины: ${cfg.machineType.name}, CPU: ${cfg.cpuCores} ядер (${cfg.cpuModel}), RAM: ${cfg.ramMb} MB")
        log(context, cfg.id, LogEntry.Level.STARTUP, "Диск: ${cfg.diskPath.ifBlank { "отсутствует" }} (${cfg.diskFormat})")
        log(context, cfg.id, LogEntry.Level.STARTUP, "ISO: ${cfg.isoPath.ifBlank { "отсутствует" }}")
        log(context, cfg.id, LogEntry.Level.STARTUP, "VNC Port: ${cfg.vncPort}, Monitor Port: ${cfg.monitorPort}")
        if (linker != null) {
            log(context, cfg.id, LogEntry.Level.STARTUP, "Линковщик: $linker")
        }
        log(context, cfg.id, LogEntry.Level.STARTUP, "LD_LIBRARY_PATH: ${env["LD_LIBRARY_PATH"] ?: ""}")
        log(context, cfg.id, LogEntry.Level.STARTUP, "PATH: ${env["PATH"] ?: ""}")
        log(context, cfg.id, LogEntry.Level.STARTUP, "Команда: ${cmd.joinToString(" ")}")
        log(context, cfg.id, LogEntry.Level.STARTUP, "=".repeat(50))
    }

    fun getLogs(context: Context, vmId: Long): List<String> {
        val mem = memoryLogs[vmId]
        if (!mem.isNullOrEmpty()) {
            synchronized(mem) {
                return mem.map { it.format() }
            }
        }
        val file = getLogFile(context, vmId)
        if (file.exists()) {
            return try {
                file.readLines()
            } catch (_: Exception) {
                emptyList()
            }
        }
        return emptyList()
    }

    fun clear(context: Context, vmId: Long) {
        memoryLogs.remove(vmId)
        try {
            getLogFile(context, vmId).delete()
        } catch (_: Exception) {}
    }

    fun clearLogs(context: Context, vmId: Long) {
        clear(context, vmId)
    }

    fun addListener(vmId: Long, callback: (LogEntry) -> Unit) {
        listeners.getOrPut(vmId) { mutableListOf() }.add(callback)
    }

    fun removeListener(vmId: Long, callback: (LogEntry) -> Unit) {
        listeners[vmId]?.remove(callback)
    }
}
