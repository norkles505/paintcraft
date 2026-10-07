package com.meshcraft.app

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Iconos del panel de capas; los dibuja LayerIconView con trazos simples (sin archivos de recursos). */
private enum class LayerIcon {
    ADD, DUPLICATE, GROUP, COLLAPSE, CAMERA, ALPHA_LOCK, MASK, MOVE, FLIP_H, FLIP_V,
    MERGE_DOWN, TRASH, DOTS, CLIP, LOCK, EYE, HANDLE, SELECTION, CHEVRON_UP
}

/** Icono de una sola tinta dibujado sobre una cuadricula de 24x24 que se escala al tamano de la vista (menos el padding). */
private class LayerIconView(context: Context, private val icon: LayerIcon, private val color: Int) : View(context) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        stroke.color = color
        fill.color = color
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.scale(w / 24f, h / 24f)
        drawIcon(canvas)
        canvas.restore()
    }

    private fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) = c.drawLine(x0, y0, x1, y1, stroke)

    /** Linea quebrada por los puntos (x0, y0, x1, y1, ...); closed la cierra. */
    private fun poly(c: Canvas, closed: Boolean, vararg p: Float) {
        path.reset()
        path.moveTo(p[0], p[1])
        var i = 2
        while (i + 1 < p.size) {
            path.lineTo(p[i], p[i + 1])
            i += 2
        }
        if (closed) path.close()
        c.drawPath(path, stroke)
    }

    private fun drawIcon(c: Canvas) {
        when (icon) {
            LayerIcon.ADD -> {
                line(c, 12f, 5f, 12f, 19f)
                line(c, 5f, 12f, 19f, 12f)
            }
            LayerIcon.DUPLICATE -> {
                rect.set(4f, 4f, 15f, 15f)
                c.drawRoundRect(rect, 2f, 2f, stroke)
                rect.set(9f, 9f, 20f, 20f)
                c.drawRoundRect(rect, 2f, 2f, stroke)
            }
            LayerIcon.GROUP -> {
                rect.set(4f, 4f, 20f, 20f)
                c.drawRoundRect(rect, 2f, 2f, stroke)
                line(c, 12f, 8f, 12f, 16f)
                line(c, 8f, 12f, 16f, 12f)
            }
            LayerIcon.COLLAPSE -> {
                line(c, 6f, 12f, 18f, 12f)
                line(c, 12f, 3f, 12f, 9f)
                poly(c, false, 8f, 5f, 12f, 9f, 16f, 5f)
                line(c, 12f, 21f, 12f, 15f)
                poly(c, false, 8f, 19f, 12f, 15f, 16f, 19f)
            }
            LayerIcon.CAMERA -> {
                rect.set(3f, 8f, 21f, 20f)
                c.drawRoundRect(rect, 2.5f, 2.5f, stroke)
                poly(c, false, 8f, 8f, 9.5f, 5f, 14.5f, 5f, 16f, 8f)
                c.drawCircle(12f, 14f, 3.5f, stroke)
            }
            LayerIcon.ALPHA_LOCK -> {
                for (row in 0 until 4) {
                    for (col in 0 until 4) {
                        if ((row + col) % 2 == 0) c.drawRect(4f + col * 4f, 4f + row * 4f, 8f + col * 4f, 8f + row * 4f, fill)
                    }
                }
                rect.set(4f, 4f, 20f, 20f)
                c.drawRect(rect, stroke)
            }
            LayerIcon.MASK -> {
                c.drawCircle(12f, 12f, 8f, stroke)
                rect.set(4f, 4f, 20f, 20f)
                c.drawArc(rect, 90f, 180f, true, fill)
            }
            LayerIcon.MOVE -> {
                line(c, 12f, 3f, 12f, 21f)
                line(c, 3f, 12f, 21f, 12f)
                poly(c, false, 9f, 6f, 12f, 3f, 15f, 6f)
                poly(c, false, 9f, 18f, 12f, 21f, 15f, 18f)
                poly(c, false, 6f, 9f, 3f, 12f, 6f, 15f)
                poly(c, false, 18f, 9f, 21f, 12f, 18f, 15f)
            }
            LayerIcon.FLIP_H -> {
                line(c, 12f, 3f, 12f, 21f)
                poly(c, true, 10f, 6f, 10f, 18f, 3f, 12f)
                poly(c, true, 14f, 6f, 14f, 18f, 21f, 12f)
            }
            LayerIcon.FLIP_V -> {
                line(c, 3f, 12f, 21f, 12f)
                poly(c, true, 6f, 10f, 18f, 10f, 12f, 3f)
                poly(c, true, 6f, 14f, 18f, 14f, 12f, 21f)
            }
            LayerIcon.MERGE_DOWN -> {
                line(c, 12f, 4f, 12f, 15f)
                poly(c, false, 7f, 10f, 12f, 15f, 17f, 10f)
                line(c, 6f, 20f, 18f, 20f)
            }
            LayerIcon.TRASH -> {
                line(c, 5f, 7f, 19f, 7f)
                poly(c, false, 9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f)
                poly(c, false, 7f, 7f, 8f, 20f, 16f, 20f, 17f, 7f)
                line(c, 10f, 10f, 10f, 17f)
                line(c, 14f, 10f, 14f, 17f)
            }
            LayerIcon.DOTS -> {
                c.drawCircle(12f, 5f, 1.8f, fill)
                c.drawCircle(12f, 12f, 1.8f, fill)
                c.drawCircle(12f, 19f, 1.8f, fill)
            }
            LayerIcon.CLIP -> {
                poly(c, false, 18f, 5f, 18f, 13f, 7f, 13f)
                poly(c, false, 11f, 9f, 7f, 13f, 11f, 17f)
            }
            LayerIcon.LOCK -> {
                rect.set(6f, 11f, 18f, 21f)
                c.drawRoundRect(rect, 2f, 2f, stroke)
                rect.set(8f, 3f, 16f, 15f)
                c.drawArc(rect, 180f, 180f, false, stroke)
                c.drawCircle(12f, 16f, 1.4f, fill)
            }
            LayerIcon.EYE -> {
                // Ojo relleno (alto 5..19) con un aro claro y la pupila del color del icono (como en ibisPaint).
                path.reset()
                path.moveTo(1.5f, 12f)
                path.quadTo(12f, -2f, 22.5f, 12f)
                path.quadTo(12f, 26f, 1.5f, 12f)
                c.drawPath(path, fill)
                val oldColor = stroke.color
                stroke.color = Color.argb(230, 255, 255, 255)
                c.drawCircle(12f, 12f, 5.2f, stroke)
                stroke.color = oldColor
                c.drawCircle(12f, 12f, 2.8f, fill)
            }
            LayerIcon.HANDLE -> {
                line(c, 5f, 8f, 19f, 8f)
                line(c, 5f, 12f, 19f, 12f)
                line(c, 5f, 16f, 19f, 16f)
            }
            LayerIcon.SELECTION -> {
                // Contorno punteado fino (como en ibisPaint): trazo mas delgado y sin puntas redondeadas.
                stroke.strokeWidth = 1.2f
                stroke.strokeCap = Paint.Cap.BUTT
                stroke.pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f)
                rect.set(2f, 4f, 22f, 19f)
                c.drawRect(rect, stroke)
                stroke.pathEffect = null
                stroke.strokeWidth = 1.8f
                stroke.strokeCap = Paint.Cap.ROUND
            }
            LayerIcon.CHEVRON_UP -> poly(c, false, 6f, 15f, 12f, 9f, 18f, 15f)
        }
    }
}

/** Que dibuja una miniatura: las de capa (con tablero de transparencia) y las del selector de fondo. */
private enum class ThumbKind { SELECTION, SKETCH, CHECKER, DARK, BG_WHITE, BG_CHECKER, BG_CHECKER_DARK, BG_NONE }

/**
 * Miniatura con borde redondeado: tablero de ajedrez, color liso o garabato, segun ThumbKind. selected = borde azul grueso.
 * Con bitmap (la vista previa cuadrada de una capa real) se dibuja encima del tablero, centrada y sin deformarse.
 */
private class ThumbView(context: Context, private val kind: ThumbKind, var chosen: Boolean, private val bitmap: Bitmap? = null) : View(context) {

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = 3f * d
        // Todo el contenido se recorta a las esquinas redondeadas.
        canvas.save()
        path.reset()
        path.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)
        paint.style = Paint.Style.FILL
        when (kind) {
            ThumbKind.DARK -> {
                paint.color = Color.rgb(24, 26, 32)
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            ThumbKind.SELECTION -> checker(canvas, w, h, Color.rgb(255, 235, 238), Color.rgb(246, 196, 203))
            ThumbKind.BG_CHECKER_DARK -> checker(canvas, w, h, Color.rgb(90, 90, 90), Color.rgb(55, 55, 55))
            ThumbKind.BG_WHITE -> {
                paint.color = Color.WHITE
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            ThumbKind.BG_NONE -> {
                paint.color = Color.WHITE
                canvas.drawRect(0f, 0f, w, h, paint)
                paint.style = Paint.Style.STROKE
                paint.color = Color.BLACK
                paint.strokeWidth = 1.2f * d
                canvas.drawLine(0f, h, w, 0f, paint)
            }
            else -> checker(canvas, w, h, Color.WHITE, Color.rgb(222, 222, 222))
        }
        // Contenido real de la capa: la textura es cuadrada, asi que va en un cuadrado centrado.
        val bmp = bitmap
        if (bmp != null) {
            val side = minOf(w, h)
            dst.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f)
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        }
        if (kind == ThumbKind.SKETCH) {
            paint.style = Paint.Style.STROKE
            paint.color = Color.argb(200, 214, 90, 96)
            paint.strokeWidth = 1.6f * d
            paint.strokeCap = Paint.Cap.ROUND
            path.reset()
            path.moveTo(w * 0.30f, h * 0.70f)
            path.cubicTo(w * 0.20f, h * 0.30f, w * 0.55f, h * 0.15f, w * 0.50f, h * 0.55f)
            path.cubicTo(w * 0.48f, h * 0.80f, w * 0.75f, h * 0.75f, w * 0.72f, h * 0.40f)
            canvas.drawPath(path, paint)
        }
        canvas.restore()
        // Borde redondeado (azul y mas grueso si es la opcion elegida).
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (if (chosen) 2.5f else 1f) * d
        paint.color = if (chosen) Color.rgb(58, 123, 213) else Color.rgb(120, 120, 120)
        val inset = paint.strokeWidth / 2f
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius, radius, paint)
    }

    private fun checker(canvas: Canvas, w: Float, h: Float, light: Int, dark: Int) {
        // Las miniaturas de capa llevan casillas un poco mas grandes que los demas tableros (fondo y capa de seleccion).
        val cell = (if (kind == ThumbKind.CHECKER) 8f else 6f) * d
        var row = 0
        var y = 0f
        while (y < h) {
            var col = 0
            var x = 0f
            while (x < w) {
                paint.color = if ((row + col) % 2 == 0) light else dark
                canvas.drawRect(x, y, minOf(x + cell, w), minOf(y + cell, h), paint)
                x += cell
                col++
            }
            y += cell
            row++
        }
    }
}

/**
 * Panel de capas estilo ibisPaint, conectado a las capas REALES del modelo que se esta pintando (ver PaintLayer y
 * TexturedMeshGeometry): lista de capas con miniatura de lo pintado, ojo, opacidad y asa; columna de acciones; selector
 * de fondo; botones de recorte y bloqueo alfa, modo de mezcla y slider de opacidad.
 *
 * Funciona: elegir capa, anadir, clonar, borrar (con confirmacion), combinar hacia abajo, limpiar, invertir color, ojo, modos de mezcla,
 * opacidad, bloqueo alfa, carpetas (crear, plegar, arrastrar capas adentro y afuera, ver computeDrop) y, en los tres puntitos,
 * renombrar, bloquear, Modo Solo y guardar la capa como PNG transparente (el selector de archivos lo abre MainActivity.startExport).
 * Pinta siempre la capa elegida.
 * Selecciona la opacidad (tres puntitos) marca lo pintado de la capa elegida (ver TexturedMeshGeometry.selectByOpacity): despues solo se pinta dentro; se quita tocando la fila "Capa de selección". Sin modelo importado la lista sale vacia.
 *
 * Los ajustes simples (ojo, nombre, opacidad, candados) se cambian directo desde la interfaz; lo que toca pixeles o la lista
 * de capas se pide al hilo de render con MyGLRenderer.editLayers. El panel se vuelve a armar al abrirse (PaintPanel crea uno
 * nuevo cada vez), asi que el estado vive en las capas del modelo y no aqui.
 *
 * Medidas pensadas para el alto fijo que le da PaintPanel (490 dp): columna izquierda de 51 dp, derecha de 35 dp, filas de ~72 dp.
 */
class LayersPanel(context: Context, private val renderer: MyGLRenderer) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private val lightBg = Color.rgb(232, 232, 232)
    private val darkText = Color.rgb(40, 40, 40)
    private val selectedBg = Color.rgb(205, 222, 247)
    private val dividerColor = Color.rgb(205, 205, 205)
    private val accent = Color.argb(235, 242, 128, 26)
    /** Lado (en muestras) de la vista previa de cada capa. */
    private val THUMB_SAMPLES = 48

    // Columna de acciones (derecha): la arma buildLightContainer y init la pone como hermana de la lista, para redondearla por fuera.
    private lateinit var actionsColumn: View
    // Boton de bloqueo alfa de la barra de abajo: se resalta cuando la capa elegida lo tiene activo (ver rebuildList).
    private lateinit var alphaLockButton: View
    // Boton de recorte de la barra de abajo: se resalta cuando la capa elegida esta recortada a la de abajo (ver rebuildList).
    private lateinit var clipButton: View
    // Las cuatro miniaturas del selector de Fondo, para marcar la elegida sin armar toda la fila (ver setBackground).
    private val backgroundSwatches = ArrayList<Pair<BackgroundKind, ThumbView>>()
    private val listBox = LinearLayout(context)
    // Texto del modo de mezcla de la capa elegida (en el cuadro de abajo a la derecha). Se actualiza en rebuildList.
    private val blendLabel = label("Normal", 15f, darkText)
    // Slider de opacidad de la capa elegida (abajo del todo).
    private val opacityRow: IbisSliderRow = IbisSliderRow(context, 100, 100, 1, true, { it.toString() + "%" }) { v -> onOpacityChanged(v) }
    // Texto "NN%" de la fila de la capa elegida, para ponerlo al dia mientras se mueve el slider sin armar toda la lista.
    private var selectedOpacityLabel: TextView? = null

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * d
            setColor(Color.argb(100, 0, 0, 0))
        }
        setPadding(dp(7), dp(9), dp(7), dp(8))

        addView(label("Capa", 18f, Color.WHITE), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(13)
        })

        // Zona central: botones de abajo a la izquierda + (lista de capas con fondo y columna de acciones) a la derecha.
        val middle = LinearLayout(context)
        middle.orientation = HORIZONTAL
        middle.addView(buildLeftButtons(), LayoutParams(dp(51), LayoutParams.MATCH_PARENT))
        middle.addView(buildLightContainer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        middle.addView(actionsColumn, LayoutParams(dp(35), LayoutParams.MATCH_PARENT))
        addView(middle, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(buildBottomControls(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        // La pista termina en negro (como en ibisPaint): de tablero transparente a negro.
        opacityRow.setTrackColor(Color.BLACK)
        // Fondo negro al 50 % de opacidad detras de la fila (numero, - , slider, +).
        opacityRow.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * d
            setColor(Color.argb(128, 0, 0, 0))
            // Pegada a la barra negra de arriba: solo las esquinas de abajo van redondeadas.
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 8 * d, 8 * d, 8 * d, 8 * d)
        }
        opacityRow.setPadding(dp(6), dp(9), dp(6), dp(9))
        addView(opacityRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 0
        })

        rebuildList()
    }

    // Deshacer/Rehacer de un movimiento de capas llega desde el hilo de render: el panel abierto vuelve a armar la lista.
    private val layersChangedListener: () -> Unit = { refresh() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer.onLayersChanged = layersChangedListener
    }

    override fun onDetachedFromWindow() {
        // Solo se suelta si sigue siendo el de este panel (otro panel nuevo pudo ponerse antes de que este saliera).
        if (renderer.onLayersChanged === layersChangedListener) renderer.onLayersChanged = null
        super.onDetachedFromWindow()
    }

    // ---- Acceso a las capas del modelo ----

    private fun geo(): TexturedMeshGeometry? = renderer.activeGeometry

    private fun selectedLayer(): PaintLayer? = geo()?.selectedLayer()

    /** Capa elegida; si no hay modelo avisa y devuelve null. */
    private fun requireLayer(): PaintLayer? {
        val layer = selectedLayer()
        if (layer == null) toast("Importa un modelo para usar capas")
        return layer
    }

    /** Vuelve a armar la lista desde el hilo de la interfaz (se puede pedir desde cualquier hilo). */
    private fun refresh() {
        post { rebuildList() }
    }

    /**
     * Pide al hilo de render una operacion sobre las capas del modelo (ver MyGLRenderer.editLayers) y despues vuelve a
     * armar la lista. clearsHistory = true para las que no se pueden deshacer (borrar y combinar).
     */
    private fun editLayers(clearsHistory: Boolean = false, block: (TexturedMeshGeometry) -> Unit) {
        if (geo() == null) {
            toast("Importa un modelo para usar capas")
            return
        }
        renderer.editLayers(clearsHistory, block) { refresh() }
    }

    // ---- Zonas del panel ----

    /** Botones de abajo a la izquierda: anadir capa, clonar capa y anadir carpeta. */
    private fun buildLeftButtons(): View {
        val col = LinearLayout(context)
        col.orientation = VERTICAL
        col.gravity = Gravity.BOTTOM
        // Columna izquierda: fondo negro translucido (redondeado a la izquierda); el bloque de abajo, con los botones, es negro solido.
        col.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(6 * d, 6 * d, 0f, 0f, 0f, 0f, 6 * d, 6 * d)
            setColor(Color.argb(80, 0, 0, 0))
        }
        val block = LinearLayout(context)
        block.orientation = VERTICAL
        block.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        block.minimumHeight = dp(104)
        block.setPadding(0, 0, 0, dp(6))
        block.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 6 * d, 6 * d)
            setColor(Color.BLACK)
        }
        val rows = listOf(
            listOf(Pair(LayerIcon.ADD, "Añadir capa")),
            listOf(Pair(LayerIcon.DUPLICATE, "Clonar capa")),
            listOf(Pair(LayerIcon.GROUP, "Añadir carpeta"))
        )
        for (items in rows) {
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            for ((icon, name) in items) {
                val res = when (icon) {
                    LayerIcon.ADD -> R.drawable.ic_layer_add
                    LayerIcon.DUPLICATE -> R.drawable.ic_layer_clone
                    LayerIcon.GROUP -> R.drawable.ic_layer_folder
                    else -> 0
                }
                val button = if (res != 0) drawableButton(res, 40, Color.WHITE, name) else iconButton(icon, 40, Color.WHITE, name)
                when (icon) {
                    LayerIcon.ADD -> button.setOnClickListener { addLayer() }
                    LayerIcon.DUPLICATE -> button.setOnClickListener { cloneLayer() }
                    LayerIcon.GROUP -> button.setOnClickListener { addFolder() }
                    else -> Unit
                }
                row.addView(button)
            }
            row.gravity = Gravity.CENTER_HORIZONTAL
            block.addView(row)
        }
        col.addView(block, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return col
    }

    /** Contenedor claro: lista de capas + selector de fondo a la izquierda, columna de acciones a la derecha. */
    private fun buildLightContainer(): View {
        val box = LinearLayout(context)
        box.orientation = HORIZONTAL
        box.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(lightBg)
        }

        val left = LinearLayout(context)
        left.orientation = VERTICAL
        val scroll = ScrollView(context)
        scroll.isVerticalScrollBarEnabled = false
        listBox.orientation = VERTICAL
        scroll.addView(listBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        left.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        left.addView(divider())
        left.addView(buildBackgroundRow(), LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
        box.addView(left, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        val actions = LinearLayout(context)
        actions.orientation = VERTICAL
        actions.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        // Columna negra; redondeada solo a la derecha para seguir la esquina del contenedor claro.
        actions.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(0f, 0f, 6 * d, 6 * d, 6 * d, 6 * d, 0f, 0f)
            setColor(Color.BLACK)
        }
        val items = listOf(
            Pair(LayerIcon.ALPHA_LOCK, "Limpiar capa"),
            Pair(LayerIcon.MASK, "Invertir color de capa"),
            Pair(LayerIcon.MERGE_DOWN, "Combinar hacia abajo"),
            Pair(LayerIcon.TRASH, "Borrar capa")
        )
        // Estos cuatro usan los vectores propios (res/drawable); el resto sigue con los iconos dibujados a mano.
        for ((icon, name) in items) {
            val res = when (icon) {
                LayerIcon.ALPHA_LOCK -> R.drawable.ic_layer_clear
                LayerIcon.MASK -> R.drawable.ic_layer_invert
                LayerIcon.MERGE_DOWN -> R.drawable.ic_layer_merge_next
                LayerIcon.TRASH -> R.drawable.ic_layer_delete
                else -> 0
            }
            val button = if (res != 0) drawableButton(res, 35, Color.WHITE, name) else iconButton(icon, 35, Color.WHITE, name)
            when (icon) {
                LayerIcon.ALPHA_LOCK -> button.setOnClickListener { clearLayer() }
                LayerIcon.MASK -> button.setOnClickListener { invertLayer() }
                LayerIcon.MERGE_DOWN -> button.setOnClickListener { mergeDown() }
                LayerIcon.TRASH -> button.setOnClickListener { confirmDelete() }
                else -> Unit
            }
            actions.addView(button)
        }
        actions.addView(dotsButton("Más opciones"))
        actionsColumn = actions
        return box
    }

    /** Fila "Fondo" con las cuatro opciones (blanco, tablero claro, tablero oscuro, sin fondo); la elegida lleva borde azul. */
    private fun buildBackgroundRow(): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), 0, dp(6), 0)
        row.addView(label("Fondo", 16f, darkText), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val kinds = listOf(ThumbKind.BG_WHITE, ThumbKind.BG_CHECKER, ThumbKind.BG_CHECKER_DARK, ThumbKind.BG_NONE)
        val backgrounds = listOf(BackgroundKind.WHITE, BackgroundKind.CHECKER, BackgroundKind.CHECKER_DARK, BackgroundKind.NONE)
        val current = geo()?.background ?: BackgroundKind.WHITE
        backgroundSwatches.clear()
        for ((i, kind) in kinds.withIndex()) {
            val swatch = ThumbView(context, kind, backgrounds[i] == current)
            backgroundSwatches.add(Pair(backgrounds[i], swatch))
            swatch.isClickable = true
            swatch.setOnClickListener { setBackground(backgrounds[i]) }
            row.addView(swatch, LayoutParams(dp(26), dp(26)).apply { leftMargin = dp(4) })
        }
        return row
    }

    /** Recorte y bloqueo alfa a la izquierda, y el modo de mezcla ("Normal") a la derecha. */
    private fun buildBottomControls(): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        // Barra negra (redondeada solo arriba) que queda pegada a la fila de opacidad de abajo.
        row.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(8 * d, 8 * d, 8 * d, 8 * d, 0f, 0f, 0f, 0f)
            setColor(Color.BLACK)
        }
        row.setPadding(dp(8), dp(6), dp(8), dp(6))
        clipButton = drawableButton(R.drawable.ic_layer_clip, 38, Color.WHITE, "Recorte de capa")
        clipButton.setOnClickListener { toggleClip() }
        row.addView(clipButton, LayoutParams(0, dp(38), 1f))
        alphaLockButton = drawableButton(R.drawable.ic_layer_alpha_lock, 38, Color.WHITE, "Bloqueo alpha")
        alphaLockButton.setOnClickListener { toggleAlphaLock() }
        row.addView(alphaLockButton, LayoutParams(0, dp(38), 1f))

        val blend = LinearLayout(context)
        blend.orientation = HORIZONTAL
        blend.gravity = Gravity.CENTER_VERTICAL
        blend.setPadding(dp(10), 0, dp(6), 0)
        blend.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4 * d
            setColor(Color.WHITE)
        }
        blend.isClickable = true
        blend.setOnClickListener { showBlendMenu(blend) }
        blendLabel.maxLines = 1
        blendLabel.ellipsize = android.text.TextUtils.TruncateAt.END
        blend.addView(blendLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        blend.addView(LayerIconView(context, LayerIcon.CHEVRON_UP, darkText), LayoutParams(dp(18), dp(18)))
        row.addView(blend, LayoutParams(0, dp(35), 1.7f))
        return row
    }

    // ---- Lista de capas ----

    private var rebuildQueued = false

    /**
     * Pide armar la lista de nuevo. Se hace siempre DESPUES del evento actual (con post): armarla quita todas las filas, y si
     * eso pasa dentro del toque o clic de una de esas filas el arbol de vistas queda inconsistente y la app se cierra
     * (NullPointerException en ViewGroup.hasChildWithZ). Varios pedidos seguidos arman la lista una sola vez.
     */
    private fun rebuildList() {
        if (rebuildQueued) return
        rebuildQueued = true
        post {
            rebuildQueued = false
            doRebuildList()
        }
    }

    private fun doRebuildList() {
        listBox.removeAllViews()
        selectedOpacityLabel = null
        val g = geo()
        val sel = g?.selectedLayer()
        blendLabel.text = sel?.blendMode?.label ?: BlendMode.NORMAL.label
        if (sel != null) opacityRow.setValue(sel.opacity)
        // Bloqueo alfa activo: el boton se resalta en naranja.
        alphaLockButton.background = if (sel != null && sel.alphaLock) highlight() else null
        // Recorte activo: igual, el boton se resalta en naranja.
        clipButton.background = if (sel != null && sel.clipToBelow) highlight() else null
        listBox.addView(buildSelectionRow())
        listBox.addView(divider())
        if (g == null) {
            val msg = label("Importa un modelo (File > Import) para usar capas.", 14f, darkText)
            msg.setPadding(dp(12), dp(16), dp(12), dp(16))
            listBox.addView(msg)
            return
        }
        // La lista se muestra de arriba hacia abajo; las capas se guardan de abajo hacia arriba.
        val all = g.layers.toList()
        for (layer in all.reversed()) {
            // Las capas de una carpeta plegada no salen en la lista (se siguen componiendo igual).
            if (layer.folderId >= 0 && all.any { it.id == layer.folderId && it.collapsed }) continue
            listBox.addView(if (layer.isFolder) buildFolderRow(g, layer).also { addFolderHandle(g, layer, it as LinearLayout) } else buildLayerRow(g, layer))
            listBox.addView(divider())
        }
    }

    /** Fila fija de arriba: la capa de seleccion. Titulo, icono punteado y "No seleccionado", uno debajo del otro. */
    private fun buildSelectionRow(): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6), dp(3), dp(6), dp(3))
        row.isClickable = true
        row.setOnClickListener { clearSelection() }
        // Con seleccion activa la miniatura muestra la zona seleccionada y el texto cambia a "Seleccionado" (tocar la fila la quita).
        val selGeo = geo()
        row.addView(ThumbView(context, ThumbKind.SELECTION, selGeo?.selectionMask != null, selGeo?.selectionThumbnail(THUMB_SAMPLES)), LayoutParams(dp(50), dp(65)))

        val info = LinearLayout(context)
        info.orientation = VERTICAL
        info.addView(compactLabel("Capa de selección", 16f, darkText))
        val sub = LinearLayout(context)
        sub.orientation = VERTICAL
        sub.gravity = Gravity.CENTER_VERTICAL
        sub.addView(LayerIconView(context, LayerIcon.SELECTION, darkText), LayoutParams(dp(28), dp(28)))
        sub.addView(compactLabel(if (selGeo?.selectionMask != null) "Seleccionado" else "No seleccionado", 16f, darkText), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = 0
        })
        info.addView(sub)
        row.addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        return row
    }

    /**
     * Fila de una capa: miniatura con lo pintado, nombre, ojo, opacidad, modo de mezcla y asa. La elegida va resaltada en
     * azul. Una capa oculta (o que Modo Solo esta escondiendo) se ve atenuada.
     */
    private fun buildLayerRow(g: TexturedMeshGeometry, layer: PaintLayer): View {
        val selected = layer.id == g.selectedLayerId
        val dimmed = !layer.visible || (g.soloLayerId >= 0 && g.soloLayerId != layer.id)
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6), dp(3), dp(6), dp(3))
        row.setBackgroundColor(if (selected) selectedBg else Color.TRANSPARENT)
        row.isClickable = true
        row.setOnClickListener {
            g.selectedLayerId = layer.id
            rebuildList()
        }

        if (layer.folderId >= 0) row.setPadding(dp(24), dp(3), dp(6), dp(3))
        // Miniatura vertical con lo pintado en la capa; la de la capa elegida lleva el borde azul.
        val thumb = ThumbView(context, ThumbKind.CHECKER, selected, layer.thumbnail(THUMB_SAMPLES, g.texSize))
        thumb.alpha = if (dimmed) 0.45f else 1f
        row.addView(thumb, LayoutParams(dp(50), dp(65)))

        // Columna de datos: nombre arriba, ojo + opacidad en medio y modo de mezcla abajo (opacidad y modo pegados a la derecha).
        val info = LinearLayout(context)
        info.orientation = VERTICAL
        info.alpha = if (dimmed) 0.45f else 1f
        info.addView(buildNameLine(layer), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = 0
        })
        val middle = LinearLayout(context)
        middle.orientation = HORIZONTAL
        middle.gravity = Gravity.CENTER_VERTICAL
        // Ojo grande (casi sin padding) para que se vea como el de ibisPaint.
        val eye = LayerIconView(context, LayerIcon.EYE, Color.rgb(115, 115, 115))
        eye.setPadding(dp(1), 0, dp(1), 0)
        eye.layoutParams = LayoutParams(dp(30), dp(26))
        eye.isClickable = true
        eye.setOnClickListener {
            layer.visible = !layer.visible
            g.requestRecomposite()
            rebuildList()
        }
        middle.addView(eye)
        middle.addView(View(context), LayoutParams(0, 1, 1f))
        val opacityText = compactLabel(layer.opacity.toString() + "%", 17f, darkText)
        if (selected) selectedOpacityLabel = opacityText
        middle.addView(opacityText)
        info.addView(middle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val blendText = compactLabel(layer.blendMode.label, 17f, darkText)
        blendText.gravity = Gravity.END
        // Los nombres largos ("Subexposición lineal") no deben agrandar la fila: se cortan con "...".
        blendText.maxLines = 1
        blendText.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(blendText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        row.addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(1)
        })

        // Asa: se arrastra hacia arriba o abajo y al soltar la capa pasa a donde quedo, respetando las carpetas (ver dragListener y computeDrop).
        val handle = iconButton(LayerIcon.HANDLE, 40, Color.rgb(150, 150, 150), "Reordenar capas")
        handle.setOnTouchListener(dragListener(g, layer, row, selected))
        row.addView(handle)
        return row
    }

    /** Fila de una carpeta: flecha de plegado, icono, nombre, ojo, opacidad y modo de mezcla. */
    private fun buildFolderRow(g: TexturedMeshGeometry, folder: PaintLayer): View {
        val selected = folder.id == g.selectedLayerId
        val dimmed = !folder.visible
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6), dp(3), dp(6), dp(3))
        row.setBackgroundColor(if (selected) selectedBg else Color.TRANSPARENT)
        row.isClickable = true
        row.setOnClickListener {
            g.selectedLayerId = folder.id
            rebuildList()
        }

        // Flecha: apunta a la derecha si esta plegada y hacia abajo si esta abierta.
        val arrow = LayerIconView(context, LayerIcon.CHEVRON_UP, Color.rgb(115, 115, 115))
        arrow.rotation = if (folder.collapsed) 90f else 180f
        arrow.setPadding(dp(4), dp(4), dp(4), dp(4))
        arrow.isClickable = true
        arrow.setOnClickListener {
            folder.collapsed = !folder.collapsed
            rebuildList()
        }
        row.addView(arrow, LayoutParams(dp(30), dp(30)))

        val icon = ImageView(context)
        icon.setImageResource(R.drawable.ic_layer_folder)
        icon.setColorFilter(Color.rgb(115, 115, 115))
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.alpha = if (dimmed) 0.45f else 1f
        row.addView(icon, LayoutParams(dp(40), dp(40)))

        val info = LinearLayout(context)
        info.orientation = VERTICAL
        info.alpha = if (dimmed) 0.45f else 1f
        info.addView(buildNameLine(folder), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val middle = LinearLayout(context)
        middle.orientation = HORIZONTAL
        middle.gravity = Gravity.CENTER_VERTICAL
        val eye = LayerIconView(context, LayerIcon.EYE, Color.rgb(115, 115, 115))
        eye.setPadding(dp(1), 0, dp(1), 0)
        eye.layoutParams = LayoutParams(dp(30), dp(26))
        eye.isClickable = true
        eye.setOnClickListener {
            folder.visible = !folder.visible
            g.requestRecomposite()
            rebuildList()
        }
        middle.addView(eye)
        middle.addView(View(context), LayoutParams(0, 1, 1f))
        val opacityText = compactLabel(folder.opacity.toString() + "%", 17f, darkText)
        if (selected) selectedOpacityLabel = opacityText
        middle.addView(opacityText)
        info.addView(middle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val blendText = compactLabel(folder.blendMode.label, 17f, darkText)
        blendText.gravity = Gravity.END
        blendText.maxLines = 1
        blendText.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(blendText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        row.addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        return row
    }

    /**
     * Donde cae una capa o carpeta arrastrada con el asa, steps filas hacia abajo (positivo) o hacia arriba (negativo) en la
     * lista que se ve. Devuelve (id de la carpeta destino o -1 si queda suelta, posicion entre las de su grupo contada desde
     * abajo) para TexturedMeshGeometry.arrangeLayer, o null si no hay nada que mover. Reglas para una capa: si cae entre las
     * capas de una carpeta, entra en ella; justo debajo de una carpeta abierta y vacia, tambien; al final de una carpeta, entra
     * si venia de afuera y sale si ya estaba dentro (la ultima capa de una carpeta sale con el primer paso hacia abajo). Una
     * carpeta siempre queda suelta y, si cae entre las capas de otra, se pega a ella.
     */
    private fun computeDrop(g: TexturedMeshGeometry, d: PaintLayer, steps: Int): Pair<Int, Int>? {
        val all = g.layers.toList()
        // Lo que se ve, de arriba hacia abajo (las capas de una carpeta plegada no salen).
        val vis = all.reversed().filter { l -> l.folderId < 0 || all.none { it.id == l.folderId && it.collapsed } }
        val from = vis.indexOfFirst { it.id == d.id }
        if (from < 0 || steps == 0) return null
        // Lo que queda sin la fila arrastrada (ni, si es carpeta, sus capas).
        val rest = vis.filter { it.id != d.id && it.folderId != d.id }
        var s = steps
        if (!d.isFolder && d.folderId >= 0 && s > 0 && vis.getOrNull(from + 1)?.folderId != d.folderId) s -= 1
        var t = (from + s).coerceIn(0, rest.size)
        val above = rest.getOrNull(t - 1)
        val below = rest.getOrNull(t)
        if (d.isFolder) {
            if (below != null && below.folderId >= 0) {
                val f = below.folderId
                t = if (steps < 0) rest.indexOfFirst { it.id == f } else rest.indexOfLast { it.folderId == f } + 1
            }
            return Pair(-1, rest.drop(t).count { it.folderId < 0 })
        }
        val parent = when {
            below != null && below.folderId >= 0 -> below.folderId
            above != null && above.isFolder && !above.collapsed -> if (d.folderId == above.id) -1 else above.id
            above != null && above.folderId >= 0 -> if (d.folderId == above.folderId) -1 else above.folderId
            else -> -1
        }
        val below2 = rest.drop(t)
        return Pair(parent, if (parent < 0) below2.count { it.folderId < 0 } else below2.count { it.folderId == parent })
    }

    /** Pone el asa de reordenar al final de la fila de una carpeta (ver buildFolderRow y dragListener). */
    private fun addFolderHandle(g: TexturedMeshGeometry, folder: PaintLayer, row: LinearLayout) {
        val handle = iconButton(LayerIcon.HANDLE, 40, Color.rgb(150, 150, 150), "Reordenar carpetas")
        handle.setOnTouchListener(dragListener(g, folder, row, folder.id == g.selectedLayerId))
        row.addView(handle)
    }

    /** Listener del asa de una fila (capa o carpeta): arrastra la fila y al soltar la coloca con arrangeLayer (ver computeDrop). */
    private fun dragListener(g: TexturedMeshGeometry, layer: PaintLayer, row: View, selected: Boolean): View.OnTouchListener =
        object : View.OnTouchListener {
            private var startY = 0f

            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        row.translationZ = 8 * d
                        row.setBackgroundColor(if (selected) selectedBg else lightBg)
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        row.translationY = event.rawY - startY
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val dy = event.rawY - startY
                        row.translationY = 0f
                        row.translationZ = 0f
                        val steps = Math.round(dy / (row.height + 1).coerceAtLeast(1))
                        val drop = if (event.actionMasked == android.view.MotionEvent.ACTION_UP) computeDrop(g, layer, steps) else null
                        if (drop != null) editLayers { it.arrangeLayer(layer.id, drop.first, drop.second) } else rebuildList()
                        return true
                    }
                }
                return false
            }
        }

    /** Linea con el nombre de la capa y, si esta bloqueada, un candadito a la derecha. */
    private fun buildNameLine(layer: PaintLayer): View {
        val line = LinearLayout(context)
        line.orientation = HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        val name = compactLabel(layer.name, 17f, darkText)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        line.addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (layer.clipToBelow) {
            line.addView(LayerIconView(context, LayerIcon.CLIP, Color.rgb(115, 115, 115)), LayoutParams(dp(16), dp(16)))
        }
        if (layer.locked) {
            line.addView(LayerIconView(context, LayerIcon.LOCK, Color.rgb(115, 115, 115)), LayoutParams(dp(16), dp(16)))
        }
        return line
    }

    // ---- Acciones sobre las capas ----

    /** Slider de opacidad de abajo: cambia la opacidad de la capa elegida y recompone la textura. */
    private fun onOpacityChanged(value: Int) {
        val g = geo() ?: return
        val layer = g.selectedLayer() ?: return
        layer.opacity = value
        g.requestRecomposite()
        selectedOpacityLabel?.text = value.toString() + "%"
    }

    private fun addLayer() {
        editLayers { g ->
            if (g.addLayer() == null) toast("Máximo $MAX_LAYERS capas")
        }
    }

    /** Clona la capa elegida: la copia va justo encima, con el mismo contenido, opacidad y visibilidad, y queda elegida. */
    private fun cloneLayer() {
        editLayers { g ->
            if (g.cloneSelectedLayer() == null) toast("No se pudo clonar (máximo $MAX_LAYERS capas)")
        }
    }

    /** Añade una carpeta vacía encima de la elegida. Para meter capas en ella: elegirla y pulsar "Añadir capa". */
    private fun addFolder() {
        editLayers { g ->
            if (g.addFolder() == null) toast("Máximo $MAX_FOLDERS carpetas")
        }
    }

    /** Selecciona lo pintado en la capa elegida (segun su opacidad). Despues solo se pinta o borra dentro de la seleccion. No se puede deshacer. */
    private fun selectByOpacity() {
        val layer = requireLayer() ?: return
        if (layer.isFolder) {
            toast("Elige una capa, no una carpeta")
            return
        }
        editLayers { g ->
            if (g.selectByOpacity()) toast("Selección creada: solo se pinta dentro de ella") else toast("La capa está vacía: no hay nada que seleccionar")
        }
    }

    /** Quita la seleccion (se toca la fila "Capa de seleccion"). */
    private fun clearSelection() {
        val g = geo()
        if (g == null || g.selectionMask == null) {
            toast("No hay nada seleccionado")
            return
        }
        g.clearSelection()
        toast("Selección quitada")
        rebuildList()
    }

    /** Deja la capa elegida transparente. Se puede deshacer. */
    private fun clearLayer() {
        val layer = requireLayer() ?: return
        if (layer.locked) {
            toast("Capa bloqueada")
            return
        }
        editLayers { g -> g.clearSelectedLayer() }
    }

    /** Invierte el color de lo pintado en la capa elegida. Se puede deshacer. */
    private fun invertLayer() {
        val layer = requireLayer() ?: return
        if (layer.locked) {
            toast("Capa bloqueada")
            return
        }
        editLayers { g -> g.invertSelectedLayer() }
    }

    /** Combina la capa elegida con la de abajo. No se puede deshacer (vacia el historial de Deshacer). */
    private fun mergeDown() {
        val g = geo()
        val layer = g?.selectedLayer()
        if (g == null || layer == null) {
            toast("Importa un modelo para usar capas")
            return
        }
        val index = g.layers.indexOf(layer)
        if (index <= 0) {
            toast("No hay una capa debajo")
            return
        }
        if (layer.isFolder || g.layers[index - 1].isFolder || layer.folderId != g.layers[index - 1].folderId) {
            toast("Solo se combinan dos capas del mismo grupo")
            return
        }
        if (layer.locked || g.layers[index - 1].locked) {
            toast("Capa bloqueada")
            return
        }
        editLayers(true) { it.mergeSelectedDown() }
    }

    /** Pregunta antes de borrar la capa elegida (no se puede deshacer, y vacia el historial de Deshacer). */
    private fun confirmDelete() {
        val g = geo()
        val layer = g?.selectedLayer()
        if (g == null || layer == null) {
            toast("Importa un modelo para usar capas")
            return
        }
        if (layer.locked) {
            toast("Capa bloqueada: desbloquéala primero")
            return
        }
        // Siempre tiene que quedar al menos una capa con pixeles (una carpeta se lleva las suyas).
        val remaining = g.layers.count { !it.isFolder && it.id != layer.id && it.folderId != layer.id }
        if (remaining < 1) {
            toast("Tiene que quedar al menos una capa")
            return
        }
        AlertDialog.Builder(context)
            .setTitle(if (layer.isFolder) "Borrar carpeta" else "Borrar capa")
            .setMessage("¿Borrar " + (if (layer.isFolder) "la carpeta \"" + layer.name + "\" y todas sus capas" else "la capa \"" + layer.name + "\"") + "? No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                editLayers(true) { it.deleteSelectedLayer() }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Cambia el fondo del modelo (blanco, tablero claro, tablero oscuro o sin fondo) y recompone la textura; se ve al instante. */
    private fun setBackground(bg: BackgroundKind) {
        val g = geo()
        if (g == null) {
            toast("Importa un modelo para usar capas")
            return
        }
        g.background = bg
        g.requestRecomposite()
        for ((kind, swatch) in backgroundSwatches) {
            swatch.chosen = kind == bg
            swatch.invalidate()
        }
    }

    /** Recorte de la capa elegida: solo se ve donde la capa de abajo tiene algo pintado. La capa de mas abajo no se puede recortar. */
    private fun toggleClip() {
        val g = geo()
        val layer = g?.selectedLayer()
        if (g == null || layer == null) {
            toast("Importa un modelo para usar capas")
            return
        }
        if (g.layers.indexOf(layer) <= 0) {
            toast("No hay una capa debajo para recortar")
            return
        }
        layer.clipToBelow = !layer.clipToBelow
        g.requestRecomposite()
        rebuildList()
        toast(if (layer.clipToBelow) "Recorte activado" else "Recorte desactivado")
    }

    /** Bloqueo alfa de la capa elegida: solo se pinta donde la capa ya tiene algo. */
    private fun toggleAlphaLock() {
        val layer = requireLayer() ?: return
        layer.alphaLock = !layer.alphaLock
        rebuildList()
        toast(if (layer.alphaLock) "Bloqueo alfa activado" else "Bloqueo alfa desactivado")
    }

    /**
     * Guarda la capa elegida como PNG transparente (tal cual, sin aplicar opacidad ni visibilidad). El selector de archivos
     * y la escritura los maneja MainActivity (ver MainActivity.startExport); aca solo se busca la actividad.
     */
    private fun saveLayerAsPng() {
        if (requireLayer() == null) return
        var ctx: Context = context
        while (ctx is ContextWrapper) {
            if (ctx is MainActivity) {
                ctx.startExport(ExportKind.LAYER)
                return
            }
            ctx = ctx.baseContext
        }
        toast("No se pudo abrir el selector de archivos")
    }

    // ---- Menu de los tres puntitos ----

    /** Boton de los tres puntitos (abajo de la columna de acciones): abre el menu de opciones de la capa elegida. */
    private fun dotsButton(label: String): View {
        val v = iconButton(LayerIcon.DOTS, 35, Color.WHITE, label)
        v.setOnClickListener { showMoreMenu(v) }
        return v
    }

    /** Desplegable de "Mas opciones" (sale hacia arriba desde los tres puntitos). Renombrar, Bloquear, Modo Solo y Guardar como PNG ya funcionan; el resto avisa "proximamente". */
    private fun showMoreMenu(anchor: View) {
        val layer = selectedLayer()
        val soloOn = (geo()?.soloLayerId ?: -1) >= 0
        val popupW = minOf(dp(250), resources.displayMetrics.widthPixels - dp(16))
        val itemH = dp(42)
        val options = listOf<Pair<String, () -> Unit>>(
            Pair("Selecciona la opacidad", { selectByOpacity() }),
            Pair("Renombrar capa", { showRenameDialog() }),
            Pair(if (layer != null && layer.locked) "Desbloquear capa" else "Bloquear capa", { toggleLock() }),
            Pair(if (soloOn) "Terminar Modo Solo" else "Iniciar Modo Solo", { toggleSolo() }),
            Pair("Guardar como PNG Transparente", { saveLayerAsPng() })
        )
        val popupH = options.size * itemH + dp(8)

        val column = LinearLayout(context)
        column.orientation = VERTICAL
        column.setPadding(0, dp(4), 0, dp(4))
        val popup = PopupWindow(column, popupW, popupH, true)
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * d
            setColor(Color.WHITE)
        })
        popup.elevation = 12 * d

        for ((text, action) in options) {
            val item = label(text, 13f, darkText)
            item.gravity = Gravity.CENTER_VERTICAL
            item.setPadding(dp(16), 0, dp(12), 0)
            item.maxLines = 1
            item.ellipsize = android.text.TextUtils.TruncateAt.END
            item.isClickable = true
            item.setOnClickListener {
                popup.dismiss()
                action()
            }
            column.addView(item, LayoutParams(LayoutParams.MATCH_PARENT, itemH))
        }

        // Sale hacia arriba, alineado al borde derecho de los tres puntitos.
        popup.showAsDropDown(anchor, anchor.width - popupW, -(anchor.height + popupH))
    }

    /** Pide un nombre nuevo para la capa elegida y se lo pone (no acepta nombres vacios). */
    private fun showRenameDialog() {
        val layer = requireLayer() ?: return
        val input = EditText(context)
        input.setSingleLine(true)
        input.setText(layer.name)
        input.setSelection(input.text.length)
        val box = FrameLayout(context)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(context)
            .setTitle("Renombrar capa")
            .setView(box)
            .setPositiveButton("Aceptar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    layer.name = newName
                    rebuildList()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Bloquea o desbloquea la capa elegida: una capa bloqueada no se puede pintar, limpiar, invertir ni combinar. */
    private fun toggleLock() {
        val layer = requireLayer() ?: return
        layer.locked = !layer.locked
        rebuildList()
        toast(if (layer.locked) "Capa bloqueada" else "Capa desbloqueada")
    }

    /** Modo Solo: muestra solo la capa elegida (las demas se ocultan sin perder su visibilidad); otra vez lo termina. */
    private fun toggleSolo() {
        val g = geo()
        val layer = g?.selectedLayer()
        if (g == null || layer == null) {
            toast("Importa un modelo para usar capas")
            return
        }
        if (g.soloLayerId >= 0) {
            g.soloLayerId = -1
            toast("Modo Solo terminado")
        } else {
            g.soloLayerId = layer.id
            toast("Modo Solo: solo se ve la capa " + layer.name)
        }
        g.requestRecomposite()
        rebuildList()
    }

    /** Cambia el modo de mezcla de la capa elegida y recompone la textura (se ve al instante en el modelo). */
    private fun setBlendMode(mode: BlendMode) {
        val g = geo()
        val layer = g?.selectedLayer()
        if (g == null || layer == null) {
            toast("Importa un modelo para usar capas")
            return
        }
        layer.blendMode = mode
        g.requestRecomposite()
        rebuildList()
    }

    // ---- Modos de mezcla ----

    private class BlendGroup(val title: String?, val modes: List<BlendMode>)

    private val blendGroups = listOf(
        BlendGroup(null, listOf(BlendMode.NORMAL)),
        BlendGroup("Oscurecer", listOf(BlendMode.DARKEN, BlendMode.MULTIPLY, BlendMode.COLOR_BURN, BlendMode.LINEAR_BURN, BlendMode.DARKER_COLOR)),
        BlendGroup("Aclarar", listOf(BlendMode.LIGHTEN, BlendMode.SCREEN, BlendMode.COLOR_DODGE, BlendMode.LINEAR_DODGE, BlendMode.ADD, BlendMode.LIGHTER_COLOR)),
        BlendGroup("Contraste", listOf(BlendMode.OVERLAY, BlendMode.SOFT_LIGHT, BlendMode.HARD_LIGHT, BlendMode.VIVID_LIGHT, BlendMode.LINEAR_LIGHT, BlendMode.PIN_LIGHT, BlendMode.HARD_MIX)),
        BlendGroup("Diferencia", listOf(BlendMode.INVERT, BlendMode.DIFFERENCE, BlendMode.EXCLUSION, BlendMode.SUBTRACT, BlendMode.DIVIDE)),
        BlendGroup("Color", listOf(BlendMode.HUE, BlendMode.SATURATION, BlendMode.COLOR, BlendMode.LUMINOSITY))
    )

    /** Desplegable con los modos de mezcla (por secciones), que sale hacia arriba desde el cuadro "Normal". Elegir uno lo aplica a la capa elegida y se ve al instante. */
    private fun showBlendMenu(anchor: View) {
        val popupW = minOf(dp(240), resources.displayMetrics.widthPixels - dp(16))
        val currentMode = selectedLayer()?.blendMode ?: BlendMode.NORMAL
        val popupH = dp(300)

        val column = LinearLayout(context)
        column.orientation = VERTICAL
        column.setPadding(0, dp(4), 0, dp(4))
        val scroll = ScrollView(context)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val popup = PopupWindow(scroll, popupW, popupH, true)
        popup.isOutsideTouchable = true
        // El fondo blanco redondeado va en toda la ventana (no en la lista interna), asi nada queda fuera de el.
        popup.setBackgroundDrawable(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * d
            setColor(Color.WHITE)
        })
        popup.elevation = 12 * d

        for (group in blendGroups) {
            if (group.title != null) {
                val header = label(group.title, 11f, Color.rgb(130, 130, 130))
                header.setTypeface(header.typeface, android.graphics.Typeface.BOLD)
                header.setPadding(dp(12), dp(8), dp(12), dp(2))
                column.addView(header)
            }
            for (mode in group.modes) {
                val item = label(mode.label, 13f, darkText)
                item.setPadding(dp(16), dp(9), dp(12), dp(9))
                item.maxLines = 1
                item.ellipsize = android.text.TextUtils.TruncateAt.END
                item.setBackgroundColor(if (mode == currentMode) selectedBg else Color.TRANSPARENT)
                item.isClickable = true
                item.setOnClickListener {
                    popup.dismiss()
                    setBlendMode(mode)
                }
                column.addView(item, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        }

        // Sale hacia arriba, alineado al borde derecho del cuadro.
        popup.showAsDropDown(anchor, anchor.width - popupW, -(anchor.height + popupH))
    }

    // ---- Utilidades ----

    private fun dp(v: Int): Int = (v * d).toInt()

    /** Fondo naranja redondeado para marcar un boton activo (por ejemplo el bloqueo alfa). */
    private fun highlight() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 8 * d
        setColor(accent)
    }

    /** Aviso corto; se puede llamar desde cualquier hilo (se muestra en el hilo de la interfaz). */
    private fun toast(message: String) {
        post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private fun soon(what: String) {
        toast(what + ": próximamente")
    }

    private fun label(text: String, sizeSp: Float, color: Int): TextView {
        val t = TextView(context)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(color)
        return t
    }

    /** Igual que label pero sin el relleno extra de la fuente (arriba y abajo), para que las filas de capa no crezcan. */
    private fun compactLabel(text: String, sizeSp: Float, color: Int): TextView {
        val t = label(text, sizeSp, color)
        t.includeFontPadding = false
        return t
    }

    private fun divider(): View {
        val v = View(context)
        v.setBackgroundColor(dividerColor)
        v.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
        return v
    }

    /** Igual que iconButton pero con un vector de res/drawable teñido del color dado. */
    private fun drawableButton(resId: Int, sizeDp: Int, color: Int, label: String): View {
        val v = ImageView(context)
        v.setImageResource(resId)
        v.setColorFilter(color)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        val p = dp(sizeDp / 5)
        v.setPadding(p, p, p, p)
        v.layoutParams = LayoutParams(dp(sizeDp), dp(sizeDp))
        v.isClickable = true
        v.setOnClickListener { soon(label) }
        return v
    }

    /** Icono tocable de sizeDp x sizeDp; al tocarlo avisa "<label>: proximamente" (salvo que se le cambie el clic). */
    private fun iconButton(icon: LayerIcon, sizeDp: Int, color: Int, label: String): View {
        val v = LayerIconView(context, icon, color)
        val p = dp(sizeDp / 5)
        v.setPadding(p, p, p, p)
        v.layoutParams = LayoutParams(dp(sizeDp), dp(sizeDp))
        v.isClickable = true
        v.setOnClickListener { soon(label) }
        return v
    }
}
