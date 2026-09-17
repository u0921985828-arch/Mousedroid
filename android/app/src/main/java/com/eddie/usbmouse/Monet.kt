package com.eddie.usbmouse

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import kotlin.math.max
import kotlin.math.min

/**
 * Color dinamico al estilo Material You, sin dependencias.
 *
 *  - Android 12+: usa directamente las rampas del sistema (`system_neutral1_*`,
 *    `system_accent1_*`), que ya son las que Android deriva del fondo de pantalla
 *    con HCT. Es la fuente buena: misma paleta que el resto del sistema.
 *  - Android 7..11: no existen esas rampas, asi que extrae un color semilla del
 *    fondo de pantalla (muestreo 24x24 + cuantizacion por cubos) y genera los
 *    tonos en HSL. Es una aproximacion: HSL no es perceptualmente uniforme como
 *    HCT, pero mantiene el tono y da una rampa coherente.
 *
 * Todo se resuelve una vez y se cachea: nada de esto toca el bucle de dibujo.
 */
object Monet {

    // chasis (neutro, poco cromatico)
    var deckHi = 0; private set
    var deck = 0; private set
    var deckLo = 0; private set
    // piezas embutidas
    var plateHi = 0; private set
    var plateLo = 0; private set
    // cristal
    var glassHi = 0; private set
    var glassLo = 0; private set
    // señales
    var etch = 0; private set
    var etchDim = 0; private set
    var accent = 0; private set
    var accentSoft = 0; private set

    private var ready = false

    fun load(ctx: Context) {
        if (ready) return
        ready = true
        val semilla =
            (if (Build.VERSION.SDK_INT >= 31) systemSeed(ctx) else wallpaperSeed(ctx))
                ?: Color.parseColor("#5C7A9E")
        val hsl = FloatArray(3)
        rgbToHsl(semilla, hsl)
        ramp(hsl[0])
    }

    /** Fuerza una recarga, por ejemplo al volver de segundo plano. */
    fun refresh(ctx: Context) {
        ready = false
        load(ctx)
    }

    /**
     * Del sistema se toma el TONO y nada mas.
     *
     * Antes se cogian sus tonos enteros, y ahi `system_neutral1_900` (chapa) y
     * `system_neutral2_900` (cristal) son los dos el tono 10 de la rampa: salian
     * separados por 2 de 255. En pantalla eso es cero: la banda de clic
     * desaparecia dentro del cristal y el aparato se leia como una plancha lisa.
     * Las cotas documentadas solo se cumplian en Android 7..11.
     */
    private fun systemSeed(ctx: Context): Int? = try {
        ctx.resources.getColor(android.R.color.system_accent1_500, ctx.theme)
    } catch (_: Throwable) {
        null
    }

    /**
     * La rampa, unica para todas las versiones: mismo tono, croma muy bajo y los
     * escalones de luminosidad de `usb-mouse-proporciones.html`. Que la separacion
     * entre deck, chapa y cristal sea SIEMPRE la misma es justo lo que hace que
     * las piezas se distingan en cualquier movil.
     */
    private fun ramp(h: Float) {
        deckHi = hsl(h, 0.05f, 0.31f)
        deck = hsl(h, 0.05f, 0.28f)
        deckLo = hsl(h, 0.05f, 0.24f)
        plateHi = hsl(h, 0.07f, 0.17f)
        plateLo = hsl(h, 0.07f, 0.14f)
        glassHi = hsl(h, 0.09f, 0.115f)
        glassLo = hsl(h, 0.09f, 0.085f)
        etch = hsl(h, 0.08f, 0.68f)
        etchDim = hsl(h, 0.07f, 0.48f)
        accent = hsl(h, 0.55f, 0.72f)
        accentSoft = hsl(h, 0.45f, 0.58f)
    }

    /** Muestrea el fondo de pantalla a 24x24 y se queda con el cubo de color mas poblado. */
    private fun wallpaperSeed(ctx: Context): Int? {
        return try {
            val wm = WallpaperManager.getInstance(ctx)
            val d = wm.drawable ?: return null
            val small = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            val cv = Canvas(small)
            if (d is BitmapDrawable && d.bitmap != null) {
                cv.drawBitmap(d.bitmap, null, android.graphics.Rect(0, 0, 24, 24), null)
            } else {
                d.setBounds(0, 0, 24, 24)
                d.draw(cv)
            }
            val buckets = HashMap<Int, Int>()
            val hsl = FloatArray(3)
            for (y in 0 until 24) for (x in 0 until 24) {
                val p = small.getPixel(x, y)
                rgbToHsl(p, hsl)
                // descarta grises y extremos: no sirven de semilla
                if (hsl[1] < 0.18f || hsl[2] < 0.12f || hsl[2] > 0.9f) continue
                val key = ((hsl[0] / 15f).toInt())
                buckets[key] = (buckets[key] ?: 0) + 1
            }
            small.recycle()
            val best = buckets.maxByOrNull { it.value }?.key ?: return null
            hsl(best * 15f + 7.5f, 0.5f, 0.5f)
        } catch (_: Throwable) {
            null   // sin permiso de fondo de pantalla en algunas ROMs
        }
    }

    // ---------------------------------------------------------------- color

    private fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        var r = 0f; var g = 0f; var b = 0f
        when (hp.toInt()) {
            0 -> { r = c; g = x }
            1 -> { r = x; g = c }
            2 -> { g = c; b = x }
            3 -> { g = x; b = c }
            4 -> { r = x; b = c }
            else -> { r = c; b = x }
        }
        val m = l - c / 2f
        return Color.rgb(
            ((r + m) * 255f).toInt().coerceIn(0, 255),
            ((g + m) * 255f).toInt().coerceIn(0, 255),
            ((b + m) * 255f).toInt().coerceIn(0, 255)
        )
    }

    private fun rgbToHsl(color: Int, out: FloatArray) {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        out[2] = (mx + mn) / 2f
        if (d < 1e-4f) { out[0] = 0f; out[1] = 0f; return }
        out[1] = d / (1f - kotlin.math.abs(2f * out[2] - 1f))
        out[0] = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (out[0] < 0f) out[0] += 360f
    }

    fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    fun alpha(color: Int, a: Int): Int =
        Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    /** [k] 0 devuelve [a], 1 devuelve [b]. Para teñir un grabado con la luz de la lampara. */
    fun mix(a: Int, b: Int, k: Float): Int {
        val t = k.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        )
    }
}
