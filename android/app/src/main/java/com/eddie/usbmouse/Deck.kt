package com.eddie.usbmouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout

// ---------------------------------------------------------------- materiales

fun dpOf(c: Context, v: Float) = (v * c.resources.displayMetrics.density).toInt()

/** Chapa de metal cepillado con filo claro arriba, embutida en el chasis. */
fun plate(c: Context, radius: Int, topOnly: Boolean = false): MetalDrawable =
    MetalDrawable(Monet.plateHi, Monet.plateLo, dpOf(c, radius.toFloat()).toFloat(), topOnly)

// ---------------------------------------------------------------- señales

/** Glifos dibujados: nada de texto en la superficie de trabajo. */
class GlyphView(context: Context, private val kind: String) : View(context) {

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * d
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#8D949B")
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 255, 255, 255) }

    var tint: Int = Color.parseColor("#8D949B")
        set(v) { field = v; p.color = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val s = 5.5f * d
        when (kind) {
            "up" -> canvas.drawPath(Path().apply {
                moveTo(cx - s, cy + s * 0.5f); lineTo(cx, cy - s * 0.5f); lineTo(cx + s, cy + s * 0.5f)
            }, p)
            "down" -> canvas.drawPath(Path().apply {
                moveTo(cx - s, cy - s * 0.5f); lineTo(cx, cy + s * 0.5f); lineTo(cx + s, cy - s * 0.5f)
            }, p)
            "cross" -> {
                canvas.drawCircle(cx, cy, 6.5f * d, p)
                canvas.drawLine(cx, cy - 10.5f * d, cx, cy - 6f * d, p)
                canvas.drawLine(cx, cy + 6f * d, cx, cy + 10.5f * d, p)
                canvas.drawLine(cx - 10.5f * d, cy, cx - 6f * d, cy, p)
                canvas.drawLine(cx + 6f * d, cy, cx + 10.5f * d, cy, p)
            }
            "dot1" -> canvas.drawCircle(cx, cy, 2.6f * d, fill)
            "dot2" -> {
                canvas.drawCircle(cx - 4f * d, cy, 2.6f * d, fill)
                canvas.drawCircle(cx + 4f * d, cy, 2.6f * d, fill)
            }
            "mouse" -> {
                val w = 6.5f * d
                val h = 9.5f * d
                canvas.drawRoundRect(RectF(cx - w, cy - h, cx + w, cy + h), w, w, p)
                canvas.drawLine(cx, cy - h, cx, cy - h * 0.15f, p)
                canvas.drawLine(cx - w, cy - h * 0.15f, cx + w, cy - h * 0.15f, p)
            }
            "pad" -> {
                val w = 10f * d
                val h = 7.5f * d
                canvas.drawRoundRect(RectF(cx - w, cy - h, cx + w, cy + h), 3f * d, 3f * d, p)
                val a = p.alpha
                p.alpha = 140
                canvas.drawLine(cx - w, cy + h * 0.45f, cx + w, cy + h * 0.45f, p)
                p.alpha = a
            }
            "keys" -> {
                val w = 10f * d
                val h = 7f * d
                canvas.drawRoundRect(RectF(cx - w, cy - h, cx + w, cy + h), 2f * d, 2f * d, p)
                val a = p.alpha
                p.alpha = 150
                // dos filas de teclas y la barra espaciadora
                var y = cy - h * 0.36f
                repeat(2) {
                    var x = cx - w * 0.55f
                    repeat(3) {
                        canvas.drawPoint(x, y, p)
                        x += w * 0.55f
                    }
                    y += h * 0.4f
                }
                canvas.drawLine(cx - w * 0.45f, cy + h * 0.52f, cx + w * 0.45f, cy + h * 0.52f, p)
                p.alpha = a
            }
        }
    }
}

/** Escalones de sensibilidad. Tocar avanza al siguiente. */
class PipsView(context: Context, private val count: Int = 3) : View(context) {

    private val d = resources.displayMetrics.density
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 46, 48, 51) }
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9AA0A7") }

    var active = 0
        set(v) { field = v; invalidate() }

    var tint: Int = Color.parseColor("#9AA0A7")
        set(v) { field = v; on.color = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val r = 3.5f * d
        val gap = 11f * d
        val total = (count - 1) * gap
        var x = width / 2f - total / 2f
        for (i in 0 until count) {
            canvas.drawCircle(x, height / 2f, r, if (i <= active) on else off)
            x += gap
        }
    }
}

// ---------------------------------------------------------------- fabrica

interface DeckIO {
    fun move(dx: Float, dy: Float)
    fun scroll(dx: Float, dy: Float)
    fun click(b: Char)
    fun button(b: Char, down: Boolean)
    fun macro(tag: String, down: Boolean)
    fun text(s: String)
    fun key(name: String)
    fun combo(mods: String, name: String)
    fun haptic()
    fun note(s: String)
}

/**
 * Etiquetas de los botones que no son de raton. Son constantes y no literales
 * sueltos porque [Deck] las pinta en el lector y [MainActivity] las traduce a
 * teclas: una tilde de menos en un sitio y el boton deja de hacer nada.
 */
object Macro {
    const val BACK = "atrás"
    const val FWD = "adelante"
    const val G1 = "G1"
    const val G2 = "G2"
    // G3 y G4 solo existen en el panel: son las esquinas de abajo del cristal
    const val G3 = "G3"
    const val G4 = "G4"
    const val SNIPER = "sniper"
}

/**
 * Construye la disposicion de cada modo.
 *  familia 0 = raton (botones arriba, rueda al centro, sensor debajo)
 *  familia 1 = panel (cristal limpio, barra de clic abajo)
 *  nivel   0 = basico, 1 = premium, 2 = gaming
 *
 * Nada aqui usa alturas fijas para el area de trabajo: los botones son
 * porcentaje con minimo, y todo lo que sobra se lo lleva el cristal.
 */
class Deck(private val ctx: Context, private val io: DeckIO) {

    lateinit var pad: TouchpadView
        private set
    private var stage = 0
    private var sniper = false
    private var halo: HaloView? = null

    var baseSens = 1.8f
    /** Lo que ha pedido el usuario. Gaming lo apaga aunque este puesto. */
    var accel = true
    var natural = false

    /**
     * Un solo objeto para toda la ruta caliente, creado con el Deck y no por
     * pantalla: asi un evento de dedo no asigna nada. El halo se resuelve al
     * vuelo porque [PadStack] lo crea despues que el pad.
     */
    private val sink = object : PadSink {
        override fun move(dx: Float, dy: Float) = io.move(dx, dy)
        override fun scroll(dx: Float, dy: Float) = io.scroll(dx, dy)
        override fun point(x: Float, y: Float, visible: Boolean) {
            halo?.show(x, y, visible)
        }
    }

    private fun dp(v: Float) = dpOf(ctx, v)

    private fun applySens() {
        val steps = floatArrayOf(1f, 1.3f, 1.65f)
        pad.sensitivity = baseSens * steps[stage] * (if (sniper) 0.3f else 1f)
    }

    /** Boton de chapa con estado pulsado; sin etiqueta, la forma ya lo dice. */
    private fun key(hold: Boolean, code: Char?, tag: String?, glyph: String? = null): View {
        val v = FrameLayout(ctx).apply { background = plate(ctx, 12) }
        glyph?.let {
            v.addView(GlyphView(ctx, it), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.alpha = 0.72f
                    io.haptic()
                    if (code != null) { if (hold) io.button(code, true) else io.click(code) }
                    if (tag != null) {
                        io.macro(tag, true)
                        if (tag == Macro.SNIPER) { sniper = true; applySens() }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    if (code != null && hold) io.button(code, false)
                    if (tag != null) {
                        io.macro(tag, false)
                        if (tag == Macro.SNIPER) { sniper = false; applySens() }
                    }
                }
            }
            true
        }
        return v
    }

    private fun pipsRow(tint: Int): View {
        val p = PipsView(ctx).apply {
            this.tint = tint
            active = stage
            setOnClickListener {
                stage = (stage + 1) % 3
                active = stage
                applySens()
                io.haptic()
                io.note("Nivel ${stage + 1}")
            }
        }
        return p
    }

    /**
     * Cristal + halo + (opcional) banda de clic integrada.
     * En el hardware real el clic no es una pieza aparte: es el tercio bajo de la
     * misma lamina de cristal, asi que la banda va DENTRO, no debajo.
     *
     * La relacion 1,62 la impone ESTA vista, no el cristal. Cuando la imponia el
     * cristal, la pila seguia midiendo toda la columna: el cristal se quedaba
     * arriba y la banda, anclada al fondo de la pila, se iba al fondo de la
     * pantalla con 400 dp de deck muerto en medio. El 32% es del cristal.
     */
    private inner class PadStack(
        withBand: Boolean,
        private val aspect: Float,
        withCorners: Boolean = false
    ) : FrameLayout(ctx) {
        private var band: View? = null
        private val corners = ArrayList<View>(4)

        init {
            val h = HaloView(ctx)
            halo = h
            addView(padRef, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(h, LayoutParams(h.size, h.size))
            if (withBand) {
                val b = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                b.addView(key(true, 'l', null), LinearLayout.LayoutParams(0, MATCH_PARENT, 3f))
                b.addView(hairline(), LinearLayout.LayoutParams(dp(1f), MATCH_PARENT).apply {
                    topMargin = dp(10f); bottomMargin = dp(10f)
                })
                b.addView(key(false, 'm', null), LinearLayout.LayoutParams(0, MATCH_PARENT, 2f))
                b.addView(hairline(), LinearLayout.LayoutParams(dp(1f), MATCH_PARENT).apply {
                    topMargin = dp(10f); bottomMargin = dp(10f)
                })
                b.addView(key(true, 'r', null), LinearLayout.LayoutParams(0, MATCH_PARENT, 3f))
                addView(b, LayoutParams(MATCH_PARENT, 1, Gravity.BOTTOM))
                band = b
            }
            if (withCorners) {
                // Las cuatro esquinas macro del prototipo: 26% x 19%, por encima
                // de la banda, que es lo que hace que en gaming el pulgar tenga
                // cuatro botones sin levantar la mano del cristal.
                val sitios = intArrayOf(
                    Gravity.TOP or Gravity.START, Gravity.TOP or Gravity.END,
                    Gravity.BOTTOM or Gravity.START, Gravity.BOTTOM or Gravity.END
                )
                val tags = arrayOf(Macro.G1, Macro.G2, Macro.G3, Macro.G4)
                for (i in sitios.indices) {
                    val c = corner(tags[i])
                    addView(c, LayoutParams(1, 1, sitios[i]).apply {
                        setMargins(dp(6f), dp(6f), dp(6f), dp(6f))
                    })
                    corners.add(c)
                }
            }
            clipChildren = true
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec)
            var h = MeasureSpec.getSize(heightSpec)
            if (aspect > 0f) {
                val deseado = (w / aspect).toInt()
                h = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) deseado
                else deseado.coerceAtMost(h)
            }
            // Las medidas de banda y esquinas se fijan AQUI, antes de medir a los
            // hijos y mutando el LayoutParams sin `setLayoutParams`. Estaban en
            // onSizeChanged, que llega con el layout ya en marcha: pedia otra
            // pasada de medida desde dentro de la anterior y la banda se quedaba
            // con el alto de 1 px con el que nace. Era invisible, no ausente.
            band?.let { (it.layoutParams as LayoutParams).height = bandHeight(w, h) }
            val lado = (w * 0.26f).toInt()
            val alto = (h * 0.19f).toInt()
            corners.forEach {
                val lp = it.layoutParams as LayoutParams
                lp.width = lado
                lp.height = alto
            }

            // Se miden los hijos contra la altura DEFINITIVA. Midiendolos contra la
            // que venia de fuera, el cristal se calculaba el degradado sobre toda
            // la columna y solo se veia recortado el trozo de arriba.
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
            setMeasuredDimension(w, h)
        }

        /**
         * 32% del alto, pero con el 32% de un cristal de proporción real como
         * techo. En un trackpad de 1,62 las dos cifras coinciden; cuando el
         * cristal se estira para llenar la pantalla, la banda mantiene su
         * profundidad en vez de comerse un tercio de toda la superficie, que es
         * también lo que pasa en el hardware. Nunca por debajo del objetivo
         * táctil de 48 dp.
         */
        private fun bandHeight(w: Int, h: Int): Int {
            val real = (w / 1.62f) * 0.32f
            return (h * 0.32f).coerceAtMost(real).toInt().coerceAtLeast(dp(48f))
        }
    }

    /** Zona macro de esquina: no es chapa, es una marca sobre el propio cristal. */
    private fun corner(tag: String): View {
        val idle = Color.argb(8, 255, 255, 255)
        val fondo = GradientDrawable().apply {
            setColor(idle)
            cornerRadius = dp(12f).toFloat()
        }
        return View(ctx).apply {
            background = fondo
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        fondo.setColor(Monet.alpha(Monet.accent, 61))
                        io.haptic()
                        io.macro(tag, true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        fondo.setColor(idle)
                        io.macro(tag, false)
                    }
                }
                true
            }
        }
    }

    private fun hairline(): View = View(ctx).apply { setBackgroundColor(Color.argb(87, 0, 0, 0)) }

    private lateinit var padRef: TouchpadView

    private fun newPadStack(
        tap: Boolean, twoFinger: Boolean, accelHere: Boolean,
        aspect: Float = 0f, band: Boolean = false, corners: Boolean = false
    ): View {
        // el cristal llena la pila entera; quien se ciñe a la relacion es la pila
        padRef = newPad(tap, twoFinger, accelHere)
        return PadStack(band, aspect, corners)
    }

    private fun newPad(tap: Boolean, twoFinger: Boolean, accelHere: Boolean): TouchpadView {
        pad = TouchpadView(ctx).apply {
            acceleration = accelHere
            naturalScroll = natural
            tapToClick = tap
            twoFingerScroll = twoFinger
            sink = this@Deck.sink
            onClick = { b -> io.click(b) }
            onButton = { b, down -> io.button(b, down) }
            onHaptic = { io.haptic() }
            applyPalette()
        }
        applySens()
        return pad
    }

    private fun rail(wheel: Boolean): ScrollStripView = ScrollStripView(ctx, wheel).apply {
        naturalScroll = natural
        onScroll = { dx, dy -> io.scroll(dx, dy) }
        onHaptic = { io.haptic() }
        applyPalette()
        if (wheel) background = plate(ctx, 12)
    }

    /** Pantallas cortas: se sacrifica el cromo, nunca el area de trabajo. */
    private fun short(): Boolean =
        ctx.resources.displayMetrics.heightPixels / ctx.resources.displayMetrics.density < 620f

    fun build(family: Int, tier: Int): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // Gaming apaga la aceleracion a proposito, este como este en los ajustes.
        val usaAccel = accel && tier != 2

        if (family == 0) {
            // --------- raton: botones arriba, rueda al medio, sensor debajo
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val left = key(true, 'l', null)
            val wheel = rail(true)
            val right = key(true, 'r', null)
            top.addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 3f))
            top.addView(wheel, LinearLayout.LayoutParams(dp(40f), MATCH_PARENT).apply {
                leftMargin = dp(7f); rightMargin = dp(7f)
            })
            top.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 3f))
            // En un ratón real los botones cubren ~44% del cuerpo (MX Master: 55 de 125 mm).
            root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, 0, if (short()) 38f else 44f).apply {
                bottomMargin = dp(3f)
            })

            val body = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            if (tier >= 1) body.addView(sideRail(tier, true), LinearLayout.LayoutParams(dp(34f), MATCH_PARENT).apply {
                rightMargin = dp(7f)
            })
            body.addView(newPadStack(tap = tier == 0, twoFinger = tier == 0, accelHere = usaAccel),
                LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            if (tier >= 1) body.addView(sideRail(tier, false), LinearLayout.LayoutParams(dp(34f), MATCH_PARENT).apply {
                leftMargin = dp(7f)
            })
            root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 56f))

            if (tier >= 1) root.addView(pipsRow(if (tier == 2) Monet.accentSoft else Monet.accent),
                LinearLayout.LayoutParams(MATCH_PARENT, dp(24f)).apply { topMargin = dp(6f) })

            if (tier == 2) root.addView(key(false, null, Macro.SNIPER, "cross"),
                LinearLayout.LayoutParams(MATCH_PARENT, 0, if (short()) 9f else 12f).apply {
                    topMargin = dp(7f)
                })

        } else {
            // --------- panel: un trackpad real es 1,62:1 apaisado (130x80 mm)
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                // arriba, no centrado: lo que sobra tiene que quedar DEBAJO del
                // cristal, que es el reposamuñecas del portatil
                gravity = android.view.Gravity.TOP
            }
            // El cristal se lleva la columna entera. La relacion 1,62 es el SUELO
            // —un trackpad nunca es mas apaisado que eso— pero no un techo: en un
            // movil alto, reservar el reposamuñecas dejaba el 60% de la pantalla
            // muerta, y aqui el cristal ocupa todo el aparato como en un trackpad
            // externo, no como el hueco recortado de un portatil.
            body.addView(
                newPadStack(tap = true, twoFinger = true, accelHere = usaAccel,
                    aspect = 0f, band = tier >= 1, corners = tier == 2),
                LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            )
            if (tier >= 1) body.addView(rail(false), LinearLayout.LayoutParams(dp(36f), MATCH_PARENT).apply {
                leftMargin = dp(7f)
            })
            root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 100f))

            if (tier == 2) root.addView(pipsRow(Monet.accentSoft),
                LinearLayout.LayoutParams(MATCH_PARENT, dp(24f)).apply { topMargin = dp(6f) })
        }
        return root
    }

    /** Rieles laterales: atras/adelante y, en gaming, dos macros. */
    private fun sideRail(tier: Int, leftSide: Boolean): View {
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        c.addView(
            key(false, null, if (leftSide) Macro.BACK else Macro.FWD, if (leftSide) "up" else "down"),
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        )
        if (tier == 2) {
            c.addView(
                key(false, null, if (leftSide) Macro.G1 else Macro.G2, if (leftSide) "dot1" else "dot2"),
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(7f) }
            )
        }
        return c
    }
}
