package com.meshcraft.app

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.hypot

enum class TouchMode { ROTATE, PAN }

/**
 * Vista 3D de la app.
 *
 * Un dedo: si se apoya sobre el modelo, pinta; si se apoya fuera del modelo, gira la vista. Con la mano activa
 * (TouchMode.PAN) un dedo desplaza la vista en cualquier parte y no pinta.
 * Dos dedos: pellizcar hace zoom y arrastrar gira o desplaza la vista segun touchMode.
 *
 * Saber si el dedo cayo sobre el modelo requiere el raycast del hilo de render (ver MyGLRenderer.isModelAt), asi que
 * la decision llega uno o dos cuadros despues de apoyar el dedo; mientras tanto no se pinta ni se gira. El trazo solo
 * empieza cuando el dedo se mueve (o al soltar, si fue un toque simple); si llega un segundo dedo antes, no se pinta nada.
 */
class MyGLSurfaceView(context: Context) : GLSurfaceView(context) {

    val renderer: MyGLRenderer

    var onRotationChanged: (() -> Unit)? = null
    /** Un dedo empezo un trazo en (x, y), la posicion donde se apoyo. */
    var onPaintStart: ((Float, Float) -> Unit)? = null
    /** El dedo del trazo en curso esta ahora en (x, y). */
    var onPaintMove: ((Float, Float) -> Unit)? = null
    /** El trazo termino (se solto el dedo, llego un segundo dedo o se cancelo el gesto). */
    var onPaintEnd: (() -> Unit)? = null
    /** Mano apagada (ROTATE): un dedo pinta o gira. Mano activa (PAN): un dedo desplaza. Con dos dedos, ROTATE gira y PAN desplaza. */
    var touchMode: TouchMode = TouchMode.ROTATE
    /** Candado de la camara: con true el arrastre no gira ni desplaza (el zoom sigue funcionando). */
    var isLocked: Boolean = false

    // Cuanto tiene que moverse el primer dedo (px) para que cuente como trazo y no como toque simple.
    private val paintSlop = 6f * context.resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    /** Numero del gesto actual (sube en cada ACTION_DOWN): descarta respuestas tardias del raycast de un gesto anterior. */
    private var touchId = 0
    /** Esperando saber si el dedo cayo sobre el modelo. */
    private var deciding = false
    private var paintPending = false
    private var painting = false
    /** Un dedo fuera del modelo: gira la vista. */
    private var rotating = false
    /** Mano activa: un dedo desplaza la vista. */
    private var panning = false
    private var navigating = false

    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastDist = 0f

    init {
        setEGLContextClientVersion(2)
        renderer = MyGLRenderer(context)
        setRenderer(renderer)
        setPreserveEGLContextOnPause(true)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchId++
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                painting = false
                paintPending = false
                rotating = false
                navigating = false
                if (touchMode == TouchMode.PAN) {
                    // Mano activa: este dedo desplaza la vista, no pinta ni gira.
                    panning = true
                    deciding = false
                } else {
                    // Se pregunta al hilo de render si hay modelo bajo el dedo; la respuesta vuelve a onTouchDecided.
                    panning = false
                    deciding = true
                    val id = touchId
                    val x = e.x
                    val y = e.y
                    queueEvent {
                        val onModel = renderer.isModelAt(x, y)
                        post { onTouchDecided(id, onModel) }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Llego un segundo dedo: se deja de pintar y los dedos pasan a mover la camara.
                if (painting) onPaintEnd?.invoke()
                painting = false
                paintPending = false
                deciding = false
                rotating = false
                panning = false
                navigating = true
                resetNavigationBase(e, -1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (navigating) {
                    if (e.pointerCount >= 2) navigate(e)
                } else if (panning) {
                    if (!isLocked) panBy(e.x - lastX, e.y - lastY)
                    lastX = e.x
                    lastY = e.y
                } else if (rotating) {
                    if (!isLocked) rotateBy(e.x - lastX, e.y - lastY)
                    lastX = e.x
                    lastY = e.y
                } else if (deciding) {
                    // Todavia no se sabe si hay modelo bajo el dedo: solo se recuerda por donde va.
                    lastX = e.x
                    lastY = e.y
                } else if (painting) {
                    onPaintMove?.invoke(e.x, e.y)
                } else if (paintPending) {
                    lastX = e.x
                    lastY = e.y
                    if (hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > paintSlop) startPaint()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Si siguen quedando 2 dedos o mas, se toma una base nueva (sin el que se levanto) para que la vista no salte.
                if (e.pointerCount - 1 >= 2) resetNavigationBase(e, e.actionIndex)
            }
            MotionEvent.ACTION_UP -> {
                if (painting) {
                    onPaintEnd?.invoke()
                } else if (paintPending) {
                    // Toque simple, sin arrastre: un solo punto de pincel.
                    onPaintStart?.invoke(downX, downY)
                    onPaintEnd?.invoke()
                }
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (painting) onPaintEnd?.invoke()
                endGesture()
            }
        }
        return true
    }

    private fun endGesture() {
        painting = false
        paintPending = false
        deciding = false
        rotating = false
        panning = false
        navigating = false
    }

    /** Respuesta del raycast del hilo de render: el dedo que se apoyo en (downX, downY) cayo sobre el modelo (pinta) o fuera (gira). */
    private fun onTouchDecided(id: Int, onModel: Boolean) {
        // Gesto viejo, o ya cambio (segundo dedo, se solto el dedo): se ignora.
        if (id != touchId || !deciding) return
        deciding = false
        if (onModel) {
            paintPending = true
            // Si el dedo ya se movio mas que el umbral mientras se esperaba, el trazo empieza ahora.
            if (hypot((lastX - downX).toDouble(), (lastY - downY).toDouble()) > paintSlop) startPaint()
        } else {
            rotating = true
        }
    }

    /** Empieza el trazo en el punto donde se apoyo el dedo y lo lleva hasta donde esta ahora. */
    private fun startPaint() {
        paintPending = false
        painting = true
        onPaintStart?.invoke(downX, downY)
        onPaintMove?.invoke(lastX, lastY)
    }

    /** Gira la vista dx, dy pixeles de pantalla. */
    private fun rotateBy(dx: Float, dy: Float) {
        renderer.angleY += dx * 0.5f
        renderer.angleX += dy * 0.5f
        renderer.isOrthographic = false
        onRotationChanged?.invoke()
        requestRender()
    }

    /** Desplaza la vista dx, dy pixeles de pantalla (la escala depende del zoom, para que siga al dedo). */
    private fun panBy(dx: Float, dy: Float) {
        val panScale = 0.01f * (renderer.cameraDistance / 6.5f)
        renderer.panX -= dx * panScale
        renderer.panZ += dy * panScale
        onRotationChanged?.invoke()
        requestRender()
    }

    /** Guarda el punto medio y la separacion de los dos primeros dedos (sin contar skipIndex) como referencia del gesto. */
    private fun resetNavigationBase(e: MotionEvent, skipIndex: Int) {
        val ids = ArrayList<Int>(2)
        for (i in 0 until e.pointerCount) {
            if (i != skipIndex && ids.size < 2) ids.add(i)
        }
        if (ids.size < 2) return
        val x0 = e.getX(ids[0])
        val y0 = e.getY(ids[0])
        val x1 = e.getX(ids[1])
        val y1 = e.getY(ids[1])
        lastMidX = (x0 + x1) / 2f
        lastMidY = (y0 + y1) / 2f
        lastDist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
    }

    /** Gesto de dos dedos: la separacion cambia el zoom y el movimiento del punto medio gira o desplaza la vista. */
    private fun navigate(e: MotionEvent) {
        val x0 = e.getX(0)
        val y0 = e.getY(0)
        val x1 = e.getX(1)
        val y1 = e.getY(1)
        val midX = (x0 + x1) / 2f
        val midY = (y0 + y1) / 2f
        val dist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()

        // Pellizco: separar los dedos acerca la camara (el setter de cameraDistance limita el rango).
        if (lastDist > 1f && dist > 1f) renderer.cameraDistance *= lastDist / dist

        if (!isLocked) {
            val dx = midX - lastMidX
            val dy = midY - lastMidY
            if (touchMode == TouchMode.ROTATE) rotateBy(dx, dy) else panBy(dx, dy)
        }

        lastMidX = midX
        lastMidY = midY
        lastDist = dist
        requestRender()
    }
}
