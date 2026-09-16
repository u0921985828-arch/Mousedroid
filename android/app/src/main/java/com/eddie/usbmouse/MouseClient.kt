package com.eddie.usbmouse

import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Cliente TCP. Los movimientos se acumulan y se envian a ~120 Hz para no
 * saturar el socket; los clicks salen de inmediato por una cola aparte.
 */
class MouseClient(private val onStatus: (String) -> Unit) : Transport {

    private val connected = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private var socket: Socket? = null
    private var out: BufferedOutputStream? = null
    private var worker: Thread? = null

    private val urgent = LinkedBlockingQueue<String>(128)
    private val lock = Object()
    private var accDx = 0f
    private var accDy = 0f
    private var accSx = 0f
    private var accSy = 0f

    override val isConnected: Boolean get() = connected.get()

    private var destino = ""
    override val label: String get() = destino

    /** Seguro de llamar en bucle: ignora la llamada si ya hay conexion o intento en curso. */
    fun connect(host: String, port: Int) {
        if (connected.get()) return
        if (!busy.compareAndSet(false, true)) return
        destino = "$host:$port"
        onStatus("Conectando a $host:$port...")
        Thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true
                socket = s
                out = BufferedOutputStream(s.getOutputStream(), 512)
                connected.set(true)
                busy.set(false)
                onStatus("Conectado - $host:$port")
                pump()
            } catch (e: Exception) {
                connected.set(false)
                onStatus("Esperando al PC...")
                closeQuiet()
            } finally {
                busy.set(false)
            }
        }.also { worker = it; it.isDaemon = true; it.start() }
    }

    private fun pump() {
        var idle = 0
        try {
            while (connected.get()) {
                var wrote = false
                while (true) {
                    val cmd = urgent.poll() ?: break
                    write(cmd); wrote = true
                }
                var dx = 0f; var dy = 0f; var sx = 0f; var sy = 0f
                synchronized(lock) {
                    dx = accDx; dy = accDy; sx = accSx; sy = accSy
                    accDx = 0f; accDy = 0f; accSx = 0f; accSy = 0f
                }
                if (abs(dx) > 0.01f || abs(dy) > 0.01f) { emit('M', dx, dy); wrote = true }
                if (abs(sx) > 0.01f || abs(sy) > 0.01f) { emit('S', sx, sy); wrote = true }
                if (wrote) {
                    out?.flush()
                    idle = 0
                } else {
                    idle++
                    if (idle > 250) { write("P\n"); out?.flush(); idle = 0 }
                }
                Thread.sleep(8)
            }
        } catch (e: Exception) {
            if (connected.get()) onStatus("Conexion perdida")
        } finally {
            connected.set(false)
            closeQuiet()
        }
    }

    // UTF-8 y no ASCII: el texto de K lleva eñes y signos de apertura. Para todo
    // lo demas son los mismos bytes, asi que el servidor antiguo no nota nada.
    private fun write(s: String) {
        out?.write(s.toByteArray(Charsets.UTF_8))
    }

    // Buffer reutilizado: emitir un movimiento no asigna nada, asi no alimentamos
    // al recolector de basura justo mientras el dedo se mueve.
    private val buf = ByteArray(48)

    private fun emit(op: Char, x: Float, y: Float) {
        var n = 0
        buf[n++] = op.code.toByte()
        n = putFixed(n, x)
        buf[n++] = ','.code.toByte()
        n = putFixed(n, y)
        buf[n++] = '\n'.code.toByte()
        out?.write(buf, 0, n)
    }

    /** Escribe el float con dos decimales sin crear String. */
    private fun putFixed(offset: Int, v: Float): Int {
        var n = offset
        var cents = (v * 100f).toInt()
        if (cents < 0) { buf[n++] = '-'.code.toByte(); cents = -cents }
        val whole = cents / 100
        val frac = cents % 100
        n = putInt(n, whole)
        buf[n++] = '.'.code.toByte()
        buf[n++] = ('0'.code + frac / 10).toByte()
        buf[n++] = ('0'.code + frac % 10).toByte()
        return n
    }

    private fun putInt(offset: Int, value: Int): Int {
        var n = offset
        if (value == 0) { buf[n++] = '0'.code.toByte(); return n }
        var digits = 0
        var t = value
        while (t > 0) { digits++; t /= 10 }
        var div = 1
        repeat(digits - 1) { div *= 10 }
        var v = value
        while (div > 0) {
            buf[n++] = ('0'.code + (v / div)).toByte()
            v %= div
            div /= 10
        }
        return n
    }

    override fun move(dx: Float, dy: Float) {
        if (!connected.get()) return
        synchronized(lock) { accDx += dx; accDy += dy }
    }

    override fun scroll(dx: Float, dy: Float) {
        if (!connected.get()) return
        synchronized(lock) { accSx += dx; accSy += dy }
    }

    override fun click(b: Char) { urgent.offer("C$b\n") }

    override fun button(b: Char, down: Boolean) { urgent.offer("${if (down) "D" else "U"}$b\n") }

    // ---------------------------------------------------------------- teclado
    // Todo esto va por la cola urgente: son acciones discretas, no ruta caliente.
    // Un salto de linea partiria el comando en dos, asi que aqui no entra: el
    // Enter viaja como tecla, no como texto.

    override fun text(s: String) {
        if (s.isEmpty()) return
        val clean = s.replace('\n', ' ').replace("\r", "")
        if (clean.isNotEmpty()) urgent.offer("K$clean\n")
    }

    override fun key(name: String) { urgent.offer("E$name\n") }

    override fun keyHold(name: String, down: Boolean) {
        urgent.offer("E${if (down) "+" else "-"}$name\n")
    }

    /** mods: letras de c(ctrl) a(alt) s(shift) w(win). */
    override fun combo(mods: String, name: String) { urgent.offer("H$mods,$name\n") }

    override fun close() {
        if (connected.getAndSet(false)) onStatus("Desconectado")
        closeQuiet()
    }

    private fun closeQuiet() {
        try { out?.flush() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
        urgent.clear()
    }
}
