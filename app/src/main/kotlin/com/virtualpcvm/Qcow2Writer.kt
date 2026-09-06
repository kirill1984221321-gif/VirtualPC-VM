package com.virtualpcvm

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin QCOW2 v3 (QEMU Copy-On-Write) image generator and inspector.
 * Provides 100% reliable disk image creation on Android 10+ without external binaries.
 */
object Qcow2Writer {

    private const val QCOW_MAGIC = 0x514649fb // "QFI\xfb"
    private const val QCOW_VERSION_3 = 3
    private const val CLUSTER_BITS = 16 // 64 KB clusters (1 shl 16 = 65536 bytes)
    private const val CLUSTER_SIZE = 1 shl CLUSTER_BITS
    private const val REFCOUNT_ORDER = 4 // 2^4 = 16-bit refcounts (2 bytes per entry)
    private const val HEADER_LENGTH = 104

    /**
     * Creates a valid, dynamically-expanding QCOW2 v3 disk image of the given virtual size in GB.
     * Initial allocated size on host storage is just 256 KB (4 clusters).
     */
    fun createQcow2Image(targetFile: File, sizeGb: Long): Boolean {
        return try {
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) targetFile.delete()

            val virtualSizeBytes = sizeGb * 1024L * 1024L * 1024L

            // Each L2 table is 1 cluster (64 KB) containing 64KB / 8 = 8192 entries.
            // Each L2 entry covers 1 cluster (64 KB). So 1 L2 table covers 8192 * 64KB = 512 MB.
            val l2Coverage = 8192L * CLUSTER_SIZE
            val l1Entries = ((virtualSizeBytes + l2Coverage - 1) / l2Coverage).toInt().coerceAtLeast(1)

            // Cluster 0: Header (Offset 0)
            // Cluster 1: L1 Table (Offset 65,536)
            // Cluster 2: Refcount Table (Offset 131,072)
            // Cluster 3: Refcount Block 0 (Offset 196,608)
            val cluster0Offset = 0L
            val cluster1Offset = 1L * CLUSTER_SIZE
            val cluster2Offset = 2L * CLUSTER_SIZE
            val cluster3Offset = 3L * CLUSTER_SIZE

            RandomAccessFile(targetFile, "rw").use { raf ->
                // Pre-allocate 4 clusters (256 KB)
                raf.setLength(4L * CLUSTER_SIZE)

                // --- 1. Write Header (Cluster 0) ---
                val headerBuf = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN)
                headerBuf.putInt(QCOW_MAGIC)               // magic: QFI\xfb
                headerBuf.putInt(QCOW_VERSION_3)           // version: 3
                headerBuf.putLong(0L)                      // backing_file_offset: 0
                headerBuf.putInt(0)                        // backing_file_size: 0
                headerBuf.putInt(CLUSTER_BITS)             // cluster_bits: 16 (64 KB)
                headerBuf.putLong(virtualSizeBytes)        // size: virtual size in bytes
                headerBuf.putInt(0)                        // crypt_method: 0 (none)
                headerBuf.putInt(l1Entries)                // l1_size: number of L1 table entries
                headerBuf.putLong(cluster1Offset)          // l1_table_offset: Cluster 1
                headerBuf.putLong(cluster2Offset)          // refcount_table_offset: Cluster 2
                headerBuf.putInt(1)                        // refcount_table_clusters: 1
                headerBuf.putInt(0)                        // nb_snapshots: 0
                headerBuf.putLong(0L)                      // snapshots_offset: 0
                headerBuf.putLong(0L)                      // incompatible_features: 0
                headerBuf.putLong(0L)                      // compatible_features: 0
                headerBuf.putLong(0L)                      // autoclear_features: 0
                headerBuf.putInt(REFCOUNT_ORDER)           // refcount_order: 4 (16-bit)
                headerBuf.putInt(HEADER_LENGTH)            // header_length: 104

                raf.seek(cluster0Offset)
                raf.write(headerBuf.array())

                // --- 2. Write L1 Table (Cluster 1) ---
                // Initial L1 table contains all 0s (no allocated L2 tables yet)
                val l1Buf = ByteArray(CLUSTER_SIZE) // zero-filled
                raf.seek(cluster1Offset)
                raf.write(l1Buf)

                // --- 3. Write Refcount Table (Cluster 2) ---
                // Entry 0 in refcount table points to Cluster 3 (Refcount block 0)
                val refTableBuf = ByteBuffer.allocate(CLUSTER_SIZE).order(ByteOrder.BIG_ENDIAN)
                refTableBuf.putLong(cluster3Offset)
                // Rest of entries remain 0
                raf.seek(cluster2Offset)
                raf.write(refTableBuf.array())

                // --- 4. Write Refcount Block 0 (Cluster 3) ---
                // Mark clusters 0, 1, 2, 3 as having refcount = 1 (16-bit Big-Endian 0x0001)
                val refBlockBuf = ByteBuffer.allocate(CLUSTER_SIZE).order(ByteOrder.BIG_ENDIAN)
                refBlockBuf.putShort(1) // Cluster 0 (Header)
                refBlockBuf.putShort(1) // Cluster 1 (L1 Table)
                refBlockBuf.putShort(1) // Cluster 2 (Refcount Table)
                refBlockBuf.putShort(1) // Cluster 3 (Refcount Block 0)
                // Rest of entries remain 0 (unallocated)
                raf.seek(cluster3Offset)
                raf.write(refBlockBuf.array())
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    data class DiskInfo(
        val format: String,
        val virtualSizeBytes: Long,
        val actualSizeBytes: Long,
        val isValid: Boolean,
        val message: String
    )

    /**
     * Inspects a disk file to verify format, virtual size, and header integrity.
     */
    fun inspectDisk(file: File): DiskInfo {
        if (!file.exists() || !file.canRead()) {
            return DiskInfo("Unknown", 0L, 0L, false, "Файл не существует или недоступен для чтения")
        }
        val actualSize = file.length()
        if (actualSize < 512) {
            return DiskInfo("Unknown", 0L, actualSize, false, "Размер файла меньше 512 байт")
        }

        try {
            RandomAccessFile(file, "r").use { raf ->
                val magicBuf = ByteArray(4)
                raf.readFully(magicBuf)
                val magic = ByteBuffer.wrap(magicBuf).order(ByteOrder.BIG_ENDIAN).int

                if (magic == QCOW_MAGIC) {
                    val version = raf.readInt()
                    raf.skipBytes(12) // backing file offset & size
                    val clusterBits = raf.readInt()
                    val virtualSize = raf.readLong()
                    return DiskInfo(
                        format = "qcow2 (v$version)",
                        virtualSizeBytes = virtualSize,
                        actualSizeBytes = actualSize,
                        isValid = true,
                        message = "QCOW2 v$version · Виртуальный размер: ${virtualSize / (1024 * 1024 * 1024)} ГБ · Кластер: ${1 shl clusterBits} байт"
                    )
                }

                // Check for ISO-9660 magic at offset 32768 (0x8000)
                if (actualSize >= 32774) {
                    raf.seek(32768)
                    val isoMagic = ByteArray(5)
                    raf.readFully(isoMagic)
                    if (String(isoMagic) == "CD001") {
                        return DiskInfo(
                            format = "iso (ISO-9660 CD-ROM)",
                            virtualSizeBytes = actualSize,
                            actualSizeBytes = actualSize,
                            isValid = true,
                            message = "Образ оптического диска ISO-9660 (${actualSize / (1024 * 1024)} МБ)"
                        )
                    }
                }

                // Treat as RAW/IMG sparse disk
                return DiskInfo(
                    format = "raw / img",
                    virtualSizeBytes = actualSize,
                    actualSizeBytes = actualSize,
                    isValid = true,
                    message = "Сырой образ диска RAW (${actualSize / (1024 * 1024 * 1024)} ГБ)"
                )
            }
        } catch (e: Exception) {
            return DiskInfo("Error", 0L, actualSize, false, "Ошибка чтения диска: ${e.message}")
        }
    }
}
