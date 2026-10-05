package com.meshcraft.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Panel de color estilo ibisPaint: titulo, rueda de tono con rombo de saturacion/brillo, codigo hex, una zona con tres
 * pestanas (Paleta, RVA con sliders R/G/B, HSB con sliders H/S/B), el slider de opacidad y la barra de pestanas.
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

    private val paletteBox = buildPalette()
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

        // Pestana RVA: sliders R, G y B.
        rgbBox.orientation = VERTICAL
        rgbBox.gravity = Gravity.CENTER_VERTICAL
        for (row in listOf(rowR, rowG, rowB)) rgbBox.addView(row, rowParams())

        // Pestana HSB: sliders H, S y B. La pista del tono es el arcoiris completo.
        hsbBox.orientation = VERTICAL
        hsbBox.gravity = Gravity.CENTER_VERTICAL
        for (row in listOf(rowH, rowS, rowV)) hsbBox.addView(row, rowParams())
        rowH.setGradient(IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) })

        // Zona de pestanas con alto fijo para que el panel no cambie de tamano al cambiar de pestana.
        val content = FrameLayout(context)
        content.addView(paletteBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        content.addView(rgbBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        content.addView(hsbBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (96 * d).toInt()))

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

        setTab(1)
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
        rgbBox.visibility = if (i == 1) View.VISIBLE else View.GONE
        hsbBox.visibility = if (i == 2) View.VISIBLE else View.GONE
        for (k in 0 until tabIcons.size) {
            val c = if (k == i) activeBlue else Color.WHITE
            tabIcons[k].setColorFilter(c)
            tabLabels[k].setTextColor(c)
        }
    }

    // ---- Piezas de interfaz ----

    private fun rowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (2 * d).toInt()
        bottomMargin = (2 * d).toInt()
    }

    /** Pestana Paleta: cuadricula de 3 filas x 8 colores. */
    private fun buildPalette(): LinearLayout {
        val box = LinearLayout(context)
        box.orientation = VERTICAL
        for (rowColors in PALETTE.chunked(8)) {
            val line = LinearLayout(context)
            line.orientation = HORIZONTAL
            for (c in rowColors) {
                val sw = View(context)
                sw.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6 * d
                    setColor(c)
                    setStroke((1 * d).toInt().coerceAtLeast(1), Color.argb(90, 255, 255, 255))
                }
                sw.setOnClickListener { setRgb(Color.red(c), Color.green(c), Color.blue(c)) }
                line.addView(sw, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    val m = (2.5f * d).toInt()
                    setMargins(m, m, m, m)
                })
            }
            box.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return box
    }

    private companion object {
        val PALETTE: List<Int> = listOf(
            "#000000", "#404040", "#808080", "#BFBFBF", "#FFFFFF", "#7B4A1E", "#E53935", "#FB8C00",
            "#FDD835", "#7CB342", "#43A047", "#00ACC1", "#1E88E5", "#3949AB", "#8E24AA", "#D81B60",
            "#FF8A80", "#FFCC80", "#FFF59D", "#C5E1A5", "#80DEEA", "#90CAF9", "#CE93D8", "#F48FB1"
        ).map { Color.parseColor(it) }
    }
}
