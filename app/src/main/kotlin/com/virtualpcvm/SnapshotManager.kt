package com.virtualpcvm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class VmSnapshot(
    val id: String,
    val tag: String,
    val vmSize: String,
    val date: String,
    val vmClock: String
)

object SnapshotManager {

    suspend fun listSnapshots(ctx: Context, cfg: VmConfig): List<VmSnapshot> = withContext(Dispatchers.IO) {
        val isRunning = QemuManager.isRunning(cfg.id)
        if (isRunning && cfg.monitorPort > 0) {
            val monitorResp = QemuManager.executeMonitorCommand(cfg.monitorPort, "info snapshots")
            val parsed = parseSnapshotOutput(monitorResp)
            if (parsed.isNotEmpty()) return@withContext parsed
        }

        val diskFile = File(cfg.diskPath)
        if (!diskFile.exists() || !cfg.diskPath.endsWith(".qcow2")) {
            return@withContext emptyList()
        }

        val qemuImg = QemuManager.findQemuImg(ctx) ?: return@withContext emptyList()
        try {
            val cmd = listOf(qemuImg, "snapshot", "-l", diskFile.absolutePath)
            val pb = ProcessBuilder(cmd)
            val termuxPrefix = QemuInstaller.termuxPrefix(ctx).absolutePath
            val libDir = File(QemuInstaller.termuxPrefix(ctx), "lib").absolutePath
            pb.environment()["LD_LIBRARY_PATH"] = "$libDir:${ctx.applicationInfo.nativeLibraryDir}"
            pb.environment()["PATH"] = "$termuxPrefix/bin:/system/bin"
            pb.redirectErrorStream(true)

            val proc = try {
                pb.start()
            } catch (e: Exception) {
                val linker = QemuManager.getSystemLinker()
                if (linker != null) ProcessBuilder(listOf(linker) + cmd).start() else throw e
            }

            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            return@withContext parseSnapshotOutput(output)
        } catch (_: Exception) {
            return@withContext emptyList()
        }
    }

    private fun parseSnapshotOutput(output: String): List<VmSnapshot> {
        val list = mutableListOf<VmSnapshot>()
        val lines = output.lines()
        var foundHeader = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("ID") && trimmed.contains("TAG")) {
                foundHeader = true
                continue
            }
            if (!foundHeader || trimmed.isEmpty()) continue

            // Example line: "1         snap1                   0 B 2026-09-05 11:30:15   00:00:00.000"
            val tokens = trimmed.split("\\s{2,}".toRegex()).map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.size >= 4) {
                val id = tokens[0]
                val tag = tokens[1]
                val size = tokens[2]
                val date = tokens[3]
                val clock = if (tokens.size > 4) tokens[4] else ""
                list.add(VmSnapshot(id = id, tag = tag, vmSize = size, date = date, vmClock = clock))
            } else {
                // Fallback splitting by spaces
                val parts = trimmed.split("\\s+".toRegex())
                if (parts.size >= 5) {
                    list.add(
                        VmSnapshot(
                            id = parts[0],
                            tag = parts[1],
                            vmSize = "${parts[2]} ${parts[3]}",
                            date = if (parts.size > 5) "${parts[4]} ${parts[5]}" else parts[4],
                            vmClock = if (parts.size > 6) parts[6] else ""
                        )
                    )
                }
            }
        }
        return list
    }

    suspend fun createSnapshot(ctx: Context, cfg: VmConfig, tag: String): Result<String> = withContext(Dispatchers.IO) {
        val safeTag = tag.trim().replace("\\s+".toRegex(), "_")
        if (safeTag.isEmpty()) return@withContext Result.failure(Exception("Имя снапшота не может быть пустым"))

        val isRunning = QemuManager.isRunning(cfg.id)
        if (isRunning && cfg.monitorPort > 0) {
            val resp = QemuManager.executeMonitorCommand(cfg.monitorPort, "savevm $safeTag")
            return@withContext if (resp.contains("Error", ignoreCase = true) || resp.contains("failed", ignoreCase = true)) {
                Result.failure(Exception(resp))
            } else {
                Result.success("Снапшот «$safeTag» успешно создан (включая память)")
            }
        }

        val diskFile = File(cfg.diskPath)
        if (!diskFile.exists()) return@withContext Result.failure(Exception("Файл диска не найден"))

        val ok = QemuManager.manageSnapshot(ctx, diskFile, safeTag, "-c") {}
        return@withContext if (ok) {
            Result.success("Снапшот «$safeTag» успешно создан")
        } else {
            Result.failure(Exception("Ошибка создания снапшота qemu-img"))
        }
    }

    suspend fun revertSnapshot(ctx: Context, cfg: VmConfig, tag: String): Result<String> = withContext(Dispatchers.IO) {
        val isRunning = QemuManager.isRunning(cfg.id)
        if (isRunning && cfg.monitorPort > 0) {
            val resp = QemuManager.executeMonitorCommand(cfg.monitorPort, "loadvm $tag")
            return@withContext if (resp.contains("Error", ignoreCase = true) || resp.contains("failed", ignoreCase = true)) {
                Result.failure(Exception(resp))
            } else {
                Result.success("Состояние откатано к «$tag»")
            }
        }

        val diskFile = File(cfg.diskPath)
        if (!diskFile.exists()) return@withContext Result.failure(Exception("Файл диска не найден"))

        val ok = QemuManager.manageSnapshot(ctx, diskFile, tag, "-a") {}
        return@withContext if (ok) {
            Result.success("Диск успешно откатан к «$tag»")
        } else {
            Result.failure(Exception("Ошибка восстановления снапшота qemu-img"))
        }
    }

    suspend fun deleteSnapshot(ctx: Context, cfg: VmConfig, tag: String): Result<String> = withContext(Dispatchers.IO) {
        val isRunning = QemuManager.isRunning(cfg.id)
        if (isRunning && cfg.monitorPort > 0) {
            val resp = QemuManager.executeMonitorCommand(cfg.monitorPort, "delvm $tag")
            return@withContext if (resp.contains("Error", ignoreCase = true) || resp.contains("failed", ignoreCase = true)) {
                Result.failure(Exception(resp))
            } else {
                Result.success("Снапшот «$tag» удален")
            }
        }

        val diskFile = File(cfg.diskPath)
        if (!diskFile.exists()) return@withContext Result.failure(Exception("Файл диска не найден"))

        val ok = QemuManager.manageSnapshot(ctx, diskFile, tag, "-d") {}
        return@withContext if (ok) {
            Result.success("Снапшот «$tag» удален")
        } else {
            Result.failure(Exception("Ошибка удаления снапшота qemu-img"))
        }
    }
}
