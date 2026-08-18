package com.virtualpcvm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "QemuManager"

/** Manages QEMU binary lookup and process lifecycle. */
object QemuManager {

    /** Map vmId → running Process */
    private val processes = mutableMapOf<Long, Process>()

    /* ── binary resolution ── */

    /** Returns the absolute path of the QEMU binary for [arch], or null if not found. */
    fun findBinary(ctx: Context, arch: Architecture): String? {
        val name = arch.binary
        // Prefer the app-managed QEMU runtime.
        val appBin = File(QemuInstaller.qemuDir(ctx), name)
        if (appBin.canExecute()) return appBin.absolutePath
        // Development fallback for manually supplied binaries.
        val systemPaths = listOf(
            "/data/local/tmp/$name",
            "/usr/bin/$name",
            "/usr/local/bin/$name",
        )
        return systemPaths.firstOrNull { File(it).canExecute() }
    }

    fun isInstalled(ctx: Context, arch: Architecture) = findBinary(ctx, arch) != null

    /* ── QEMU command builder ── */

    fun buildCommand(ctx: Context, cfg: VmConfig): List<String> {
        val bin = findBinary(ctx, cfg.architecture)
            ?: error("Бинарник ${cfg.architecture.binary} не найден. Установите QEMU.")

        return buildList {
            add(bin)

            // Machine type
            add("-machine"); add(cfg.machineType.value)

            // RAM
            add("-m"); add("${cfg.ramMb}M")

            // CPU cores + max feature set
            add("-smp"); add("${cfg.cpuCores}")
            add("-cpu"); add("max")

            // Acceleration: TCG multi-thread (works without KVM on Android)
            if (cfg.enableKvm) {
                add("-enable-kvm")
            }
            add("-accel"); add(if (cfg.enableKvm) "kvm" else "tcg,thread=multi")

            // TSC flag
            if (cfg.disableTsc) { add("-global"); add("kvm-apic.vapic=false") }

            // UEFI firmware (bundled OVMF if present)
            if (cfg.firmware == Firmware.UEFI) {
                val ovmf = File(QemuInstaller.qemuDir(ctx), "OVMF.fd")
                if (ovmf.exists()) { add("-bios"); add(ovmf.absolutePath) }
            }

            // Disk image
            if (File(cfg.diskPath).exists()) {
                add("-hda"); add(cfg.diskPath)
            }

            // ISO / install media
            if (cfg.isoPath.isNotBlank() && File(cfg.isoPath).exists()) {
                add("-cdrom"); add(cfg.isoPath)
                add("-boot"); add("d")
            }

            // Audio: PulseAudio backend + Intel HDA
            if (cfg.enableAudio) {
                add("-audiodev"); add("pa,id=snd0")
                add("-device"); add("ich9-intel-hda")
                add("-device"); add("hda-output,audiodev=snd0")
            }

            // VNC display. Bind to localhost because the Android app owns the display.
            add("-vnc"); add("127.0.0.1:${cfg.vncDisplay}")

            // Disable graphical window (headless — we use VNC)
            add("-nographic")
        }
    }

    /* ── launch ── */

    /**
     * Spawns QEMU for [cfg]. Streams stdout/stderr via [onLog].
     * Runs on [Dispatchers.IO].
     */
    suspend fun start(ctx: Context, cfg: VmConfig, onLog: (String) -> Unit): Process =
        withContext(Dispatchers.IO) {
            stop(cfg.id) // kill any previous instance

            val cmd = buildCommand(ctx, cfg)
            val cmdStr = cmd.joinToString(" ")
            Log.i(TAG, "Starting QEMU: $cmdStr")
            onLog("$ $cmdStr")

            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            processes[cfg.id] = proc

            Thread {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, line)
                    onLog(line)
                }
                val code = try { proc.waitFor() } catch (_: Exception) { -1 }
                onLog("[QEMU завершился с кодом $code]")
                processes.remove(cfg.id)
            }.apply { isDaemon = true; start() }

            proc
        }

    fun stop(vmId: Long) {
        processes[vmId]?.let { proc ->
            try { proc.destroy() } catch (_: Exception) {}
            processes.remove(vmId)
            Log.i(TAG, "Stopped VM $vmId")
        }
    }

    fun isRunning(vmId: Long) = processes[vmId]?.isAlive == true

    /** Polls [host]:[port] until reachable or [timeoutMs] exceeded. */
    suspend fun waitForVnc(
        host: String = "127.0.0.1",
        port: Int = 5901,
        timeoutMs: Long = 8_000,
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket(host, port).use { return@withContext true }
            } catch (_: Exception) {
                delay(400)
            }
        }
        false
    }
}
