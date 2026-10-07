package com.meshcraft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Iconitos del encabezado de la paleta (flecha, tres puntos y lista), dibujados con trazos simples. */
private class PaletteIconView(context: Context, private val kind: Int) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        paint.strokeWidth = 1.8f * d
        when (kind) {
            CHEVRON -> {
                paint.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(w * 0.32f, h * 0.40f)
                path.lineTo(w * 0.50f, h * 0.60f)
                path.lineTo(w * 0.68f, h * 0.40f)
                canvas.drawPath(path, paint)
            }
            DOTS -> {
                paint.style = Paint.Style.FILL
                for (f in floatArrayOf(0.28f, 0.5f, 0.72f)) canvas.drawCircle(w / 2f, h * f, 1.6f * d, paint)
            }
            else -> {
                paint.style = Paint.Style.STROKE
                for (f in floatArrayOf(0.32f, 0.5f, 0.68f)) canvas.drawLine(w * 0.28f, h * f, w * 0.72f, h * f, paint)
            }
        }
    }

    companion object {
        const val CHEVRON = 0
        const val DOTS = 1
        const val LIST = 2
    }
}

/**
 * Cuadricula de la paleta: celdas CUADRADAS pegadas (ancho / columnas). Cuantas filas caben depende del alto disponible;
 * el fondo es un tablero de transparencia que se ve en los huecos libres (colorAt devuelve null). Un toque llama a onTap
 * con el indice de la celda (fila * columnas + columna).
 */
private class PaletteGridView(
    context: Context,
    private val cols: Int,
    private val colorAt: (Int) -> Int?,
    private val onTap: (Int) -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val paint = Paint()

    private fun cellSize(): Float = width.toFloat() / cols

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Fondo: tablero de transparencia.
        val chk = 8f * d
        var row = 0
        var y = 0f
        while (y < h) {
            var col = 0
            var x = 0f
            while (x < w) {
                paint.color = if ((row + col) % 2 == 0) Color.WHITE else Color.rgb(228, 228, 228)
                canvas.drawRect(x, y, minOf(x + chk, w), minOf(y + chk, h), paint)
                x += chk
                col++
            }
            y += chk
            row++
        }

        // Celdas con color, cuadradas y sin separacion.
        val cell = cellSize()
        if (cell <= 0f) return
        val rows = (h / cell).toInt()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val color = colorAt(r * cols + c) ?: continue
                paint.color = color
                canvas.drawRect(
                    (c * cell).roundToInt().toFloat(), (r * cell).roundToInt().toFloat(),
                    ((c + 1) * cell).roundToInt().toFloat(), ((r + 1) * cell).roundToInt().toFloat(), paint
                )
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val cell = cellSize()
                if (cell <= 0f) return true
                val c = (e.x / cell).toInt()
                val r = (e.y / cell).toInt()
                val rows = (height / cell).toInt()
                if (c in 0 until cols && r in 0 until rows) onTap(r * cols + c)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

/**
 * Panel de color estilo ibisPaint: titulo, rueda de tono con rombo de saturacion/brillo, codigo hex, una zona con tres
 * pestanas (Paleta, RVA con sliders R/G/B, HSB con sliders H/S/B), el slider de opacidad y la barra de pestanas.
 *
 * Pestana Paleta: encabezado, cuadricula de colores cuadrados (8 columnas; los huecos libres con tablero de transparencia
 * guardan el color actual al tocarlos) y una tira con los ultimos colores usados. La rueda y el hex solo se ven en RVA y HSB.
 *
 * onColor se llama con el color RGB cada vez que el usuario lo cambia desde el panel; setColor lo fija desde afuera
 * (cuentagotas, color inicial) sin llamar a onColor. onOpacity / setOpacity hacen lo mismo con la opacidad (0..100).
 */
class ColorPanel(
    context: Context,
    private val onColor: (Int, Int, Int) -> Unit,
    private val onOpacity: (Int) -> Unit
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private val activeBlue = Color.rgb(58, 123, 213)
    // Tono 0..360, saturacion 0..1, brillo 0..1 (el tono se conserva cuando el color es gris o negro).
    private val hsv = floatArrayOf(0f, 0f, 0f)
    private var cr = 0
    private var cg = 0
    private var cb = 0

    private val wheel = ColorWheelView(context) { h, s, v -> setHsb(h, s, v) }
    private val hexText = TextView(context)
    private val rowR = IbisSliderRow(context, 255, 0, 1, false, { it.toString() }) { setRgb(it, cg, cb) }
    private val rowG = IbisSliderRow(context, 255, 0, 1, false, { it.toString() }) { setRgb(cr, it, cb) }
    private val rowB = IbisSliderRow(context, 255, 0, 1, false, { it.toString() }) { setRgb(cr, cg, it) }
    private val rowH = IbisSliderRow(context, 359, 0, 1, false, { it.toString() }) { setHsb(it.toFloat(), hsv[1], hsv[2]) }
    private val rowS = IbisSliderRow(context, 100, 0, 1, false, { it.toString() }) { setHsb(hsv[0], it / 100f, hsv[2]) }
    private val rowV = IbisSliderRow(context, 100, 0, 1, false, { it.toString() }) { setHsb(hsv[0], hsv[1], it / 100f) }
    private val rowOpacity = IbisSliderRow(context, 100, 100, 1, true, { "$it%" }) { onOpacity(it) }

    // Paleta: ultimos colores usados (el mas nuevo primero) y colores guardados por el usuario en los huecos libres (indice del hueco -> color).
    private val recent = ArrayList<Int>()
    private val savedSlots = HashMap<Int, Int>()
    private val handler = Handler(Looper.getMainLooper())
    // El color actual pasa a "recientes" cuando deja de cambiar un momento (no en cada paso del slider).
    private val commitRecent = Runnable { addRecent(Color.rgb(cr, cg, cb)) }

    private val paletteBox = LinearLayout(context)
    private val rgbBox = LinearLayout(context)
    private val hsbBox = LinearLayout(context)
    private val tabIcons = ArrayList<ImageView>()
    private val tabLabels = ArrayList<TextView>()

    init {
        orientation = VERTICAL
        val pad = (10 * d).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * d
            setColor(Color.argb(245, 36, 36, 36))
        }

        // Titulo.
        val title = TextView(context)
        title.text = "Color"
        title.setTextColor(Color.WHITE)
        title.textSize = 16f
        title.setTypeface(title.typeface, Typeface.BOLD)
        addView(title)

        // Rueda de tono + rombo, centrada, y el codigo hex abajo a la izquierda.
        addView(wheel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (4 * d).toInt()
        })
        hexText.setTextColor(Color.WHITE)
        hexText.textSize = 12f
        addView(hexText)

        // Pestana Paleta.
        paletteBox.orientation = VERTICAL
        rebuildPalette()

        // Pestana RVA: sliders R, G y B.
        rgbBox.orientation = VERTICAL
        rgbBox.gravity = Gravity.CENTER_VERTICAL
        for (row in listOf(rowR, rowG, rowB)) rgbBox.addView(row, rowParams())

        // Pestana HSB: sliders H, S y B. La pista del tono es el arcoiris completo.
        hsbBox.orientation = VERTICAL
        hsbBox.gravity = Gravity.CENTER_VERTICAL
        for (row in listOf(rowH, rowS, rowV)) hsbBox.addView(row, rowParams())
        rowH.setGradient(IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) })

        // Zona de pestanas: ocupa el espacio que sobra, asi el panel no cambia de tamano al cambiar de pestana.
        val content = FrameLayout(context)
        content.addView(paletteBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        content.addView(rgbBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        content.addView(hsbBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Linea separadora y slider de opacidad.
        val line = View(context)
        line.setBackgroundColor(Color.rgb(70, 70, 70))
        addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt().coerceAtLeast(1)).apply {
            topMargin = (4 * d).toInt()
            bottomMargin = (4 * d).toInt()
        })
        addView(rowOpacity, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Barra de pestanas: Paleta, RVA y HSB.
        val bar = LinearLayout(context)
        bar.orientation = HORIZONTAL
        bar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10 * d
            setColor(Color.rgb(24, 24, 24))
        }
        val tabs = listOf(
            Pair(R.drawable.ic_tab_palette, "Paleta"),
            Pair(R.drawable.ic_tab_rgb, "RVA"),
            Pair(R.drawable.ic_tab_hsb, "HSB")
        )
        for ((i, t) in tabs.withIndex()) {
            val cell = LinearLayout(context)
            cell.orientation = VERTICAL
            cell.gravity = Gravity.CENTER
            cell.setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
            val icon = ImageView(context)
            icon.setImageResource(t.first)
            val label = TextView(context)
            label.text = t.second
            label.textSize = 10f
            label.gravity = Gravity.CENTER
            cell.addView(icon, LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()))
            cell.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            cell.setOnClickListener { setTab(i) }
            tabIcons.add(icon)
            tabLabels.add(label)
            bar.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (6 * d).toInt()
        })

        setTab(0)
        updateUi()
    }

    // ---- API ----

    /** Fija el color desde afuera (cuentagotas, color inicial) sin llamar a onColor. */
    fun setColor(r: Int, g: Int, b: Int) {
        cr = r
        cg = g
        cb = b
        syncHsvFromRgb()
        updateUi()
        scheduleRecent()
    }

    /** Fija la opacidad (0..100) desde afuera sin llamar a onOpacity. */
    fun setOpacity(percent: Int) {
        rowOpacity.setValue(percent)
    }

    // ---- Estado ----

    private fun setRgb(r: Int, g: Int, b: Int) {
        cr = r
        cg = g
        cb = b
        syncHsvFromRgb()
        updateUi()
        onColor(cr, cg, cb)
        scheduleRecent()
    }

    private fun setHsb(h: Float, s: Float, v: Float) {
        hsv[0] = h
        hsv[1] = s
        hsv[2] = v
        val c = Color.HSVToColor(hsv)
        cr = Color.red(c)
        cg = Color.green(c)
        cb = Color.blue(c)
        updateUi()
        onColor(cr, cg, cb)
        scheduleRecent()
    }

    /** Recalcula el HSV desde el RGB; si el color es gris/negro conserva el tono anterior en vez de saltar a rojo. */
    private fun syncHsvFromRgb() {
        val t = FloatArray(3)
        Color.RGBToHSV(cr, cg, cb, t)
        if (t[1] > 0.001f && t[2] > 0.001f) hsv[0] = t[0]
        hsv[1] = t[1]
        hsv[2] = t[2]
    }

    /** Pone todos los controles (rueda, hex, sliders y degradados) de acuerdo al color actual. */
    private fun updateUi() {
        wheel.setHsv(hsv[0], hsv[1], hsv[2])
        hexText.text = "#%02X%02X%02X".format(cr, cg, cb)

        rowR.setValue(cr)
        rowG.setValue(cg)
        rowB.setValue(cb)
        rowR.setGradient(intArrayOf(Color.rgb(0, cg, cb), Color.rgb(255, cg, cb)))
        rowG.setGradient(intArrayOf(Color.rgb(cr, 0, cb), Color.rgb(cr, 255, cb)))
        rowB.setGradient(intArrayOf(Color.rgb(cr, cg, 0), Color.rgb(cr, cg, 255)))

        rowH.setValue(hsv[0].roundToInt().coerceIn(0, 359))
        rowS.setValue((hsv[1] * 100f).roundToInt())
        rowV.setValue((hsv[2] * 100f).roundToInt())
        rowS.setGradient(intArrayOf(
            Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])),
            Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))
        ))
        rowV.setGradient(intArrayOf(
            Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 0f)),
            Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))
        ))

        rowOpacity.setTrackColor(Color.rgb(cr, cg, cb))
    }

    private fun setTab(i: Int) {
        paletteBox.visibility = if (i == 0) View.VISIBLE else View.GONE
        // La rueda y el hex solo se ven en RVA y HSB; en Paleta se esconden.
        wheel.visibility = if (i == 0) View.GONE else View.VISIBLE
        hexText.visibility = if (i == 0) View.GONE else View.VISIBLE
        rgbBox.visibility = if (i == 1) View.VISIBLE else View.GONE
        hsbBox.visibility = if (i == 2) View.VISIBLE else View.GONE
        for (k in 0 until tabIcons.size) {
            val c = if (k == i) activeBlue else Color.WHITE
            tabIcons[k].setColorFilter(c)
            tabLabels[k].setTextColor(c)
        }
    }

    // ---- Colores recientes ----

    private fun scheduleRecent() {
        handler.removeCallbacks(commitRecent)
        handler.postDelayed(commitRecent, 700)
    }

    private fun addRecent(color: Int) {
        recent.remove(color)
        recent.add(0, color)
        while (recent.size > RECENT_COUNT) recent.removeAt(recent.size - 1)
        rebuildPalette()
    }

    // ---- Piezas de interfaz ----

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun rowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (2 * d).toInt()
        bottomMargin = (2 * d).toInt()
    }

    private fun soon(what: String) {
        Toast.makeText(context, what + ": próximamente", Toast.LENGTH_SHORT).show()
    }

    /** Pestana Paleta: encabezado, cuadricula de colores cuadrados (los huecos libres muestran el tablero) y tira de recientes. */
    private fun rebuildPalette() {
        paletteBox.removeAllViews()

        // Encabezado: flecha + "Paleta" a la izquierda, tres puntos y lista a la derecha.
        val header = LinearLayout(context)
        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(PaletteIconView(context, PaletteIconView.CHEVRON), LinearLayout.LayoutParams(dp(24), dp(24)))
        val name = TextView(context)
        name.text = "Paleta"
        name.setTextColor(Color.WHITE)
        name.textSize = 13f
        header.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(2) })
        val dots = PaletteIconView(context, PaletteIconView.DOTS)
        dots.setOnClickListener { soon("Opciones de paleta") }
        header.addView(dots, LinearLayout.LayoutParams(dp(24), dp(24)))
        val list = PaletteIconView(context, PaletteIconView.LIST)
        list.setOnClickListener { soon("Lista de paletas") }
        header.addView(list, LinearLayout.LayoutParams(dp(24), dp(24)))
        paletteBox.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))

        // Cuadricula de celdas cuadradas: tocar un color lo elige; tocar un hueco libre guarda ahi el color actual.
        val grid = PaletteGridView(context, GRID_COLS, { idx -> PALETTE.getOrNull(idx) ?: savedSlots[idx] }) { idx ->
            val color = PALETTE.getOrNull(idx) ?: savedSlots[idx]
            if (color != null) {
                setRgb(Color.red(color), Color.green(color), Color.blue(color))
            } else {
                savedSlots[idx] = Color.rgb(cr, cg, cb)
                rebuildPalette()
            }
        }
        paletteBox.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Tira de los ultimos colores usados (los libres se ven oscuros).
        val strip = LinearLayout(context)
        strip.orientation = HORIZONTAL
        for (i in 0 until RECENT_COUNT) {
            val color = recent.getOrNull(i)
            val cell = View(context)
            cell.setBackgroundColor(color ?: Color.rgb(28, 28, 28))
            if (color != null) {
                cell.isClickable = true
                cell.setOnClickListener { setRgb(Color.red(color), Color.green(color), Color.blue(color)) }
            }
            strip.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        paletteBox.addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).apply {
            topMargin = dp(6)
        })
    }

    private companion object {
        const val GRID_COLS = 8
        const val RECENT_COUNT = 8

        // 31 colores; el resto de los huecos queda libre (tablero) para colores guardados.
        val PALETTE: List<Int> = listOf(
            "#000000", "#FFFFFF", "#FF0000", "#FF5A00", "#FFA800", "#FFFF00", "#AAF000", "#66FF00",
            "#00FF1E", "#00FF6E", "#00FFAA", "#00FFFF", "#00A2FF", "#0055FF", "#0000FF", "#6600FF",
            "#AA00FF", "#E600E6", "#FF00AA", "#FF0055", "#FFF4E0", "#FFEDBF", "#FFE8B8", "#FFC8A0",
            "#B08968", "#3A9BFF", "#E6E6C8", "#C8A8B8", "#0A1EA0", "#606060", "#303030"
        ).map { Color.parseColor(it) }
    }
}
