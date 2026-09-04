package com.virtualpcvm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

private const val TAG = "QemuInstaller"

private const val REPO_BASE = "https://packages.termux.dev/apt/termux-main"

private val QEMU_PACKAGES = listOf(
    "qemu-system-x86-64-headless",
    "qemu-system-aarch64-headless",
    "qemu-system-arm-headless",
    "qemu-system-i386-headless",
    "qemu-system-ppc-headless",
    "qemu-system-mips-headless",
    "qemu-system-riscv32-headless",
    "qemu-system-riscv64-headless",
    "qemu-system-sparc-headless",
    "qemu-utils"
)

data class InstallProgress(
    val step: String,
    val percent: Int,
    val log: String = "",
    val isDone: Boolean = false,
    val error: String? = null,
)

typealias ProgressCallback = (InstallProgress) -> Unit

data class PackageInfo(
    val name: String,
    val depends: List<String>,
    val filename: String
)

object QemuInstaller {

    fun qemuDir(ctx: Context): File =
        File(ctx.filesDir, "qemu-bins").also { it.mkdirs() }
        
    fun termuxPrefix(ctx: Context): File =
        File(qemuDir(ctx), "usr").also { it.mkdirs() }

    fun isInstalled(ctx: Context, arch: Architecture): Boolean =
        File(termuxPrefix(ctx), "bin/${arch.binary}").canExecute()

    fun anyInstalled(ctx: Context): Boolean =
        Architecture.values().any { isInstalled(ctx, it) }

    suspend fun install(ctx: Context, onProgress: ProgressCallback) = withContext(Dispatchers.IO) {
        val qemuDir = qemuDir(ctx)
        val termuxPrefix = termuxPrefix(ctx)
        val tmpDir = File(ctx.cacheDir, "qemu-tmp").also { it.mkdirs() }

        try {
            // Detect host architecture
            val hostArch = when (android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
                "arm64-v8a" -> "aarch64"
                "armeabi-v7a", "armeabi" -> "arm"
                "x86_64" -> "x86_64"
                "x86" -> "i686"
                else -> "aarch64"
            }

            val packagesUrl = "$REPO_BASE/dists/stable/main/binary-$hostArch/Packages"
            
            onProgress(InstallProgress("Получение списка пакетов для $hostArch...", 2, "GET $packagesUrl"))
            val packagesText = fetchText(packagesUrl)
            
            onProgress(InstallProgress("Список пакетов получен", 5, "Размер: ${packagesText.length} байт"))

            val index = parsePackagesIndex(packagesText)
            val packagesToInstall = resolveDependencies(QEMU_PACKAGES, index)
            
            onProgress(InstallProgress("Разрешены зависимости", 6, "Всего пакетов: ${packagesToInstall.size}"))

            var downloadedCount = 0
            val totalCount = packagesToInstall.size

            for (pkgName in packagesToInstall) {
                val pkgInfo = index[pkgName]
                if (pkgInfo == null) {
                    onProgress(InstallProgress("Ошибка: пакет $pkgName не найден", downloadedCount * 100 / totalCount, error = "not found"))
                    continue
                }

                val debUrl = "$REPO_BASE/${pkgInfo.filename}"
                val debFile = File(tmpDir, "$pkgName.deb")
                
                onProgress(InstallProgress("Загрузка $pkgName...", 5 + (downloadedCount * 80 / totalCount), "↓ $debUrl"))
                downloadFile(debUrl, debFile) { _, _ -> }
                
                onProgress(InstallProgress("Распаковка $pkgName...", 5 + (downloadedCount * 80 / totalCount) + 1, "Распаковка ${debFile.name}"))
                extractDebToPrefix(debFile, termuxPrefix)
                debFile.delete()
                
                downloadedCount++
            }

            // Верификация
            onProgress(InstallProgress("Настройка прав...", 90))
            val binDir = File(termuxPrefix, "bin")
            if (binDir.exists()) {
                binDir.listFiles()?.forEach { it.setExecutable(true, false) }
            }

            onProgress(InstallProgress("Установка завершена!", 100, "Успешно", isDone = true))

        } catch (e: Exception) {
            Log.e(TAG, "Install error", e)
            onProgress(InstallProgress("Ошибка установки", 0, e.message ?: "Неизвестная ошибка", error = e.message))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun parsePackagesIndex(packagesText: String): Map<String, PackageInfo> {
        val map = mutableMapOf<String, PackageInfo>()
        var currentName = ""
        val currentDepends = mutableListOf<String>()
        var currentFilename = ""
        
        for (line in packagesText.lineSequence()) {
            when {
                line.startsWith("Package: ") -> currentName = line.removePrefix("Package: ").trim()
                line.startsWith("Depends: ") -> {
                    val deps = line.removePrefix("Depends: ").split(",")
                    deps.forEach { dep ->
                        val cleanDep = dep.split("|")[0].substringBefore("(").trim()
                        if (cleanDep.isNotEmpty()) {
                            currentDepends.add(cleanDep)
                        }
                    }
                }
                line.startsWith("Filename: ") -> currentFilename = line.removePrefix("Filename: ").trim()
                line.isBlank() -> {
                    if (currentName.isNotEmpty() && currentFilename.isNotEmpty()) {
                        map[currentName] = PackageInfo(currentName, currentDepends.toList(), currentFilename)
                    }
                    currentName = ""
                    currentDepends.clear()
                    currentFilename = ""
                }
            }
        }
        return map
    }

    private fun resolveDependencies(
        rootPackages: List<String>,
        index: Map<String, PackageInfo>
    ): Set<String> {
        val toInstall = mutableSetOf<String>()
        val queue = ArrayDeque<String>(rootPackages)
        
        while (queue.isNotEmpty()) {
            val pkgName = queue.removeFirst()
            if (toInstall.add(pkgName)) {
                val info = index[pkgName]
                if (info != null) {
                    for (dep in info.depends) {
                        queue.addLast(dep)
                    }
                } else {
                    Log.w(TAG, "Package \$pkgName not found in index!")
                }
            }
        }
        return toInstall
    }

    private fun downloadFile(urlStr: String, dest: File, onProgress: (Long, Long) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout    = 60_000
            val total = conn.contentLengthLong
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(65_536)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchText(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun extractDebToPrefix(debFile: File, prefix: File) {
        ArArchiveInputStream(debFile.inputStream().buffered()).use { ar ->
            var arEntry = ar.nextArEntry
            while (arEntry != null) {
                if (arEntry.name.startsWith("data.tar")) {
                    val decompressed = when {
                        arEntry.name.endsWith(".xz")  -> XZCompressorInputStream(ar)
                        arEntry.name.endsWith(".gz")  -> GZIPInputStream(ar)
                        else -> ar
                    }
                    TarArchiveInputStream(decompressed).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val name = entry.name.removePrefix("./").removePrefix("data/data/com.termux/files/usr/")
                                val dest = File(prefix, name)
                                dest.parentFile?.mkdirs()
                                
                                if (entry.isSymbolicLink) {
                                    try {
                                        val destPath = dest.toPath()
                                        java.nio.file.Files.deleteIfExists(destPath)
                                        var link = entry.linkName
                                        if (link.startsWith("/data/data/com.termux/files/usr/")) {
                                            link = prefix.absolutePath + "/" + link.removePrefix("/data/data/com.termux/files/usr/")
                                        }
                                        java.nio.file.Files.createSymbolicLink(destPath, java.nio.file.Paths.get(link))
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to create symlink \$name -> \${entry.linkName}", e)
                                    }
                                } else {
                                    dest.outputStream().use { out ->
                                        val buf = ByteArray(65_536)
                                        var read: Int
                                        while (tar.read(buf).also { read = it } != -1) {
                                            out.write(buf, 0, read)
                                        }
                                    }
                                }
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                    break
                }
                arEntry = ar.nextArEntry
            }
        }
    }
}
