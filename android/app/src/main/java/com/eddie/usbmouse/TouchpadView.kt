package com.eddie.usbmouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Cristal hundido. Ruta caliente: aqui NO se hace ninguna asignacion ni
 * `Color.parseColor` por fotograma, y el dedo no provoca invalidaciones de esta
 * vista — el halo vive en [HaloView], que se mueve con translationX/Y.
 */
class TouchpadView(context: Context) : View(context) {

    var onMove: ((Float, Float) -> Unit)? = null
    var onScroll: ((Float, Float) -> Unit)? = null
    var onClick: ((Char) -> Unit)? = null
    var onButton: ((Char, Boolean) -> Unit)? = null
    var onHaptic: (() -> Unit)? = null
    /** x, y, visible. Lo consume el halo; no redibuja el cristal. */
    var onTouchPoint: ((Float, Float, Boolean) -> Unit)? = null

    var sensitivity = 1.8f
    var acceleration = true
    var naturalScroll = false
    var tapToClick = true
    var twoFingerScroll = true
    /** Si es > 0, la vista conserva esa relación ancho/alto (1.62 = trackpad real). */
    var aspect = 0f

    private val d = resources.displayMetrics.density
    private val tapMs = 190L
    private val doubleTapMs = 280L
    private val longPressMs = 450L
    private val slop = 14f * d
    private val scrollStep = 18f * d
    private val radius = 14f * d

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var maxPointers = 1
    private var scrolling = false
    private var dragging = false
    private var lastTapUp = 0L
    private var accX = 0f
    private var accY = 0f

    private val longPress = Runnable {
        if (!moved && !scrolling && !dragging) {
            dragging = true
            onButton?.invoke('l', true)
            onHaptic?.invoke()
            invalidate()
        }
    }

    // ---------------------------------------------------------------- pintura

    private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = Textures.shader(Textures.grain())
        alpha = 20
    }
    private val bevel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(24, 255, 255, 255) }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)

    // colores resueltos una sola vez
    private var cInnerIdle = Color.argb(89, 0, 0, 0)
    private var cInnerDrag = Color.argb(150, 120, 160, 255)
    private val face = RectF()

    fun applyPalette() {
        cInnerDrag = Monet.alpha(Monet.accent, 150)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        if (aspect > 0f) {
            val w = measuredWidth
            val h = (w / aspect).toInt().coerceAtMost(measuredHeight)
            setMeasuredDimension(w, h)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        glass.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            Monet.glassLo, Monet.glassHi, Shader.TileMode.CLAMP)
        // reflejo diagonal suave: el plano deja de leerse como un rectangulo pintado
        sheen.shader = LinearGradient(0f, 0f, w * 0.8f, h.toFloat(),
            intArrayOf(Color.argb(16, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.55f), Shader.TileMode.CLAMP)
        face.set(0f, 1f * d, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, bevel)
        canvas.drawRoundRect(face, radius, radius, glass)
        canvas.drawRoundRect(face, radius, radius, grain)
        canvas.drawRoundRect(face, radius, radius, sheen)
        inner.color = if (dragging) cInnerDrag else cInnerIdle
        canvas.drawRoundRect(
            face.left + 0.5f * d, face.top + 0.5f * d,
            face.right - 0.5f * d, face.bottom - 0.5f * d,
            radius, radius, inner
        )
    }

    // ---------------------------------------------------------------- gestos

    private fun focalX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    private fun focalY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                onTouchPoint?.invoke(e.x, e.y, true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                onTouchPoint?.invoke(e.x, e.y, false)
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                downTime = System.currentTimeMillis()
                moved = false
                maxPointers = 1
                scrolling = false
                accX = 0f; accY = 0f
                if (tapToClick && downTime - lastTapUp < doubleTapMs) {
                    dragging = true
                    onButton?.invoke('l', true)
                    invalidate()
                } else {
                    postDelayed(longPress, longPressMs)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                maxPointers = maxOf(maxPointers, e.pointerCount)
                if (e.pointerCount >= 2 && twoFingerScroll) {
                    if (dragging) {
                        dragging = false
                        onButton?.invoke('l', false)
                        invalidate()
                    }
                    scrolling = true
                    accX = 0f; accY = 0f
                    lastX = focalX(e); lastY = focalY(e)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (scrolling) {
                    val fx = focalX(e)
                    val fy = focalY(e)
                    accX += fx - lastX
                    accY += fy - lastY
                    lastX = fx; lastY = fy
                    val nx = (accX / scrollStep).toInt()
                    val ny = (accY / scrollStep).toInt()
                    if (nx != 0 || ny != 0) {
                        accX -= nx * scrollStep
                        accY -= ny * scrollStep
                        val sign = if (naturalScroll) 1f else -1f
                        onScroll?.invoke(sign * nx.toFloat(), sign * ny.toFloat())
                        moved = true
                    }
                } else {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x; lastY = e.y
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                        moved = true
                        removeCallbacks(longPress)
                    }
                    if (moved || dragging) {
                        val dist = hypot(dx, dy)
                        val f = sensitivity * if (acceleration) 1f + min(dist / 9f, 2.2f) else 1f
                        if (abs(dx) > 0.01f || abs(dy) > 0.01f) onMove?.invoke(dx * f, dy * f)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount == 2) {
                    val idx = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(idx); lastY = e.getY(idx)
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val dur = System.currentTimeMillis() - downTime
                if (dragging) {
                    dragging = false
                    onButton?.invoke('l', false)
                    invalidate()
                } else if (!moved && dur < tapMs && tapToClick) {
                    when (maxPointers) {
                        1 -> { onClick?.invoke('l'); lastTapUp = System.currentTimeMillis() }
                        2 -> onClick?.invoke('r')
                        else -> onClick?.invoke('m')
                    }
                    onHaptic?.invoke()
                }
                scrolling = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) {
                    dragging = false
                    onButton?.invoke('l', false)
                    invalidate()
                }
                scrolling = false
            }
        }
        return true
    }
}

/**
 * Reflejo bajo el dedo. Vista propia de tamaño fijo: se mueve con translation,
 * asi que no invalida nada, solo se recompone la capa.
 */
class HaloView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    val size = (150f * d).toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        alpha = 0f
        p.shader = RadialGradient(
            size / 2f, size / 2f, size / 2f,
            intArrayOf(Color.argb(42, 206, 222, 244), Color.argb(0, 206, 222, 244)),
            floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP
        )
    }

    fun show(x: Float, y: Float, visible: Boolean) {
        translationX = x - size / 2f
        translationY = y - size / 2f
        if (visible) {
            if (alpha < 1f) { animate().cancel(); alpha = 1f }
        } else if (alpha > 0f) {
            animate().alpha(0f).setDuration(180).start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, p)
    }
}

/** Raíl de scroll: liso para el panel, estriado cuando hace de rueda. */
class ScrollStripView(context: Context, private val wheel: Boolean = false) : View(context) {

    var onScroll: ((Float, Float) -> Unit)? = null
    var onHaptic: (() -> Unit)? = null
    var naturalScroll = false

    private val d = resources.displayMetrics.density
    private val step = 16f * d
    private var lastY = 0f
    private var acc = 0f
    private var active = false

    private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = Textures.shader(Textures.grain())
        alpha = 18
    }
    private val bevel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(24, 255, 255, 255) }
    private val rail = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cRailIdle = Color.parseColor("#3A4149")
    private var cRailOn = Color.parseColor("#8FB4FF")
    private val face = RectF()

    fun applyPalette() {
        cRailIdle = Monet.etchDim
        cRailOn = Monet.accent
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        if (aspect > 0f) {
            val w = measuredWidth
            val h = (w / aspect).toInt().coerceAtMost(measuredHeight)
            setMeasuredDimension(w, h)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        glass.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            Monet.glassLo, Monet.glassHi, Shader.TileMode.CLAMP)
        face.set(0f, 1f * d, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val r = 14f * d
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bevel)
        canvas.drawRoundRect(face, r, r, glass)
        canvas.drawRoundRect(face, r, r, grain)
        rail.color = if (active) cRailOn else cRailIdle
        val cx = width / 2f
        if (wheel) {
            val w = 15f * d
            var y = height / 2f - 10f * d
            repeat(5) {
                canvas.drawRect(cx - w / 2f, y, cx + w / 2f, y + 1f * d, rail)
                y += 5f * d
            }
        } else {
            val h = height * 0.15f
            canvas.drawRoundRect(cx - 1.5f * d, height / 2f - h / 2f,
                cx + 1.5f * d, height / 2f + h / 2f, 2f * d, 2f * d, rail)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastY = e.y; acc = 0f
                active = true; invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                acc += e.y - lastY
                lastY = e.y
                val n = (acc / step).toInt()
                if (n != 0) {
                    acc -= n * step
                    onScroll?.invoke(0f, (if (naturalScroll) 1f else -1f) * n.toFloat())
                    if (wheel) onHaptic?.invoke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false; invalidate()
            }
        }
        return true
    }
}
