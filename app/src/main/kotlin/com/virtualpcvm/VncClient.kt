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

private const val TAG = "VncClient"

/** RFB 3.8 client implementation for QEMU VNC server. */
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
    var onConnectingProgress: ((attempt: Int, maxAttempts: Int) -> Unit)? = null
    var onDesktopSizeChanged: ((width: Int, height: Int) -> Unit)? = null
    var isProcessAliveCheck: (() -> Boolean)? = null

    var fbWidth = 0
    var fbHeight = 0
    private var framebuffer: IntArray = IntArray(0)

    @Volatile
    private var running = false
    private var job: Job? = null

    val isConnected: Boolean
        get() = running && socket?.isConnected == true && socket?.isClosed == false

    fun connect(scope: CoroutineScope, maxRetries: Int = 10) {
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            var connected = false

            while (attempt < maxRetries && !connected && isActive) {
                if (isProcessAliveCheck?.invoke() == false) {
                    Log.w(TAG, "Process is dead, stopping VNC connection retries")
                    onDisconnected?.invoke(
                        "DIAGNOSTICS_FAILED:Процесс виртуальной машины QEMU не запущен или завершился до установления связи с VNC.\n\n" +
                        "Откройте «Логи» для просмотра вывода консоли QEMU."
                    )
                    return@launch
                }

                attempt++
                onConnectingProgress?.invoke(attempt, maxRetries)
                Log.d(TAG, "Connecting to $host:$port (attempt $attempt/$maxRetries)...")

                try {
                    val s = Socket()
                    socket = s
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(host, port), 2500)
                    s.soTimeout = 10000

                    input = DataInputStream(s.getInputStream())
                    output = DataOutputStream(s.getOutputStream())

                    handshake()
                    s.soTimeout = 0 // Remove timeout for main update loop
                    running = true
                    connected = true
                    Log.i(TAG, "VNC connected successfully on attempt $attempt")
                } catch (e: java.net.ConnectException) {
                    Log.w(TAG, "Attempt $attempt failed: connection refused to $host:$port")
                    try { socket?.close() } catch (_: Exception) {}
                    if (attempt < maxRetries) {
                        delay(1200)
                    } else {
                        onDisconnected?.invoke(
                            "DIAGNOSTICS_FAILED:Не удалось подключиться к порту $host:$port после $maxRetries попыток.\n\n" +
                            "Возможные причины:\n" +
                            "• Процесс QEMU завершился с ошибкой (проверьте журнал логов)\n" +
                            "• VNC-сервер не успел запуститься"
                        )
                        return@launch
                    }
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "Attempt $attempt timed out")
                    try { socket?.close() } catch (_: Exception) {}
                    if (attempt < maxRetries) {
                        delay(1000)
                    } else {
                        onDisconnected?.invoke("DIAGNOSTICS_FAILED:Таймаут соединения с сервером VNC.")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connection handshake error: ${e.message}", e)
                    try { socket?.close() } catch (_: Exception) {}
                    onDisconnected?.invoke("Ошибка рукопожатия VNC: ${e.message}")
                    return@launch
                }
            }

            if (running) {
                try {
                    mainLoop()
                } catch (e: EOFException) {
                    Log.i(TAG, "VNC server closed connection (EOF)")
                    if (running) onDisconnected?.invoke("Сервер QEMU завершил соединение")
                } catch (e: Exception) {
                    Log.i(TAG, "VNC loop stopped: ${e.message}")
                    if (running) onDisconnected?.invoke(e.message ?: "Соединение разорвано")
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
        val serverVer = String(verBuf)
        Log.d(TAG, "Server version: $serverVer")
        dout.write("RFB 003.008\n".toByteArray())
        dout.flush()

        // 2. Security types
        val numTypes = din.readUnsignedByte()
        if (numTypes == 0) {
            val len = din.readInt()
            val msg = ByteArray(len)
            din.readFully(msg)
            throw IOException("Server refused connection: ${String(msg)}")
        }
        val types = ByteArray(numTypes)
        din.readFully(types)
        
        // Choose None (1) if available, or first offered
        val chosen = if (types.contains(1.toByte())) 1 else types[0].toInt()
        dout.writeByte(chosen)
        dout.flush()

        if (chosen == 2) {
            val challenge = ByteArray(16)
            din.readFully(challenge)
            dout.write(ByteArray(16))
            dout.flush()
        }

        // 3. SecurityResult
        val result = din.readInt()
        if (result != 0) {
            val len = din.readInt()
            val msg = ByteArray(len)
            din.readFully(msg)
            throw IOException("Auth failed: ${String(msg)}")
        }

        // 4. ClientInit (shared = 1)
        dout.writeByte(1)
        dout.flush()

        // 5. ServerInit
        fbWidth = din.readUnsignedShort()
        fbHeight = din.readUnsignedShort()
        framebuffer = IntArray(fbWidth * fbHeight)

        // Pixel format (16 bytes)
        val pf = ByteArray(16)
        din.readFully(pf)

        // Name
        val nameLen = din.readInt()
        val nameBuf = ByteArray(nameLen)
        din.readFully(nameBuf)
        val name = String(nameBuf)
        Log.i(TAG, "Connected: ${fbWidth}x${fbHeight} '$name'")

        onConnected?.invoke(fbWidth, fbHeight, name)

        // 6. Set pixel format: 32-bpp RGBX little-endian
        sendSetPixelFormat()

        // 7. Set encodings: Raw (0)
        sendSetEncodings(intArrayOf(0))

        // 8. Request initial full framebuffer
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
                else -> { /* ignore unknown */ }
            }
        }
    }

    private fun handleFramebufferUpdate() {
        val din = input ?: return
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
                -223 -> handleDesktopSize(w, h)
                -239 -> skipCursor(w, h)
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
            framebuffer = IntArray(newW * newH)
            onDesktopSizeChanged?.invoke(newW, newH)
        }
    }

    private fun skipCursor(w: Int, h: Int) {
        val din = input ?: return
        val pixelBytes = w * h * 4
        val maskBytes = ((w + 7) / 8) * h
        din.skipBytes(pixelBytes + maskBytes)
    }

    private fun decodeRaw(x: Int, y: Int, w: Int, h: Int) {
        val din = input ?: return
        val pixels = IntArray(w * h)
        val buf = ByteArray(w * h * 4)
        din.readFully(buf)
        for (i in pixels.indices) {
            val r = buf[i * 4 + 2].toInt() and 0xFF
            val g = buf[i * 4 + 1].toInt() and 0xFF
            val b = buf[i * 4 + 0].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        for (row in 0 until h) {
            val srcOff = row * w
            val dstOff = (y + row) * fbWidth + x
            if (dstOff >= 0 && dstOff + w <= framebuffer.size) {
                System.arraycopy(pixels, srcOff, framebuffer, dstOff, w)
            }
        }
        onFramebufferUpdate?.invoke(x, y, w, h, pixels)
    }

    private fun skipUnknownEncoding(w: Int, h: Int) {
        val toSkip = (w * h * 4).toLong()
        input?.skipBytes(toSkip.toInt())
    }

    private fun handleBell() {}

    private fun handleServerCutText() {
        val din = input ?: return
        val buf = ByteArray(3)
        din.readFully(buf)
        val len = din.readInt()
        val text = ByteArray(len)
        din.readFully(text)
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
        dout.writeByte(32)
        dout.writeByte(24)
        dout.writeByte(0)
        dout.writeByte(1)
        dout.writeShort(255)
        dout.writeShort(255)
        dout.writeShort(255)
        dout.writeByte(16)
        dout.writeByte(8)
        dout.writeByte(0)
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

    private fun sendFbUpdateRequest(x: Int, y: Int, w: Int, h: Int, incremental: Boolean) {
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

    fun getFramebuffer(): IntArray = framebuffer

    fun disconnect() {
        running = false
        job?.cancel()
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }
}
