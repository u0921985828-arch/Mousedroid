package com.eddie.usbmouse

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Chasis y conexion. La superficie de trabajo la construye [Deck] segun el modo.
 * Por defecto: panel premium, que es el que menos exige a la mano y el que
 * mejor escala a cualquier pantalla.
 */
class MainActivity : Activity(), DeckIO {

    private val LED_ON = "#8FDC9A"
    private val LED_WAIT = "#D9B45A"
    private val LED_OFF = "#575B60"

    private lateinit var usb: MouseClient
    private var bt: BtHid? = null

    /** 0 auto, 1 solo cable, 2 solo bluetooth. */
    private var modo = AUTO

    /**
     * Por donde salen las ordenes AHORA MISMO.
     *
     * En automatico los dos transportes estan levantados a la vez —el Bluetooth
     * no molesta mientras nadie se conecte, solo se anuncia— y manda el que este
     * enganchado. Si lo estan los dos gana el cable: menos latencia y es el unico
     * que escribe tildes.
     */
    private val link: Transport
        get() {
            val b = bt
            return when {
                modo == CABLE -> usb
                modo == BLUETOOTH -> b ?: usb
                usb.isConnected -> usb
                b != null && b.isConnected -> b
                else -> usb
            }
        }

    private val btMode: Boolean get() = link !== usb
    private lateinit var deck: Deck
    private lateinit var keys: KeyDeck
    private lateinit var led: LedView
    private lateinit var wire: TextView
    private lateinit var stageHost: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var hostIn: EditText
    private lateinit var portIn: EditText
    private lateinit var famMouse: GlyphView
    private lateinit var famPad: GlyphView
    private lateinit var keyGlyph: GlyphView
    private lateinit var tierPips: PipsView
    private var vib: Vibrator? = null

    companion object {
        private const val AUTO = 0
        private const val CABLE = 1
        private const val BLUETOOTH = 2
        private val MODOS = arrayOf("Auto", "Cable", "Bluetooth")
    }

    private var family = 1   // 0 raton, 1 panel
    private var tier = 1     // 0 basico, 1 premium, 2 gaming
    private var autoConnect = true
    @Volatile private var autoRunning = false
    private var lastWire = 0L

    private val prefs by lazy { getSharedPreferences("usbmouse", Context.MODE_PRIVATE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(hex: String) = Color.parseColor(hex)

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Monet.load(this)
        window.statusBarColor = Monet.deck
        window.navigationBarColor = Monet.deckLo
        @Suppress("DEPRECATION")
        vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        family = prefs.getInt("family", 1)
        tier = prefs.getInt("tier", 1)

        usb = MouseClient { msg -> runOnUiThread { onStatus(msg) } }
        // migracion del interruptor viejo de dos estados
        modo = prefs.getInt("modo", if (prefs.getBoolean("bt", false)) BLUETOOTH else AUTO)
        deck = Deck(this, this)
        keys = KeyDeck(this, this)
        setContentView(buildChassis())
        rebuild()
        arrancarEnlace()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ------------------------------------------------------------ DeckIO

    // El movimiento no escribe en pantalla: formatear en cada evento tactil era
    // trabajo puro de descarte. El lector solo refleja acciones discretas.
    override fun move(dx: Float, dy: Float) = link.move(dx, dy)

    override fun scroll(dx: Float, dy: Float) = link.scroll(dx, dy)

    override fun click(b: Char) { link.click(b); lastWire = 0; say("C $b") }

    override fun button(b: Char, down: Boolean) {
        link.button(b, down); lastWire = 0; say("${if (down) "D" else "U"} $b")
    }

    /**
     * Los botones que no son de raton se traducen aqui a teclas de verdad.
     * `sniper` no aparece: ese actua en local bajando la sensibilidad, no viaja.
     * G1 y G2 mandan F13/F14, que son las teclas que ningun programa usa por su
     * cuenta y que por eso se dejan para asignar en el juego.
     */
    override fun macro(tag: String, down: Boolean) {
        when (tag) {
            Macro.BACK -> if (down) link.combo("a", "left")
            Macro.FWD -> if (down) link.combo("a", "right")
            Macro.G1 -> link.keyHold("f13", down)
            Macro.G2 -> link.keyHold("f14", down)
            Macro.G3 -> link.keyHold("f15", down)
            Macro.G4 -> link.keyHold("f16", down)
        }
        if (down) { lastWire = 0; say(tag) }
    }

    // El lector no repite lo escrito: lo que se teclea puede ser una contraseña.
    override fun text(s: String) {
        link.text(s)
        lastWire = 0
        // Un teclado HID manda POSICIONES de tecla, no letras: las tildes y la
        // eñe no tienen posicion en la distribucion que se supone. Mejor avisar
        // que tragarselas en silencio.
        val fuera = if (btMode) HidKeys.unsupported(s) else ""
        say(if (fuera.isEmpty()) "K·" else "sin HID: $fuera")
    }

    override fun key(name: String) { link.key(name); lastWire = 0; say(name) }

    override fun combo(mods: String, name: String) {
        link.combo(mods, name); lastWire = 0; say("$mods+$name")
    }

    override fun haptic() {
        val v = vib ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(9, 50))
        else @Suppress("DEPRECATION") v.vibrate(9)
    }

    override fun note(s: String) { lastWire = 0; say(s) }

    private fun say(t: String) {
        val now = System.currentTimeMillis()
        if (now - lastWire < 170) return
        lastWire = now
        wire.text = t
    }

    private fun onStatus(msg: String) {
        val l = link
        val ok = l.isConnected
        led.color = col(if (ok) LED_ON else if (autoConnect) LED_WAIT else LED_OFF)
        // El mensaje se tiraba: el LED decia el color pero no el motivo. Los
        // cambios de conexion son raros, asi que se saltan la espera del lector.
        // En automatico, ademas, hay que decir CUAL de los dos enlaces ganó.
        if (msg.isEmpty()) return
        lastWire = 0
        say(if (ok && modo == AUTO) "${if (l === usb) "cable" else "bt"} · ${l.label}" else msg)
    }

    // ------------------------------------------------------------ chasis

    private fun etched(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (Build.VERSION.SDK_INT >= 21) letterSpacing = 0.03f
    }

    private fun buildChassis(): View {
        val root = FrameLayout(this).apply {
            background = MetalDrawable(Monet.deckHi, Monet.deckLo, 0f, brushAlpha = 22)
        }
        val colv = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(14))
        }
        colv.addView(head(), LinearLayout.LayoutParams(MATCH_PARENT, dp(30)))
        stageHost = FrameLayout(this)
        colv.addView(stageHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(9)
        })
        root.addView(colv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // El modo inmersivo solo esconde la barra de navegacion, no la de estado,
        // y la ventana se extiende por debajo de esta: la cabecera entera (LED,
        // glifos, engranaje) quedaba tapada por el reloj y la bateria. Se baja el
        // contenido justo lo que ocupa esa barra.
        //
        // Con la API de plataforma y no con androidx: el resto de la app no usa
        // ni una clase de la biblioteca de compatibilidad y no vale la pena
        // empezar por esto. WindowInsets.Type es de API 30, asi que por debajo
        // se usa el accesor viejo, que cubre desde la 20.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val arriba =
                if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top
                else @Suppress("DEPRECATION") insets.systemWindowInsetTop
            colv.setPadding(dp(11), dp(10) + arriba, dp(11), dp(14))
            insets
        }
        // el teclado va antes que los ajustes: si por lo que sea coinciden, manda
        // el panel de ajustes, que es el que tiene el boton de aplicar
        root.addView(keys.build(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })
        root.addView(settingsPanel(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })
        return root
    }

    private fun head(): View {
        // Chapa embutida y no texto suelto sobre el chasis: en un aparato la
        // instrumentacion va montada en su propia pieza, no serigrafiada al aire.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = plate(this@MainActivity, 8)
            setPadding(dp(8), 0, dp(5), 0)
        }
        led = LedView(this).apply { color = col(LED_OFF) }
        bar.addView(led, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(8) })

        wire = etched("—", 10.5f, Monet.etchDim).apply { typeface = Typeface.MONOSPACE }
        bar.addView(wire, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        famMouse = GlyphView(this, "mouse").apply {
            setOnClickListener { family = 0; haptic(); rebuild() }
        }
        famPad = GlyphView(this, "pad").apply {
            setOnClickListener { family = 1; haptic(); rebuild() }
        }
        bar.addView(famMouse, LinearLayout.LayoutParams(dp(28), dp(22)))
        bar.addView(famPad, LinearLayout.LayoutParams(dp(30), dp(22)).apply { leftMargin = dp(2) })

        keyGlyph = GlyphView(this, "keys").apply {
            alpha = 0.38f
            setOnClickListener { toggleKeys() }
        }
        bar.addView(keyGlyph, LinearLayout.LayoutParams(dp(30), dp(22)).apply { leftMargin = dp(5) })

        tierPips = PipsView(this).apply {
            active = tier
            setOnClickListener { tier = (tier + 1) % 3; haptic(); rebuild() }
        }
        bar.addView(tierPips, LinearLayout.LayoutParams(dp(44), dp(22)).apply { leftMargin = dp(4) })

        val gear = etched("·  ·  ·", 12f, Monet.etchDim).apply {
            setPadding(dp(9), dp(3), dp(4), dp(3))
            setOnClickListener { haptic(); togglePanel() }
        }
        bar.addView(gear)
        return bar
    }

    private fun rebuild() {
        prefs.edit().putInt("family", family).putInt("tier", tier).apply()
        deck.baseSens = prefs.getFloat("sens", 1.8f)
        deck.natural = prefs.getBoolean("natural", false)
        // faltaba, y por eso apagar la aceleracion duraba hasta el siguiente
        // cambio de modo: al reconstruir se creaba un pad nuevo con el valor viejo
        deck.accel = prefs.getBoolean("accel", true)
        stageHost.removeAllViews()
        stageHost.addView(deck.build(family, tier),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        famMouse.alpha = if (family == 0) 1f else 0.38f
        famPad.alpha = if (family == 1) 1f else 0.38f
        tierPips.active = tier
        tierPips.tint = when (tier) { 2 -> Monet.accentSoft; 1 -> Monet.accent; else -> Monet.etch }
    }

    // ------------------------------------------------------------ ajustes

    private fun check(text: String, initial: Boolean, onChange: (Boolean) -> Unit) =
        CheckBox(this).apply {
            this.text = text
            setTextColor(Monet.etch)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isChecked = initial
            if (Build.VERSION.SDK_INT >= 21) buttonTintList = ColorStateList.valueOf(Monet.etchDim)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    private fun settingsPanel(): View {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = plate(this@MainActivity, 18, topOnly = true)
            setPadding(dp(18), dp(12), dp(18), dp(20))
            visibility = View.GONE
        }
        panel.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(Monet.etchDim); cornerRadius = dp(2).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(34), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16)
        })

        val base = prefs.getFloat("sens", 1.8f)
        val sensRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sensRow.addView(etched("Sensibilidad", 11.5f, Monet.etchDim))
        val out = etched(String.format(Locale.US, "%.1f", base), 11.5f, Monet.etch).apply {
            typeface = Typeface.MONOSPACE; gravity = Gravity.END
        }
        val seek = SeekBar(this).apply {
            max = 60
            progress = ((base - 0.5f) * 10f).toInt().coerceIn(0, 60)
            if (Build.VERSION.SDK_INT >= 21) {
                thumbTintList = ColorStateList.valueOf(Monet.accent)
                progressTintList = ColorStateList.valueOf(Monet.accent)
                progressBackgroundTintList = ColorStateList.valueOf(Monet.plateLo)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    val v = 0.5f + p / 10f
                    deck.baseSens = v
                    deck.pad.sensitivity = v
                    out.text = String.format(Locale.US, "%.1f", v)
                    prefs.edit().putFloat("sens", v).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        sensRow.addView(seek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            leftMargin = dp(14); rightMargin = dp(10)
        })
        sensRow.addView(out, LinearLayout.LayoutParams(dp(30), WRAP_CONTENT))
        panel.addView(sensRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })

        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val a = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        a.addView(check("Aceleración", prefs.getBoolean("accel", true)) {
            prefs.edit().putBoolean("accel", it).apply()
            deck.accel = it
            // en gaming manda el modo: ahi la aceleracion esta apagada a proposito
            if (tier != 2) deck.pad.acceleration = it
        })
        a.addView(check("Conectar sola", prefs.getBoolean("autoconnect", true)) {
            autoConnect = it
            prefs.edit().putBoolean("autoconnect", it).apply()
            if (it) startAutoLoop() else usb.close()
            onStatus("")
        })
        b.addView(check("Scroll natural", prefs.getBoolean("natural", false)) {
            prefs.edit().putBoolean("natural", it).apply()
            deck.natural = it
            deck.pad.naturalScroll = it
        })
        b.addView(check("Abrir al enchufar", prefs.getBoolean("autolaunch", false)) {
            prefs.edit().putBoolean("autolaunch", it).apply()
            if (it) requestOverlay()
        })
        val enlace = etched("Enlace: ${MODOS[modo]}", 13f, Monet.etch).apply {
            setPadding(0, dp(6), 0, dp(6))
        }
        enlace.setOnClickListener {
            modo = (modo + 1) % 3
            prefs.edit().putInt("modo", modo).apply()
            enlace.text = "Enlace: ${MODOS[modo]}"
            haptic()
            bt?.close(); bt = null
            arrancarEnlace()
            onStatus("Enlace: ${MODOS[modo]}")
        }
        a.addView(enlace)
        b.addView(etched("Elegir aparato…", 13f, Monet.etch).apply {
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { elegirAparato() }
        })
        grid.addView(a, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        grid.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        panel.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        panel.addView(etched("Destino por cable    auto = túnel USB y búsqueda automática",
            11f, Monet.etchDim))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostIn = EditText(this).apply {
            setText(prefs.getString("host", "auto")); hint = "auto"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Monet.etch); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        portIn = EditText(this).apply {
            setText(prefs.getInt("port", 8777).toString()); setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Monet.etch); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val apply = etched("Aplicar", 12.5f, Monet.etch).apply {
            gravity = Gravity.CENTER
            background = plate(this@MainActivity, 11)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { applyTarget(); togglePanel() }
        }
        row.addView(hostIn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 3f))
        row.addView(portIn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f))
        row.addView(apply, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = dp(10)
        })
        panel.addView(row)
        return panel
    }

    private fun toggleKeys() {
        haptic()
        if (keys.isOpen) {
            closeKeys()
        } else {
            if (panel.visibility == View.VISIBLE) togglePanel()
            keys.open()
            keyGlyph.alpha = 1f
        }
    }

    private fun closeKeys() {
        keys.close()
        keyGlyph.alpha = 0.38f
    }

    /** Sin haptico propio: lo da quien lo abre, y asi abrir uno cerrando el otro no vibra dos veces. */
    private fun togglePanel() {
        if (keys.isOpen) closeKeys()
        if (panel.visibility == View.VISIBLE) {
            panel.animate().translationY(panel.height.toFloat()).setDuration(150)
                .withEndAction { panel.visibility = View.GONE }.start()
        } else {
            panel.visibility = View.VISIBLE
            panel.translationY = panel.height.toFloat().coerceAtLeast(dp(280).toFloat())
            panel.animate().translationY(0f).setDuration(180).start()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            panel.visibility == View.VISIBLE -> togglePanel()
            keys.isOpen -> closeKeys()
            else -> super.onBackPressed()
        }
    }

    private fun applyTarget() {
        val host = hostIn.text.toString().trim().ifEmpty { "auto" }
        val port = portIn.text.toString().trim().toIntOrNull() ?: 8777
        prefs.edit().putString("host", host).putInt("port", port).apply()
        usb.close()
        if (!host.equals("auto", true)) usb.connect(host, port)
        startAutoLoop()
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Concede 'Mostrar sobre otras apps' para que se abra sola",
                Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------ bluetooth

    /**
     * Arranca el modo periferico. En Android 12+ BLUETOOTH_CONNECT se pide en
     * caliente; por debajo basta con declararlo en el manifiesto.
     */
    /**
     * Levanta lo que toque segun el modo. En automatico levanta los dos: el
     * cable reintenta en bucle y el Bluetooth se queda anunciado esperando que
     * alguien empareje. El primero que conteste se lleva el mando.
     */
    private fun arrancarEnlace() {
        if (modo != BLUETOOTH) startAutoLoop() else usb.close()
        if (modo != CABLE) arrancarBt(pedirVisible = modo == BLUETOOTH)
        else { bt?.close(); bt = null }
    }

    private fun arrancarBt(pedirVisible: Boolean) {
        if (!BtHid.disponible()) {
            if (modo == BLUETOOTH) say("Bluetooth HID pide Android 9")
            return
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 7)
            return
        }
        val b = bt ?: BtHid(this) { msg -> runOnUiThread { onStatus(msg) } }.also { bt = it }
        val mac = prefs.getString("btmac", null)
        b.start(mac)
        // Registrar el perfil anuncia el servicio pero NO hace visible al movil:
        // el anfitrion busca y no encuentra nada, sin ningun error de por medio.
        // Es un dialogo del sistema, asi que en automatico no se saca a la cara:
        // solo cuando se pide Bluetooth a proposito y aun no hay aparato.
        if (pedirVisible && mac == null) hacerseVisible()
    }

    /** Cinco minutos de visibilidad para que el anfitrion pueda emparejar. */
    private fun hacerseVisible() {
        try {
            startActivity(
                Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            )
        } catch (_: Exception) {
            say("No pude pedir visibilidad")
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 7) {
            if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED)
                arrancarBt(pedirVisible = modo == BLUETOOTH)
            else say("Sin permiso de Bluetooth")
        }
    }

    /** Lista de emparejados. El aparato elegido se recuerda por su MAC. */
    private fun elegirAparato() {
        val b = bt ?: BtHid(this) { msg -> runOnUiThread { onStatus(msg) } }.also { bt = it }
        val lista = b.paired()
        if (lista.isEmpty()) {
            Toast.makeText(this, "Empareja antes la tele o el PC en Ajustes",
                Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {}
            return
        }
        val nombres = ArrayList<String>(lista.size + 1)
        lista.forEach { d ->
            nombres.add(try { d.name ?: d.address } catch (_: Throwable) { d.address })
        }
        nombres.add("Hacerme visible 5 min…")
        AlertDialog.Builder(this)
            .setTitle("¿A qué aparato?")
            .setItems(nombres.toTypedArray()) { _, i ->
                if (i == lista.size) { hacerseVisible(); return@setItems }
                prefs.edit().putString("btmac", lista[i].address).apply()
                bt?.close()
                bt = null
                arrancarBt(pedirVisible = false)
            }
            .show()
    }

    // ------------------------------------------------------------ conexion

    private fun startAutoLoop() {
        if (autoRunning) return
        autoRunning = true
        Thread {
            while (autoRunning) {
                if (autoConnect && modo != BLUETOOTH && !usb.isConnected) {
                    val typed = prefs.getString("host", "auto") ?: "auto"
                    val port = prefs.getInt("port", 8777)
                    if (typed.equals("auto", true) || typed.isEmpty()) {
                        usb.connect("127.0.0.1", port)
                        Thread.sleep(600)
                        if (!usb.isConnected) {
                            val found = Discovery.find()
                            if (found != null) usb.connect(found.first, found.second)
                        }
                    } else {
                        usb.connect(typed, port)
                    }
                }
                try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onResume() {
        super.onResume()
        autoConnect = prefs.getBoolean("autoconnect", true)
        onStatus("")
        if (autoConnect) startAutoLoop()
    }

    override fun onDestroy() {
        autoRunning = false
        usb.close()
        bt?.close()
        super.onDestroy()
    }
}
