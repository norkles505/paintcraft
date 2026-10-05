package com.meshcraft.app

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.hypot

enum class TouchMode { ROTATE, PAN }

class MyGLSurfaceView(context: Context) : GLSurfaceView(context) {

    val renderer: MyGLRenderer
    private var previousX = 0f
    private var previousY = 0f

    private var downX = 0f
    private var downY = 0f
    private val tapMoveThreshold = 20f // px de tolerancia: por debajo de esto, ACTION_UP cuenta como tap (seleccion), no arrastre

    var onRotationChanged: (() -> Unit)? = null
    /** Se dispara en ACTION_UP si el dedo no se movio mas que tapMoveThreshold - usado para seleccion de objetos. */
    var onTap: ((Float, Float) -> Unit)? = null
    /**
     * Se dispara en cada ACTION_MOVE con el delta de pantalla (dx, dy), ANTES de la logica de
     * rotar/pan camara. Debe devolver true si el arrastre ya fue manejado (por ejemplo, moviendo
     * el objeto seleccionado con la herramienta Move de Layout) - en ese caso la vista no rota ni
     * hace pan. A proposito no depende de isLocked: el candado de rotacion es sobre la camara, no
     * sobre mover objetos.
     */
    /**
     * screenX/screenY (parametros 3ro y 4to) son la posicion ABSOLUTA actual del dedo, ademas del
     * delta dx/dy - los agrega el gizmo de rotacion (ver MainActivity.onViewportDragMove) para
     * recalcular en vivo la marca de angulo mientras se arrastra un anillo (ver
     * MyGLRenderer.updateActiveRotateCurrentDir), ya que esa cuenta necesita saber DONDE esta el
     * dedo ahora, no solo cuanto se movio desde el frame anterior.
     */
    var onDragMove: ((Float, Float, Float, Float) -> Boolean)? = null
    /**
     * Se dispara en ACTION_DOWN, antes que cualquier otra logica - usado para el hit-test del
     * gizmo de ejes (ver MainActivity.onViewportDragStart): si el dedo toco una flecha, el
     * arrastre que sigue queda restringido a ese eje.
     */
    var onDragStart: ((Float, Float) -> Unit)? = null
    /** Se dispara en ACTION_UP, antes de evaluar si fue un tap - usado para soltar el eje bloqueado del gizmo. */
    var onDragEnd: (() -> Unit)? = null
    var touchMode: TouchMode = TouchMode.ROTATE
    var isLocked: Boolean = false

    init {
        setEGLContextClientVersion(2)
        renderer = MyGLRenderer(context)
        setRenderer(renderer)
        setPreserveEGLContextOnPause(true)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y

        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                onDragStart?.invoke(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - previousX
                val dy = y - previousY

                val handledByDrag = onDragMove?.invoke(dx, dy, x, y) ?: false
                if (handledByDrag) {
                    requestRender()
                } else if (!isLocked) {
                    if (touchMode == TouchMode.ROTATE) {
                        renderer.angleY += dx * 0.5f
                        renderer.angleX += dy * 0.5f
                        renderer.isOrthographic = false
                        renderer.gridPlaneAxis = 'Z'
                    } else {
                        val panScale = 0.01f * (renderer.cameraDistance / 6.5f)
                        renderer.panX -= dx * panScale
                        renderer.panZ += dy * panScale
                    }

                    requestRender()
                    onRotationChanged?.invoke()
                }
            }
            MotionEvent.ACTION_UP -> {
                onDragEnd?.invoke()
                val moved = hypot((x - downX).toDouble(), (y - downY).toDouble())
                if (moved < tapMoveThreshold) {
                    onTap?.invoke(x, y)
                }
            }
        }

        previousX = x
        previousY = y
        return true
    }
}
