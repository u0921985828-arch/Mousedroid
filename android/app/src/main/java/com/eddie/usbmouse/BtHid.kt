package com.eddie.usbmouse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * El movil COMO periferico: se anuncia por Bluetooth como un raton y un teclado
 * de verdad. Al otro lado no hace falta instalar nada — vale contra una tele,
 * una tablet, una consola o un PC.
 *
 * Es HID real, no eventos sintetizados, asi que tambien lo ven los juegos que
 * leen entrada en crudo y que con `pynput` se quedaban sin enterarse.
 *
 * Requiere Android 9 (API 28): [disponible] lo comprueba antes de nada.
 */
@SuppressLint("MissingPermission")   // los permisos los pide MainActivity antes de arrancar
class BtHid(private val ctx: Context, private val onStatus: (String) -> Unit) : Transport {

    companion object {
        const val MOUSE = 1
        const val KEYBOARD = 2

        fun disponible(): Boolean = Build.VERSION.SDK_INT >= 28

        /**
         * Descriptor HID: un raton con tres botones y rueda (informe 1) y un
         * teclado de seis teclas simultaneas (informe 2). Es el descriptor de
         * combo de toda la vida; no inventar nada aqui, los anfitriones son
         * quisquillosos y un byte mal puesto deja el aparato mudo sin decir por que.
         */
        private val DESCRIPTOR = byteArrayOf(
            // ---------------- raton
            0x05, 0x01,                    // Usage Page (Generic Desktop)
            0x09, 0x02,                    // Usage (Mouse)
            0xA1.toByte(), 0x01,           // Collection (Application)
            0x85.toByte(), MOUSE.toByte(), //   Report ID (1)
            0x09, 0x01,                    //   Usage (Pointer)
            0xA1.toByte(), 0x00,           //   Collection (Physical)
            0x05, 0x09,                    //     Usage Page (Button)
            0x19, 0x01,                    //     Usage Minimum (1)
            0x29, 0x03,                    //     Usage Maximum (3)
            0x15, 0x00,                    //     Logical Minimum (0)
            0x25, 0x01,                    //     Logical Maximum (1)
            0x75, 0x01,                    //     Report Size (1)
            0x95.toByte(), 0x03,           //     Report Count (3)
            0x81.toByte(), 0x02,           //     Input (Data,Var,Abs)
            0x75, 0x05,                    //     Report Size (5)
            0x95.toByte(), 0x01,           //     Report Count (1)
            0x81.toByte(), 0x03,           //     Input (Cnst) relleno
            0x05, 0x01,                    //     Usage Page (Generic Desktop)
            0x09, 0x30,                    //     Usage (X)
            0x09, 0x31,                    //     Usage (Y)
            0x09, 0x38,                    //     Usage (Wheel)
            0x15, 0x81.toByte(),           //     Logical Minimum (-127)
            0x25, 0x7F,                    //     Logical Maximum (127)
            0x75, 0x08,                    //     Report Size (8)
            0x95.toByte(), 0x03,           //     Report Count (3)
            0x81.toByte(), 0x06,           //     Input (Data,Var,Rel)
            0xC0.toByte(),                 //   End Collection
            0xC0.toByte(),                 // End Collection

            // ---------------- teclado
            0x05, 0x01,                       // Usage Page (Generic Desktop)
            0x09, 0x06,                       // Usage (Keyboard)
            0xA1.toByte(), 0x01,              // Collection (Application)
            0x85.toByte(), KEYBOARD.toByte(), //   Report ID (2)
            0x05, 0x07,                       //   Usage Page (Keyboard)
            0x19, 0xE0.toByte(),              //   Usage Minimum (LeftControl)
            0x29, 0xE7.toByte(),              //   Usage Maximum (RightGUI)
            0x15, 0x00,                       //   Logical Minimum (0)
            0x25, 0x01,                       //   Logical Maximum (1)
            0x75, 0x01,                       //   Report Size (1)
            0x95.toByte(), 0x08,              //   Report Count (8)
            0x81.toByte(), 0x02,              //   Input (Data,Var,Abs) modificadores
            0x95.toByte(), 0x01,              //   Report Count (1)
            0x75, 0x08,                       //   Report Size (8)
            0x81.toByte(), 0x03,              //   Input (Cnst) reservado
            0x95.toByte(), 0x06,              //   Report Count (6)
            0x75, 0x08,                       //   Report Size (8)
            0x15, 0x00,                       //   Logical Minimum (0)
            0x25, 0x65,                       //   Logical Maximum (101)
            0x05, 0x07,                       //   Usage Page (Keyboard)
            0x19, 0x00,                       //   Usage Minimum (0)
            0x29, 0x65,                       //   Usage Maximum (101)
            0x81.toByte(), 0x00,              //   Input (Data,Array)
            0xC0.toByte()                     // End Collection
        )
    }

    private val conectado = AtomicBoolean(false)
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var deseado: String? = null          // MAC a la que conectar
    private val exec = Executors.newSingleThreadExecutor()
    private var bomba: Thread? = null
    @Volatile private var vivo = false

    override val isConnected: Boolean get() = conectado.get()
    override val label: String get() = nombreHost ?: "Bluetooth"
    private var nombreHost: String? = null

    // ---------------------------------------------------------------- perfil

    fun paired(): List<BluetoothDevice> = try {
        val m = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        m?.adapter?.bondedDevices?.toList() ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** [mac] es el aparato emparejado al que conectarse; null = el primero que enganche. */
    fun start(mac: String?) {
        if (!disponible()) { onStatus("Bluetooth HID pide Android 9"); return }
        deseado = mac
        val adapter: BluetoothAdapter? = try {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (_: Throwable) { null }
        if (adapter == null || !adapter.isEnabled) { onStatus("Enciende el Bluetooth"); return }

        onStatus("Anunciandose como raton...")
        adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "USB Mouse", "Raton y teclado", "eddie",
                    BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR
                )
                // Valores de servicio del ejemplo de referencia de Android: la
                // latencia importa mas que el caudal, esto son 4 bytes por informe.
                val qos = BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                    800, 9, 0, 11250, 11250
                )
                h.registerApp(sdp, null, qos, exec, callback)
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hid = null
                    conectado.set(false)
                    onStatus("Perfil HID caido")
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (!registered) { onStatus("No pude anunciarme"); return }
            onStatus("Anunciado. Empareja desde la tele o el PC.")
            // Si ya hay un aparato elegido, se le llama; si no, se espera a que
            // sea el anfitrion quien conecte, que es lo normal en una tele.
            val m = deseado ?: return
            paired().firstOrNull { it.address == m }?.let { hid?.connect(it) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Si hay aparato elegido, solo ese. Estando visible cinco
                    // minutos, cualquier otro equipo a tiro podia engancharse y
                    // quedarse con todo lo que se tecleara.
                    val m = deseado
                    if (m != null && device?.address != m) {
                        onStatus("Rechazado ${device?.address ?: "?"}: no es el aparato elegido")
                        try { hid?.disconnect(device) } catch (_: Throwable) {}
                        return
                    }
                    host = device
                    nombreHost = try { device?.name } catch (_: Throwable) { null }
                    // Lo encolado mientras no habia nadie se tira: si no, al
                    // conectar salia de golpe una rafaga de hasta 128 clics y
                    // teclas contra la tele.
                    urgentes.clear()
                    conectado.set(true)
                    arrancarBomba()
                    onStatus("Conectado - ${nombreHost ?: "?"}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    conectado.set(false)
                    host = null
                    onStatus("Desconectado")
                }
            }
        }
    }

    // ---------------------------------------------------------------- envio

    // Un informe de raton son 4 bytes y uno de teclado 8. Los arrays se
    // reutilizan: mover el dedo no puede asignar nada (regla de la ruta caliente).
    private val infRaton = ByteArray(4)
    private val infTecla = ByteArray(8)
    private val urgentes = LinkedBlockingQueue<Runnable>(128)

    private val lock = Object()
    private var accDx = 0f
    private var accDy = 0f
    private var accRueda = 0f
    private var botones = 0

    /**
     * Arranca el hilo que vacia los acumuladores.
     *
     * Antes era `if (vivo) return`, y eso tenia una carrera: si el anfitrion
     * volvia dentro de los 8 ms que el hilo viejo pasa durmiendo, la llamada
     * salia por ese return y acto seguido el hilo viejo veia su condicion falsa,
     * ponia `vivo = false` y moria. Quedaba conectado y sin bomba: el puntero
     * congelado y ni un error. Ahora se espera a que el viejo muera de verdad.
     */
    private fun arrancarBomba() {
        val vieja = bomba
        vivo = false
        if (vieja != null && vieja.isAlive && vieja !== Thread.currentThread()) {
            try { vieja.join(80) } catch (_: InterruptedException) {}
        }
        vivo = true
        lateinit var yo: Thread
        bomba = Thread {
            while (vivo && conectado.get() && bomba === yo) {
                try {
                    while (true) (urgentes.poll() ?: break).run()
                    var dx: Int; var dy: Int; var w: Int
                    synchronized(lock) {
                        dx = tomar(accDx).also { accDx -= it }
                        dy = tomar(accDy).also { accDy -= it }
                        w = tomar(accRueda).also { accRueda -= it }
                    }
                    if (dx != 0 || dy != 0 || w != 0) enviarRaton(dx, dy, w)
                    Thread.sleep(8)
                } catch (_: Exception) {
                    break
                }
            }
            // Solo el hilo que sigue siendo el titular apaga la bandera: si no,
            // un hilo viejo agonizando apagaba la bomba del nuevo.
            if (bomba === yo) vivo = false
        }.also { yo = it }.apply { isDaemon = true; start() }
    }

    /** Parte entera acotada a lo que cabe en un byte con signo. */
    private fun tomar(v: Float): Int {
        val n = v.toInt()
        return if (n > 127) 127 else if (n < -127) -127 else n
    }

    private fun enviarRaton(dx: Int, dy: Int, rueda: Int) {
        val h = hid ?: return
        val d = host ?: return
        infRaton[0] = botones.toByte()
        infRaton[1] = dx.toByte()
        infRaton[2] = dy.toByte()
        infRaton[3] = rueda.toByte()
        try { h.sendReport(d, MOUSE, infRaton) } catch (_: Exception) {}
    }

    private fun enviarTecla(mods: Int, codigo: Int) {
        val h = hid ?: return
        val d = host ?: return
        infTecla[0] = mods.toByte()
        infTecla[1] = 0
        infTecla[2] = codigo.toByte()
        for (i in 3..7) infTecla[i] = 0
        try { h.sendReport(d, KEYBOARD, infTecla) } catch (_: Exception) {}
    }

    private fun pulsarYSoltar(mods: Int, codigo: Int) {
        if (codigo == 0) return
        enviarTecla(mods, codigo)
        enviarTecla(0, 0)
    }

    private fun bit(b: Char): Int = when (b) {
        'l' -> 1
        'r' -> 2
        'm' -> 4
        else -> 0
    }

    // ---------------------------------------------------------------- Transport

    override fun move(dx: Float, dy: Float) {
        if (!conectado.get()) return
        synchronized(lock) { accDx += dx; accDy += dy }
    }

    override fun scroll(dx: Float, dy: Float) {
        if (!conectado.get()) return
        // HID solo lleva rueda vertical en este descriptor; el eje X se descarta.
        // Y el signo va al reves que en el protocolo: arriba es positivo.
        synchronized(lock) { accRueda += -dy }
    }

    override fun click(b: Char) {
        val m = bit(b)
        if (!conectado.get()) return
        urgentes.offer(Runnable {
            botones = botones or m; enviarRaton(0, 0, 0)
            botones = botones and m.inv(); enviarRaton(0, 0, 0)
        })
    }

    override fun button(b: Char, down: Boolean) {
        val m = bit(b)
        if (!conectado.get()) return
        urgentes.offer(Runnable {
            botones = if (down) botones or m else botones and m.inv()
            enviarRaton(0, 0, 0)
        })
    }

    override fun text(s: String) {
        if (s.isEmpty()) return
        if (!conectado.get()) return
        urgentes.offer(Runnable {
            for (c in s) {
                val (codigo, shift) = HidKeys.charOf(c)
                if (codigo != 0) pulsarYSoltar(if (shift) HidKeys.SHIFT else 0, codigo)
            }
        })
    }

    override fun key(name: String) {
        if (!conectado.get()) return
        val raw = HidKeys.keyOf(name)
        val c = raw and 0xFF
        val m = if (raw and HidKeys.NEEDS_SHIFT != 0) HidKeys.SHIFT else 0
        urgentes.offer(Runnable { pulsarYSoltar(m, c) })
    }

    override fun keyHold(name: String, down: Boolean) {
        if (!conectado.get()) return
        val raw = HidKeys.keyOf(name)
        val c = raw and 0xFF
        val m = if (raw and HidKeys.NEEDS_SHIFT != 0) HidKeys.SHIFT else 0
        urgentes.offer(Runnable { if (down) enviarTecla(m, c) else enviarTecla(0, 0) })
    }

    override fun combo(mods: String, name: String) {
        if (!conectado.get()) return
        val raw = HidKeys.keyOf(name)
        val c = raw and 0xFF
        var m = HidKeys.modMask(mods)
        if (raw and HidKeys.NEEDS_SHIFT != 0) m = m or HidKeys.SHIFT
        urgentes.offer(Runnable { pulsarYSoltar(m, c) })
    }

    override fun close() {
        vivo = false
        conectado.set(false)
        try {
            // soltar lo que quedara pulsado antes de irse, como hace el servidor
            botones = 0
            enviarRaton(0, 0, 0)
            enviarTecla(0, 0)
            host?.let { hid?.disconnect(it) }
            hid?.unregisterApp()
        } catch (_: Exception) {}
        // Soltar el proxy y el executor. Sin esto, cada cambio de modo dejaba
        // un hilo colgado y un perfil sin devolver, y al cabo de unos cuantos
        // el registro empezaba a fallar con "No pude anunciarme".
        try {
            val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            hid?.let { a?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (_: Throwable) {}
        hid = null
        // shutdown y no shutdownNow: las devoluciones de llamada del desregistro
        // corren en este mismo executor y tienen que poder terminar.
        try { exec.shutdown() } catch (_: Throwable) {}
        host = null
        urgentes.clear()
    }
}
