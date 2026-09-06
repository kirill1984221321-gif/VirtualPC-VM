package com.virtualpcvm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Environment
import android.os.StatFs
import android.util.AttributeSet
import android.view.View
import java.io.File
import java.util.Locale

/**
 * Custom chart view that monitors and visualizes storage growth of virtual disk images (QCOW2, RAW, IMG)
 * on the Android device filesystem.
 * Displays physical storage consumed vs declared virtual limits and available Android storage.
 */
class DiskUsageChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class DiskItem(
        val vmName: String,
        val diskPath: String,
        val format: String,
        val actualSizeBytes: Long,
        val virtualSizeBytes: Long,
        val color: Int
    ) {
        val actualMb: Float get() = actualSizeBytes / (1024f * 1024f)
        val actualGb: Float get() = actualSizeBytes / (1024f * 1024f * 1024f)
        val virtualGb: Float get() = virtualSizeBytes / (1024f * 1024f * 1024f)
        val growthPercent: Float
            get() = if (virtualSizeBytes > 0) (actualSizeBytes.toFloat() / virtualSizeBytes.toFloat()) * 100f else 0f
    }

    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2A2B36.toInt()
    }

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0E0E0.toInt()
        textSize = 28f
        isFakeBoldText = true
    }

    private val textSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt()
        textSize = 22f
    }

    private val legendDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val diskItems = mutableListOf<DiskItem>()
    private var deviceTotalBytes: Long = 0L
    private var deviceFreeBytes: Long = 0L

    private val palette = intArrayOf(
        0xFF4F6DFF.toInt(), // Indigo
        0xFF00B0FF.toInt(), // Cyan
        0xFF00E676.toInt(), // Green
        0xFFFF9100.toInt(), // Amber
        0xFFE040FB.toInt(), // Purple
        0xFFFF5252.toInt()  // Coral
    )

    fun updateDisks(items: List<DiskItem>, freeBytes: Long, totalBytes: Long) {
        diskItems.clear()
        diskItems.addAll(items)
        this.deviceFreeBytes = freeBytes
        this.deviceTotalBytes = totalBytes
        requestLayout()
        invalidate()
    }

    fun scanDisks(vms: List<VmConfig>) {
        val items = mutableListOf<DiskItem>()
        var colorIdx = 0

        for (vm in vms) {
            val path = vm.diskPath.trim()
            if (path.isNotEmpty()) {
                val f = File(path)
                val actualSize = if (f.exists()) f.length() else 0L
                val virtualSize = if (vm.diskSizeGb > 0) {
                    vm.diskSizeGb * 1024L * 1024L * 1024L
                } else {
                    actualSize
                }
                val format = if (path.endsWith(".qcow2", true)) "QCOW2" else vm.diskFormat.uppercase(Locale.US)

                items.add(
                    DiskItem(
                        vmName = vm.name,
                        diskPath = path,
                        format = format,
                        actualSizeBytes = actualSize,
                        virtualSizeBytes = virtualSize,
                        color = palette[colorIdx % palette.size]
                    )
                )
                colorIdx++
            }
        }

        val stat = try {
            val filesDir = context.filesDir
            StatFs(filesDir.absolutePath)
        } catch (_: Exception) {
            null
        }

        val free = stat?.availableBytes ?: (10L * 1024L * 1024L * 1024L)
        val total = stat?.totalBytes ?: (64L * 1024L * 1024L * 1024L)

        updateDisks(items, free, total)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Dynamic height based on number of disk items
        val barHeight = 80f
        val itemLineHeight = 36f
        val totalHeight = (barHeight + (diskItems.size * itemLineHeight) + 40f).toInt().coerceAtLeast(160)
        setMeasuredDimension(width, totalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val padding = 16f
        val usableWidth = w - (padding * 2f)

        if (diskItems.isEmpty()) {
            textSecondaryPaint.textSize = 24f
            canvas.drawText(
                "Нет подключенных образов дисков QCOW2",
                padding,
                60f,
                textSecondaryPaint
            )
            return
        }

        val totalDisksBytes = diskItems.sumOf { it.actualSizeBytes }
        val totalDisksMb = totalDisksBytes / (1024f * 1024f)
        val freeGb = deviceFreeBytes / (1024f * 1024f * 1024f)

        // Title & Summary
        textPrimaryPaint.textSize = 26f
        canvas.drawText("Рост образов QCOW2: ${String.format(Locale.US, "%.1f MB", totalDisksMb)}", padding, 36f, textPrimaryPaint)

        textSecondaryPaint.textSize = 20f
        val freeText = "Свободно на Android: ${String.format(Locale.US, "%.1f GB", freeGb)}"
        val freeTextWidth = textSecondaryPaint.measureText(freeText)
        canvas.drawText(freeText, w - padding - freeTextWidth, 36f, textSecondaryPaint)

        // Segmented Horizontal Bar
        val barTop = 50f
        val barBottom = barTop + 24f
        val barRadius = 12f

        val barRect = RectF(padding, barTop, padding + usableWidth, barBottom)
        canvas.drawRoundRect(barRect, barRadius, barRadius, barBgPaint)

        // Draw segments relative to total disk images + free space
        val totalReferenceSpace = (totalDisksBytes + (deviceFreeBytes.coerceAtLeast(1024L * 1024L * 1024L))).toFloat()
        var currentX = padding

        diskItems.forEach { item ->
            if (item.actualSizeBytes > 0 && totalReferenceSpace > 0) {
                val segWidth = ((item.actualSizeBytes.toFloat() / totalReferenceSpace) * usableWidth).coerceAtLeast(6f)
                val segRect = RectF(currentX, barTop, (currentX + segWidth).coerceAtMost(padding + usableWidth), barBottom)
                segmentPaint.color = item.color
                canvas.drawRoundRect(segRect, barRadius, barRadius, segmentPaint)
                currentX += segWidth
            }
        }

        // Legends & Growth Statistics
        var textY = barBottom + 32f
        diskItems.forEach { item ->
            // Color dot
            legendDotPaint.color = item.color
            canvas.drawCircle(padding + 8f, textY - 8f, 7f, legendDotPaint)

            // Disk details
            textPrimaryPaint.textSize = 21f
            val nameText = "${item.vmName} (${item.format})"
            canvas.drawText(nameText, padding + 24f, textY, textPrimaryPaint)

            // Growth % and physical vs virtual
            textSecondaryPaint.textSize = 20f
            val sizeInfo = if (item.actualMb >= 1024f) {
                String.format(Locale.US, "%.2f GB / %.1f GB (%.1f%%)", item.actualGb, item.virtualGb, item.growthPercent)
            } else {
                String.format(Locale.US, "%.1f MB / %.1f GB (%.1f%%)", item.actualMb, item.virtualGb, item.growthPercent)
            }
            val sizeInfoWidth = textSecondaryPaint.measureText(sizeInfo)
            canvas.drawText(sizeInfo, w - padding - sizeInfoWidth, textY, textSecondaryPaint)

            textY += 34f
        }
    }
}
