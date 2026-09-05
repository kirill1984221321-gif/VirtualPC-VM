package com.virtualpcvm

import android.util.Log
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "VncClient"

/**
 * Robust RFB 3.8 client implementation for QEMU VNC server.
 * Supports:
 * - Raw encoding (0)
 * - DesktopSize pseudo-encoding (-223)
 * - Cursor pseudo-encoding (-239)
 * - LastRect pseudo-encoding (-224)
 * - Multi-listener event bus for simultaneous View and Activity observers.
 */
class VncClient(
    val host: String,
    val port: Int,
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    interface Listener {
        fun onConnected(width: Int, height: Int, name: String) {}
        fun onDisconnected(reason: String) {}
        fun onFramebufferUpdate(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) {}
        fun onDesktopSizeChanged(width: Int, height: Int) {}
        fun onConnectingProgress(attempt: Int, maxAttempts: Int) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // Single lambda callbacks for convenience (internally registered)
    var onConnected: ((width: Int, height: Int, name: String) -> Unit)? = null
    var onDisconnected: ((reason: String) -> Unit)? = null
    var onFramebufferUpdate: ((x: Int, y: Int, w: Int, h: Int, pixels: IntArray) -> Unit)? = null
    var onDesktopSizeChanged: ((width: Int, height: Int) -> Unit)? = null
    var onConnectingProgress: ((attempt: Int, maxAttempts: Int) -> Unit)? = null
    var isProcessAliveCheck: (() -> Boolean)? = null

    var fbWidth = 0
    var fbHeight = 0
    private var framebuffer: IntArray = IntArray(0)
    private val fbLock = Any()

    @Volatile
    private var running = false
    private var job: Job? = null

    val isConnected: Boolean
        get() = running && socket?.isConnected == true && socket?.isClosed == false

    fun connect(scope: CoroutineScope, maxRetries: Int = 15) {
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            var connected = false

            while (attempt < maxRetries && !connected && isActive) {
                if (isProcessAliveCheck?.invoke() == false) {
                    Log.w(TAG, "Process is not running, stopping VNC retries")
                    val msg = "DIAGNOSTICS_FAILED:Процесс виртуальной машины QEMU не активен.\n\nПроверьте журнал логов для деталей."
                    notifyDisconnected(msg)
                    return@launch
                }

                attempt++
                notifyProgress(attempt, maxRetries)
                Log.d(TAG, "Connecting to $host:$port (attempt $attempt/$maxRetries)...")

                try {
                    val s = Socket()
                    socket = s
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(host, port), 2000)
                    s.soTimeout = 4000 // Handshake timeout

                    input = DataInputStream(s.getInputStream())
                    output = DataOutputStream(s.getOutputStream())

                    handshake()
                    s.soTimeout = 0 // Stream loop timeout disabled
                    running = true
                    connected = true
                    Log.i(TAG, "VNC connected successfully on attempt $attempt")
                } catch (e: java.net.ConnectException) {
                    Log.w(TAG, "Attempt $attempt failed: connection refused to $host:$port")
                    closeSocketSilently()
                    if (attempt < maxRetries && isActive) {
                        delay(1000)
                    } else {
                        val msg = "DIAGNOSTICS_FAILED:Не удалось подключиться к порту $host:$port после $maxRetries попыток.\n\n" +
                                "Возможные причины:\n" +
                                "• QEMU ещё инициализирует устройства\n" +
                                "• VNC-сервер не открыт на порту $port\n" +
                                "• Процесс завершился с ошибкой"
                        notifyDisconnected(msg)
                        return@launch
                    }
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "Attempt $attempt timed out")
                    closeSocketSilently()
                    if (attempt < maxRetries && isActive) {
                        delay(800)
                    } else {
                        val msg = "DIAGNOSTICS_FAILED:Превышено время ожидания ответа от VNC-сервера ($host:$port)."
                        notifyDisconnected(msg)
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connection handshake error: ${e.message}", e)
                    closeSocketSilently()
                    notifyDisconnected("Ошибка рукопожатия VNC: ${e.message}")
                    return@launch
                }
            }

            if (connected && running && isActive) {
                try {
                    mainLoop()
                } catch (e: EOFException) {
                    Log.i(TAG, "VNC server closed connection (EOF)")
                    if (running) notifyDisconnected("Сервер QEMU закрыл соединение")
                } catch (e: Exception) {
                    Log.i(TAG, "VNC loop stopped: ${e.message}")
                    if (running) notifyDisconnected(e.message ?: "Соединение разорвано")
                } finally {
                    disconnect()
                }
            }
        }
    }

    private fun handshake() {
        val din = input ?: throw IOException("Input stream is null")
        val dout = output ?: throw IOException("Output stream is null")

        // 1. ProtocolVersion
        val verBuf = ByteArray(12)
        din.readFully(verBuf)
        val serverVer = String(verBuf).trim()
        Log.d(TAG, "Server version: $serverVer")
        dout.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        dout.flush()

        // 2. Security types
        val numTypes = din.readUnsignedByte()
        if (numTypes == 0) {
            val len = din.readInt().coerceIn(0, 4096)
            val msg = ByteArray(len)
            din.readFully(msg)
            throw IOException("Server refused connection: ${String(msg)}")
        }
        val types = ByteArray(numTypes)
        din.readFully(types)

        // Choose None (1) if available, or first offered
        val chosen = if (types.contains(1.toByte())) 1 else (types[0].toInt() and 0xFF)
        dout.writeByte(chosen)
        dout.flush()

        if (chosen == 2) { // VncAuth (empty password support)
            val challenge = ByteArray(16)
            din.readFully(challenge)
            dout.write(ByteArray(16))
            dout.flush()
        }

        // 3. SecurityResult: In RFB 3.8, if chosen == 1 or 2, server sends 4-byte result (0 = OK)
        val result = din.readInt()
        if (result != 0) {
            var errStr = "Код ошибки $result"
            try {
                val len = din.readInt().coerceIn(0, 4096)
                val msg = ByteArray(len)
                din.readFully(msg)
                errStr = String(msg)
            } catch (_: Exception) {}
            throw IOException("Auth failed: $errStr")
        }

        // 4. ClientInit (shared = 1)
        dout.writeByte(1)
        dout.flush()

        // 5. ServerInit
        fbWidth = din.readUnsignedShort()
        fbHeight = din.readUnsignedShort()
        if (fbWidth <= 0 || fbHeight <= 0) {
            throw IOException("Invalid resolution from VNC server: ${fbWidth}x${fbHeight}")
        }
        synchronized(fbLock) {
            framebuffer = IntArray(fbWidth * fbHeight)
        }

        // Pixel format (16 bytes)
        val pf = ByteArray(16)
        din.readFully(pf)

        // Name
        val nameLen = din.readInt().coerceIn(0, 1024)
        val nameBuf = ByteArray(nameLen)
        din.readFully(nameBuf)
        val name = String(nameBuf)
        Log.i(TAG, "Connected: ${fbWidth}x${fbHeight} '$name'")

        // 6. Set pixel format: 32-bpp RGBX little-endian
        sendSetPixelFormat()

        // 7. Set encodings: Raw (0), DesktopSize (-223), Cursor (-239), LastRect (-224)
        sendSetEncodings(intArrayOf(0, -223, -239, -224))

        // Notify connected observers
        notifyConnected(fbWidth, fbHeight, name)

        // 8. Request initial full framebuffer (non-incremental)
        sendFbUpdateRequest(0, 0, fbWidth, fbHeight, false)
    }

    private fun mainLoop() {
        val din = input ?: return
        while (running) {
            val msgType = din.readUnsignedByte()
            when (msgType) {
                0 -> handleFramebufferUpdate()
                2 -> handleBell()
                3 -> handleServerCutText()
                else -> {
                    Log.d(TAG, "Ignored server message type: $msgType")
                }
            }
        }
    }

    private fun handleFramebufferUpdate() {
        val din = input ?: return
        din.readUnsignedByte() // padding
        val numRects = din.readUnsignedShort()
        for (i in 0 until numRects) {
            val x = din.readUnsignedShort()
            val y = din.readUnsignedShort()
            val w = din.readUnsignedShort()
            val h = din.readUnsignedShort()
            val encoding = din.readInt()
            when (encoding) {
                0 -> decodeRaw(x, y, w, h)
                -223 -> handleDesktopSize(w, h)
                -239 -> skipCursor(w, h)
                -224 -> break // LastRect pseudo-encoding
                else -> {
                    if (encoding >= 0) {
                        skipUnknownEncoding(w, h)
                    }
                }
            }
        }
        if (running) {
            sendFbUpdateRequest(0, 0, fbWidth, fbHeight, true)
        }
    }

    private fun handleDesktopSize(newW: Int, newH: Int) {
        if (newW > 0 && newH > 0) {
            Log.i(TAG, "Desktop size changed to ${newW}x${newH}")
            fbWidth = newW
            fbHeight = newH
            synchronized(fbLock) {
                framebuffer = IntArray(newW * newH)
            }
            notifyDesktopSizeChanged(newW, newH)
        }
    }

    private fun skipCursor(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val pixelBytes = w * h * 4
        val maskBytes = ((w + 7) / 8) * h
        skipFully(pixelBytes + maskBytes)
    }

    private fun decodeRaw(x: Int, y: Int, w: Int, h: Int) {
        val din = input ?: return
        if (w <= 0 || h <= 0) return

        val pixels = IntArray(w * h)
        val buf = ByteArray(w * h * 4)
        din.readFully(buf)

        for (i in pixels.indices) {
            val b = buf[i * 4 + 0].toInt() and 0xFF
            val g = buf[i * 4 + 1].toInt() and 0xFF
            val r = buf[i * 4 + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        synchronized(fbLock) {
            for (row in 0 until h) {
                val srcOff = row * w
                val dstOff = (y + row) * fbWidth + x
                if (dstOff >= 0 && dstOff + w <= framebuffer.size) {
                    System.arraycopy(pixels, srcOff, framebuffer, dstOff, w)
                }
            }
        }
        notifyFramebufferUpdate(x, y, w, h, pixels)
    }

    private fun skipUnknownEncoding(w: Int, h: Int) {
        val toSkip = (w * h * 4)
        if (toSkip > 0) {
            skipFully(toSkip)
        }
    }

    private fun skipFully(totalBytes: Int) {
        val din = input ?: return
        var remaining = totalBytes
        val buffer = ByteArray(minOf(remaining, 4096))
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size)
            val read = din.read(buffer, 0, toRead)
            if (read < 0) throw EOFException("End of stream while skipping bytes")
            remaining -= read
        }
    }

    private fun handleBell() {}

    private fun handleServerCutText() {
        val din = input ?: return
        val buf = ByteArray(3)
        din.readFully(buf)
        val len = din.readInt().coerceIn(0, 65536)
        if (len > 0) {
            val text = ByteArray(len)
            din.readFully(text)
        }
    }

    fun sendPointerEvent(x: Int, y: Int, buttons: Int) {
        val dout = output ?: return
        synchronized(dout) {
            try {
                dout.writeByte(5)
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
                dout.writeShort(0)
                dout.writeInt(keySym.toInt())
                dout.flush()
            } catch (_: Exception) {}
        }
    }

    private fun sendSetPixelFormat() {
        val dout = output ?: return
        dout.writeByte(0)
        dout.write(ByteArray(3))
        dout.writeByte(32) // 32 bpp
        dout.writeByte(24) // 24 depth
        dout.writeByte(0)  // little-endian
        dout.writeByte(1)  // true color
        dout.writeShort(255)
        dout.writeShort(255)
        dout.writeShort(255)
        dout.writeByte(16) // red shift
        dout.writeByte(8)  // green shift
        dout.writeByte(0)  // blue shift
        dout.write(ByteArray(3))
        dout.flush()
    }

    private fun sendSetEncodings(encodings: IntArray) {
        val dout = output ?: return
        dout.writeByte(2)
        dout.writeByte(0)
        dout.writeShort(encodings.size)
        encodings.forEach { dout.writeInt(it) }
        dout.flush()
    }

    fun sendFbUpdateRequest(x: Int, y: Int, w: Int, h: Int, incremental: Boolean) {
        val dout = output ?: return
        synchronized(dout) {
            try {
                dout.writeByte(3)
                dout.writeByte(if (incremental) 1 else 0)
                dout.writeShort(x)
                dout.writeShort(y)
                dout.writeShort(w)
                dout.writeShort(h)
                dout.flush()
            } catch (_: Exception) {}
        }
    }

    fun getFramebuffer(): IntArray = synchronized(fbLock) { framebuffer.clone() }

    private fun closeSocketSilently() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    fun disconnect() {
        running = false
        job?.cancel()
        closeSocketSilently()
    }

    private fun notifyConnected(w: Int, h: Int, name: String) {
        onConnected?.invoke(w, h, name)
        for (l in listeners) l.onConnected(w, h, name)
    }

    private fun notifyDisconnected(reason: String) {
        onDisconnected?.invoke(reason)
        for (l in listeners) l.onDisconnected(reason)
    }

    private fun notifyFramebufferUpdate(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) {
        onFramebufferUpdate?.invoke(x, y, w, h, pixels)
        for (l in listeners) l.onFramebufferUpdate(x, y, w, h, pixels)
    }

    private fun notifyDesktopSizeChanged(w: Int, h: Int) {
        onDesktopSizeChanged?.invoke(w, h)
        for (l in listeners) l.onDesktopSizeChanged(w, h)
    }

    private fun notifyProgress(attempt: Int, maxAttempts: Int) {
        onConnectingProgress?.invoke(attempt, maxAttempts)
        for (l in listeners) l.onConnectingProgress(attempt, maxAttempts)
    }
}
