package com.eddie.usbmouse

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.random.Random

/**
 * Texturas procedurales. Todo se genera una vez en un bitmap pequeño y se repite
 * con un shader, asi que el coste por fotograma es el de una capa mas en GPU:
 * no hay bucles de pixeles en el dibujado.
 */
object Textures {

    private var brushed: Bitmap? = null
    private var grain: Bitmap? = null

    /** Aluminio cepillado: ruido muy estirado en horizontal. */
    fun brushed(): Bitmap = brushed ?: make(160, 160) { bmp, rnd ->
        val px = IntArray(160 * 160)
        // una fila de ruido se arrastra en horizontal con pequeñas variaciones:
        // eso es lo que produce la veta anisotropica del metal cepillado
        for (y in 0 until 160) {
            var v = rnd.nextInt(-14, 15)
            for (x in 0 until 160) {
                v = (v * 3 + rnd.nextInt(-16, 17)) / 4
                val a = (v.coerceIn(-20, 20) + 20) * 255 / 40
                px[y * 160 + x] = Color.argb(a, 255, 255, 255)
            }
        }
        bmp.setPixels(px, 0, 160, 0, 0, 160, 160)
    }.also { brushed = it }

    /** Grano fino para el cristal: ruido isotropico, casi invisible pero rompe el plano. */
    fun grain(): Bitmap = grain ?: make(96, 96) { bmp, rnd ->
        val px = IntArray(96 * 96)
        for (i in px.indices) {
            val a = rnd.nextInt(0, 26)
            px[i] = Color.argb(a, 255, 255, 255)
        }
        bmp.setPixels(px, 0, 96, 0, 0, 96, 96)
    }.also { grain = it }

    private inline fun make(w: Int, h: Int, fill: (Bitmap, Random) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fill(b, Random(7))
        return b
    }

    fun shader(bmp: Bitmap) = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
}

/**
 * Chapa metalica: degradado base + veta cepillada + filo claro arriba.
 * Sustituye al LayerDrawable de degradados planos.
 */
class MetalDrawable(
    private val top: Int,
    private val bottom: Int,
    private val radius: Float,
    private val topOnly: Boolean = false,
    private val brushAlpha: Int = 16,
    /**
     * Radio por vertice, en el orden de Path: arriba-izq, arriba-der, abajo-der,
     * abajo-izq (dos floats cada uno). Null = los cuatro a [radius].
     *
     * Hace falta porque una pieza metida dentro de otra tiene que **anidar**: si
     * la banda de clic lleva 11 en las cuatro esquinas y el cristal 14 en las
     * suyas, las dos curvas no encajan y la banda deja de leerse como el tercio
     * bajo del mismo cristal para parecer tres pastillas ahi sueltas.
     */
    corners: FloatArray? = null
) : Drawable() {

    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val veta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = Textures.shader(Textures.brushed())
        alpha = brushAlpha
    }
    private val bevel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(26, 255, 255, 255) }
    private val rect = RectF()

    private var suelto: LinearGradient? = null
    private var pulsado: LinearGradient? = null
    private var hundido = false

    // Los caminos se arman en onBoundsChange, nunca por fotograma.
    private val radii = FloatArray(8).also { r ->
        when {
            corners != null -> corners.copyInto(r)
            topOnly -> { r[0] = radius; r[1] = radius; r[2] = radius; r[3] = radius }
            else -> java.util.Arrays.fill(r, radius)
        }
    }
    private val pFuera = Path()
    private val pSuelto = Path()
    private val pPulsado = Path()

    /**
     * Pulsar una tecla de metal no la vuelve translucida: le da la vuelta a la
     * luz. El filo claro se va de arriba a abajo y el degradado se invierte y se
     * oscurece, que es lo que hace el ojo leer "esto se ha metido para dentro".
     * Antes esto era `view.alpha = 0.72f`, que es lo que hace una app.
     */
    fun sink(down: Boolean) {
        if (hundido == down) return
        hundido = down
        invalidateSelf()
    }

    override fun onBoundsChange(b: android.graphics.Rect) {
        val y0 = b.top.toFloat()
        val y1 = b.bottom.toFloat()
        suelto = LinearGradient(0f, y0, 0f, y1, top, bottom, Shader.TileMode.CLAMP)
        pulsado = LinearGradient(0f, y0, 0f, y1,
            Monet.shade(bottom, 0.88f), Monet.shade(top, 0.88f), Shader.TileMode.CLAMP)

        val l = b.left.toFloat(); val r = b.right.toFloat()
        arma(pFuera, l, y0, r, y1)
        arma(pSuelto, l, y0 + 1f, r, y1)     // filo de luz arriba
        arma(pPulsado, l, y0, r, y1 - 1f)    // y abajo cuando se hunde
    }

    private fun arma(p: Path, l: Float, t: Float, r: Float, b: Float) {
        p.reset()
        rect.set(l, t, r, b)
        p.addRoundRect(rect, radii, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(pFuera, bevel)
        base.shader = if (hundido) pulsado else suelto
        canvas.drawPath(if (hundido) pPulsado else pSuelto, base)
        canvas.drawPath(if (hundido) pPulsado else pSuelto, veta)
    }

    override fun setAlpha(alpha: Int) { base.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { base.colorFilter = cf }
    @Deprecated("deprecated en API 29, pero sigue siendo obligatorio implementarlo")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Ventana de instrumento: un hueco abierto en la chapa, no una etiqueta.
 *
 * El bisel va justo al reves que el de [MetalDrawable] —el filo de luz al pie y
 * no en la coronilla— porque es lo unico que distingue "hundido" de "montado":
 * en un hueco la luz de arriba cae dentro y encharca el borde de abajo. El
 * degradado hace lo mismo, oscuro arriba y menos oscuro abajo.
 *
 * Encima lleva una veladura del color de la lampara. Un lector de verdad no es
 * negro: el fosforo recoge algo de la luz del propio aparato, y es lo que hace
 * que el texto parezca emitido y no impreso.
 */
class WindowDrawable(private val radius: Float) : Drawable() {

    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val filo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(30, 255, 255, 255) }
    private val fosforo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
    private val rect = RectF()

    private val arriba = Monet.shade(Monet.plateLo, 0.52f)
    private val abajo = Monet.shade(Monet.plateLo, 0.80f)

    /** Color de la lampara. Se resuelve a Int fuera de [draw]; aqui no se parsea nada. */
    var tinte: Int = Color.TRANSPARENT
        set(v) {
            if (field == v) return
            field = v
            // Alfa 10 y no 24: con 24 el ambar de "buscando" dejaba el hueco en
            // (41,38,31) —marron— dentro de una chapa fria de (42,45,47), y
            // encima igual de claro que ella, asi que ni se leia como hundido.
            fosforo.color = if (v == Color.TRANSPARENT) v else Monet.alpha(v, 10)
            invalidateSelf()
        }

    override fun onBoundsChange(b: android.graphics.Rect) {
        base.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(), arriba, abajo, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        canvas.drawRoundRect(rect, radius, radius, filo)
        rect.bottom -= 1f
        canvas.drawRoundRect(rect, radius, radius, base)
        if (fosforo.color != Color.TRANSPARENT) {
            canvas.drawRoundRect(rect, radius, radius, fosforo)
        }
    }

    override fun setAlpha(alpha: Int) { base.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { base.colorFilter = cf }
    @Deprecated("deprecated en API 29, pero sigue siendo obligatorio implementarlo")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
