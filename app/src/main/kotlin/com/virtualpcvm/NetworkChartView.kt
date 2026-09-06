package com.virtualpcvm

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Custom real-time network traffic chart view.
 * Visualizes Rx (Receive) and Tx (Transmit) throughput.
 */
class NetworkChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF4CAF50.toInt() // Green for Rx
        pathEffect = CornerPathEffect(4f)
    }

    private val txPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFFFF9800.toInt() // Orange for Tx
        pathEffect = CornerPathEffect(4f)
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x33FFFFFF.toInt()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        isFakeBoldText = true
    }
    
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x88000000.toInt() // Semi-transparent black background
    }

    private val rxPath = Path()
    private val txPath = Path()

    private val rxPoints = mutableListOf<Float>()
    private val txPoints = mutableListOf<Float>()
    
    private var currentRxKbps: Float = 0f
    private var currentTxKbps: Float = 0f

    fun updateMetrics(rxKbps: Float, txKbps: Float) {
        this.currentRxKbps = rxKbps.coerceAtLeast(0f)
        this.currentTxKbps = txKbps.coerceAtLeast(0f)

        rxPoints.add(this.currentRxKbps)
        txPoints.add(this.currentTxKbps)

        if (rxPoints.size > 30) rxPoints.removeAt(0)
        if (txPoints.size > 30) txPoints.removeAt(0)

        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        
        canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, bgPaint)

        val chartTop = 32f
        val chartBottom = h - 8f
        val chartHeight = (chartBottom - chartTop).coerceAtLeast(10f)

        // Draw horizontal grid lines
        canvas.drawLine(0f, chartTop, w, chartTop, gridPaint)
        canvas.drawLine(0f, chartBottom, w, chartBottom, gridPaint)

        // Labels
        textPaint.color = rxPaint.color
        val rxStr = "Rx: ${formatSpeed(currentRxKbps)}"
        canvas.drawText(rxStr, 8f, 22f, textPaint)

        textPaint.color = txPaint.color
        val txStr = "Tx: ${formatSpeed(currentTxKbps)}"
        val txTextWidth = textPaint.measureText(txStr)
        canvas.drawText(txStr, w - txTextWidth - 8f, 22f, textPaint)

        if (rxPoints.isEmpty()) return

        val maxVal = (rxPoints.maxOrNull() ?: 10f).coerceAtLeast(txPoints.maxOrNull() ?: 10f).coerceAtLeast(50f)

        rxPath.reset()
        txPath.reset()

        val stepX = (w - 8f) / (rxPoints.size - 1).coerceAtLeast(1)

        rxPath.moveTo(4f, chartBottom - (rxPoints[0] / maxVal) * chartHeight)
        txPath.moveTo(4f, chartBottom - (txPoints[0] / maxVal) * chartHeight)

        for (i in 1 until rxPoints.size) {
            val currX = 4f + i * stepX
            val rxY = chartBottom - (rxPoints[i] / maxVal) * chartHeight
            val txY = chartBottom - (txPoints[i] / maxVal) * chartHeight
            
            rxPath.lineTo(currX, rxY)
            txPath.lineTo(currX, txY)
        }

        canvas.drawPath(rxPath, rxPaint)
        canvas.drawPath(txPath, txPaint)
    }
    
    private fun formatSpeed(kbps: Float): String {
        return if (kbps > 1024) {
            String.format("%.1f MB/s", kbps / 1024f)
        } else {
            String.format("%.1f KB/s", kbps)
        }
    }
}
