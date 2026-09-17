package com.eddie.usbmouse

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Campo invisible que se come lo que teclea el usuario y lo reenvia al PC.
 *
 * Se envuelve la InputConnection en vez de escuchar el texto porque es el unico
 * sitio donde el retroceso llega siempre: segun el teclado, borrar llega como
 * KEYCODE_DEL, como deleteSurroundingText o como una composicion que encoge.
 * El campo guarda de verdad lo escrito (no se vacia sobre la marcha) para que el
 * teclado siempre tenga algo que borrar y mande el evento.
 */
private class CaptureField(ctx: Context, private val io: DeckIO) : EditText(ctx) {

    init {
        // Sin sugerencias ni autocorreccion: asi cada tecla llega como un
        // commitText suelto y no hay que adivinar la region de composicion.
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setBackgroundColor(Color.TRANSPARENT)
        setTextColor(Color.TRANSPARENT)
        setPadding(0, 0, 0, 0)
        isCursorVisible = false
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return Relay(target)
    }

    private inner class Relay(target: InputConnection) : InputConnectionWrapper(target, false) {

        /** Lo que el teclado lleva escrito sin confirmar; se reenvia por diferencias. */
        private var composing = ""

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            swap(text?.toString() ?: "")
            composing = ""
            return super.commitText(text, newCursorPosition)
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            swap(text?.toString() ?: "")
            return super.setComposingText(text, newCursorPosition)
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return super.finishComposingText()
        }

        override fun deleteSurroundingText(before: Int, after: Int): Boolean {
            repeat(before) { io.key("back") }
            repeat(after) { io.key("del") }
            return super.deleteSurroundingText(before, after)
        }

        override fun deleteSurroundingTextInCodePoints(before: Int, after: Int): Boolean {
            repeat(before) { io.key("back") }
            repeat(after) { io.key("del") }
            return super.deleteSurroundingTextInCodePoints(before, after)
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                val name = when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> "back"
                    KeyEvent.KEYCODE_FORWARD_DEL -> "del"
                    KeyEvent.KEYCODE_ENTER -> "enter"
                    KeyEvent.KEYCODE_TAB -> "tab"
                    KeyEvent.KEYCODE_ESCAPE -> "esc"
                    KeyEvent.KEYCODE_DPAD_UP -> "up"
                    KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                    KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                    KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                    else -> null
                }
                if (name != null) {
                    io.key(name)
                } else {
                    val u = event.unicodeChar
                    if (u != 0) io.text(String(Character.toChars(u)))
                }
            }
            return super.sendKeyEvent(event)
        }

        /**
         * Reemplaza la composicion pendiente por [now]: retrocede lo que ya no
         * coincide y escribe lo que ha crecido. Con el teclado de deslizar la
         * palabra se rehace entera, y asi no se duplica al confirmarla.
         */
        private fun swap(now: String) {
            var common = 0
            val n = minOf(composing.length, now.length)
            while (common < n && composing[common] == now[common]) common++
            repeat(composing.length - common) { io.key("back") }
            if (now.length > common) io.text(now.substring(common))
            composing = now
        }
    }
}

/**
 * Capa de teclado. No dibuja letras a proposito: de eso ya se encarga el teclado
 * del sistema, que el usuario tiene en su idioma y con su diccionario. Aqui solo
 * estan las teclas que un teclado de movil no da (esc, tabulador, flechas) y los
 * atajos que en el PC se hacen a dos manos.
 */
class KeyDeck(private val ctx: Context, private val io: DeckIO) {

    private val field = CaptureField(ctx, io)
    private var sheet: LinearLayout? = null

    private fun dp(v: Float) = dpOf(ctx, v)

    // Estado propio y no `sheet.visibility`: al cerrar, GONE no llega hasta que
    // termina la animacion, y hasta entonces el estado se leeria al reves.
    var isOpen = false
        private set

    fun build(): View {
        val s = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = plate(ctx, 18, topOnly = true)
            setPadding(dp(11f), dp(12f), dp(11f), dp(14f))
            visibility = View.GONE
        }
        s.addView(row(
            cap("esc") { io.key("esc") },
            cap("tab") { io.key("tab") },
            cap("⌫", repeat = true) { io.key("back") },
            cap("⏎") { io.key("enter") }
        ), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        s.addView(row(
            cap("←", repeat = true) { io.key("left") },
            cap("↑", repeat = true) { io.key("up") },
            cap("↓", repeat = true) { io.key("down") },
            cap("→", repeat = true) { io.key("right") }
        ), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(7f) })
        s.addView(row(
            cap("copiar") { io.combo("c", "c") },
            cap("pegar") { io.combo("c", "v") },
            cap("deshacer") { io.combo("c", "z") }
        ), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(7f) })
        // 1 px y transparente: esta en el arbol solo para tener el foco del teclado
        s.addView(field, LinearLayout.LayoutParams(1, 1))
        sheet = s
        return s
    }

    fun open() {
        val s = sheet ?: return
        if (isOpen) return
        isOpen = true
        s.animate().cancel()
        s.visibility = View.VISIBLE
        s.translationY = s.height.toFloat().coerceAtLeast(dp(220f).toFloat())
        s.animate().translationY(0f).setDuration(180).start()
        field.requestFocus()
        imm()?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    fun close() {
        val s = sheet ?: return
        if (!isOpen) return
        isOpen = false
        imm()?.hideSoftInputFromWindow(field.windowToken, 0)
        field.setText("")
        field.clearFocus()
        s.animate().cancel()
        s.animate().translationY(s.height.toFloat()).setDuration(150)
            .withEndAction { s.visibility = View.GONE }.start()
    }

    private fun imm() =
        ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun row(vararg keys: View): LinearLayout {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        keys.forEachIndexed { i, k ->
            r.addView(k, LinearLayout.LayoutParams(0, dp(48f), 1f).apply {
                if (i > 0) leftMargin = dp(7f)
            })
        }
        return r
    }

    /** Tecla de chapa. [repeat] la hace repetir mientras se mantiene, como un teclado real. */
    private fun cap(label: String, repeat: Boolean = false, onTap: () -> Unit): View {
        val v = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Monet.etch)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = plate(ctx, 11)
        }
        val tick = object : Runnable {
            override fun run() {
                onTap()
                v.postDelayed(this, 45L)
            }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    (view.background as? MetalDrawable)?.sink(true)
                    io.haptic()
                    onTap()
                    if (repeat) view.postDelayed(tick, 350L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (view.background as? MetalDrawable)?.sink(false)
                    if (repeat) view.removeCallbacks(tick)
                }
            }
            true
        }
        return v
    }
}
