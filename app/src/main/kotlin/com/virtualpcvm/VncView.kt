package com.virtualpcvm

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.roundToInt

/**
 * High-performance VNC Canvas view supporting:
 * - Direct Touch mode & Laptop Trackpad mode with visible virtual cursor
 * - Pinch to zoom and 2-finger panning
 * - Mouse wheel scrolling (2-finger swipe)
 * - Soft keyboard and hardware keyboard input forwarding
 * - Programmatic Zoom In / Zoom Out / Fit Screen controls
 */
class VncView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : View(ctx, attrs), VncClient.Listener {

    private var client: VncClient? = null
    private var bitmap: Bitmap? = null
    private val bitmapLock = Any()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Transformation / Viewport
    private var scaleX = 1f
    private var scaleY = 1f
    private var panX = 0f
    private var panY = 0f

    // Mouse / Touch mode
    var isTouchMode = true
        set(value) {
            field = value
            invalidate()
        }

    var isRelativeMouseMode: Boolean
        get() = !isTouchMode
        set(value) {
            isTouchMode = !value
        }

    fun setMouseMode(relative: Boolean) {
        isTouchMode = !relative
    }

    // Virtual mouse pointer position (in VNC coordinates)
    private var cursorVncX = 0f
    private var cursorVncY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDraggingWithLeftClick = false

    // Visual pointer path
    private val cursorPath = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 22f)
        lineTo(6f, 17f)
        lineTo(11f, 26f)
        lineTo(14f, 24f)
        lineTo(9f, 15f)
        lineTo(16f, 15f)
        close()
    }
    private val cursorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val cursorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    // Long-press ripple animation
    private var rippleX = 0f
    private var rippleY = 0f
    private var rippleAlpha = 0f
    private val ripplePaint = Paint().apply {
        color = 0xAAFF4444.toInt()
        style = Paint.Style.FILL
    }
    private val rippleAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 350
        addUpdateListener {
            rippleAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    // Gesture detector
    private val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (isTouchMode) {
                val (vx, vy) = viewToVnc(e.x, e.y)
                client?.sendPointerEvent(vx, vy, 0x01) // Left click down
                postDelayed({ client?.sendPointerEvent(vx, vy, 0x00) }, 50)
            } else {
                val cx = cursorVncX.roundToInt()
                val cy = cursorVncY.roundToInt()
                client?.sendPointerEvent(cx, cy, 0x01)
                postDelayed({ client?.sendPointerEvent(cx, cy, 0x00) }, 50)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // In trackpad mode, double tap initiates a drag-with-click
            if (!isTouchMode) {
                isDraggingWithLeftClick = true
                val cx = cursorVncX.roundToInt()
                val cy = cursorVncY.roundToInt()
                client?.sendPointerEvent(cx, cy, 0x01)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showRipple(e.x, e.y)
            if (isTouchMode) {
                val (vx, vy) = viewToVnc(e.x, e.y)
                client?.sendPointerEvent(vx, vy, 0x04) // Right click down
                postDelayed({ client?.sendPointerEvent(vx, vy, 0x00) }, 60)
            } else {
                val cx = cursorVncX.roundToInt()
                val cy = cursorVncY.roundToInt()
                client?.sendPointerEvent(cx, cy, 0x04)
                postDelayed({ client?.sendPointerEvent(cx, cy, 0x00) }, 60)
            }
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e2.pointerCount >= 2) {
                // Two-finger scroll: in trackpad mode, forward vertical scroll as mouse wheel
                if (!isTouchMode && Math.abs(distanceY) > Math.abs(distanceX) * 1.5f) {
                    val cx = cursorVncX.roundToInt()
                    val cy = cursorVncY.roundToInt()
                    val wheelButton = if (distanceY > 0) 0x10 else 0x08 // 0x08 = Wheel Up, 0x10 = Wheel Down
                    client?.sendPointerEvent(cx, cy, wheelButton)
                    postDelayed({ client?.sendPointerEvent(cx, cy, 0) }, 40)
                    return true
                }

                // Otherwise pan the viewport
                panX -= distanceX
                panY -= distanceY
                clampPan()
                invalidate()
                return true
            }
            return false
        }
    })

    // Pinch-to-zoom detector
    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val focusX = detector.focusX
            val focusY = detector.focusY

            val newScale = (scaleX * scaleFactor).coerceIn(0.2f, 6.0f)
            val actualFactor = newScale / scaleX

            scaleX = newScale
            scaleY = newScale

            panX = focusX + (panX - focusX) * actualFactor
            panY = focusY + (panY - focusY) * actualFactor

            clampPan()
            invalidate()
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun attach(vncClient: VncClient) {
        client?.removeListener(this)
        client = vncClient
        vncClient.addListener(this)

        // If client is already connected, initialize bitmap immediately
        if (vncClient.isConnected && vncClient.fbWidth > 0 && vncClient.fbHeight > 0) {
            onConnected(vncClient.fbWidth, vncClient.fbHeight, "QEMU")
        }
    }

    override fun onConnected(width: Int, height: Int, name: String) {
        post {
            synchronized(bitmapLock) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            cursorVncX = width / 2f
            cursorVncY = height / 2f
            recalcScale(width, height)
        }
    }

    override fun onDesktopSizeChanged(width: Int, height: Int) {
        post {
            synchronized(bitmapLock) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            cursorVncX = cursorVncX.coerceIn(0f, (width - 1).toFloat())
            cursorVncY = cursorVncY.coerceIn(0f, (height - 1).toFloat())
            recalcScale(width, height)
        }
    }

    override fun onFramebufferUpdate(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) {
        synchronized(bitmapLock) {
            val bm = bitmap
            if (bm != null && x >= 0 && y >= 0 && x + w <= bm.width && y + h <= bm.height) {
                bm.setPixels(pixels, 0, w, x, y, w, h)
            }
        }
        postInvalidate()
    }

    fun zoomIn() {
        val cx = width / 2f
        val cy = height / 2f
        scaleX = (scaleX * 1.25f).coerceAtMost(6.0f)
        scaleY = scaleX
        panX = cx + (panX - cx) * 1.25f
        panY = cy + (panY - cy) * 1.25f
        clampPan()
        invalidate()
    }

    fun zoomOut() {
        val cx = width / 2f
        val cy = height / 2f
        scaleX = (scaleX * 0.8f).coerceAtLeast(0.2f)
        scaleY = scaleX
        panX = cx + (panX - cx) * 0.8f
        panY = cy + (panY - cy) * 0.8f
        clampPan()
        invalidate()
    }

    fun fitToScreen() {
        val bm = synchronized(bitmapLock) { bitmap }
        if (bm != null) {
            recalcScale(bm.width, bm.height)
        }
    }

    fun resetToActualSize() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val bm = synchronized(bitmapLock) { bitmap } ?: return
        scaleX = 1f
        scaleY = 1f
        panX = (vw - bm.width) / 2f
        panY = (vh - bm.height) / 2f
        invalidate()
    }

    private fun recalcScale(vncW: Int, vncH: Int) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0 || vncW <= 0 || vncH <= 0) return

        val s = minOf(vw / vncW, vh / vncH)
        scaleX = s
        scaleY = s
        panX = (vw - vncW * s) / 2f
        panY = (vh - vncH * s) / 2f
        invalidate()
    }

    private fun clampPan() {
        // Allow user to pan freely within reasonable canvas boundaries
        val bm = synchronized(bitmapLock) { bitmap } ?: return
        val contentW = bm.width * scaleX
        val contentH = bm.height * scaleY
        val vw = width.toFloat()
        val vh = height.toFloat()

        if (contentW <= vw) {
            panX = (vw - contentW) / 2f
        } else {
            panX = panX.coerceIn(vw - contentW - 200f, 200f)
        }

        if (contentH <= vh) {
            panY = (vh - contentH) / 2f
        } else {
            panY = panY.coerceIn(vh - contentH - 200f, 200f)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val bm = synchronized(bitmapLock) { bitmap } ?: return
        recalcScale(bm.width, bm.height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        val bm = synchronized(bitmapLock) { bitmap } ?: return
        val dst = RectF(panX, panY, panX + bm.width * scaleX, panY + bm.height * scaleY)
        canvas.drawBitmap(bm, null, dst, paint)

        // Draw long-press ripple if active
        if (rippleAlpha > 0) {
            ripplePaint.alpha = (rippleAlpha * 160).roundToInt()
            canvas.drawCircle(rippleX, rippleY, 50f * rippleAlpha, ripplePaint)
        }

        // In Trackpad / Mouse mode, draw virtual mouse pointer
        if (!isTouchMode) {
            val cursorScreenX = panX + cursorVncX * scaleX
            val cursorScreenY = panY + cursorVncY * scaleY

            canvas.save()
            canvas.translate(cursorScreenX, cursorScreenY)
            canvas.drawPath(cursorPath, cursorFillPaint)
            canvas.drawPath(cursorPath, cursorStrokePaint)
            canvas.restore()
        }
    }

    private fun viewToVnc(vx: Float, vy: Float): Pair<Int, Int> {
        val bw = synchronized(bitmapLock) { bitmap?.width } ?: (client?.fbWidth ?: 1)
        val bh = synchronized(bitmapLock) { bitmap?.height } ?: (client?.fbHeight ?: 1)
        val nx = ((vx - panX) / scaleX).roundToInt().coerceIn(0, bw - 1)
        val ny = ((vy - panY) / scaleY).roundToInt().coerceIn(0, bh - 1)
        return nx to ny
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)

        val numPointers = e.pointerCount
        val action = e.actionMasked

        // Block mouse pointer transmission if user is actively pinching to zoom
        if (scaleDetector.isInProgress) {
            return true
        }

        // Two-finger tap detection (Middle Click)
        if (action == MotionEvent.ACTION_POINTER_DOWN && numPointers == 2) {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            val cx = if (isTouchMode) viewToVnc(e.getX(0), e.getY(0)).first else cursorVncX.roundToInt()
            val cy = if (isTouchMode) viewToVnc(e.getX(0), e.getY(0)).second else cursorVncY.roundToInt()
            client?.sendPointerEvent(cx, cy, 0x02) // Middle click down
            postDelayed({ client?.sendPointerEvent(cx, cy, 0) }, 50)
            return true
        }

        if (numPointers >= 2) {
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = e.x
                lastTouchY = e.y
                if (isTouchMode) {
                    val (vx, vy) = viewToVnc(e.x, e.y)
                    client?.sendPointerEvent(vx, vy, 0)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTouchMode) {
                    val (vx, vy) = viewToVnc(e.x, e.y)
                    client?.sendPointerEvent(vx, vy, if (isDraggingWithLeftClick) 0x01 else 0x00)
                } else {
                    // Trackpad relative movement
                    val dx = (e.x - lastTouchX) / scaleX
                    val dy = (e.y - lastTouchY) / scaleY
                    val bw = synchronized(bitmapLock) { bitmap?.width } ?: (client?.fbWidth ?: 800)
                    val bh = synchronized(bitmapLock) { bitmap?.height } ?: (client?.fbHeight ?: 600)

                    cursorVncX = (cursorVncX + dx).coerceIn(0f, (bw - 1).toFloat())
                    cursorVncY = (cursorVncY + dy).coerceIn(0f, (bh - 1).toFloat())

                    lastTouchX = e.x
                    lastTouchY = e.y

                    val cx = cursorVncX.roundToInt()
                    val cy = cursorVncY.roundToInt()
                    client?.sendPointerEvent(cx, cy, if (isDraggingWithLeftClick) 0x01 else 0x00)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cx = if (isTouchMode) viewToVnc(e.x, e.y).first else cursorVncX.roundToInt()
                val cy = if (isTouchMode) viewToVnc(e.x, e.y).second else cursorVncY.roundToInt()
                client?.sendPointerEvent(cx, cy, 0)
                isDraggingWithLeftClick = false
            }
        }
        return true
    }

    private fun showRipple(x: Float, y: Float) {
        rippleX = x
        rippleY = y
        rippleAnimator.cancel()
        rippleAnimator.start()
    }

    // Keyboard support
    fun toggleSoftKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        return object : android.view.inputmethod.BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.forEach { ch ->
                    val keySym = charToKeySym(ch)
                    client?.sendKeyEvent(keySym, true)
                    client?.sendKeyEvent(keySym, false)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    client?.sendKeyEvent(0xFF08, true) // Backspace
                    client?.sendKeyEvent(0xFF08, false)
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                return dispatchKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val keySym = keyCodeToKeySym(keyCode, event)
        if (keySym != 0L) {
            client?.sendKeyEvent(keySym, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val keySym = keyCodeToKeySym(keyCode, event)
        if (keySym != 0L) {
            client?.sendKeyEvent(keySym, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun charToKeySym(ch: Char): Long {
        return when (ch) {
            '\n', '\r' -> 0xFF0D // Return
            '\t' -> 0xFF09 // Tab
            '\b' -> 0xFF08 // Backspace
            else -> ch.code.toLong()
        }
    }

    private fun keyCodeToKeySym(keyCode: Int, event: KeyEvent): Long {
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> 0xFF08
            KeyEvent.KEYCODE_TAB -> 0xFF09
            KeyEvent.KEYCODE_ENTER -> 0xFF0D
            KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
            KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
            KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
            KeyEvent.KEYCODE_PAGE_UP -> 0xFF55
            KeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56
            KeyEvent.KEYCODE_MOVE_HOME -> 0xFF50
            KeyEvent.KEYCODE_MOVE_END -> 0xFF57
            KeyEvent.KEYCODE_INSERT -> 0xFF63
            KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF
            KeyEvent.KEYCODE_CTRL_LEFT -> 0xFFE3
            KeyEvent.KEYCODE_CTRL_RIGHT -> 0xFFE4
            KeyEvent.KEYCODE_ALT_LEFT -> 0xFFE9
            KeyEvent.KEYCODE_ALT_RIGHT -> 0xFFEA
            KeyEvent.KEYCODE_SHIFT_LEFT -> 0xFFE1
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xFFE2
            else -> {
                val unicode = event.getUnicodeChar(event.metaState)
                if (unicode != 0) unicode.toLong() else 0L
            }
        }
    }
}
