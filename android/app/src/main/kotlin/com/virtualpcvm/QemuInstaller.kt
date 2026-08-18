package com.virtualpcvm

import android.content.Context
import android.os.Build
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

private const val TAG = "QemuInstaller"
private const val REPO_BASE = "https://packages.termux.dev/apt/termux-main"

data class InstallProgress(
    val step: String,
    val percent: Int,
    val log: String = "",
    val isDone: Boolean = false,
    val error: String? = null,
)

typealias ProgressCallback = (InstallProgress) -> Unit

/** Downloads only QEMU binaries that match the Android host ABI. */
object QemuInstaller {

    private val qemuPackages = listOf(
        "qemu-system-x86-64" to "qemu-system-x86_64",
        "qemu-system-aarch64" to "qemu-system-aarch64",
        "qemu-system-arm" to "qemu-system-arm",
        "qemu-system-i386" to "qemu-system-i386",
    )

    private fun termuxArch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        else -> error("VirtualPC-VM supports ARM Android hosts only")
    }

    private fun packagesUrl(): String =
        "$REPO_BASE/dists/stable/main/binary-${termuxArch()}/Packages"

    fun qemuDir(ctx: Context): File =
        File(ctx.filesDir, "qemu-bins").also { it.mkdirs() }

    fun isInstalled(ctx: Context, binaryName: String): Boolean =
        File(qemuDir(ctx), binaryName).canExecute()

    fun anyInstalled(ctx: Context): Boolean =
        qemuPackages.any { isInstalled(ctx, it.second) }

    suspend fun install(ctx: Context, onProgress: ProgressCallback) = withContext(Dispatchers.IO) {
        val dir = qemuDir(ctx)
        val tmpDir = File(ctx.cacheDir, "qemu-tmp").also { it.mkdirs() }

        try {
            val indexUrl = packagesUrl()
            onProgress(InstallProgress("Получение списка QEMU для ${termuxArch()}...", 2, "GET $indexUrl"))
            val packagesText = fetchText(indexUrl)

            qemuPackages.forEachIndexed { index, (pkgName, binName) ->
                val base = 5 + index * 22
                if (isInstalled(ctx, binName)) {
                    onProgress(InstallProgress("$binName уже установлен", base + 18, "✓"))
                    return@forEachIndexed
                }

                val filename = parsePackageFilename(packagesText, pkgName)
                    ?: throw IllegalStateException("Пакет $pkgName отсутствует в Termux ${termuxArch()} repository")
                val debUrl = "$REPO_BASE/$filename"
                val debFile = File(tmpDir, "$pkgName.deb")

                onProgress(InstallProgress("Загрузка $pkgName...", base + 2, debUrl))
                downloadFile(debUrl, debFile) { downloaded, total ->
                    val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                    onProgress(InstallProgress("Загрузка $pkgName: $pct%", base + 2 + pct / 20))
                }

                if (!extractDebBinary(debFile, dir, binName)) {
                    throw IllegalStateException("$binName не найден внутри $pkgName")
                }
                debFile.delete()
                File(dir, binName).setExecutable(true, false)
                onProgress(InstallProgress("$binName установлен", base + 20, "✓"))
            }

            val missing = qemuPackages.map { it.second }.filterNot { isInstalled(ctx, it) }
            if (missing.isNotEmpty()) throw IllegalStateException("Не установлены: ${missing.joinToString()}")
            onProgress(InstallProgress("QEMU установлен", 100, "host=${termuxArch()}", isDone = true))
        } catch (e: Exception) {
            Log.e(TAG, "Install error", e)
            onProgress(InstallProgress("Ошибка установки QEMU", 0, error = e.message))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun parsePackageFilename(text: String, packageName: String): String? {
        var match = false
        for (line in text.lineSequence()) {
            when {
                line.startsWith("Package: ") -> match = line.substringAfter("Package: ").trim() == packageName
                match && line.startsWith("Filename: ") -> return line.substringAfter("Filename: ").trim()
                line.isBlank() -> match = false
            }
        }
        return null
    }

    private fun downloadFile(urlStr: String, dest: File, progress: (Long, Long) -> Unit) {
        val conn = followRedirects(urlStr)
        try {
            val total = conn.contentLengthLong
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(65_536)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        progress(done, total)
                    }
                }
            }
        } finally { conn.disconnect() }
    }

    private fun followRedirects(urlStr: String): HttpURLConnection {
        var current = urlStr
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "VirtualPC-VM/0.1")
            if (conn.responseCode in 301..308) {
                current = conn.getHeaderField("Location") ?: error("Redirect without Location")
                conn.disconnect()
            } else return conn
        }
        error("Too many redirects: $urlStr")
    }

    private fun fetchText(url: String): String = followRedirects(url).let { conn ->
        try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
    }

    private fun extractDebBinary(debFile: File, outDir: File, target: String): Boolean {
        ArArchiveInputStream(debFile.inputStream().buffered()).use { ar ->
            var entry = ar.nextArEntry
            while (entry != null) {
                if (entry.name.startsWith("data.tar")) {
                    val stream = when {
                        entry.name.endsWith(".xz") -> XZCompressorInputStream(ar)
                        entry.name.endsWith(".gz") -> java.util.zip.GZIPInputStream(ar)
                        else -> ar
                    }
                    TarArchiveInputStream(stream).use { tar ->
                        var tarEntry = tar.nextTarEntry
                        while (tarEntry != null) {
                            if (!tarEntry.isDirectory && File(tarEntry.name).name == target) {
                                File(outDir, target).outputStream().use { output -> tar.copyTo(output) }
                                return true
                            }
                            tarEntry = tar.nextTarEntry
                        }
                    }
                }
                entry = ar.nextArEntry
            }
        }
        return false
    }
}
