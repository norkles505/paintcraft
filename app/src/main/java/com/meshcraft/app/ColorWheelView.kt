package com.meshcraft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Rueda de color: un anillo con el tono (hue) y, adentro, un rombo de saturacion/brillo (el cuadrado clasico girado 45 grados:
 * arriba blanco, derecha tono puro, abajo y izquierda negro). Cada vez que el usuario mueve el dedo llama a onChange con
 * (tono 0..360, saturacion 0..1, brillo 0..1).
 */
class ColorWheelView(context: Context, private val onChange: (Float, Float, Float) -> Unit) : View(context) {

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val diamond = RectF()
    private val hsv = floatArrayOf(0f, 0f, 0f)
    private var cx = 0f
    private var cy = 0f
    private var outerR = 0f
    private var ringW = 0f
    private var innerR = 0f
    // Mitad de la diagonal del rombo (del centro a una punta).
    private var half = 0f
    private var ringShader: SweepGradient? = null
    // 0 = ninguno, 1 = arrastrando el anillo (tono), 2 = arrastrando el rombo (saturacion/brillo).
    private var drag = 0

    /** Fija el color mostrado (sin avisar a onChange). */
    fun setHsv(h: Float, s: Float, v: Float) {
        hsv[0] = h
        hsv[1] = s
        hsv[2] = v
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxSize = (230 * d).toInt()
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        val s = if (avail <= 0) maxSize else min(avail, maxSize)
        setMeasuredDimension(s, s)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        outerR = min(w, h) / 2f - 4f * d
        ringW = outerR * 0.24f
        innerR = outerR - ringW
        half = innerR * 0.8f
        // SweepGradient gira en sentido horario desde las 3 en punto; el tono crece en sentido antihorario, por eso va invertido.
        val colors = IntArray(13) { Color.HSVToColor(floatArrayOf(((360 - it * 30) % 360).toFloat(), 1f, 1f)) }
        ringShader = SweepGradient(cx, cy, colors, null)
    }

    override fun onDraw(canvas: Canvas) {
        // Anillo de tono.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringW
        paint.shader = ringShader
        canvas.drawCircle(cx, cy, outerR - ringW / 2f, paint)
        paint.shader = null

        // Rombo: cuadrado de saturacion/brillo girado 45 grados en sentido horario.
        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val hs = half * 1.4142135f / 2f
        diamond.set(-hs, -hs, hs, hs)
        val rr = 5f * d
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(45f)
        paint.style = Paint.Style.FILL
        // De blanco a tono puro (izquierda a derecha) y de transparente a negro (arriba a abajo).
        paint.shader = LinearGradient(-hs, 0f, hs, 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(diamond, rr, rr, paint)
        paint.shader = LinearGradient(0f, -hs, 0f, hs, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(diamond, rr, rr, paint)
        paint.shader = null
        canvas.restore()

        // Marcador del tono, sobre el anillo.
        val ang = Math.toRadians(hsv[0].toDouble())
        val rMid = outerR - ringW / 2f
        val hx = cx + rMid * cos(ang).toFloat()
        val hy = cy - rMid * sin(ang).toFloat()
        drawMarker(canvas, hx, hy, ringW * 0.38f)

        // Marcador de saturacion/brillo, dentro del rombo.
        val px = cx + half * (hsv[1] + hsv[2] - 1f)
        val py = cy + half * (hsv[1] - hsv[2])
        drawMarker(canvas, px, py, 7f * d)
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float, radius: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * d
        paint.color = Color.BLACK
        canvas.drawCircle(x, y, radius + 1.8f * d, paint)
        paint.strokeWidth = 2.6f * d
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, radius, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                drag = if (hypot(e.x - cx, e.y - cy) > innerR) 1 else 2
                updateFromTouch(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = 0
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun updateFromTouch(x: Float, y: Float) {
        if (drag == 1) {
            var deg = Math.toDegrees(atan2((cy - y).toDouble(), (x - cx).toDouble())).toFloat()
            if (deg < 0f) deg += 360f
            hsv[0] = deg.coerceAtMost(359.99f)
        } else if (drag == 2) {
            val a = (x - cx) / half
            val b = (cy - y) / half
            hsv[1] = ((a - b + 1f) / 2f).coerceIn(0f, 1f)
            hsv[2] = ((a + b + 1f) / 2f).coerceIn(0f, 1f)
        } else {
            return
        }
        invalidate()
        onChange(hsv[0], hsv[1], hsv[2])
    }
}
