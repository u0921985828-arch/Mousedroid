package com.eddie.usbmouse

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renderiza la interfaz de verdad —las mismas clases que van al APK— en un
 * bitmap y lo deja en PNG. No es un emulador, pero dibuja con el Skia real:
 * es la unica forma de MIRAR esto sin un movil delante.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Shot {

    private class Nulo : DeckIO {
        override fun move(dx: Float, dy: Float) {}
        override fun scroll(dx: Float, dy: Float) {}
        override fun click(b: Char) {}
        override fun button(b: Char, down: Boolean) {}
        override fun macro(tag: String, down: Boolean) {}
        override fun text(s: String) {}
        override fun key(name: String) {}
        override fun combo(mods: String, name: String) {}
        override fun haptic(fuerte: Boolean) {}
        override fun note(s: String) {}
    }

    @Test
    fun retratos() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        Monet.load(act)
        val d = act.resources.displayMetrics.density
        val w = (411 * d).toInt()
        val h = (891 * d).toInt()
        val salida = File(System.getProperty("shot.out") ?: "/tmp/shot")
        salida.mkdirs()

        val deck = Deck(act, Nulo())
        for (family in 0..1) {
            for (tier in 0..2) {
                val raiz = FrameLayout(act)
                raiz.background = MetalDrawable(Monet.deckHi, Monet.deckLo, 0f, brushAlpha = 22)
                val col = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((11 * d).toInt(), (10 * d).toInt(),
                               (11 * d).toInt(), (14 * d).toInt())
                }
                // cabecera de mentira, del mismo alto que la de verdad
                col.addView(View(act).apply { background = plate(act, 8) },
                    LinearLayout.LayoutParams(MATCH_PARENT, (30 * d).toInt()))
                val host = FrameLayout(act)
                col.addView(host, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                    topMargin = (9 * d).toInt()
                })
                host.addView(deck.build(family, tier),
                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                raiz.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

                raiz.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                raiz.layout(0, 0, w, h)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                raiz.draw(Canvas(bmp))
                val nombre = (if (family == 0) "raton" else "panel") + "-" +
                    arrayOf("basico", "premium", "gaming")[tier]
                File(salida, "$nombre.png").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                println("RETRATO $nombre ${w}x${h}")
                volcarCotas(nombre, host)
            }
        }
    }

    /**
     * La Activity de verdad, con su cabecera: LED, lector en su hueco, glifos y
     * escalones. Es la parte que mas he cambiado y la que menos se ha mirado.
     */
    @Test
    fun cabecera() {
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = ctrl.get()
        // El contenido, no el decorView: sin el manifiesto de verdad Robolectric
        // pone un tema con barra de titulo que la app no tiene.
        val raiz = (act.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0)
        val d = act.resources.displayMetrics.density
        val w = (411 * d).toInt()
        val h = (891 * d).toInt()
        raiz.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        raiz.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        raiz.draw(Canvas(bmp))
        val salida = File(System.getProperty("shot.out") ?: "/tmp/shot")
        salida.mkdirs()
        File(salida, "activity.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("RETRATO activity ${w}x${h}")

        // Y con las hojas de abajo abiertas: ajustes y teclado, que viven ocultas
        // y nunca se han mirado. Las referencias se cogen UNA vez: escondiendo
        // "todos los LinearLayout" entre pasada y pasada se apagaba tambien la
        // columna principal y la segunda foto salia sin hoja.
        val fondo = raiz as android.view.ViewGroup
        val hojas = ArrayList<View>()
        for (i in 0 until fondo.childCount) {
            val c = fondo.getChildAt(i)
            if (c is LinearLayout && c.visibility == View.GONE) hojas.add(c)
        }
        val nombres = listOf("teclado", "ajustes")   // en el orden en que se anaden
        for ((k, hoja) in hojas.withIndex()) {
            hojas.forEach { it.visibility = View.GONE }
            hoja.visibility = View.VISIBLE
            hoja.translationY = 0f
            raiz.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            raiz.layout(0, 0, w, h)
            val b2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            raiz.draw(Canvas(b2))
            val n = nombres.getOrElse(k) { "hoja$k" }
            File(salida, "$n.png").outputStream().use {
                b2.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            println("RETRATO $n ${w}x${h}")
        }
    }


    /** Las cotas de cada pieza, para comprobar numeros y no solo el ojo. */
    private fun volcarCotas(nombre: String, v: View, prof: Int = 0) {
        val d = v.resources.displayMetrics.density
        val etiqueta = v.javaClass.simpleName
        println("COTA $nombre ${"  ".repeat(prof)}$etiqueta " +
            "${(v.width / d).toInt()}x${(v.height / d).toInt()}dp @${(v.top / d).toInt()}")
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) volcarCotas(nombre, v.getChildAt(i), prof + 1)
        }
    }
}
