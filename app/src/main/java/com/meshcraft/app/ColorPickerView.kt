package com.meshcraft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * Selector de color: un cuadrado de saturacion/brillo arriba y una barra de tono (hue) abajo. Cada vez que el usuario
 * mueve el dedo llama a onColor con el color RGB resultante.
 */
class ColorPickerView(context: Context, private val onColor: (Int, Int, Int) -> Unit) : View(context) {

    private val density = resources.displayMetrics.density
    private val hsv = floatArrayOf(0f, 0.8f, 0.9f)
    private val svRect = RectF()
    private val hueRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corner = 8f * density
    private val gap = 12f * density
    private val hueHeight = 28f * density
    // 0 = ninguno, 1 = arrastrando el cuadrado de saturacion/brillo, 2 = arrastrando la barra de tono.
    private var dragging = 0

    /** Fija el color mostrado (sin avisar a onColor). Si el color es gris/negro conserva el tono anterior en vez de saltar a rojo. */
    fun setColor(r: Int, g: Int, b: Int) {
        val t = FloatArray(3)
        Color.RGBToHSV(r, g, b, t)
        if (t[1] > 0.001f && t[2] > 0.001f) hsv[0] = t[0]
        hsv[1] = t[1]
        hsv[2] = t[2]
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        svRect.set(0f, 0f, w.toFloat(), h - hueHeight - gap)
        hueRect.set(0f, h - hueHeight, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        paint.style = Paint.Style.FILL
        // Cuadrado: de blanco a tono puro (izquierda a derecha) y de transparente a negro (arriba a abajo).
        paint.shader = LinearGradient(svRect.left, 0f, svRect.right, 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(svRect, corner, corner, paint)
        paint.shader = LinearGradient(0f, svRect.top, 0f, svRect.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(svRect, corner, corner, paint)
        // Barra de tono: arcoiris.
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        paint.shader = LinearGradient(hueRect.left, 0f, hueRect.right, 0f, hues, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(hueRect, corner, corner, paint)
        paint.shader = null

        // Marcadores.
        paint.style = Paint.Style.STROKE
        val cx = svRect.left + hsv[1] * svRect.width()
        val cy = svRect.top + (1f - hsv[2]) * svRect.height()
        paint.strokeWidth = 3f * density
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 9f * density, paint)
        paint.strokeWidth = 1.2f * density
        paint.color = Color.BLACK
        canvas.drawCircle(cx, cy, 11f * density, paint)

        val hx = hueRect.left + hsv[0] / 360f * hueRect.width()
        paint.strokeWidth = 3f * density
        paint.color = Color.WHITE
        canvas.drawRoundRect(hx - 4f * density, hueRect.top - 2f * density, hx + 4f * density, hueRect.bottom + 2f * density, 4f * density, 4f * density, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = if (e.y <= svRect.bottom + gap / 2f) 1 else 2
                updateFromTouch(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = 0
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun updateFromTouch(x: Float, y: Float) {
        if (dragging == 1) {
            hsv[1] = ((x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
            hsv[2] = 1f - ((y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
        } else if (dragging == 2) {
            hsv[0] = ((x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f) * 359.99f
        } else {
            return
        }
        invalidate()
        val c = Color.HSVToColor(hsv)
        onColor(Color.red(c), Color.green(c), Color.blue(c))
    }
}
