package com.virtualpcvm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "QemuManager"

object QemuManager {

    private val processes = ConcurrentHashMap<Long, Process>()
    private val vmLogs = ConcurrentHashMap<Long, MutableList<String>>()
    private val vmExitCodes = ConcurrentHashMap<Long, Int>()

    fun isRunning(vmId: Long): Boolean {
        val p = processes[vmId] ?: return false
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    fun getVmLog(vmId: Long): List<String> =
        vmLogs[vmId]?.toList() ?: emptyList()

    fun getLogs(vmId: Long): List<String> =
        getVmLog(vmId)

    fun clearAllLogs() {
        vmLogs.clear()
    }

    fun getExitCode(vmId: Long): Int? =
        vmExitCodes[vmId]

    fun findBinary(ctx: Context, arch: Architecture): String? {
        val name = arch.binary
        val appBin = File(QemuInstaller.termuxPrefix(ctx), "bin/$name")
        if (appBin.exists()) {
            appBin.setExecutable(true, false)
            return appBin.absolutePath
        }

        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val nativeBin = File(nativeDir, name)
        if (nativeBin.exists() && nativeBin.canExecute()) return nativeBin.absolutePath

        val systemPaths = listOf(
            "/data/data/com.termux/files/usr/bin/$name",
            "/system/bin/$name",
            "/system/xbin/$name"
        )
        return systemPaths.firstOrNull { File(it).canExecute() }
    }

    fun findQemuImg(ctx: Context): String? {
        val bin = File(QemuInstaller.termuxPrefix(ctx), "bin/qemu-img")
        if (bin.exists()) {
            bin.setExecutable(true, false)
            return bin.absolutePath
        }
        return null
    }

    fun getSystemLinker(): String? {
        val is64 = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val paths = if (is64) {
            listOf("/system/bin/linker64", "/apex/com.android.runtime/bin/linker64", "/system/bin/bootstrap/linker64")
        } else {
            listOf("/system/bin/linker", "/apex/com.android.runtime/bin/linker", "/system/bin/bootstrap/linker")
        }
        return paths.firstOrNull { File(it).exists() }
    }

    fun buildCommand(ctx: Context, cfg: VmConfig): List<String> {
        val bin = findBinary(ctx, cfg.architecture)
            ?: error("Бинарник ${cfg.architecture.binary} не найден. Установите QEMU через Менеджер QEMU.")

        return buildList {
            add(bin)
            add("-display"); add("none")

            // Firmware and share directory
            val shareDir = File(QemuInstaller.termuxPrefix(ctx), "share/qemu")
            if (shareDir.exists()) {
                add("-L"); add(shareDir.absolutePath)
            }

            // RAM and CPU
            add("-m"); add("${cfg.ramMb}M")
            add("-smp"); add("${cfg.cpuCores}")

            // Architecture-specific setup
            when (cfg.architecture) {
                Architecture.X86_64, Architecture.I386 -> {
                    add("-machine"); add(cfg.machineType.value)
                    val cpu = if (cfg.cpuModel.isNotBlank()) cfg.cpuModel else "max"
                    add("-cpu"); add(cpu)

                    if (cfg.enableKvm) {
                        add("-enable-kvm")
                        add("-accel"); add("kvm")
                    } else {
                        add("-accel"); add("tcg")
                    }

                    if (cfg.disableTsc) {
                        add("-global"); add("kvm-apic.vapic=false")
                    }

                    // Disks
                    if (cfg.diskPath.isNotBlank() && File(cfg.diskPath).exists()) {
                        add("-drive")
                        add("file=${cfg.diskPath},if=ide,format=${cfg.diskFormat},index=0")
                    }

                    if (cfg.isoPath.isNotBlank() && File(cfg.isoPath).exists()) {
                        add("-drive")
                        add("file=${cfg.isoPath},media=cdrom,readonly=on")
                    }

                    add("-boot"); add(if (cfg.bootDevice == "cdrom") "d" else "c")

                    // Display & Input
                    if (cfg.vgaDriver.isNotBlank() && cfg.vgaDriver != "none") {
                        add("-vga"); add(cfg.vgaDriver)
                    }
                    add("-usb")
                    if (cfg.enableUsbTablet) {
                        add("-device"); add("usb-tablet")
                    }

                    // Network
                    if (cfg.networkMode == "user") {
                        add("-netdev"); add("user,id=net0")
                        add("-device"); add("rtl8139,netdev=net0")
                    }

                    // Audio
                    if (cfg.enableAudio) {
                        add("-audiodev"); add("none,id=snd0")
                        val aModel = if (cfg.audioModel.isNotBlank()) cfg.audioModel else "AC97"
                        add("-device"); add("$aModel,audiodev=snd0")
                    }
                }

                Architecture.ARM64, Architecture.ARM -> {
                    val mach = if (cfg.machineType == MachineType.PC || cfg.machineType == MachineType.Q35) {
                        "virt"
                    } else {
                        cfg.machineType.value
                    }
                    add("-machine"); add(mach)

                    val cpu = if (cfg.cpuModel.isNotBlank()) {
                        cfg.cpuModel
                    } else if (cfg.architecture == Architecture.ARM64) {
                        "cortex-a57"
                    } else {
                        "cortex-a15"
                    }
                    add("-cpu"); add(cpu)
                    add("-accel"); add("tcg")

                    if (cfg.diskPath.isNotBlank() && File(cfg.diskPath).exists()) {
                        add("-drive")
                        add("file=${cfg.diskPath},if=virtio,format=${cfg.diskFormat}")
                    }

                    if (cfg.isoPath.isNotBlank() && File(cfg.isoPath).exists()) {
                        add("-drive")
                        add("file=${cfg.isoPath},if=virtio,media=cdrom,readonly=on")
                    }

                    add("-boot"); add(if (cfg.bootDevice == "cdrom") "d" else "c")

                    add("-device"); add("virtio-gpu-pci")
                    add("-device"); add("virtio-tablet-pci")

                    if (cfg.networkMode == "user") {
                        add("-netdev"); add("user,id=net0")
                        add("-device"); add("virtio-net-pci,netdev=net0")
                    }

                    if (cfg.enableAudio) {
                        add("-audiodev"); add("none,id=snd0")
                    }
                }

                Architecture.RISCV64, Architecture.RISCV32 -> {
                    add("-machine"); add("virt")
                    val cpu = if (cfg.cpuModel.isNotBlank()) cfg.cpuModel else "max"
                    add("-cpu"); add(cpu)
                    add("-accel"); add("tcg")

                    if (cfg.diskPath.isNotBlank() && File(cfg.diskPath).exists()) {
                        add("-drive")
                        add("file=${cfg.diskPath},if=virtio,format=${cfg.diskFormat}")
                    }
                    if (cfg.isoPath.isNotBlank() && File(cfg.isoPath).exists()) {
                        add("-drive")
                        add("file=${cfg.isoPath},if=virtio,media=cdrom,readonly=on")
                    }

                    add("-device"); add("virtio-gpu-pci")
                    add("-device"); add("virtio-tablet-pci")

                    if (cfg.networkMode == "user") {
                        add("-netdev"); add("user,id=net0")
                        add("-device"); add("virtio-net-pci,netdev=net0")
                    }
                }

                Architecture.POWERPC, Architecture.PPC64 -> {
                    add("-machine"); add("mac99")
                    val cpu = if (cfg.cpuModel.isNotBlank()) cfg.cpuModel else "g4"
                    add("-cpu"); add(cpu)
                    add("-accel"); add("tcg")

                    if (cfg.diskPath.isNotBlank() && File(cfg.diskPath).exists()) {
                        add("-drive"); add("file=${cfg.diskPath},format=${cfg.diskFormat}")
                    }
                    if (cfg.isoPath.isNotBlank() && File(cfg.isoPath).exists()) {
                        add("-cdrom"); add(cfg.isoPath)
                    }
                    add("-vga"); add("std")
                }

                Architecture.M68K -> {
                    add("-machine"); add("q800")
                    add("-accel"); add("tcg")
                    if (cfg.diskPath.isNotBlank() && File(cfg.diskPath).exists()) {
                        add("-drive"); add("file=${cfg.diskPath},format=${cfg.diskFormat}")
                    }
                }
            }

            // VNC display binding
            add("-vnc"); add("127.0.0.1:${cfg.vncDisplay}")

            // Custom extra arguments
            if (cfg.extraArgs.isNotBlank()) {
                val tokens = tokenizeArgs(cfg.extraArgs)
                addAll(tokens)
            }
        }
    }

    private fun tokenizeArgs(args: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var quoteChar = ' '

        for (ch in args) {
            when {
                (ch == '"' || ch == '\'') && !inQuotes -> {
                    inQuotes = true
                    quoteChar = ch
                }
                ch == quoteChar && inQuotes -> {
                    inQuotes = false
                }
                ch.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current = StringBuilder()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    suspend fun start(
        ctx: Context,
        cfg: VmConfig,
        onLog: (String) -> Unit
    ): Process = withContext(Dispatchers.IO) {
        stop(cfg.id)

        val cmd = buildCommand(ctx, cfg)
        Log.i(TAG, "Command: ${cmd.joinToString(" ")}")
        onLog("[QEMU] Команда запуска: ${cmd.first().substringAfterLast('/')} ${cmd.drop(1).joinToString(" ")}")

        val logList = mutableListOf<String>()
        vmLogs[cfg.id] = logList
        vmExitCodes.remove(cfg.id)

        val termuxPrefix = QemuInstaller.termuxPrefix(ctx).absolutePath
        val libDir = File(QemuInstaller.termuxPrefix(ctx), "lib").absolutePath
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val is64 = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val sysLib = if (is64) "/system/lib64:/apex/com.android.runtime/lib64" else "/system/lib:/apex/com.android.runtime/lib"

        fun preparePb(commandList: List<String>): ProcessBuilder {
            val pb = ProcessBuilder(commandList)
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = "$libDir:$libDir/qemu:$nativeDir:$sysLib"
            env["PATH"] = "$termuxPrefix/bin:/system/bin"
            env["TMPDIR"] = ctx.cacheDir.absolutePath
            env["HOME"] = termuxPrefix
            env["PREFIX"] = termuxPrefix
            env["TERMUX_PREFIX"] = termuxPrefix
            env["XDG_RUNTIME_DIR"] = ctx.cacheDir.absolutePath
            env["ANDROID_DATA"] = "/data"
            env["ANDROID_ROOT"] = "/system"
            pb.directory(ctx.filesDir)
            pb.redirectErrorStream(true)
            return pb
        }

        var proc: Process
        val linker = getSystemLinker()
        try {
            proc = preparePb(cmd).start()
        } catch (e: java.io.IOException) {
            if (linker != null) {
                onLog("[QEMU] Прямой запуск ограничен W^X (${e.message}). Запуск через системный линковщик $linker...")
                val linkerCmd = listOf(linker) + cmd
                proc = preparePb(linkerCmd).start()
            } else {
                throw e
            }
        }

        // Quick self-check: if process failed instantly (e.g. exit code 127/139), retry via linker
        try {
            Thread.sleep(200)
            if (!proc.isAlive && proc.exitValue() != 0 && linker != null) {
                onLog("[QEMU] Прямой запуск завершился с кодом ${proc.exitValue()}. Попытка запуска через $linker...")
                val linkerCmd = listOf(linker) + cmd
                proc = preparePb(linkerCmd).start()
            }
        } catch (_: Exception) {}

        processes[cfg.id] = proc

        Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        val l = line ?: break
                        Log.d(TAG, "[VM-${cfg.id}] $l")
                        synchronized(logList) {
                            if (logList.size > 2000) logList.removeAt(0)
                            logList.add(l)
                        }
                        onLog(l)
                    }
                }
            } catch (_: Exception) {}

            val code = try { proc.waitFor() } catch (_: Exception) { -1 }
            vmExitCodes[cfg.id] = code
            processes.remove(cfg.id)

            val exitMsg = if (code == 0) {
                "[QEMU] Процесс ВМ штатно завершён (код 0)"
            } else {
                "[QEMU] Процесс ВМ завершился с кодом $code"
            }
            Log.i(TAG, exitMsg)
            synchronized(logList) { logList.add(exitMsg) }
            onLog(exitMsg)
        }.start()

        proc
    }

    fun stop(vmId: Long) {
        val p = processes.remove(vmId) ?: return
        try {
            p.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping process: ${e.message}")
        }
    }

    suspend fun waitForVnc(
        vmId: Long,
        host: String = "127.0.0.1",
        port: Int = 5901,
        timeoutMs: Long = 20_000,
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning(vmId)) {
                Log.w(TAG, "waitForVnc: VM $vmId is no longer running")
                return@withContext false
            }
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(host, port), 500)
                    return@withContext true
                }
            } catch (_: Exception) {
                delay(400)
            }
        }
        false
    }

    suspend fun createDiskImage(
        ctx: Context,
        diskFile: File,
        sizeGb: Int,
        format: String,
        onLog: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        diskFile.parentFile?.mkdirs()
        val qemuImg = findQemuImg(ctx)
        if (qemuImg != null) {
            try {
                val cmd = listOf(qemuImg, "create", "-f", format, diskFile.absolutePath, "${sizeGb}G")
                val pb = ProcessBuilder(cmd)
                val termuxPrefix = QemuInstaller.termuxPrefix(ctx).absolutePath
                val libDir = File(QemuInstaller.termuxPrefix(ctx), "lib").absolutePath
                pb.environment()["LD_LIBRARY_PATH"] = "$libDir:${ctx.applicationInfo.nativeLibraryDir}"
                pb.environment()["PATH"] = "$termuxPrefix/bin:/system/bin"
                pb.redirectErrorStream(true)

                val proc = try {
                    pb.start()
                } catch (e: Exception) {
                    val linker = getSystemLinker()
                    if (linker != null) {
                        ProcessBuilder(listOf(linker) + cmd).start()
                    } else throw e
                }

                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { onLog(it) }
                }
                return@withContext (proc.waitFor() == 0)
            } catch (e: Exception) {
                onLog("qemu-img error: ${e.message}")
            }
        }

        // Fallback for raw format via sparse file creation
        try {
            RandomAccessFile(diskFile, "rw").use { raf ->
                raf.setLength(sizeGb.toLong() * 1024L * 1024L * 1024L)
            }
            onLog("Диск создан (sparse file): ${diskFile.name} ($sizeGb ГБ)")
            return@withContext true
        } catch (e: Exception) {
            onLog("Ошибка создания файла диска: ${e.message}")
            return@withContext false
        }
    }

    suspend fun manageSnapshot(
        ctx: Context,
        diskFile: File,
        snapshotName: String,
        operation: String, // "-c" for create, "-a" for apply/restore
        onLog: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val qemuImg = findQemuImg(ctx) ?: run {
            onLog("qemu-img не найден. Установите qemu-utils.")
            return@withContext false
        }
        try {
            val cmd = listOf(qemuImg, "snapshot", operation, snapshotName, diskFile.absolutePath)
            val pb = ProcessBuilder(cmd)
            val termuxPrefix = QemuInstaller.termuxPrefix(ctx).absolutePath
            val libDir = File(QemuInstaller.termuxPrefix(ctx), "lib").absolutePath
            pb.environment()["LD_LIBRARY_PATH"] = "$libDir:${ctx.applicationInfo.nativeLibraryDir}"
            pb.environment()["PATH"] = "$termuxPrefix/bin:/system/bin"
            pb.redirectErrorStream(true)

            val proc = try {
                pb.start()
            } catch (e: Exception) {
                val linker = getSystemLinker()
                if (linker != null) {
                    ProcessBuilder(listOf(linker) + cmd).start()
                } else throw e
            }

            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { onLog(it) }
            }
            return@withContext (proc.waitFor() == 0)
        } catch (e: Exception) {
            onLog("Ошибка снапшота: ${e.message}")
            return@withContext false
        }
    }
}
