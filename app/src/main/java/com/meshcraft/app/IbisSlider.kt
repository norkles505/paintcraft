package com.meshcraft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * Slider estilo ibisPaint: pista gris redondeada, pulgar blanco grande con sombra. Con opacityStyle = false el tramo
 * recorrido se pinta de azul (tamano), o la pista se pinta con un degradado si se llamo a setGradient (canales de color);
 * con true la pista es un tablero de ajedrez que se va cubriendo del color del pincel de izquierda a derecha
 * (opacidad, ver setTrackColor).
 */
class IbisSlider(
    context: Context,
    private val max: Int,
    initial: Int,
    private val opacityStyle: Boolean,
    private val onProgress: (Int) -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val clip = Path()
    private val thumbRadius = 10.5f * d
    private val trackHeight = 8f * d
    private val blue = Color.rgb(58, 123, 213)
    private val trackGray = Color.rgb(172, 172, 172)
    private var trackColor = Color.BLACK
    private var gradient: IntArray? = null

    var progress: Int = initial.coerceIn(0, max)
        private set

    init {
        // La sombra del pulgar (setShadowLayer) necesita capa por software.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** Color con el que termina la pista de opacidad (el color actual del pincel). Sin efecto en el estilo de tamano. */
    fun setTrackColor(color: Int) {
        trackColor = color
        invalidate()
    }

    /** Pista con degradado (de izquierda a derecha) en vez de gris + azul. null vuelve a la pista normal. Sin efecto en el estilo de opacidad. */
    fun setGradient(colors: IntArray?) {
        gradient = colors
        invalidate()
    }

    /** Fija el valor y avisa a onProgress (lo usan los botones - y +). */
    fun setProgressAndNotify(value: Int) {
        val v = value.coerceIn(0, max)
        if (v == progress) return
        progress = v
        invalidate()
        onProgress(v)
    }

    /** Fija el valor sin avisar a onProgress (para sincronizar el slider con otro control). */
    fun setProgressSilently(value: Int) {
        progress = value.coerceIn(0, max)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (2 * thumbRadius + 4f * d).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    private fun thumbX(): Float {
        val span = width - 2f * thumbRadius
        return thumbRadius + span * progress / max.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val r = trackHeight / 2f
        track.set(2f * d, cy - r, width - 2f * d, cy + r)
        val x = thumbX()

        if (opacityStyle) {
            clip.reset()
            clip.addRoundRect(track, r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            // Tablero de ajedrez.
            val cell = 4f * d
            var row = 0
            var y = track.top
            while (y < track.bottom) {
                var col = 0
                var cx = track.left
                while (cx < track.right) {
                    paint.shader = null
                    paint.style = Paint.Style.FILL
                    paint.color = if ((row + col) % 2 == 0) Color.WHITE else Color.rgb(200, 200, 200)
                    canvas.drawRect(cx, y, cx + cell, y + cell, paint)
                    cx += cell
                    col++
                }
                y += cell
                row++
            }
            // De transparente al color del pincel.
            val transparent = Color.argb(0, Color.red(trackColor), Color.green(trackColor), Color.blue(trackColor))
            paint.shader = LinearGradient(track.left, 0f, track.right, 0f, transparent, trackColor or (0xFF shl 24), Shader.TileMode.CLAMP)
            canvas.drawRect(track, paint)
            paint.shader = null
            canvas.restore()
        } else {
            paint.style = Paint.Style.FILL
            val g = gradient
            if (g != null) {
                paint.shader = LinearGradient(track.left, 0f, track.right, 0f, g, null, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(track, r, r, paint)
                paint.shader = null
            } else {
                paint.shader = null
                paint.color = trackGray
                canvas.drawRoundRect(track, r, r, paint)
                paint.color = blue
                canvas.drawRoundRect(RectF(track.left, track.top, maxOf(x, track.left + trackHeight), track.bottom), r, r, paint)
            }
        }

        // Pulgar blanco con sombra suave.
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.setShadowLayer(3f * d, 0f, 1f * d, Color.argb(110, 0, 0, 0))
        canvas.drawCircle(x, cy, thumbRadius, paint)
        paint.clearShadowLayer()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(e.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(e.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(e)
    }

    private fun updateFromTouch(x: Float) {
        val span = width - 2f * thumbRadius
        if (span <= 0f) return
        val v = ((x - thumbRadius) / span * max).roundToInt()
        setProgressAndNotify(v)
    }
}

/** Boton redondo negro con un "-" o un "+" blanco (los de los sliders de ibisPaint). Mantenerlo apretado repite el paso. */
class IbisStepButton(context: Context, private val plus: Boolean, private val onStep: () -> Unit) : View(context) {

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val repeat = object : Runnable {
        override fun run() {
            onStep()
            handler.postDelayed(this, 70)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawCircle(cx, cy, minOf(cx, cy), paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 2.6f * d
        paint.strokeCap = Paint.Cap.ROUND
        val arm = width * 0.22f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, paint)
        if (plus) canvas.drawLine(cx, cy - arm, cx, cy + arm, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onStep()
                handler.postDelayed(repeat, 350)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeat)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(repeat)
        super.onDetachedFromWindow()
    }
}

/**
 * Fila de ibisPaint: valor numerico, boton -, slider y boton +. format convierte el progreso al texto mostrado; onChange
 * aplica el valor al pincel.
 */
class IbisSliderRow(
    context: Context,
    max: Int,
    initial: Int,
    step: Int,
    opacityStyle: Boolean,
    private val format: (Int) -> String,
    onChange: (Int) -> Unit
) : LinearLayout(context) {

    private val slider: IbisSlider
    private val valueText: OutlinedTextView

    init {
        val d = resources.displayMetrics.density
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        valueText = OutlinedTextView(context)
        valueText.setTextColor(Color.WHITE)
        valueText.textSize = 11f
        valueText.gravity = Gravity.CENTER
        valueText.text = format(initial)

        slider = IbisSlider(context, max, initial, opacityStyle) { v ->
            valueText.text = format(v)
            onChange(v)
        }

        val minus = IbisStepButton(context, false) { slider.setProgressAndNotify(slider.progress - step) }
        val plus = IbisStepButton(context, true) { slider.setProgressAndNotify(slider.progress + step) }

        val btn = (24 * d).toInt()
        addView(valueText, LayoutParams((36 * d).toInt(), LayoutParams.WRAP_CONTENT))
        addView(minus, LayoutParams(btn, btn))
        addView(slider, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = (6 * d).toInt()
            rightMargin = (6 * d).toInt()
        })
        addView(plus, LayoutParams(btn, btn))
    }

    /** Color final de la pista (solo estilo opacidad): el color actual del pincel. */
    fun setTrackColor(color: Int) = slider.setTrackColor(color)

    /** Degradado de la pista (solo estilo normal): por ejemplo de negro a rojo para el canal R. */
    fun setGradient(colors: IntArray?) = slider.setGradient(colors)

    /** Fija el valor y el numero mostrado sin llamar a onChange (para sincronizar con otro control). */
    fun setValue(value: Int) {
        slider.setProgressSilently(value)
        valueText.text = format(slider.progress)
    }
}
