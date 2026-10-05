package com.meshcraft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Overlay 2D transparente que dibuja el rectángulo de Box Select mientras se arrastra (ver
 * MainActivity.onViewportDragStart/DragMove/DragEnd y boxSelectDragging) - mismo criterio que
 * GizmoLabelView.kt: un overlay Canvas puro encima de glView, porque OpenGL ES aca no aporta nada
 * (es un rectángulo 2D en coordenadas de pantalla, no geometría 3D) y así se evita mezclar dibujo
 * 2D con el pipeline de render 3D del viewport.
 *
 * No es clickable, para que los gestos del viewport 3D de abajo (glView) sigan llegando normal -
 * ver MainActivity.onCreate, se agrega en el mismo FrameLayout que gizmoLabelView.
 *
 * setRect(...) con null implícito (visible=false) es el estado de reposo: no dibuja nada. Se
 * activa con setRect en cada onViewportDragStart/DragMove de un Box Select en curso, y se apaga
 * llamando a clear() en onViewportDragEnd (ver ahi).
 */
class BoxSelectOverlayView(context: Context) : View(context) {

    private var visible = false
    private var left = 0f
    private var top = 0f
    private var right = 0f
    private var bottom = 0f

    private val density = context.resources.displayMetrics.density

    // Mismo naranja que el resto de la UI (ver circleBackground en MainActivity) - relleno muy
    // transparente para no tapar la geometría de abajo, borde solido para que el rectángulo se
    // distinga con claridad mientras se arrastra.
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 242, 128, 26)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 242, 128, 26)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    init {
        isClickable = false
        isFocusable = false
    }

    /** Actualiza el rectángulo (dos esquinas cualquiera, no necesariamente min/max) y lo muestra. */
    fun setRect(x0: Float, y0: Float, x1: Float, y1: Float) {
        visible = true
        left = minOf(x0, x1)
        top = minOf(y0, y1)
        right = maxOf(x0, x1)
        bottom = maxOf(y0, y1)
        invalidate()
    }

    /** Apaga el overlay (fin del arrastre, ver onViewportDragEnd) - no vuelve a dibujar nada hasta el próximo setRect. */
    fun clear() {
        if (!visible) return
        visible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible) return
        canvas.drawRect(left, top, right, bottom, fillPaint)
        canvas.drawRect(left, top, right, bottom, strokePaint)
    }
}
