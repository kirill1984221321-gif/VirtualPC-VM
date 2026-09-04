package com.virtualpcvm

import android.util.Log
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

private const val TAG = "VncClient"

/** Minimal RFB 3.8 client — enough for display + mouse/keyboard input. */
class VncClient(
    private val host: String,
    private val port: Int,
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    var onFramebufferUpdate: ((x: Int, y: Int, w: Int, h: Int, pixels: IntArray) -> Unit)? = null
    var onConnected: ((width: Int, height: Int, name: String) -> Unit)? = null
    var onDisconnected: ((reason: String) -> Unit)? = null

    var fbWidth = 0; var fbHeight = 0
    private var framebuffer: IntArray = IntArray(0)

    private var running = false
    private var job: Job? = null

    /* ── connect & handshake ── */
    fun connect(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            try {
                socket = Socket()
                socket!!.connect(java.net.InetSocketAddress(host, port), 5000)
                socket!!.soTimeout = 10000 // Only used for initial handshakes/reads before loop
                input  = DataInputStream(socket!!.getInputStream())
                output = DataOutputStream(socket!!.getOutputStream())
                handshake()
                socket!!.soTimeout = 0 // Remove timeout for the main loop, as updates might be infrequent
                running = true
                mainLoop()
            } catch (e: Exception) {
                Log.e(TAG, "VNC error: ${e.message}")
                onDisconnected?.invoke(e.message ?: "Ошибка подключения")
            }
        }
    }

    private fun handshake() {
        val din = input!!; val dout = output!!

        // 1. ProtocolVersion
        val verBuf = ByteArray(12)
        din.readFully(verBuf)
        val serverVer = String(verBuf)
        Log.d(TAG, "Server version: $serverVer")
        dout.write("RFB 003.008\n".toByteArray())
        dout.flush()

        // 2. Security types
        val numTypes = din.readUnsignedByte()
        if (numTypes == 0) {
            val len = din.readInt()
            val msg = ByteArray(len); din.readFully(msg)
            throw IOException("Server refused: ${String(msg)}")
        }
        val types = ByteArray(numTypes); din.readFully(types)
        // Prefer None (1) or VNC auth (2)
        val chosen = if (types.contains(1.toByte())) 1 else types[0].toInt()
        dout.writeByte(chosen); dout.flush()

        if (chosen == 2) { // VNC auth — no password for localhost
            val challenge = ByteArray(16); din.readFully(challenge)
            dout.write(ByteArray(16)); dout.flush() // empty DES response
        }

        // 3. SecurityResult
        val result = din.readInt()
        if (result != 0) {
            val len = din.readInt()
            val msg = ByteArray(len); din.readFully(msg)
            throw IOException("Auth failed: ${String(msg)}")
        }

        // 4. ClientInit (shared)
        dout.writeByte(1); dout.flush()

        // 5. ServerInit
        fbWidth  = din.readUnsignedShort()
        fbHeight = din.readUnsignedShort()
        framebuffer = IntArray(fbWidth * fbHeight)

        // pixel format (16 bytes)
        val pf = ByteArray(16); din.readFully(pf)

        // name
        val nameLen = din.readInt()
        val nameBuf = ByteArray(nameLen); din.readFully(nameBuf)
        val name = String(nameBuf)
        Log.i(TAG, "Connected: ${fbWidth}x${fbHeight} '$name'")

        onConnected?.invoke(fbWidth, fbHeight, name)

        // 6. Set pixel format → 32-bpp RGBX little-endian
        sendSetPixelFormat()

        // 7. Set encodings → Raw only for simplicity
        sendSetEncodings(intArrayOf(0)) // 0 = Raw

        // 8. Request first full framebuffer update
        sendFbUpdateRequest(0, 0, fbWidth, fbHeight, false)
    }

    private fun mainLoop() {
        val din = input!!
        while (running) {
            when (din.readUnsignedByte()) {
                0 -> handleFramebufferUpdate()
                2 -> handleBell()
                3 -> handleServerCutText()
                else -> { /* unknown, ignore */ }
            }
        }
    }

    private fun handleFramebufferUpdate() {
        val din = input!!
        din.readUnsignedByte() // padding
        val numRects = din.readUnsignedShort()
        repeat(numRects) {
            val x = din.readUnsignedShort()
            val y = din.readUnsignedShort()
            val w = din.readUnsignedShort()
            val h = din.readUnsignedShort()
            val encoding = din.readInt()
            when (encoding) {
                0 -> decodeRaw(x, y, w, h)
                else -> skipUnknownEncoding(w, h)
            }
        }
        // Request next incremental update
        if (running) sendFbUpdateRequest(0, 0, fbWidth, fbHeight, true)
    }

    private fun decodeRaw(x: Int, y: Int, w: Int, h: Int) {
        val din = input!!
        val pixels = IntArray(w * h)
        val buf = ByteArray(w * h * 4)
        din.readFully(buf)
        for (i in pixels.indices) {
            val r = buf[i * 4 + 2].toInt() and 0xFF
            val g = buf[i * 4 + 1].toInt() and 0xFF
            val b = buf[i * 4 + 0].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        // blit into main framebuffer
        for (row in 0 until h) {
            val srcOff = row * w
            val dstOff = (y + row) * fbWidth + x
            System.arraycopy(pixels, srcOff, framebuffer, dstOff, w)
        }
        onFramebufferUpdate?.invoke(x, y, w, h, pixels)
    }

    private fun skipUnknownEncoding(w: Int, h: Int) {
        val toSkip = (w * h * 4).toLong()
        input!!.skipBytes(toSkip.toInt())
    }

    private fun handleBell() { /* ignore */ }

    private fun handleServerCutText() {
        val din = input!!
        val buf = ByteArray(3); din.readFully(buf) // padding
        val len = din.readInt()
        val text = ByteArray(len); din.readFully(text)
    }

    /* ── send helpers ── */

    fun sendPointerEvent(x: Int, y: Int, buttons: Int) {
        val dout = output ?: return
        synchronized(dout) {
            try {
                dout.writeByte(5)           // PointerEvent
                dout.writeByte(buttons)
                dout.writeShort(x)
                dout.writeShort(y)
                dout.flush()
            } catch (_: Exception) {}
        }
    }

    fun sendKeyEvent(keySym: Long, down: Boolean) {
        val dout = output ?: return
        synchronized(dout) {
            try {
                dout.writeByte(4)
                dout.writeByte(if (down) 1 else 0)
                dout.writeShort(0) // padding
                dout.writeInt(keySym.toInt())
                dout.flush()
            } catch (_: Exception) {}
        }
    }

    private fun sendSetPixelFormat() {
        val dout = output!!
        dout.writeByte(0)                // SetPixelFormat
        dout.write(ByteArray(3))         // padding
        // pixel format: 32bpp, 24 depth, little-endian, true-colour
        dout.writeByte(32)               // bits-per-pixel
        dout.writeByte(24)               // depth
        dout.writeByte(0)                // big-endian-flag (little-endian)
        dout.writeByte(1)                // true-colour-flag
        dout.writeShort(255)             // red-max
        dout.writeShort(255)             // green-max
        dout.writeShort(255)             // blue-max
        dout.writeByte(16)               // red-shift
        dout.writeByte(8)                // green-shift
        dout.writeByte(0)                // blue-shift
        dout.write(ByteArray(3))         // padding
        dout.flush()
    }

    private fun sendSetEncodings(encodings: IntArray) {
        val dout = output!!
        dout.writeByte(2)
        dout.writeByte(0)
        dout.writeShort(encodings.size)
        encodings.forEach { dout.writeInt(it) }
        dout.flush()
    }

    private fun sendFbUpdateRequest(x: Int, y: Int, w: Int, h: Int, incremental: Boolean) {
        val dout = output ?: return
        synchronized(dout) {
            try {
                dout.writeByte(3)
                dout.writeByte(if (incremental) 1 else 0)
                dout.writeShort(x); dout.writeShort(y)
                dout.writeShort(w); dout.writeShort(h)
                dout.flush()
            } catch (_: Exception) {}
        }
    }

    fun getFramebuffer(): IntArray = framebuffer

    fun disconnect() {
        running = false
        job?.cancel()
        try { socket?.close() } catch (_: Exception) {}
    }
}
