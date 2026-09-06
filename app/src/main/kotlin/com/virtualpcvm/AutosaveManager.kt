package com.virtualpcvm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object AutosaveManager {
    private const val TAG = "AutosaveManager"
    private const val AUTOSAVE_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

    private val lastSnapshotTimes = ConcurrentHashMap<Long, Long>()
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start(context: Context, vmListProvider: () -> List<VmConfig>) {
        if (job?.isActive == true) return

        job = scope.launch {
            while (isActive) {
                delay(60_000L) // Check every minute
                try {
                    val appSettings = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                    val autosaveEnabled = appSettings.getBoolean("autosave_enabled", true)
                    if (!autosaveEnabled) continue

                    val vms = vmListProvider()
                    val now = System.currentTimeMillis()

                    for (vm in vms) {
                        if (QemuManager.isRunning(vm.id)) {
                            val lastTime = lastSnapshotTimes[vm.id]
                            if (lastTime == null) {
                                // Initialize first run timer
                                lastSnapshotTimes[vm.id] = now
                                continue
                            }

                            if (now - lastTime >= AUTOSAVE_INTERVAL_MS) {
                                performAutosave(context, vm)
                                lastSnapshotTimes[vm.id] = now
                            }
                        } else {
                            lastSnapshotTimes.remove(vm.id)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Autosave loop error: ${e.message}")
                }
            }
        }
    }

    fun recordManualSnapshot(vmId: Long) {
        lastSnapshotTimes[vmId] = System.currentTimeMillis()
    }

    private suspend fun performAutosave(context: Context, vm: VmConfig) {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
        val tag = "auto_${dateFormat.format(Date())}"
        Log.i(TAG, "Triggering automatic snapshot «$tag» for VM «${vm.name}» (ID: ${vm.id})")

        val result = SnapshotManager.createSnapshot(context, vm, tag)
        if (result.isSuccess) {
            VmStateNotifier.notifySnapshotSaved(context, vm.name, tag, isAutosave = true)
        } else {
            Log.w(TAG, "Autosave failed for ${vm.name}: ${result.exceptionOrNull()?.message}")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastSnapshotTimes.clear()
    }
}
