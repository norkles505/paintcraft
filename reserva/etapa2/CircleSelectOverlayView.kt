package com.meshcraft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.sqrt

/**
 * Overlay 2D transparente que dibuja el círculo de Circle Select mientras se arrastra (ver
 * MainActivity.onViewportDragStart/DragMove/DragEnd y circleSelectDragging) - mismo criterio que
 * BoxSelectOverlayView.kt: un overlay Canvas puro encima de glView, sin usar OpenGL ES (es un
 * círculo 2D en coordenadas de pantalla, no geometría 3D).
 *
 * No es clickable, para que los gestos del viewport 3D de abajo (glView) sigan llegando normal -
 * ver MainActivity.onCreate, se agrega en el mismo FrameLayout que boxSelectOverlay.
 *
 * A diferencia de Box Select (dos esquinas cualquiera), acá el gesto es centro + radio:
 * setCenter fija el centro en ACTION_DOWN, setRadiusToPoint recalcula el radio en cada
 * ACTION_MOVE como la distancia centro-dedo. Estado de reposo (visible=false): no dibuja nada,
 * se apaga con clear() en onViewportDragEnd.
 */
class CircleSelectOverlayView(context: Context) : View(context) {

    private var visible = false
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    private val density = context.resources.displayMetrics.density

    // Mismo naranja que BoxSelectOverlayView - relleno muy transparente para no tapar la
    // geometría de abajo, borde solido para que el círculo se distinga con claridad.
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

    /** Fija el centro del círculo (ACTION_DOWN) - arranca con radio 0, lo muestra. */
    fun setCenter(x: Float, y: Float) {
        visible = true
        centerX = x
        centerY = y
        radius = 0f
        invalidate()
    }

    /** Recalcula el radio como la distancia entre el centro fijado y el punto dado (ACTION_MOVE). */
    fun setRadiusToPoint(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        radius = sqrt(dx * dx + dy * dy)
        invalidate()
    }

    /** Apaga el overlay (fin del arrastre, ver onViewportDragEnd) - no vuelve a dibujar nada hasta el próximo setCenter. */
    fun clear() {
        if (!visible) return
        visible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible) return
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        canvas.drawCircle(centerX, centerY, radius, strokePaint)
    }
}
