package com.virtualpcvm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

private const val TAG = "QemuInstaller"

private val REPO_MIRRORS = listOf(
    "https://packages.termux.dev/apt/termux-main",
    "https://packages-cf.termux.dev/apt/termux-main",
    "https://grimler.se/termux/termux-main",
    "https://mirror.mwt.me/termux/main",
    "https://packages.termux.org/apt/termux-main"
)

private val QEMU_PACKAGES = listOf(
    "qemu-common",
    "qemu-system-x86-64-headless",
    "qemu-system-aarch64-headless",
    "qemu-system-arm-headless",
    "qemu-system-i386-headless",
    "qemu-system-ppc-headless",
    "qemu-system-ppc64-headless",
    "qemu-system-riscv32-headless",
    "qemu-system-riscv64-headless",
    "qemu-system-m68k-headless",
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

    fun isInstalled(ctx: Context, arch: Architecture): Boolean {
        val binFile = File(termuxPrefix(ctx), "bin/${arch.binary}")
        return binFile.exists() && (binFile.canExecute() || binFile.length() > 0)
    }

    fun anyInstalled(ctx: Context): Boolean =
        Architecture.entries.any { isInstalled(ctx, it) }

    suspend fun install(
        ctx: Context,
        archOverride: String? = null,
        onProgress: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        val termuxPrefix = termuxPrefix(ctx)
        val tmpDir = File(ctx.cacheDir, "qemu-tmp").also { it.mkdirs() }

        try {
            // Detect host architecture (Termux packaging arch: aarch64, arm, x86_64, i686)
            val supportedAbis = android.os.Build.SUPPORTED_ABIS.toList()
            val primaryAbi = supportedAbis.firstOrNull() ?: "arm64-v8a"
            var hostArch = archOverride ?: when (primaryAbi) {
                "arm64-v8a" -> "aarch64"
                "armeabi-v7a", "armeabi" -> "arm"
                "x86_64" -> "x86_64"
                "x86" -> "i686"
                else -> if (primaryAbi.contains("64")) "aarch64" else "arm"
            }

            // Prefer 64-bit packages if the platform supports them (aarch64 or x86_64)
            if (hostArch == "arm" && supportedAbis.contains("arm64-v8a")) {
                hostArch = "aarch64"
            } else if (hostArch == "i686" && supportedAbis.contains("x86_64")) {
                hostArch = "x86_64"
            }

            // Fallback for deprecated 32-bit architectures
            val isLegacyArch = hostArch == "arm" || hostArch == "i686"
            val mirrorsToTry = if (isLegacyArch) {
                listOf(
                    "https://packages.termux.dev/apt/termux-main-21",
                    "https://grimler.se/termux/termux-main-21"
                ) + REPO_MIRRORS
            } else {
                REPO_MIRRORS
            }

            var repoBase = mirrorsToTry.first()
            var packagesText: String? = null

            for (mirror in mirrorsToTry) {
                onProgress(InstallProgress("Поиск пакетов ($hostArch) на $mirror...", 3, "GET $mirror/dists/stable/main/binary-$hostArch"))
                val fetched = fetchPackagesIndex(mirror, hostArch)
                if (fetched.isNotBlank()) {
                    packagesText = fetched
                    repoBase = mirror
                    break
                }
            }

            if (packagesText.isNullOrBlank()) {
                throw IllegalStateException("Не удалось загрузить индекс пакетов для архитектуры $hostArch")
            }

            onProgress(InstallProgress("Список пакетов получен", 8, "Размер: ${packagesText.length} байт"))

            val index = parsePackagesIndex(packagesText)
            val packagesToInstall = resolveDependencies(QEMU_PACKAGES, index)
            
            onProgress(InstallProgress("Разрешены зависимости", 10, "Всего пакетов к установке: ${packagesToInstall.size}"))

            var downloadedCount = 0
            val totalCount = packagesToInstall.size.coerceAtLeast(1)

            for (pkgName in packagesToInstall) {
                val pkgInfo = index[pkgName]
                if (pkgInfo == null) {
                    onProgress(InstallProgress("Пропуск $pkgName: не найден в репозитории", 10 + (downloadedCount * 75 / totalCount), "$pkgName пропущен"))
                    downloadedCount++
                    continue
                }

                val debUrl = "$repoBase/${pkgInfo.filename}"
                val debFile = File(tmpDir, "$pkgName.deb")
                
                val currentPct = 10 + (downloadedCount * 75 / totalCount)
                onProgress(InstallProgress("Загрузка $pkgName...", currentPct, "↓ $debUrl"))
                downloadFile(debUrl, debFile)
                
                onProgress(InstallProgress("Распаковка $pkgName...", currentPct + 1, "Распаковка ${debFile.name}"))
                extractDebToPrefix(debFile, termuxPrefix)
                debFile.delete()
                
                downloadedCount++
            }

            // Настройка прав исполнения
            onProgress(InstallProgress("Настройка прав доступа...", 92, "chmod +x bin/*"))
            val binDir = File(termuxPrefix, "bin")
            if (binDir.exists()) {
                binDir.listFiles()?.forEach { f ->
                    f.setExecutable(true, false)
                    f.setReadable(true, false)
                    try {
                        Runtime.getRuntime().exec(arrayOf("chmod", "755", f.absolutePath)).waitFor()
                    } catch (_: Exception) {}
                }
            }

            val libDir = File(termuxPrefix, "lib")
            if (libDir.exists()) {
                libDir.listFiles()?.forEach { f ->
                    f.setReadable(true, false)
                    f.setExecutable(true, false)
                }
            }

            onProgress(InstallProgress("Установка QEMU завершена!", 100, "Все пакеты успешно установлены", isDone = true))

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
        if (currentName.isNotEmpty() && currentFilename.isNotEmpty()) {
            map[currentName] = PackageInfo(currentName, currentDepends.toList(), currentFilename)
        }
        return map
    }

    private fun resolveDependencies(
        rootPackages: List<String>,
        index: Map<String, PackageInfo>
    ): List<String> {
        val toInstall = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        
        // Add root packages that exist in the index
        for (pkg in rootPackages) {
            if (index.containsKey(pkg)) {
                queue.addLast(pkg)
            } else {
                Log.w(TAG, "Root package $pkg not present in index for this architecture")
            }
        }
        
        while (queue.isNotEmpty()) {
            val pkgName = queue.removeFirst()
            if (toInstall.add(pkgName)) {
                val info = index[pkgName]
                if (info != null) {
                    for (dep in info.depends) {
                        if (!toInstall.contains(dep) && index.containsKey(dep)) {
                            queue.addLast(dep)
                        }
                    }
                }
            }
        }
        return toInstall.toList()
    }

    private fun fetchPackagesIndex(mirror: String, hostArch: String): String {
        val base = "$mirror/dists/stable/main/binary-$hostArch"
        val candidates = listOf(
            "$base/Packages.xz" to "xz",
            "$base/Packages.gz" to "gz",
            "$base/Packages" to "plain"
        )
        for ((url, format) in candidates) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 40_000
                    setRequestProperty("User-Agent", "VirtualPCVM/1.0 (Linux; Android)")
                }
                if (conn.responseCode in 200..299) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    if (bytes.isNotEmpty()) {
                        return when (format) {
                            "xz" -> org.tukaani.xz.XZInputStream(ByteArrayInputStream(bytes)).bufferedReader().use { it.readText() }
                            "gz" -> GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().use { it.readText() }
                            else -> String(bytes, Charsets.UTF_8)
                        }
                    }
                } else {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Index fetch try failed for $url: ${e.message}")
            }
        }
        return ""
    }

    private fun downloadFile(urlStr: String, dest: File) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "VirtualPCVM/1.0 (Linux; Android)")
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(65_536)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                    }
                }
            }
        } finally {
            conn?.disconnect()
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
                                var rawName = entry.name
                                if (rawName.startsWith("./")) rawName = rawName.substring(2)
                                if (rawName.startsWith("/")) rawName = rawName.substring(1)
                                if (rawName.startsWith("data/data/com.termux/files/usr/")) {
                                    rawName = rawName.removePrefix("data/data/com.termux/files/usr/")
                                }
                                
                                val dest = File(prefix, rawName)
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
                                        Log.w(TAG, "Symlink fallback for $rawName -> ${entry.linkName}")
                                    }
                                } else {
                                    dest.outputStream().use { out ->
                                        val buf = ByteArray(65_536)
                                        var read: Int
                                        while (tar.read(buf).also { read = it } != -1) {
                                            out.write(buf, 0, read)
                                        }
                                    }
                                    if (rawName.startsWith("bin/")) {
                                        dest.setExecutable(true, false)
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
