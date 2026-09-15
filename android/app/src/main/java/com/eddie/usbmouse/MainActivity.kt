package com.eddie.usbmouse

import android.app.Activity
import android.content.Context
import android.content.Intent
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

    private lateinit var client: MouseClient
    private lateinit var deck: Deck
    private lateinit var led: View
    private lateinit var wire: TextView
    private lateinit var stageHost: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var hostIn: EditText
    private lateinit var portIn: EditText
    private lateinit var famMouse: GlyphView
    private lateinit var famPad: GlyphView
    private lateinit var tierPips: PipsView
    private var vib: Vibrator? = null

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

        client = MouseClient { msg -> runOnUiThread { onStatus(msg) } }
        deck = Deck(this, this)
        setContentView(buildChassis())
        rebuild()
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
    override fun move(dx: Float, dy: Float) = client.move(dx, dy)

    override fun scroll(dx: Float, dy: Float) = client.scroll(dx, dy)

    override fun click(b: Char) { client.click(b); lastWire = 0; say("C $b") }

    override fun button(b: Char, down: Boolean) {
        client.button(b, down); lastWire = 0; say("${if (down) "D" else "U"} $b")
    }

    override fun macro(tag: String, down: Boolean) { if (down) { lastWire = 0; say(tag) } }

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
        val ok = client.isConnected
        led.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(col(if (ok) LED_ON else if (autoConnect) LED_WAIT else LED_OFF))
        }
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
        root.addView(settingsPanel(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })
        return root
    }

    private fun head(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(2), 0)
        }
        led = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(col(LED_OFF))
            }
        }
        bar.addView(led, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(10) })

        wire = etched("—", 10.5f, Monet.etchDim).apply { typeface = Typeface.MONOSPACE }
        bar.addView(wire, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        famMouse = GlyphView(this, "mouse").apply {
            setOnClickListener { family = 0; haptic(); rebuild() }
        }
        famPad = GlyphView(this, "pad").apply {
            setOnClickListener { family = 1; haptic(); rebuild() }
        }
        bar.addView(famMouse, LinearLayout.LayoutParams(dp(30), dp(26)))
        bar.addView(famPad, LinearLayout.LayoutParams(dp(32), dp(26)).apply { leftMargin = dp(2) })

        tierPips = PipsView(this).apply {
            active = tier
            setOnClickListener { tier = (tier + 1) % 3; haptic(); rebuild() }
        }
        bar.addView(tierPips, LinearLayout.LayoutParams(dp(46), dp(26)).apply { leftMargin = dp(4) })

        val gear = etched("·  ·  ·", 12f, Monet.etchDim).apply {
            setPadding(dp(10), dp(5), dp(6), dp(5))
            setOnClickListener { togglePanel() }
        }
        bar.addView(gear)
        return bar
    }

    private fun rebuild() {
        prefs.edit().putInt("family", family).putInt("tier", tier).apply()
        deck.baseSens = prefs.getFloat("sens", 1.8f)
        deck.natural = prefs.getBoolean("natural", false)
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
            deck.pad.acceleration = it
        })
        a.addView(check("Conectar sola", prefs.getBoolean("autoconnect", true)) {
            autoConnect = it
            prefs.edit().putBoolean("autoconnect", it).apply()
            if (it) startAutoLoop() else client.close()
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
        grid.addView(a, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        grid.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        panel.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        panel.addView(etched("Destino    auto = túnel USB y búsqueda automática", 11f, Monet.etchDim))
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

    private fun togglePanel() {
        haptic()
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
        if (panel.visibility == View.VISIBLE) togglePanel() else super.onBackPressed()
    }

    private fun applyTarget() {
        val host = hostIn.text.toString().trim().ifEmpty { "auto" }
        val port = portIn.text.toString().trim().toIntOrNull() ?: 8777
        prefs.edit().putString("host", host).putInt("port", port).apply()
        client.close()
        if (!host.equals("auto", true)) client.connect(host, port)
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

    // ------------------------------------------------------------ conexion

    private fun startAutoLoop() {
        if (autoRunning) return
        autoRunning = true
        Thread {
            while (autoRunning) {
                if (autoConnect && !client.isConnected) {
                    val typed = prefs.getString("host", "auto") ?: "auto"
                    val port = prefs.getInt("port", 8777)
                    if (typed.equals("auto", true) || typed.isEmpty()) {
                        client.connect("127.0.0.1", port)
                        Thread.sleep(600)
                        if (!client.isConnected) {
                            val found = Discovery.find()
                            if (found != null) client.connect(found.first, found.second)
                        }
                    } else {
                        client.connect(typed, port)
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
        client.close()
        super.onDestroy()
    }
}
