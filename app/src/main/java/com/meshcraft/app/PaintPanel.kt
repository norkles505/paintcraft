package com.meshcraft.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Panel de pintura estilo ibisPaint, abajo de la pantalla (solo visible con la herramienta Paint, ver MainActivity).
 *
 * De arriba hacia abajo: panel desplegable (herramientas, tipos de pincel, color o capas), sliders de tamano y
 * opacidad, y la barra de 6 botones:
 *   1. Cambiar: icono fijo de intercambio; alterna con la herramienta usada justo antes.
 *   2. Herramienta: icono de la herramienta activa; abre el panel con todas las herramientas.
 *   3. Tipo de pincel: abre el panel con los tipos de pincel.
 *   4. Color: cuadro con el color actual; abre el selector de color.
 *   5. Ocultar: triangulo que esconde todo menos si mismo.
 *   6. Capas: panel de capas reales del modelo (LayersPanel).
 * El panel de herramientas trae ademas Mano (rotar / desplazar la camara) y Bloqueo (bloquear la camara): no son herramientas
 * de pintura, solo se alternan ahi y, como las herramientas, cierran el panel (la accion la hace MainActivity, ver onHandToggle / onLockToggle).
 * Los cambios se aplican directo en MyGLRenderer (paintTool, paintBrushType, paintColor, paintRadius, paintOpacity).
 */
class PaintPanel(context: Context, private val renderer: MyGLRenderer) : LinearLayout(context) {

    private enum class Popup { NONE, TOOLS, BRUSHES, COLOR, LAYERS }

    private class GridItem(val iconRes: Int, val label: String, val active: Boolean, val enabled: Boolean, val onClick: () -> Unit)

    private val d = resources.displayMetrics.density
    /** Alto compartido por el panel de color y el de capas (para que al cambiar de uno a otro no salte). */
    private val bigPopupHeight = (490 * d).toInt()
    private val accent = Color.argb(235, 242, 128, 26)

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
    private var openPopup = Popup.NONE
    private var barHidden = false
    /** Al pulsar Mano (panel de herramientas): MainActivity cambia entre rotar y desplazar la camara y devuelve true si quedo en modo desplazar. */
    var onHandToggle: (() -> Boolean)? = null
    /** Al pulsar Bloqueo (panel de herramientas): MainActivity bloquea/desbloquea la camara y devuelve true si quedo bloqueada. */
    var onLockToggle: (() -> Boolean)? = null
    private var handActive = false
    private var lockActive = false

    private val popupContainer = FrameLayout(context)
    private val slidersBox = LinearLayout(context)
    // Sliders estilo ibisPaint: tamano del pincel (radio en texeles, 1..100) y opacidad (0..100).
    private val sizeRow = IbisSliderRow(context, 990, 170, 5, false, { "%.1f".format(1f + it / 10f) }) { renderer.paintRadius = 1f + it / 10f }
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
        renderer.paintBrushType = currentBrushType
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

    private fun buildBrushesPopup(): View {
        val items = BrushType.values().map { bt ->
            GridItem(R.drawable.ic_brush_type, bt.label, bt == currentBrushType, true) {
                currentBrushType = bt
                renderer.paintBrushType = bt
                rebuildPopup()
            }
        }
        return buildGrid(items, 3)
    }

    /** Panel de capas estilo ibisPaint (ver LayersPanel), conectado a las capas reales del modelo que se pinta. */
    private fun buildLayersPopup(): View = LayersPanel(context, renderer)

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
        val usesBrushSettings = !handActive && (currentTool == PaintTool.BRUSH || currentTool == PaintTool.ERASER)
        popupContainer.visibility = if (shown && openPopup != Popup.NONE) View.VISIBLE else View.GONE
        // Los sliders de tamano y opacidad del pincel se esconden con el panel de color o el de capas abierto.
        slidersBox.visibility = if (shown && usesBrushSettings && openPopup != Popup.COLOR && openPopup != Popup.LAYERS) View.VISIBLE else View.GONE
        for (b in barButtons) b.visibility = if (shown) View.VISIBLE else View.GONE
        // Con el panel de capas abierto, el panel de pintura ocupa todo el ancho de la pantalla (sin margen lateral).
        (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val side = if (shown && openPopup == Popup.LAYERS) 0 else (8 * d).toInt()
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
