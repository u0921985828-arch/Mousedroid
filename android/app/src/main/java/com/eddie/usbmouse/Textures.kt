package com.eddie.usbmouse

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
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
    private val brushAlpha: Int = 16
) : Drawable() {

    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val veta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = Textures.shader(Textures.brushed())
        alpha = brushAlpha
    }
    private val bevel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(26, 255, 255, 255) }
    private val rect = RectF()

    override fun onBoundsChange(b: android.graphics.Rect) {
        base.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            top, bottom, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val r = if (topOnly) radius else radius
        canvas.drawRoundRect(rect, r, r, bevel)
        rect.top += 1f
        canvas.drawRoundRect(rect, r, r, base)
        canvas.drawRoundRect(rect, r, r, veta)
    }

    override fun setAlpha(alpha: Int) { base.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { base.colorFilter = cf }
    @Deprecated("deprecated en API 29, pero sigue siendo obligatorio implementarlo")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
