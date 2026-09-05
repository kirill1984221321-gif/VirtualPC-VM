package com.virtualpcvm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Custom real-time metrics chart view.
 * Renders a smooth sparkline chart for CPU % and a mini RAM indicator bar.
 */
class MetricsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF2196F3.toInt() // Vibrant Blue
        pathEffect = CornerPathEffect(8f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x22FFFFFF.toInt()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0BEC5.toInt()
        textSize = 28f
        isFakeBoldText = true
    }

    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33FFFFFF.toInt()
    }

    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF4CAF50.toInt() // Green
    }

    private val path = Path()
    private val fillPath = Path()

    private val cpuPoints = mutableListOf<Float>()
    private var currentCpuPercent: Float = 0f
    private var currentRamMb: Int = 0
    private var totalRamMb: Int = 1024
    private var isVmRunning: Boolean = false

    fun updateMetrics(
        cpuPercent: Float,
        ramUsedMb: Int,
        ramMaxMb: Int,
        isRunning: Boolean
    ) {
        this.isVmRunning = isRunning
        this.currentCpuPercent = cpuPercent.coerceIn(0f, 100f)
        this.currentRamMb = ramUsedMb.coerceAtLeast(0)
        this.totalRamMb = ramMaxMb.coerceAtLeast(1)

        cpuPoints.add(this.currentCpuPercent)
        if (cpuPoints.size > 25) {
            cpuPoints.removeAt(0)
        }
        postInvalidate()
    }

    fun clearMetrics() {
        cpuPoints.clear()
        currentCpuPercent = 0f
        currentRamMb = 0
        isVmRunning = false
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Background grid / container
        val chartTop = 38f
        val chartBottom = h - 36f
        val chartHeight = (chartBottom - chartTop).coerceAtLeast(10f)

        // Draw horizontal guide lines
        canvas.drawLine(0f, chartTop, w, chartTop, gridPaint)
        canvas.drawLine(0f, chartTop + chartHeight / 2f, w, chartTop + chartHeight / 2f, gridPaint)
        canvas.drawLine(0f, chartBottom, w, chartBottom, gridPaint)

        // 2. Header text: CPU % and RAM MB
        val cpuColor = if (currentCpuPercent > 80f) 0xFFF44336.toInt() else 0xFF64B5F6.toInt()
        textPaint.color = cpuColor
        canvas.drawText(
            if (isVmRunning) String.format("CPU: %.1f%%", currentCpuPercent) else "CPU: 0.0%",
            8f,
            28f,
            textPaint
        )

        textPaint.color = 0xFF81C784.toInt()
        val ramStr = if (isVmRunning && currentRamMb > 0) {
            "RAM: $currentRamMb MB / $totalRamMb MB"
        } else {
            "RAM: -- / $totalRamMb MB"
        }
        val ramTextWidth = textPaint.measureText(ramStr)
        canvas.drawText(ramStr, (w - ramTextWidth - 8f).coerceAtLeast(0f), 28f, textPaint)

        // 3. Draw RAM bar at the bottom
        val barY = h - 22f
        val barH = 12f
        canvas.drawRoundRect(8f, barY, w - 8f, barY + barH, 6f, 6f, barBgPaint)

        val ramRatio = (currentRamMb.toFloat() / totalRamMb.toFloat()).coerceIn(0f, 1f)
        val ramFillWidth = (w - 16f) * ramRatio
        if (ramFillWidth > 0 && isVmRunning) {
            barFillPaint.color = if (ramRatio > 0.85f) 0xFFE57373.toInt() else 0xFF81C784.toInt()
            canvas.drawRoundRect(8f, barY, 8f + ramFillWidth, barY + barH, 6f, 6f, barFillPaint)
        }

        // 4. Draw CPU sparkline
        if (cpuPoints.size < 2 || !isVmRunning) {
            // Flat baseline
            linePaint.color = 0x5590CAF9.toInt()
            canvas.drawLine(8f, chartBottom, w - 8f, chartBottom, linePaint)
            return
        }

        path.reset()
        fillPath.reset()

        val stepX = (w - 16f) / (cpuPoints.size - 1).coerceAtLeast(1)
        var startX = 8f

        path.moveTo(startX, chartBottom - (cpuPoints[0] / 100f) * chartHeight)
        fillPath.moveTo(startX, chartBottom)
        fillPath.lineTo(startX, chartBottom - (cpuPoints[0] / 100f) * chartHeight)

        for (i in 1 until cpuPoints.size) {
            val prevX = 8f + (i - 1) * stepX
            val prevY = chartBottom - (cpuPoints[i - 1] / 100f) * chartHeight
            val currX = 8f + i * stepX
            val currY = chartBottom - (cpuPoints[i] / 100f) * chartHeight

            val midX = (prevX + currX) / 2f
            path.cubicTo(midX, prevY, midX, currY, currX, currY)
            fillPath.cubicTo(midX, prevY, midX, currY, currX, currY)
        }

        val lastX = 8f + (cpuPoints.size - 1) * stepX
        fillPath.lineTo(lastX, chartBottom)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, chartTop, 0f, chartBottom,
            0x442196F3.toInt(), 0x052196F3.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        linePaint.color = 0xFF29B6F6.toInt()
        canvas.drawPath(path, linePaint)
    }
}
