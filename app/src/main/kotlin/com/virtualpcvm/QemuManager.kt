package com.virtualpcvm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "QemuManager"

object QemuManager {

    private val processes = mutableMapOf<Long, Process>()
    private val vmLogs = java.util.concurrent.ConcurrentHashMap<Long, MutableList<String>>()

    fun getLogs(vmId: Long): List<String> = vmLogs[vmId]?.toList() ?: emptyList()

    fun findBinary(ctx: Context, arch: Architecture): String? {
        val name = arch.binary
        val appBin = File(QemuInstaller.termuxPrefix(ctx), "bin/$name")
        if (appBin.canExecute()) return appBin.absolutePath
        
        val systemPaths = listOf(
            "/data/local/tmp/$name",
            "/usr/bin/$name",
            "/usr/local/bin/$name",
        )
        return systemPaths.firstOrNull { File(it).canExecute() }
    }

    fun isInstalled(ctx: Context, arch: Architecture) = findBinary(ctx, arch) != null

    fun buildCommand(ctx: Context, cfg: VmConfig): List<String> {
        val bin = findBinary(ctx, cfg.architecture)
            ?: error("Бинарник ${cfg.architecture.binary} не найден. Установите QEMU.")

        return buildList {
            add(bin)

            // Termux-specific share dir for firmware
            val shareDir = File(QemuInstaller.termuxPrefix(ctx), "share/qemu")
            if (shareDir.exists()) {
                add("-L"); add(shareDir.absolutePath)
            }

            add("-machine"); add(cfg.machineType.value)
            add("-m"); add("${cfg.ramMb}M")
            add("-smp"); add("${cfg.cpuCores}")
            add("-cpu"); add("max")

            if (cfg.enableKvm) {
                add("-enable-kvm")
            }
            add("-accel"); add(if (cfg.enableKvm) "kvm" else "tcg,thread=multi")

            if (cfg.disableTsc) { add("-global"); add("kvm-apic.vapic=false") }

            if (cfg.firmware == Firmware.UEFI) {
                val ovmf = File(shareDir, "edk2-x86_64-code.fd")
                val oldOvmf = File(QemuInstaller.qemuDir(ctx), "OVMF.fd")
                if (ovmf.exists()) { 
                    add("-bios"); add(ovmf.absolutePath) 
                } else if (oldOvmf.exists()) {
                    add("-bios"); add(oldOvmf.absolutePath)
                }
            }

            if (File(cfg.diskPath).exists()) {
                add("-hda"); add(cfg.diskPath)
            }

            if (cfg.isoPath.isNotBlank() && File(cfg.isoPath).exists()) {
                add("-cdrom"); add(cfg.isoPath)
                add("-boot"); add("d")
            }

            if (cfg.enableAudio) {
                // By default pulse audio crashes since there's no XDG_RUNTIME_DIR daemon.
                // We fallback to a dummy backend or alsa if supported, using 'none' for stability.
                add("-audiodev"); add("none,id=snd0")
                add("-device"); add("ich9-intel-hda")
                add("-device"); add("hda-output,audiodev=snd0")
            }

            add("-vnc"); add(":${cfg.vncDisplay}")
            add("-nographic")
        }
    }

    suspend fun createDiskImage(ctx: Context, diskFile: File, sizeGb: Int, format: String = "qcow2", onLog: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val qemuImg = File(QemuInstaller.termuxPrefix(ctx), "bin/qemu-img")
            if (!qemuImg.exists()) {
                onLog("qemu-img не найден. Убедитесь, что qemu-utils установлены.")
                return@withContext false
            }

            val cmd = listOf(
                qemuImg.absolutePath,
                "create",
                "-f", format,
                diskFile.absolutePath,
                "${sizeGb}G"
            )

            val cmdStr = cmd.joinToString(" ")
            Log.i(TAG, "Creating disk: $cmdStr")
            onLog("$ $cmdStr")

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val libDir = File(QemuInstaller.termuxPrefix(ctx), "lib").absolutePath
            pb.environment()["LD_LIBRARY_PATH"] = libDir
            pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath

            val proc = pb.start()
            proc.inputStream.bufferedReader().forEachLine { line ->
                Log.d(TAG, line)
                onLog(line)
            }
            val code = proc.waitFor()
            onLog("[qemu-img завершился с кодом $code]")
            code == 0
        }

    suspend fun start(ctx: Context, cfg: VmConfig, onLog: (String) -> Unit): Process =
        withContext(Dispatchers.IO) {
            stop(cfg.id)

            // clear old logs
            vmLogs[cfg.id] = java.util.Collections.synchronizedList(mutableListOf<String>())
            val logs = vmLogs[cfg.id]!!

            fun logBoth(line: String) {
                logs.add(line)
                onLog(line)
            }

            val cmd = buildCommand(ctx, cfg)
            val cmdStr = cmd.joinToString(" ")
            Log.i(TAG, "Starting QEMU: $cmdStr")
            logBoth("$ $cmdStr")

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            
            // Set library path to Termux's lib directory inside app data
            val libDir = File(QemuInstaller.termuxPrefix(ctx), "lib").absolutePath
            pb.environment()["LD_LIBRARY_PATH"] = libDir
            
            // Set generic tmpdir and XDG_RUNTIME_DIR
            pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
            pb.environment()["XDG_RUNTIME_DIR"] = ctx.cacheDir.absolutePath
            
            val proc = pb.start()
            processes[cfg.id] = proc

            Thread {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, line)
                    logBoth(line)
                }
                val code = try { proc.waitFor() } catch (_: Exception) { -1 }
                logBoth("[QEMU завершился с кодом $code]")
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

    suspend fun waitForVnc(
        vmId: Long,
        host: String = "127.0.0.1",
        port: Int = 5901,
        timeoutMs: Long = 8_000,
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning(vmId)) return@withContext false
            try {
                java.net.Socket(host, port).use { return@withContext true }
            } catch (_: Exception) {
                delay(400)
            }
        }
        false
    }
}
