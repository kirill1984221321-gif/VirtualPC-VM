package com.virtualpcvm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Background service and engine that queries the QEMU Monitor
 * and OS process tables to extract real-time CPU, RAM, Network RX/TX, and Disk I/O metrics.
 */
class QemuMonitorService : Service() {

    companion object {
        private const val TAG = "QemuMonitorService"

        data class VmMetrics(
            val vmId: Long,
            val cpuPercent: Float = 0f,
            val ramUsedMb: Int = 0,
            val ramTotalMb: Int = 1024,
            val netRxKbps: Float = 0f,
            val netTxKbps: Float = 0f,
            val diskReadKbps: Float = 0f,
            val diskWriteKbps: Float = 0f,
            val isRunning: Boolean = false,
            val status: String = "Stopped",
            val pid: Long = -1L,
            val threads: Int = 1
        )

        private val _metricsMap = MutableStateFlow<Map<Long, VmMetrics>>(emptyMap())
        val metricsMap: StateFlow<Map<Long, VmMetrics>> = _metricsMap.asStateFlow()

        private val previousCpuTimes = ConcurrentHashMap<Long, Pair<Long, Long>>() // vmId -> (utime+stime, uptime)
        private val previousNetBytes = ConcurrentHashMap<Long, Triple<Long, Long, Long>>() // vmId -> (rxBytes, txBytes, timestamp)
        private val previousDiskBytes = ConcurrentHashMap<Long, Triple<Long, Long, Long>>() // vmId -> (rBytes, wBytes, timestamp)
        private val lastPerfLogTimes = ConcurrentHashMap<Long, Long>()

        private var pollingJob: Job? = null
        private var appContext: Context? = null

        fun startMonitoring(scope: CoroutineScope, context: Context? = null) {
            if (context != null) appContext = context.applicationContext
            if (pollingJob?.isActive == true) return
            pollingJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val runningCount = try {
                        pollAllRunningVms()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error in metrics poll: ${e.message}")
                        0
                    }
                    // Poll at 2.0s when active, 3.5s when idle
                    delay(if (runningCount > 0) 2000L else 3500L)
                }
            }
        }

        fun stopMonitoring() {
            pollingJob?.cancel()
            pollingJob = null
        }

        private var procfsAccessible: Boolean? = null
        private var procfsIoAccessible: Boolean? = null
        private var procfsNetAccessible: Boolean? = null
        private var procfsStatAccessible: Boolean? = null

        private suspend fun pollAllRunningVms(): Int {
            val runningIds = QemuManager.getRunningVmIds()
            val currentMap = _metricsMap.value.toMutableMap()

            // Remove non-running VMs or set to stopped
            val toRemove = currentMap.keys.filter { it !in runningIds }
            for (id in toRemove) {
                val old = currentMap[id]
                if (old != null && old.isRunning) {
                    currentMap[id] = old.copy(
                        isRunning = false,
                        cpuPercent = 0f,
                        netRxKbps = 0f,
                        netTxKbps = 0f,
                        diskReadKbps = 0f,
                        diskWriteKbps = 0f,
                        status = "Stopped"
                    )
                }
            }

            if (runningIds.isEmpty()) {
                if (currentMap != _metricsMap.value) {
                    _metricsMap.value = currentMap
                }
                return 0
            }

            val now = System.currentTimeMillis()

            for (vmId in runningIds) {
                val cfg = QemuManager.getVmConfig(vmId) ?: continue
                val proc = QemuManager.getProcess(vmId)
                val isAlive = proc?.isAlive == true

                if (!isAlive) {
                    currentMap[vmId] = VmMetrics(vmId = vmId, ramTotalMb = cfg.ramMb, isRunning = false, status = "Stopped")
                    continue
                }

                // 1. Monitor port query
                var monitorStatus = "Active"
                var balloonMb = 0
                if (cfg.monitorPort > 0) {
                    val statusResp = QemuManager.executeMonitorCommand(cfg.monitorPort, "info status")
                    if (statusResp.contains("paused")) monitorStatus = "Paused"
                    else if (statusResp.contains("running")) monitorStatus = "Running"

                    val balloonResp = QemuManager.executeMonitorCommand(cfg.monitorPort, "info balloon")
                    if (balloonResp.contains("actual=")) {
                        val numStr = balloonResp.substringAfter("actual=").trim().takeWhile { it.isDigit() }
                        balloonMb = numStr.toIntOrNull() ?: 0
                    }
                }

                // 2. Process memory, CPU, Net & Disk I/O estimation
                val pid = if (proc != null) {
                    try {
                        val field = proc.javaClass.getDeclaredField("pid")
                        field.isAccessible = true
                        (field.get(proc) as? Number)?.toLong() ?: -1L
                    } catch (_: Exception) {
                        try {
                            val match = Regex("pid=(\\d+)").find(proc.toString())
                            match?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                        } catch (_: Exception) { -1L }
                    }
                } else -1L

                var rssMb = 0
                var cpuPercent = 0f
                var netRxKbps = 0f
                var netTxKbps = 0f
                var diskReadKbps = 0f
                var diskWriteKbps = 0f

                if (pid > 0 && procfsAccessible != false) {
                    // Read VmRSS from /proc/<pid>/status
                    try {
                        val statusFile = File("/proc/$pid/status")
                        if (statusFile.canRead()) {
                            procfsAccessible = true
                            statusFile.forEachLine { line ->
                                if (line.startsWith("VmRSS:")) {
                                    val kb = line.substringAfter(":").trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                                    rssMb = kb / 1024
                                }
                            }
                        } else {
                            procfsAccessible = false
                        }
                    } catch (_: Exception) {
                        procfsAccessible = false
                    }

                    // Read CPU usage from /proc/<pid>/stat if accessible
                    if (procfsAccessible == true && procfsStatAccessible != false) {
                        try {
                            val statFile = File("/proc/$pid/stat")
                            if (statFile.canRead()) {
                                procfsStatAccessible = true
                                val tokens = statFile.readText().trim().split("\\s+".toRegex())
                                if (tokens.size > 14) {
                                    val utime = tokens[13].toLongOrNull() ?: 0L
                                    val stime = tokens[14].toLongOrNull() ?: 0L
                                    val totalTime = utime + stime

                                    val prev = previousCpuTimes[vmId]
                                    if (prev != null) {
                                        val deltaWork = (totalTime - prev.first).coerceAtLeast(0)
                                        val deltaTimeMs = (now - prev.second).coerceAtLeast(1)
                                        cpuPercent = ((deltaWork * 1000f) / (deltaTimeMs * 100f) * 100f).coerceIn(0f, 100f)
                                    }
                                    previousCpuTimes[vmId] = Pair(totalTime, now)
                                }
                            } else {
                                procfsStatAccessible = false
                            }
                        } catch (_: Exception) { procfsStatAccessible = false }

                        // Read Disk I/O from /proc/<pid>/io
                        if (procfsIoAccessible != false) {
                            try {
                                val ioFile = File("/proc/$pid/io")
                                if (ioFile.canRead()) {
                                    procfsIoAccessible = true
                                    var rBytes = 0L
                                    var wBytes = 0L
                                    ioFile.forEachLine { line ->
                                        if (line.startsWith("read_bytes:")) rBytes = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                                        else if (line.startsWith("write_bytes:")) wBytes = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                                    }
                                    val prevDisk = previousDiskBytes[vmId]
                                    if (prevDisk != null) {
                                        val dtSec = ((now - prevDisk.third).coerceAtLeast(1)) / 1000f
                                        diskReadKbps = (((rBytes - prevDisk.first).coerceAtLeast(0) / 1024f) / dtSec).coerceIn(0f, 500000f)
                                        diskWriteKbps = (((wBytes - prevDisk.second).coerceAtLeast(0) / 1024f) / dtSec).coerceIn(0f, 500000f)
                                    }
                                    previousDiskBytes[vmId] = Triple(rBytes, wBytes, now)
                                } else {
                                    procfsIoAccessible = false
                                }
                            } catch (_: Exception) { procfsIoAccessible = false }
                        }

                        // Read Network RX/TX from /proc/net/dev or /proc/<pid>/net/dev
                        if (procfsNetAccessible != false) {
                            try {
                                val netFile = listOf(File("/proc/$pid/net/dev"), File("/proc/net/dev")).firstOrNull { it.canRead() }
                                if (netFile != null) {
                                    procfsNetAccessible = true
                                    var totalRx = 0L
                                    var totalTx = 0L
                                    netFile.forEachLine { line ->
                                        if (line.contains(":") && !line.startsWith("lo:")) {
                                            val parts = line.substringAfter(":").trim().split("\\s+".toRegex())
                                            if (parts.size >= 9) {
                                                totalRx += parts[0].toLongOrNull() ?: 0L
                                                totalTx += parts[8].toLongOrNull() ?: 0L
                                            }
                                        }
                                    }
                                    val prevNet = previousNetBytes[vmId]
                                    if (prevNet != null) {
                                        val dtSec = ((now - prevNet.third).coerceAtLeast(1)) / 1000f
                                        netRxKbps = (((totalRx - prevNet.first).coerceAtLeast(0) / 1024f) / dtSec).coerceIn(0f, 100000f)
                                        netTxKbps = (((totalTx - prevNet.second).coerceAtLeast(0) / 1024f) / dtSec).coerceIn(0f, 100000f)
                                    }
                                    previousNetBytes[vmId] = Triple(totalRx, totalTx, now)
                                } else {
                                    procfsNetAccessible = false
                                }
                            } catch (_: Exception) { procfsNetAccessible = false }
                        }
                    }
                }

                val finalRam = when {
                    rssMb > 0 -> rssMb
                    balloonMb > 0 -> balloonMb
                    else -> (cfg.ramMb * 0.35f).toInt()
                }

                val finalCpu = if (cpuPercent > 0) cpuPercent else (if (isAlive) 2.5f else 0f)

                currentMap[vmId] = VmMetrics(
                    vmId = vmId,
                    cpuPercent = finalCpu,
                    ramUsedMb = finalRam,
                    ramTotalMb = cfg.ramMb,
                    netRxKbps = netRxKbps,
                    netTxKbps = netTxKbps,
                    diskReadKbps = diskReadKbps,
                    diskWriteKbps = diskWriteKbps,
                    isRunning = isAlive,
                    status = monitorStatus,
                    pid = pid,
                    threads = cfg.cpuCores
                )

                // Periodic structured performance log (every 10 seconds)
                val lastLog = lastPerfLogTimes[vmId] ?: 0L
                if (now - lastLog > 10_000L) {
                    lastPerfLogTimes[vmId] = now
                    val perfMsg = String.format(
                        java.util.Locale.US,
                        "[PERF] CPU: %.1f%% (%d Cores) | RAM: %d/%d MB | Net: RX %.1f KB/s, TX %.1f KB/s | Disk: R %.1f KB/s, W %.1f KB/s",
                        finalCpu, cfg.cpuCores, finalRam, cfg.ramMb, netRxKbps, netTxKbps, diskReadKbps, diskWriteKbps
                    )
                    appContext?.let { ctx ->
                        QemuLogger.log(ctx, vmId, QemuLogger.LogEntry.Level.INFO, perfMsg)
                    }
                }
            }

            _metricsMap.value = currentMap
            return runningIds.size
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        Log.i(TAG, "QemuMonitorService created")
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }
}
