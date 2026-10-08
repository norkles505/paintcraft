package com.meshcraft.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Panel de pintura estilo ibisPaint, abajo de la pantalla (solo visible con la herramienta Paint, ver MainActivity).
 *
 * De arriba hacia abajo: panel desplegable (herramientas, tipos de pincel, color o capas), sliders de tamano y
 * opacidad, y la barra de 6 botones:
 *   1. Cambiar: icono fijo de intercambio; alterna con la herramienta usada justo antes.
 *   2. Herramienta: icono de la herramienta activa; abre el panel con todas las herramientas.
 *   3. Tipo de pincel: abre la lista de pinceles (cada fila con su trazo de muestra, nombre y tamano).
 *   4. Color: cuadro con el color actual; abre el selector de color.
 *   5. Ocultar: triangulo que esconde todo menos si mismo.
 *   6. Capas: panel de capas reales del modelo (LayersPanel).
 * El panel de herramientas trae ademas Mano (rotar / desplazar la camara) y Bloqueo (bloquear la camara): no son herramientas
 * de pintura, solo se alternan ahi y, como las herramientas, cierran el panel (la accion la hace MainActivity, ver onHandToggle / onLockToggle).
 * Los cambios se aplican directo en MyGLRenderer (paintTool, paintBrushType, paintColor, paintRadius, paintOpacity).
 */
class PaintPanel(context: Context, private val renderer: MyGLRenderer) : LinearLayout(context) {

    private enum class Popup { NONE, TOOLS, BRUSHES, COLOR, LAYERS }

    /** Fondo de cuadritos blancos y grises (el clasico de "transparencia"), para la cabecera de la lista de pinceles. */
    private class CheckerboardDrawable(private val cell: Float) : android.graphics.drawable.Drawable() {
        private val light = android.graphics.Paint().apply { color = Color.WHITE }
        private val dark = android.graphics.Paint().apply { color = Color.rgb(204, 204, 204) }

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), light)
            var row = 0
            var y = b.top.toFloat()
            while (y < b.bottom) {
                var col = 0
                var x = b.left.toFloat()
                while (x < b.right) {
                    if ((row + col) % 2 == 1) {
                        canvas.drawRect(x, y, minOf(x + cell, b.right.toFloat()), minOf(y + cell, b.bottom.toFloat()), dark)
                    }
                    x += cell
                    col++
                }
                y += cell
                row++
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    private class GridItem(val iconRes: Int, val label: String, val active: Boolean, val enabled: Boolean, val onClick: () -> Unit)

    /** Una categoria de la barra negra de la lista de pinceles: nombre e icono (iconRes = 0: todavia sin icono). */
    private class BrushCategory(val label: String, val iconRes: Int)

    private val d = resources.displayMetrics.density
    /** Alto compartido por el panel de color y el de capas (para que al cambiar de uno a otro no salte). */
    private val bigPopupHeight = (490 * d).toInt()
    /** Alto de la lista de pinceles. */
    private val brushPopupHeight = (580 * d).toInt()
    private val accent = Color.argb(235, 242, 128, 26)
    /** Ancho que ocupan los botones + y > a la derecha de cada fila de pinceles (2 botones de 36 dp con sus espacios y el margen del borde). */
    private val brushRowButtonsPx = (84 * d).toInt()

    // Tamano de los botones de la barra: son 6 en total (5 botones y el cuadro de color) y en pantallas angostas no caben todos, asi que
    // se reparte el ancho disponible (pantalla menos los margenes y el relleno del panel, y menos el cuadro de color que no cambia).
    // Nunca pasan de 40 dp, el tamano de siempre.
    private val swatchPx = (34 * d).toInt()
    private val barSlotPx = (resources.displayMetrics.widthPixels - (32 * d).toInt() - (swatchPx + (8 * d).toInt())) / 5
    private val buttonSizePx = minOf((40 * d).toInt(), barSlotPx - (4 * d).toInt())
    private val buttonMarginPx = (barSlotPx - buttonSizePx) / 2

    private var currentTool = PaintTool.BRUSH
    private var previousTool = PaintTool.ERASER
    private var currentBrushType = BrushType.SOFT
    /** Tamano (radio en texeles) que recuerda cada pincel: al elegirlo se vuelve a poner ese tamano (empieza en BrushType.defaultSize). */
    private val brushSizes = HashMap<BrushType, Float>().also { m -> for (bt in BrushType.values()) m[bt] = bt.defaultSize }
    /** Numeros de tamano de la lista de pinceles abierta, para actualizarlos mientras se mueve el slider. */
    private val brushSizeLabels = HashMap<BrushType, TextView>()
    /** Trazos de muestra ya dibujados (no cambian: se dibujan con el tamano por defecto de cada pincel). */
    private val brushPreviews = HashMap<BrushType, Bitmap>()
    /** Trazos de muestra de las filas de la lista: mas cortos que los de la cabecera, para dejar sitio a los botones + y >. */
    private val brushRowPreviews = HashMap<BrushType, Bitmap>()
    private var openPopup = Popup.NONE
    private var barHidden = false
    /** Pestana elegida en la lista de pinceles: 0 = Basico, 1 = Personalizado. */
    private var brushTab = 0
    /** Categorias de la barra negra de la lista de pinceles. Los iconos (iconRes) se ponen cuando lleguen los SVG: mientras tanto queda el espacio vacio. */
    private val brushCategories = listOf(
        BrushCategory("Todos", 0),
        BrushCategory("Simple", 0),
        BrushCategory("Bosquejo", 0),
        BrushCategory("Cómic", 0),
        BrushCategory("Tinta", 0),
        BrushCategory("Vector", 0),
        BrushCategory("Aerógrafo", 0),
        BrushCategory("Acuarela (plana)", 0)
    )
    /** Categoria elegida en la barra negra (indice en brushCategories). */
    private var brushCategory = 0
    /** Ancho de la barra negra de categorias. */
    private val brushCategoryBarPx = (65 * d).toInt()
    /** Al pulsar Mano (panel de herramientas): MainActivity cambia entre rotar y desplazar la camara y devuelve true si quedo en modo desplazar. */
    var onHandToggle: (() -> Boolean)? = null
    /** Al pulsar Bloqueo (panel de herramientas): MainActivity bloquea/desbloquea la camara y devuelve true si quedo bloqueada. */
    var onLockToggle: (() -> Boolean)? = null
    private var handActive = false
    private var lockActive = false

    private val popupContainer = FrameLayout(context)
    private val slidersBox = LinearLayout(context)
    // Sliders estilo ibisPaint: tamano del pincel (radio en texeles, 1..100) y opacidad (0..100).
    private val sizeRow = IbisSliderRow(context, 990, 170, 5, false, { "%.1f".format(1f + it / 10f) }) {
        val radius = 1f + it / 10f
        renderer.paintRadius = radius
        // El tamano queda guardado para el pincel actual y se actualiza su numero en la lista (si esta abierta).
        brushSizes[currentBrushType] = radius
        brushSizeLabels[currentBrushType]?.text = "%.1f".format(radius)
    }
    private val opacityRow: IbisSliderRow = IbisSliderRow(context, 100, 100, 1, true, { it.toString() }) { renderer.paintOpacity = it / 100f; colorPanel.setOpacity(it) }
    private val colorPanel: ColorPanel = ColorPanel(context, { r, g, b -> onPickerColor(r, g, b) }) { p -> renderer.paintOpacity = p / 100f; opacityRow.setValue(p) }
    private val colorBtn = View(context)
    private val switchBtn = makeBarButton(R.drawable.ic_swap)
    private val toolBtn = makeBarButton(iconFor(currentTool))
    private val brushTypeBtn = makeBarButton(R.drawable.ic_brush_type)
    private val hideBtn = makeBarButton(R.drawable.ic_triangle_down)
    private val layersBtn = makeBarButton(R.drawable.ic_layers)
    private val bar = LinearLayout(context)
    // Todos los botones de la barra menos Ocultar (ese siempre se ve).
    private val barButtons: List<View> = listOf(switchBtn, toolBtn, brushTypeBtn, colorBtn, layersBtn)

    init {
        orientation = LinearLayout.VERTICAL
        val pad = (8 * d).toInt()
        setPadding(pad, pad, pad, pad)

        popupContainer.visibility = View.GONE
        popupContainer.setPadding(0, 0, 0, (6 * d).toInt())
        addView(popupContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Sliders: tamano del pincel (radio en texeles, 1..100) y opacidad (0..100).
        slidersBox.orientation = LinearLayout.VERTICAL
        slidersBox.addView(sizeRow)
        slidersBox.addView(opacityRow)
        // Sin fondo: los sliders quedan directo sobre el panel oscuro.
        slidersBox.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10 * d
            setColor(Color.TRANSPARENT)
        }
        slidersBox.setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
        addView(slidersBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (6 * d).toInt()
        })

        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER

        switchBtn.setOnClickListener { selectTool(if (handActive) currentTool else previousTool) }
        toolBtn.setOnClickListener { togglePopup(Popup.TOOLS) }
        brushTypeBtn.setOnClickListener { togglePopup(Popup.BRUSHES) }
        colorBtn.setOnClickListener { togglePopup(Popup.COLOR) }
        hideBtn.setOnClickListener {
            barHidden = !barHidden
            refresh()
        }
        layersBtn.setOnClickListener { togglePopup(Popup.LAYERS) }

        colorBtn.layoutParams = LinearLayout.LayoutParams(swatchPx, swatchPx).apply {
            leftMargin = (4 * d).toInt()
            rightMargin = (4 * d).toInt()
        }
        colorBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6 * d
        }
        colorBtn.isClickable = true

        for (v in listOf(switchBtn, toolBtn, brushTypeBtn, colorBtn, hideBtn, layersBtn)) bar.addView(v)
        addView(bar)

        renderer.paintTool = currentTool
        applyBrush(currentBrushType)



        // Cuentagotas: el renderer avisa desde el hilo de render, el cambio de color se aplica en el hilo de la interfaz.
        renderer.onColorPicked = { r, g, b -> post { applyColor(r, g, b, true) } }
        applyColor(225, 70, 60, true)
        refresh()
    }

    // ---- Herramientas ----

    private fun iconFor(tool: PaintTool): Int = when (tool) {
        PaintTool.BRUSH -> R.drawable.ic_brush
        PaintTool.ERASER -> R.drawable.ic_eraser
        PaintTool.EYEDROPPER -> R.drawable.ic_eyedropper
        PaintTool.BLUR -> R.drawable.ic_blur
        PaintTool.FILL -> R.drawable.ic_fill
        PaintTool.SMUDGE -> R.drawable.ic_smudge
    }

    /** Elige una herramienta desde el panel o el boton Cambiar: avisa si todavia no esta lista y cierra el panel desplegable. */
    private fun selectTool(tool: PaintTool) {
        if (!tool.implemented) {
            Toast.makeText(context, tool.label + ": próximamente", Toast.LENGTH_SHORT).show()
            return
        }
        // Elegir una herramienta de pintura saca de la mano (con la mano un dedo desplaza la vista y no pinta).
        if (handActive) handActive = onHandToggle?.invoke() ?: false
        setActiveTool(tool)
        openPopup = Popup.NONE
        rebuildPopup()
    }

    /** Cambia la herramienta activa y recuerda la anterior (la usa el boton Cambiar). */
    private fun setActiveTool(tool: PaintTool) {
        if (tool != currentTool) {
            previousTool = currentTool
            currentTool = tool
        }
        renderer.paintTool = currentTool
        refresh()
    }

    // ---- Pinceles ----

    /** Deja elegido el pincel bt: lo pasa al renderer y vuelve a poner el tamano que ese pincel tenia (en el renderer y en el slider). */
    private fun applyBrush(bt: BrushType) {
        currentBrushType = bt
        renderer.paintBrushType = bt
        val size = brushSizes[bt] ?: bt.defaultSize
        renderer.paintRadius = size
        sizeRow.setValue(((size - 1f) * 10f).roundToInt())
    }

    // ---- Color ----

    private fun applyColor(r: Int, g: Int, b: Int, updatePicker: Boolean) {
        renderer.paintColor = intArrayOf(r, g, b)
        opacityRow.setTrackColor(Color.rgb(r, g, b))
        (colorBtn.background as GradientDrawable).setColor(Color.rgb(r, g, b))
        if (updatePicker) colorPanel.setColor(r, g, b)
    }

    private fun onPickerColor(r: Int, g: Int, b: Int) {
        applyColor(r, g, b, false)
        // Elegir un color con el borrador activo vuelve al pincel.
        if (currentTool == PaintTool.ERASER) setActiveTool(PaintTool.BRUSH)
    }

    // ---- Panel desplegable ----

    private fun togglePopup(p: Popup) {
        openPopup = if (openPopup == p) Popup.NONE else p
        rebuildPopup()
    }

    private fun rebuildPopup() {
        popupContainer.removeAllViews()
        // Si los sliders estaban dentro de la lista de pinceles, vuelven a su sitio en el panel (entre el desplegable y la barra).
        if (slidersBox.parent !== this) {
            (slidersBox.parent as? android.view.ViewGroup)?.removeView(slidersBox)
            addView(slidersBox, 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (6 * d).toInt()
            })
        }
        when (openPopup) {
            Popup.TOOLS -> popupContainer.addView(buildToolsPopup())
            Popup.BRUSHES -> popupContainer.addView(buildBrushesPopup())
            Popup.COLOR -> popupContainer.addView(
                colorPanel,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, bigPopupHeight)
            )
            Popup.LAYERS -> popupContainer.addView(buildLayersPopup(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, bigPopupHeight))
            Popup.NONE -> Unit
        }
        refresh()
    }

    /**
     * Cuadricula de herramientas (pincel, borrador, cuentagotas...) y al final Mano y Bloqueo de la camara.
     * Mano y Bloqueo se encienden/apagan (naranja = activo); como las herramientas, cierran el panel al tocarlos (como ibisPaint).
     */
    private fun buildToolsPopup(): View {
        val items = PaintTool.values().map { t ->
            GridItem(iconFor(t), t.label, t == currentTool && !handActive, t.implemented) { selectTool(t) }
        }.toMutableList()
        items.add(GridItem(R.drawable.ic_hand, "Mano", handActive, true) {
            openPopup = Popup.NONE
            handActive = onHandToggle?.invoke() ?: false
            rebuildPopup()
        })
        items.add(GridItem(R.drawable.ic_lock_rotation, "Bloqueo", lockActive, true) {
            openPopup = Popup.NONE
            lockActive = onLockToggle?.invoke() ?: false
            rebuildPopup()
        })
        return buildGrid(items, 3)
    }

    /**
     * Lista de pinceles estilo ibisPaint: titulo "Pincel (N)" y una lista con scroll donde cada fila muestra el trazo de muestra
     * del pincel, su nombre y su tamano actual. Tocar una fila elige el pincel y deja la lista abierta (se cierra con el boton de pinceles).
     */
    private fun buildBrushesPopup(): View {
        // Arriba: titulo "Pincel (N)" con los 3 puntitos, cabecera con el trazo grande y pestanas Basico / Personalizado.
        // Debajo: la barra negra de categorias, la lista y los sliders (ver buildBrushesBody).
        val outer = LinearLayout(context)
        outer.orientation = LinearLayout.VERTICAL
        outer.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, brushPopupHeight)
        outer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * d
            setColor(Color.argb(100, 0, 0, 0))
        }
        outer.setPadding((7 * d).toInt(), (9 * d).toInt(), (7 * d).toInt(), (8 * d).toInt())

        val title = TextView(context)
        title.text = "Pincel (" + BrushType.values().size + ")"
        title.setTextColor(Color.WHITE)
        title.textSize = 18f
        title.setTypeface(title.typeface, Typeface.BOLD)
        val titleRow = LinearLayout(context)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.setPadding(0, 0, 0, (13 * d).toInt())
        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val menuDots = TextView(context)
        menuDots.text = "\u22EE"
        menuDots.setTextColor(Color.WHITE)
        menuDots.textSize = 18f
        menuDots.setTypeface(menuDots.typeface, Typeface.NORMAL)
        menuDots.gravity = Gravity.CENTER
        menuDots.setOnClickListener { showBrushMenu(menuDots) }
        menuDots.setPadding((10 * d).toInt(), 0, (6 * d).toInt(), 0)
        titleRow.addView(menuDots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        outer.addView(titleRow)
        outer.addView(buildBrushHeader(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (64 * d).toInt()).apply {
            bottomMargin = (8 * d).toInt()
        })
        outer.addView(buildBrushTabs(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (28 * d).toInt()).apply {
            bottomMargin = (8 * d).toInt()
        })
        outer.addView(buildBrushesBody(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return outer
    }

    private fun buildBrushesBody(): View {
        brushSizeLabels.clear()
        // Todo el panel: barra negra de categorias a la izquierda (a lo alto) y, a la derecha, la lista arriba y los sliders abajo.
        val box = LinearLayout(context)
        box.orientation = LinearLayout.HORIZONTAL
        box.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, brushPopupHeight)
        box.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * d
            setColor(Color.BLACK)
        }
        box.clipToOutline = true
        box.addView(buildBrushCategoryBar(), LinearLayout.LayoutParams(brushCategoryBarPx, LinearLayout.LayoutParams.MATCH_PARENT))

        val right = LinearLayout(context)
        right.orientation = LinearLayout.VERTICAL
        right.setBackgroundColor(Color.rgb(26, 26, 26))

        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        list.setBackgroundColor(Color.WHITE)
        for ((index, bt) in BrushType.values().withIndex()) {
            if (index > 0) {
                val divider = View(context)
                divider.setBackgroundColor(Color.rgb(205, 205, 205))
                list.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, (1 * d).toInt())))
            }
            list.addView(buildBrushRow(bt), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (50 * d).toInt()))
        }
        val scroll = ScrollView(context)
        scroll.isFillViewport = false
        scroll.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        right.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Los sliders de grosor y opacidad pasan a vivir aqui debajo de la lista mientras este abierta (rebuildPopup los devuelve al panel).
        (slidersBox.parent as? android.view.ViewGroup)?.removeView(slidersBox)
        right.addView(slidersBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        return box
    }

    /**
     * Menu de los 3 puntitos de la lista de pinceles (estilo ibisPaint): fondo claro redondeado con dos opciones, importar y exportar
     * el codigo QR de un pincel. Por ahora solo avisan "proximamente". Aparece pegado al borde derecho del boton de los puntitos.
     */
    private fun showBrushMenu(anchor: View) {
        val menu = LinearLayout(context)
        menu.orientation = LinearLayout.VERTICAL
        menu.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * d
            setColor(Color.rgb(240, 240, 240))
        }
        // Ancho fijo: el del texto mas largo + icono + rellenos (12 + 22 + 10 + 16 dp, mas 6 dp de margen para que el texto no se parta).
        // Asi el menu no queda con espacio de sobra a la derecha. Para ensancharlo o angostarlo, cambia ese 6.
        val labelPaint = android.graphics.Paint()
        labelPaint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        val textWidth = maxOf(labelPaint.measureText("Importar código QR del pincel"), labelPaint.measureText("Exportar código QR del pincel"))
        val menuWidth = ceil(textWidth).toInt() + ((12 + 22 + 10 + 16 + 6) * d).toInt()
        val popup = android.widget.PopupWindow(menu, menuWidth, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        popup.elevation = 12 * d

        menu.addView(buildBrushMenuRow(R.drawable.ic_qr_scan, "Importar código QR del pincel") {
            popup.dismiss()
            Toast.makeText(context, "Importar código QR: próximamente", Toast.LENGTH_SHORT).show()
        })
        val divider = View(context)
        divider.setBackgroundColor(Color.rgb(200, 200, 200))
        menu.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, (1 * d).toInt())))
        menu.addView(buildBrushMenuRow(R.drawable.ic_qr_code, "Exportar código QR del pincel") {
            popup.dismiss()
            Toast.makeText(context, "Exportar código QR: próximamente", Toast.LENGTH_SHORT).show()
        })

        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    /** Fila del menu de los 3 puntitos: icono opcional a la izquierda (iconRes = 0: sin icono) y el texto. */
    private fun buildBrushMenuRow(iconRes: Int, label: String, onClick: () -> Unit): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding((12 * d).toInt(), (9 * d).toInt(), (16 * d).toInt(), (9 * d).toInt())

        if (iconRes != 0) {
            val icon = ImageView(context)
            icon.setImageResource(iconRes)
            row.addView(icon, LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()).apply {
                rightMargin = (10 * d).toInt()
            })
        }

        val text = TextView(context)
        text.text = label
        text.setTextColor(Color.rgb(30, 30, 30))
        text.textSize = 13f
        row.addView(text)

        row.isClickable = true
        row.setOnClickListener { onClick() }
        return row
    }

    /** Pestanas "Basico" y "Personalizado" (estilo ibisPaint): contenedor con borde blanco, la pestana elegida va blanca con letra oscura. */
    private fun buildBrushTabs(): View {
        val tabs = LinearLayout(context)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10 * d
            setColor(Color.TRANSPARENT)
            setStroke(maxOf(1, (1.5f * d).toInt()), Color.WHITE)
        }
        // El relleno es del grosor del borde: asi las pestanas quedan dentro y no tapan la linea blanca.
        val borderPx = maxOf(1, (1.5f * d).toInt())
        tabs.setPadding(borderPx, borderPx, borderPx, borderPx)
        val names = listOf("Básico", "Personalizado")
        for ((i, n) in names.withIndex()) {
            val selected = i == brushTab
            val tab = TextView(context)
            tab.text = n
            tab.textSize = 13f
            tab.gravity = Gravity.CENTER
            tab.setTextColor(if (selected) Color.rgb(30, 30, 30) else Color.WHITE)
            // Seleccionada: blanca. No seleccionada: transparente (se ve el fondo oscuro del panel). Las esquinas externas van redondeadas.
            val r = 9 * d
            tab.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = if (i == 0) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                setColor(if (selected) Color.WHITE else Color.TRANSPARENT)
            }
            tab.isClickable = true
            tab.setOnClickListener {
                brushTab = i
                rebuildPopup()
            }
            tabs.addView(tab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        return tabs
    }

    /** Cuadro de arriba de la lista: trazo de muestra grande del pincel elegido con su nombre (como el de ibisPaint). */
    private fun buildBrushHeader(): View {
        val header = FrameLayout(context)
        header.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10 * d
            setColor(Color.rgb(232, 232, 232))
        }
        header.clipToOutline = true

        // Fondo de cuadritos (transparencia): va detras de la muestra, que tiene partes transparentes.
        val checker = View(context)
        checker.background = CheckerboardDrawable(8 * d)
        header.addView(checker, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val preview = ImageView(context)
        preview.scaleType = ImageView.ScaleType.FIT_XY
        preview.setImageBitmap(brushPreview(currentBrushType))
        header.addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val name = TextView(context)
        name.text = currentBrushType.label
        name.setTextColor(Color.rgb(30, 30, 30))
        name.textSize = 15f
        header.addView(name, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = (8 * d).toInt()
            bottomMargin = (4 * d).toInt()
        })
        return header
    }

    /** Una fila de la lista de pinceles: trazo de muestra de fondo, nombre abajo a la izquierda y tamano arriba a la derecha. */
    private fun buildBrushRow(bt: BrushType): View {
        val row = FrameLayout(context)
        row.setBackgroundColor(if (bt == currentBrushType) Color.rgb(205, 222, 247) else Color.WHITE)

        val preview = ImageView(context)
        preview.scaleType = ImageView.ScaleType.FIT_XY
        preview.setImageBitmap(brushRowPreview(bt))
        row.addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { rightMargin = brushRowButtonsPx })

        val name = TextView(context)
        name.text = bt.label
        name.setTextColor(Color.rgb(30, 30, 30))
        name.textSize = 13f
        row.addView(name, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = (8 * d).toInt()
            bottomMargin = (4 * d).toInt()
        })

        // Botones redondos de la derecha (como ibisPaint): "+" y ">". Por ahora solo avisan "proximamente".
        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER_VERTICAL
        for (symbol in listOf("+", ">")) {
            val btn = TextView(context)
            btn.text = symbol
            btn.setTextColor(Color.rgb(30, 30, 30))
            btn.textSize = 16f
            btn.gravity = Gravity.CENTER
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(maxOf(1, (1 * d).toInt()), Color.rgb(200, 200, 200))
            }
            btn.isClickable = true
            btn.setOnClickListener { Toast.makeText(context, "Próximamente", Toast.LENGTH_SHORT).show() }
            buttons.addView(btn, LinearLayout.LayoutParams((25 * d).toInt(), (25 * d).toInt()).apply { leftMargin = (8 * d).toInt() })
        }
        row.addView(buttons, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = (8 * d).toInt()
        })

        val size = TextView(context)
        size.text = "%.1f".format(brushSizes[bt] ?: bt.defaultSize)
        size.setTextColor(Color.rgb(30, 30, 30))
        size.textSize = 13f
        // El numero del tamano queda a la izquierda de los botones + y > (el relleno de la derecha lo aparta).
        size.setPadding(0, 0, brushRowButtonsPx, 0)
        brushSizeLabels[bt] = size
        row.addView(size, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = (4 * d).toInt()
            rightMargin = (10 * d).toInt()
        })

        row.isClickable = true
        row.setOnClickListener {
            applyBrush(bt)
            rebuildPopup()
        }
        return row
    }

    /** Trazo de muestra para las filas de la lista: igual que el de la cabecera pero mas corto, para que no pase por debajo de los botones + y >. */
    private fun brushRowPreview(bt: BrushType): Bitmap = brushRowPreviews.getOrPut(bt) {
        val width = maxOf(160, resources.displayMetrics.widthPixels - brushRowButtonsPx - brushCategoryBarPx)
        BrushPreview.render(bt, width, (60 * d).toInt(), d)
    }

    /** Trazo de muestra del pincel (se dibuja la primera vez y se guarda). */
    private fun brushPreview(bt: BrushType): Bitmap = brushPreviews.getOrPut(bt) {
        val width = maxOf(200, resources.displayMetrics.widthPixels - (32 * d).toInt())
        BrushPreview.render(bt, width, (60 * d).toInt(), d)
    }

    /** Panel de capas estilo ibisPaint (ver LayersPanel), conectado a las capas reales del modelo que se pinta. */
    private fun buildLayersPopup(): View = LayersPanel(context, renderer)

    /**
     * Barra negra de categorias a la izquierda de la lista de pinceles (Todos, Simple, Bosquejo...), con scroll. Sin cajas: icono encima
     * del nombre; la categoria elegida va en azul y las demas en blanco. Los iconos (iconRes) se ponen cuando lleguen los SVG.
     * Por ahora solo marca la categoria: todavia no filtra la lista (BrushType no tiene categoria).
     */
    private fun buildBrushCategoryBar(): View {
        val blue = Color.rgb(64, 128, 232)
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        for ((i, cat) in brushCategories.withIndex()) {
            val selected = i == brushCategory
            val item = LinearLayout(context)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            val icon = ImageView(context)
            if (cat.iconRes != 0) icon.setImageResource(cat.iconRes)
            icon.setColorFilter(if (selected) blue else Color.WHITE)
            item.addView(icon, LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()))
            val label = TextView(context)
            label.text = cat.label
            label.textSize = 11f
            label.gravity = Gravity.CENTER
            label.setTextColor(if (selected) blue else Color.WHITE)
            item.addView(label)
            item.isClickable = true
            item.setOnClickListener {
                brushCategory = i
                rebuildPopup()
            }
            column.addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * d).toInt()))
        }
        val scroll = ScrollView(context)
        scroll.isVerticalScrollBarEnabled = false
        scroll.setBackgroundColor(Color.BLACK)
        scroll.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    /** Cuadricula de botones con etiqueta; los que tienen enabled = false se ven apagados (herramienta todavia no lista). */
    private fun buildGrid(items: List<GridItem>, columns: Int): View {
        val grid = LinearLayout(context)
        grid.orientation = LinearLayout.VERTICAL
        for (chunk in items.chunked(columns)) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            for (item in chunk) {
                val cell = LinearLayout(context)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                val pad = (4 * d).toInt()
                cell.setPadding(0, pad, 0, pad)

                val btn = makeIconButton(item.iconRes)
                btn.background = circle(item.active)
                btn.setOnClickListener { item.onClick() }

                val label = TextView(context)
                label.text = item.label
                label.setTextColor(Color.WHITE)
                label.textSize = 11f
                label.gravity = Gravity.CENTER

                cell.addView(btn)
                cell.addView(label)
                cell.alpha = if (item.enabled) 1f else 0.45f
                cell.isClickable = true
                cell.setOnClickListener { item.onClick() }
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            // Fila incompleta: se rellena con huecos vacios para que las celdas no se estiren y queden alineadas con las de arriba.
            repeat(columns - chunk.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        return grid
    }

    // ---- Estado visual ----

    /** Actualiza iconos, resaltados y visibilidad segun el estado actual (herramienta, panel abierto, barra oculta). */
    private fun refresh() {
        val shown = !barHidden
        val usesBrushSettings = !handActive && (currentTool == PaintTool.BRUSH || currentTool == PaintTool.ERASER || currentTool == PaintTool.BLUR || currentTool == PaintTool.SMUDGE)
        popupContainer.visibility = if (shown && openPopup != Popup.NONE) View.VISIBLE else View.GONE
        // Los sliders de tamano y opacidad del pincel se esconden con el panel de color o el de capas abierto.
        slidersBox.visibility = if (shown && usesBrushSettings && openPopup != Popup.COLOR && openPopup != Popup.LAYERS) View.VISIBLE else View.GONE
        for (b in barButtons) b.visibility = if (shown) View.VISIBLE else View.GONE
        // Con el panel de capas abierto, el panel de pintura ocupa todo el ancho de la pantalla (sin margen lateral).
        (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val side = if (shown && (openPopup == Popup.LAYERS || openPopup == Popup.BRUSHES)) 0 else (8 * d).toInt()
            if (lp.leftMargin != side || lp.rightMargin != side) {
                lp.leftMargin = side
                lp.rightMargin = side
                layoutParams = lp
            }
        }


        toolBtn.setImageResource(if (handActive) R.drawable.ic_hand else iconFor(currentTool))
        toolBtn.background = circle(openPopup == Popup.TOOLS)
        brushTypeBtn.background = circle(openPopup == Popup.BRUSHES)
        layersBtn.background = circle(openPopup == Popup.LAYERS)
        (colorBtn.background as GradientDrawable).setStroke(
            (2 * d).toInt(),
            if (openPopup == Popup.COLOR) accent else Color.WHITE
        )
        hideBtn.rotation = if (barHidden) 180f else 0f

        // Oculta: sin fondo y sin consumir toques, para que solo quede flotando el triangulo.
        background = null
        isClickable = !barHidden

    }

    // ---- Piezas de interfaz ----

    private fun panelBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 14 * d
        setColor(Color.argb(245, 32, 32, 32))
    }

    private fun circle(active: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (active) accent else Color.argb(150, 40, 40, 40))
    }

    /** Boton de la barra inferior: usa el tamano repartido segun el ancho de pantalla (buttonSizePx, nunca mas de 40 dp) para que quepan todos. */
    private fun makeBarButton(iconRes: Int): ImageView {
        val p = buttonSizePx * 9 / 40
        val iv = ImageView(context)
        iv.setImageResource(iconRes)
        iv.setPadding(p, p, p, p)
        iv.background = circle(false)
        iv.layoutParams = LinearLayout.LayoutParams(buttonSizePx, buttonSizePx).apply {
            leftMargin = buttonMarginPx
            rightMargin = buttonMarginPx
        }
        iv.isClickable = true
        return iv
    }

    private fun makeIconButton(iconRes: Int): ImageView {
        val size = (40 * d).toInt()
        val p = (9 * d).toInt()
        val iv = ImageView(context)
        iv.setImageResource(iconRes)
        iv.setPadding(p, p, p, p)
        iv.background = circle(false)
        iv.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            leftMargin = (4 * d).toInt()
            rightMargin = (4 * d).toInt()
        }
        iv.isClickable = true
        return iv
    }
}

/**
 * Trazo de muestra de un pincel para la lista (ver PaintPanel.buildBrushesPopup): una curva ondulada negra pintada con los mismos
 * datos del pincel (borde segun BrushType.falloff, espaciado segun BrushType.spacing, grosor segun BrushType.defaultSize), asi la
 * muestra siempre coincide con lo que pinta y un pincel nuevo trae su muestra sin crear ninguna imagen. Como en el trazo real, una
 * pasada no pasa de la opacidad de su toque mas fuerte (se queda con el maximo en cada pixel).
 */
private object BrushPreview {
    fun render(brush: BrushType, widthPx: Int, heightPx: Int, density: Float): Bitmap {
        val alpha = FloatArray(widthPx * heightPx)
        // Radio de la muestra en pixeles: crece con el tamano del pincel, con tope para que quepa en la fila.
        val radius = ((brush.defaultSize * 0.35f + 1.5f) * density).coerceIn(1.5f * density, heightPx * 0.42f)
        val step = maxOf(1f, radius * brush.spacing)
        val margin = radius + 6f * density
        val xStart = margin
        val xEnd = widthPx - margin
        if (xEnd > xStart) {
            var x = xStart
            while (x <= xEnd) {
                val k = (x - xStart) / (xEnd - xStart)
                val cy = heightPx * 0.52f - sin(k * 2.0 * Math.PI).toFloat() * heightPx * 0.12f
                val minX = maxOf(0, floor((x - radius).toDouble()).toInt())
                val maxX = minOf(widthPx - 1, ceil((x + radius).toDouble()).toInt())
                val minY = maxOf(0, floor((cy - radius).toDouble()).toInt())
                val maxY = minOf(heightPx - 1, ceil((cy + radius).toDouble()).toInt())
                for (py in minY..maxY) {
                    for (px in minX..maxX) {
                        val dx = px - x
                        val dy = py - cy
                        val dist = sqrt(dx * dx + dy * dy)
                        if (dist >= radius) continue
                        val a = brush.falloff(1f - dist / radius).coerceIn(0f, 1f)
                        val i = py * widthPx + px
                        if (a > alpha[i]) alpha[i] = a
                    }
                }
                x += step
            }
        }
        val pixels = IntArray(widthPx * heightPx) { i -> ((alpha[i] * 255f + 0.5f).toInt() shl 24) or 0x141414 }
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, widthPx, 0, 0, widthPx, heightPx)
        return bmp
    }
}
