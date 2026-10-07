package com.example.popcorn

import android_serialport_api.SerialPort
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Modbus CRC-16, sent low byte first (verified against the machine's own log). */
fun crc16(b: ByteArray, len: Int = b.size): Int {
    var c = 0xFFFF
    for (i in 0 until len) {
        c = c xor (b[i].toInt() and 0xFF)
        for (k in 0 until 8) c = if ((c and 1) != 0) (c ushr 1) xor 0xA001 else (c ushr 1)
    }
    return c
}

fun frame(vararg v: Int): ByteArray {
    val b = ByteArray(v.size + 2)
    for (i in v.indices) b[i] = v[i].toByte()
    val c = crc16(b, v.size)
    b[v.size] = (c and 0xFF).toByte()
    b[v.size + 1] = ((c shr 8) and 0xFF).toByte()
    return b
}

interface Link {
    fun open()
    fun close()
    val input: InputStream
    val output: OutputStream
}

class SerialLink(private val path: String, private val baud: Int) : Link {
    private var port: SerialPort? = null
    override fun open() { port = SerialPort(File(path), baud, 0) }
    override fun close() { port?.close(); port = null }
    override val input: InputStream get() = port!!.inputStream
    override val output: OutputStream get() = port!!.outputStream
}

/** For testing against fake_controller.py. From the Android emulator the PC is 10.0.2.2. */
class TcpLink(private val host: String, private val port: Int) : Link {
    private var s: Socket? = null
    override fun open() { val k = Socket(); k.connect(InetSocketAddress(host, port), 1500); s = k }
    override fun close() { s?.close(); s = null }
    override val input: InputStream get() = s!!.getInputStream()
    override val output: OutputStream get() = s!!.getOutputStream()
}

class Controller(private val link: Link) {
    @Synchronized
    private fun txrx(req: ByteArray, expect: Int, timeoutMs: Long = 800): ByteArray? {
        val inp = link.input
        while (inp.available() > 0) inp.read(ByteArray(inp.available()))   // drop stale bytes
        link.output.write(req); link.output.flush()
        val buf = ByteArray(expect)
        var n = 0
        val end = System.currentTimeMillis() + timeoutMs
        while (n < expect && System.currentTimeMillis() < end) {
            if (inp.available() > 0) n += inp.read(buf, n, expect - n) else Thread.sleep(10)
        }
        if (n < expect) return null
        val c = crc16(buf, expect - 2)
        val ok = (buf[expect - 2].toInt() and 0xFF) == (c and 0xFF) && (buf[expect - 1].toInt() and 0xFF) == ((c shr 8) and 0xFF)
        return if (ok) buf else null
    }

    /** Registers 0x00..0x1C, the same poll the vendor app makes every ~1.3 s. */
    fun readAll(): IntArray? {
        val r = txrx(frame(0, 3, 0, 0, 0, 29), 5 + 58) ?: return null
        if (r[1].toInt() != 3) return null
        return IntArray(29) { ((r[3 + it * 2].toInt() and 0xFF) shl 8) or (r[4 + it * 2].toInt() and 0xFF) }
    }

    /** Write a single coil. The controller echoes the request when it accepts it. */
    fun coil(addr: Int, on: Boolean): Boolean {
        val req = frame(0, 5, 0, addr, if (on) 0xFF else 0, 0)
        val r = txrx(req, 8) ?: return false
        return r.contentEquals(req)
    }

    /** Write one holding register (function 0x10). True if the controller echoes a valid reply. */
    fun writeReg(addr: Int, value: Int): Boolean {
        val req = frame(0, 0x10, 0, addr, 0, 1, 2, (value shr 8) and 0xFF, value and 0xFF)
        val r = txrx(req, 8) ?: return false
        return (r[1].toInt() and 0xFF) == 0x10
    }
}
