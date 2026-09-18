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

    /**
     * El codigo de verdad, cuando se ha entrado con un vale. Llega dentro de la
     * sesion ya cifrada: asi el codigo no viaja nunca como argumento de `adb`,
     * que cualquier usuario del PC puede leer en /proc.
     */
    var onCodigo: ((String) -> Unit)? = null

    private val connected = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private var socket: Socket? = null
    private var out: BufferedOutputStream? = null
    /** Las claves de esta conexion. Sin ella no sale ni un byte de protocolo. */
    @Volatile private var sesion: Pairing.Sesion? = null
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

    /**
     * Seguro de llamar en bucle: ignora la llamada si ya hay conexion o intento
     * en curso.
     *
     * [code] es el codigo de emparejamiento del PC. Sin el no se conecta, y no
     * es una molestia gratuita: por este socket viajan pulsaciones de teclado,
     * asi que quien lo abra ejecuta lo que quiera en el otro lado.
     */
    fun connect(host: String, port: Int, code: String) {
        if (connected.get()) return
        if (code.length < Pairing.LARGO) { onStatus("Falta el código del PC"); return }
        if (!busy.compareAndSet(false, true)) return
        destino = "$host:$port"
        onStatus("Conectando a $host:$port...")
        Thread {
            var s: Socket? = null
            try {
                s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true
                // Mientras dura el apreton de manos hay que poder rendirse: un
                // impostor que conteste al sondeo y luego calle nos dejaria
                // colgados para siempre.
                s.soTimeout = 5000
                val o = BufferedOutputStream(s.getOutputStream(), 512)
                val ses = Pairing.handshake(s.getInputStream(), o, code)
                if (ses == null) {
                    onStatus("Código rechazado por $host")
                    try { s.close() } catch (_: Exception) {}
                    return@Thread
                }
                if (Pairing.esVale(code)) {
                    // Solo al enrolar: es la unica vez que el PC dice algo.
                    val linea = Pairing.linea(s.getInputStream())
                    val txt = if (linea != null) ses.abridor.abrir(linea) else null
                    if (txt != null && txt.startsWith("#CODE ")) {
                        val real = Pairing.normal(txt.substring(6))
                        if (real.length == Pairing.LARGO) onCodigo?.invoke(real)
                    }
                }
                s.soTimeout = 0
                sesion = ses
                socket = s
                out = o
                connected.set(true)
                busy.set(false)
                onStatus("Conectado - $host:$port")
                pump()
            } catch (e: Exception) {
                connected.set(false)
                try { s?.close() } catch (_: Exception) {}
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

    /**
     * Cierra el sobre y lo manda. Nada sale en claro: sin esto, quien estuviera
     * escuchando la Wi-Fi leia el texto de `K` tal cual —contrasenas incluidas—
     * y podia colar sus propias lineas sin saber el codigo, porque solo se
     * autenticaba el saludo y no lo que venia detras.
     */
    private fun enviar(pt: ByteArray, n: Int) {
        val c = sesion?.cerrador ?: return
        val largo = c.cerrar(pt, n)
        if (largo > 0) out?.write(c.sobre, 0, largo)
    }

    // UTF-8 y no ASCII: el texto de K lleva eñes y signos de apertura.
    private fun write(s: String) {
        // El fin de linea lo pone el sobre; dentro estorba.
        val b = s.trimEnd('\n', '\r').toByteArray(Charsets.UTF_8)
        if (b.size <= Pairing.MAX_LINEA) enviar(b, b.size)
    }

    // Buffer reutilizado: emitir un movimiento no asigna nada, asi no alimentamos
    // al recolector de basura justo mientras el dedo se mueve. El sobre tambien
    // va sin asignaciones, que si no daria igual todo esto.
    private val buf = ByteArray(48)

    private fun emit(op: Char, x: Float, y: Float) {
        var n = 0
        buf[n++] = op.code.toByte()
        n = putFixed(n, x)
        buf[n++] = ','.code.toByte()
        n = putFixed(n, y)
        enviar(buf, n)
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
        // Las claves mueren con la conexion: la siguiente trae numeros nuevos y
        // los numeros de orden vuelven a empezar.
        sesion = null
        urgent.clear()
    }
}
