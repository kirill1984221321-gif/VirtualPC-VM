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
    private val activeConfigs = ConcurrentHashMap<Long, VmConfig>()
    private val vmLogs = ConcurrentHashMap<Long, MutableList<String>>()
    private val vmExitCodes = ConcurrentHashMap<Long, Int>()

    fun getRunningVmIds(): Set<Long> = processes.keys.toSet()
    fun getProcess(vmId: Long): Process? = processes[vmId]
    fun getVmConfig(vmId: Long): VmConfig? = activeConfigs[vmId]

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

        val customBin = File(ctx.filesDir, "qemu-bins/$name")
        if (customBin.exists()) {
            customBin.setExecutable(true, false)
            return customBin.absolutePath
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
        val customBin = File(ctx.filesDir, "qemu-bins/qemu-img")
        if (customBin.exists()) {
            customBin.setExecutable(true, false)
            return customBin.absolutePath
        }
        return null
    }

    fun getSystemLinker(is64: Boolean = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()): String? {
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
                    when (cfg.inputDevice) {
                        "usb-tablet" -> { add("-device"); add("usb-tablet") }
                        "virtio-tablet" -> { add("-device"); add("virtio-tablet-pci") }
                        "usb-mouse" -> { add("-device"); add("usb-mouse") }
                        "ps2" -> { /* PS/2 standard controller */ }
                        else -> {
                            if (cfg.enableUsbTablet) {
                                add("-device"); add("usb-tablet")
                            }
                        }
                    }

                    // Network
                    if (cfg.networkMode == "user" && cfg.networkAdapter != "none") {
                        add("-netdev"); add("user,id=net0")
                        val netCard = when (cfg.networkAdapter) {
                            "virtio-net-pci", "virtio" -> "virtio-net-pci"
                            "e1000" -> "e1000"
                            else -> "rtl8139"
                        }
                        add("-device"); add("$netCard,netdev=net0")
                    }
                    
                    add("-device"); add("virtio-balloon")

                    // Audio
                    if (cfg.enableAudio) {
                        add("-audiodev"); add("none,id=snd0")
                        val aModel = if (cfg.audioModel.isNotBlank()) cfg.audioModel else "AC97"
                        if (aModel.lowercase() == "hda") {
                            add("-device"); add("intel-hda")
                            add("-device"); add("hda-micro,audiodev=snd0")
                        } else {
                            add("-device"); add("$aModel,audiodev=snd0")
                        }
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
            
            // QEMU Monitor (HMP) binding for snapshots & stats
            add("-monitor"); add("tcp:127.0.0.1:${cfg.monitorPort},server,nowait")

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
        activeConfigs[cfg.id] = cfg

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

        val envMap = mapOf(
            "LD_LIBRARY_PATH" to "$libDir:$libDir/qemu:$nativeDir:$sysLib",
            "PATH" to "$termuxPrefix/bin:/system/bin",
            "TMPDIR" to ctx.cacheDir.absolutePath,
            "HOME" to termuxPrefix,
            "PREFIX" to termuxPrefix,
            "TERMUX_PREFIX" to termuxPrefix,
            "XDG_RUNTIME_DIR" to ctx.cacheDir.absolutePath,
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system"
        )

        fun preparePb(commandList: List<String>): ProcessBuilder {
            val pb = ProcessBuilder(commandList)
            val env = pb.environment()
            env.putAll(envMap)
            pb.directory(ctx.filesDir)
            pb.redirectErrorStream(true)
            return pb
        }

        // Ensure binary file has execute permissions
        try {
            File(cmd.first()).setExecutable(true, false)
        } catch (_: Exception) {}

        val linker = getSystemLinker(is64)
        QemuLogger.logStartup(ctx, cfg, cmd, envMap, linker)

        var proc: Process
        var usedLinker = false

        try {
            if (android.os.Build.VERSION.SDK_INT >= 29 && linker != null) {
                // On Android 10+, use linker directly to satisfy W^X
                proc = preparePb(listOf(linker) + cmd).start()
                usedLinker = true
            } else {
                proc = preparePb(cmd).start()
            }
        } catch (e: java.io.IOException) {
            if (linker != null && !usedLinker) {
                onLog("[QEMU] Прямой запуск ограничен (${e.message}). Запуск через системный линковщик $linker...")
                QemuLogger.log(ctx, cfg.id, QemuLogger.LogEntry.Level.WARNING, "Direct start failed (${e.message}), using linker $linker")
                proc = preparePb(listOf(linker) + cmd).start()
                usedLinker = true
            } else {
                QemuLogger.log(ctx, cfg.id, QemuLogger.LogEntry.Level.ERROR, "Startup execution failure: ${e.message}")
                throw e
            }
        }

        // Verification check: if process died immediately and we didn't use linker yet, try linker once
        try {
            Thread.sleep(200)
            if (!proc.isAlive && proc.exitValue() != 0 && linker != null && !usedLinker) {
                onLog("[QEMU] Прямой запуск завершился с кодом ${proc.exitValue()}. Попытка запуска через $linker...")
                QemuLogger.log(ctx, cfg.id, QemuLogger.LogEntry.Level.WARNING, "Process died with code ${proc.exitValue()}, trying via $linker")
                proc = preparePb(listOf(linker) + cmd).start()
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
                        QemuLogger.log(ctx, cfg.id, QemuLogger.LogEntry.Level.STDOUT, l)
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
            QemuLogger.log(ctx, cfg.id, if (code == 0) QemuLogger.LogEntry.Level.INFO else QemuLogger.LogEntry.Level.ERROR, exitMsg)
            onLog(exitMsg)
        }.start()

        proc
    }

    fun stop(vmId: Long) {
        activeConfigs.remove(vmId)
        val p = processes.remove(vmId) ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                p.destroyForcibly()
            } else {
                p.destroy()
            }
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
        var success = false

        if (qemuImg != null) {
            try {
                File(qemuImg).setExecutable(true, false)
                val cmd = listOf(qemuImg, "create", "-f", format.lowercase(), diskFile.absolutePath, "${sizeGb}G")
                val pb = ProcessBuilder(cmd)
                val termuxPrefix = QemuInstaller.termuxPrefix(ctx).absolutePath
                val libDir = File(QemuInstaller.termuxPrefix(ctx), "lib").absolutePath
                pb.environment()["LD_LIBRARY_PATH"] = "$libDir:${ctx.applicationInfo.nativeLibraryDir}"
                pb.environment()["PATH"] = "$termuxPrefix/bin:/system/bin"
                pb.redirectErrorStream(true)

                val linker = getSystemLinker()
                val proc = if (android.os.Build.VERSION.SDK_INT >= 29 && linker != null) {
                    ProcessBuilder(listOf(linker) + cmd).also {
                        it.environment()["LD_LIBRARY_PATH"] = "$libDir:${ctx.applicationInfo.nativeLibraryDir}"
                        it.environment()["PATH"] = "$termuxPrefix/bin:/system/bin"
                        it.redirectErrorStream(true)
                    }.start()
                } else {
                    try {
                        pb.start()
                    } catch (e: Exception) {
                        if (linker != null) {
                            ProcessBuilder(listOf(linker) + cmd).start()
                        } else throw e
                    }
                }

                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { onLog(it) }
                }
                success = (proc.waitFor() == 0)
                if (success) {
                    onLog("qemu-img: успешно создан диск ${diskFile.name} ($sizeGb ГБ, $format)")
                    return@withContext true
                }
            } catch (e: Exception) {
                onLog("qemu-img warning: ${e.message}. Использование встроенного генератора дисков...")
            }
        }

        // Reliable fallback for Android 10+ without external binary requirement
        if (format.equals("qcow2", ignoreCase = true)) {
            val created = Qcow2Writer.createQcow2Image(diskFile, sizeGb.toLong())
            if (created) {
                onLog("Создан образ QCOW2 v3 (встроенный модуль): ${diskFile.name} ($sizeGb ГБ)")
                return@withContext true
            } else {
                onLog("Ошибка создания QCOW2 образа")
            }
        }

        // Fallback for raw / img format via sparse file creation
        try {
            RandomAccessFile(diskFile, "rw").use { raf ->
                raf.setLength(sizeGb.toLong() * 1024L * 1024L * 1024L)
            }
            onLog("Создан образ RAW / Sparse file: ${diskFile.name} ($sizeGb ГБ)")
            return@withContext true
        } catch (e: Exception) {
            onLog("Ошибка создания файла диска: ${e.message}")
            return@withContext false
        }
    }

    fun inspectDisk(file: File): Qcow2Writer.DiskInfo {
        return Qcow2Writer.inspectDisk(file)
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

    suspend fun executeMonitorCommand(monitorPort: Int, command: String): String = withContext(Dispatchers.IO) {
        try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", monitorPort), 2000)
                s.soTimeout = 5000
                val reader = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream()))
                val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(s.getOutputStream()))
                
                // Read until first "(qemu) "
                val sb = StringBuilder()
                var lastChars = ""
                while (true) {
                    val c = reader.read()
                    if (c == -1) break
                    val ch = c.toChar()
                    lastChars += ch
                    if (lastChars.length > 7) lastChars = lastChars.substring(1)
                    if (lastChars == "(qemu) ") break
                }
                
                writer.write("$command\n")
                writer.flush()
                
                // Read response until next "(qemu) "
                lastChars = ""
                while (true) {
                    val c = reader.read()
                    if (c == -1) break
                    val ch = c.toChar()
                    sb.append(ch)
                    lastChars += ch
                    if (lastChars.length > 7) lastChars = lastChars.substring(1)
                    if (lastChars == "(qemu) ") break
                }
                
                val output = sb.toString()
                return@withContext output.removeSuffix("(qemu) ").trim()
            }
        } catch (e: Exception) {
            return@withContext "Error: ${e.message}"
        }
    }
}
