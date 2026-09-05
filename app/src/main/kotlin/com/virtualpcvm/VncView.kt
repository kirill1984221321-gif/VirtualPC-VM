package com.virtualpcvm

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Renders a VNC framebuffer and forwards touch events as RFB pointer events.
 *
 * Touch mapping:
 *  - Single tap           → left click (button 1)
 *  - Long press           → right click (button 3), shown with red ripple
 *  - Two-finger tap       → middle click (button 2)
 *  - Drag (1 finger)      → mouse move + left button held
 *  - Scroll (2-finger drag) → mouse wheel (buttons 8/16)
 */
class VncView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    private var client: VncClient? = null
    private var bitmap: Bitmap? = null
    private val bitmapLock = Any()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // scale / pan
    private var scaleX = 1f; private var scaleY = 1f
    private var panX   = 0f; private var panY   = 0f

    // gesture detector for long-press, tap, and 2-finger pan
    private val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val (vx, vy) = viewToVnc(e.x, e.y)
            client?.sendPointerEvent(vx, vy, 0x04) // right button down
            client?.sendPointerEvent(vx, vy, 0x00) // release
            showRipple(e.x, e.y)
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val (vx, vy) = viewToVnc(e.x, e.y)
            client?.sendPointerEvent(vx, vy, 0x01) // left down
            client?.sendPointerEvent(vx, vy, 0x00) // left up
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e2.pointerCount >= 2) {
                panX -= distanceX
                panY -= distanceY
                invalidate()
                return true
            }
            return false
        }
    })

    // pinch to zoom
    private val scaleDetector = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val focusX = detector.focusX
            val focusY = detector.focusY
            
            scaleX *= scaleFactor
            scaleY *= scaleFactor
            
            panX = focusX + (panX - focusX) * scaleFactor
            panY = focusY + (panY - focusY) * scaleFactor
            
            invalidate()
            return true
        }
    })

    // ripple for right-click visual feedback
    private var rippleX = 0f; private var rippleY = 0f; private var rippleAlpha = 0f
    private val ripplePaint = Paint().apply { color = 0xAAFF4444.toInt(); style = Paint.Style.FILL }
    private val rippleAnimator = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 400
        addUpdateListener { rippleAlpha = it.animatedValue as Float; invalidate() }
    }

    var isTouchMode = true

    fun attach(vncClient: VncClient) {
        client = vncClient
        vncClient.onConnected = { w, h, _ ->
            post {
                synchronized(bitmapLock) {
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                }
                recalcScale(w, h)
            }
        }
        vncClient.onDesktopSizeChanged = { w, h ->
            post {
                synchronized(bitmapLock) {
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                }
                recalcScale(w, h)
            }
        }
        vncClient.onFramebufferUpdate = { x, y, w, h, pixels ->
            synchronized(bitmapLock) {
                val bm = bitmap
                if (bm != null && x >= 0 && y >= 0 && x + w <= bm.width && y + h <= bm.height) {
                    bm.setPixels(pixels, 0, w, x, y, w, h)
                }
            }
            postInvalidate()
        }
    }

    private fun recalcScale(vncW: Int, vncH: Int) {
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        scaleX = vw / vncW; scaleY = vh / vncH
        val s = minOf(scaleX, scaleY)
        scaleX = s; scaleY = s
        panX = (vw - vncW * s) / 2f
        panY = (vh - vncH * s) / 2f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val bm = bitmap ?: return
        recalcScale(bm.width, bm.height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bm = synchronized(bitmapLock) { bitmap } ?: return
        val dst = RectF(panX, panY, panX + bm.width * scaleX, panY + bm.height * scaleY)
        canvas.drawBitmap(bm, null, dst, paint)
        if (rippleAlpha > 0) {
            ripplePaint.alpha = (rippleAlpha * 160).roundToInt()
            canvas.drawCircle(rippleX, rippleY, 60f * rippleAlpha, ripplePaint)
        }
    }

    // touch → VNC coordinates
    private fun viewToVnc(vx: Float, vy: Float): Pair<Int, Int> {
        val nx = ((vx - panX) / scaleX).roundToInt().coerceIn(0, (client?.fbWidth ?: 1) - 1)
        val ny = ((vy - panY) / scaleY).roundToInt().coerceIn(0, (client?.fbHeight ?: 1) - 1)
        return nx to ny
    }

    private var prevButtons = 0
    private var twoFingerStart = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)

        val numPointers = e.pointerCount
        val action = e.actionMasked

        // Block mouse events if we are zooming or panning with 2+ fingers
        if (scaleDetector.isInProgress || numPointers >= 2) {
            return true
        }

        val (vx, vy) = viewToVnc(e.x, e.y)
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                client?.sendPointerEvent(vx, vy, prevButtons)
            }
            MotionEvent.ACTION_DOWN -> {
                prevButtons = 0
                client?.sendPointerEvent(vx, vy, 0)
            }
            MotionEvent.ACTION_UP -> {
                client?.sendPointerEvent(vx, vy, 0)
                prevButtons = 0
            }
        }
        return true
    }

    private fun showRipple(x: Float, y: Float) {
        rippleX = x; rippleY = y
        rippleAnimator.cancel(); rippleAnimator.start()
    }
}
