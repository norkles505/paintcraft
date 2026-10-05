package com.meshcraft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.TextView

/** TextView con contorno negro fino, para que el texto se lea sobre cualquier fondo (lo usan los numeros de los sliders). */
class OutlinedTextView(context: Context) : TextView(context) {

    private val d = resources.displayMetrics.density
    private var drawing = false

    // setTextColor llama a invalidate(); durante onDraw se ignora para no entrar en un bucle de redibujado.
    override fun invalidate() {
        if (drawing) return
        super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawing = true
        val fill = currentTextColor
        // Primero el contorno negro y encima el relleno.
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 1.8f * d
        setTextColor(Color.BLACK)
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        setTextColor(fill)
        super.onDraw(canvas)
        drawing = false
    }
}
