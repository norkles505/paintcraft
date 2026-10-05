package com.meshcraft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Overlay 2D transparente que dibuja el trazo libre de Lasso Select mientras se arrastra (ver
 * MainActivity.onViewportDragStart/DragMove/DragEnd y lassoSelectDragging) - mismo criterio que
 * BoxSelectOverlayView.kt/CircleSelectOverlayView.kt: un overlay Canvas puro encima de glView,
 * sin usar OpenGL ES (es un trazo 2D en coordenadas de pantalla, no geometría 3D).
 *
 * No es clickable, para que los gestos del viewport 3D de abajo (glView) sigan llegando normal -
 * ver MainActivity.onCreate, se agrega en el mismo FrameLayout que boxSelectOverlay/circleSelectOverlay.
 *
 * A diferencia de Box/Circle Select (forma fija, dos parámetros alcanzan), acá el gesto acumula
 * una lista de puntos (ver MainActivity.lassoPoints) - addPoint agrega cada punto nuevo del
 * arrastre a un Path que se dibuja cerrado (ultimo punto conectado de vuelta al primero, mismo
 * criterio visual que el lazo de Blender) aunque el gesto en si todavia no haya terminado.
 */
class LassoSelectOverlayView(context: Context) : View(context) {

    private var visible = false
    private val path = Path()
    private var firstX = 0f
    private var firstY = 0f
    private var hasPoints = false

    private val density = context.resources.displayMetrics.density

    // Mismo naranja que Box Select/Circle Select - relleno muy transparente para no tapar la
    // geometría de abajo, borde solido para que el trazo se distinga con claridad.
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

    /** Arranca un trazo nuevo (ACTION_DOWN) - primer punto del lazo. */
    fun startPath(x: Float, y: Float) {
        visible = true
        hasPoints = true
        firstX = x
        firstY = y
        path.reset()
        path.moveTo(x, y)
        invalidate()
    }

    /** Agrega un punto nuevo al trazo (ACTION_MOVE) - se dibuja cerrado de vuelta al primer punto. */
    fun addPoint(x: Float, y: Float) {
        if (!hasPoints) return
        path.lineTo(x, y)
        invalidate()
    }

    /** Apaga el overlay (fin del arrastre, ver onViewportDragEnd) - no vuelve a dibujar nada hasta el próximo startPath. */
    fun clear() {
        if (!visible) return
        visible = false
        hasPoints = false
        path.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible || !hasPoints) return
        val closedPath = Path(path)
        closedPath.lineTo(firstX, firstY)
        closedPath.close()
        canvas.drawPath(closedPath, fillPaint)
        canvas.drawPath(closedPath, strokePaint)
    }
}
