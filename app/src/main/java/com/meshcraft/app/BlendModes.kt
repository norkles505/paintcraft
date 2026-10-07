package com.meshcraft.app

/** Modos de mezcla de una capa (como en ibisPaint / Photoshop). El nombre del enum es lo que se guarda en el proyecto (ver ProjectSerializer). */
enum class BlendMode(val label: String) {
    NORMAL("Normal"),
    // Oscurecer
    DARKEN("Oscurecer"),
    MULTIPLY("Multiplicar"),
    COLOR_BURN("Subexponer color"),
    LINEAR_BURN("Subexposición lineal"),
    DARKER_COLOR("Color más oscuro"),
    // Aclarar
    LIGHTEN("Aclarar"),
    SCREEN("Trama"),
    COLOR_DODGE("Sobreexponer color"),
    LINEAR_DODGE("Sobreexposición lineal"),
    ADD("Añadir"),
    LIGHTER_COLOR("Color más claro"),
    // Contraste
    OVERLAY("Superponer"),
    SOFT_LIGHT("Luz suave"),
    HARD_LIGHT("Luz fuerte"),
    VIVID_LIGHT("Luz intensa"),
    LINEAR_LIGHT("Luz lineal"),
    PIN_LIGHT("Luz focal"),
    HARD_MIX("Mezcla definida"),
    // Diferencia
    INVERT("Invertir"),
    DIFFERENCE("Diferencia"),
    EXCLUSION("Exclusión"),
    SUBTRACT("Restar"),
    DIVIDE("Dividir"),
    // Color
    HUE("Tono"),
    SATURATION("Saturación"),
    COLOR("Color"),
    LUMINOSITY("Luminosidad");

    companion object {
        /** Lee el modo guardado; si el nombre no existe (archivo de otra version) cae a Normal. */
        fun fromName(name: String?): BlendMode = values().firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * Calcula como se mezcla un color (la capa de arriba, "fuente") con el de abajo (el "fondo"), con las formulas habituales
 * de Photoshop / W3C. Todo en enteros 0..255. Cada instancia tiene su propio espacio de trabajo, asi que NO se comparte
 * entre hilos: cada hilo crea la suya.
 */
class BlendMixer {

    private val t = FloatArray(3)

    /** Color mezclado B(fondo, fuente) empaquetado como 0xRRGGBB (sin tener en cuenta alfas). */
    fun mix(mode: BlendMode, br: Int, bg: Int, bb: Int, sr: Int, sg: Int, sb: Int): Int {
        return when (mode) {
            BlendMode.NORMAL -> pack(sr, sg, sb)
            BlendMode.DARKER_COLOR ->
                if (luma(sr, sg, sb) < luma(br, bg, bb)) pack(sr, sg, sb) else pack(br, bg, bb)
            BlendMode.LIGHTER_COLOR ->
                if (luma(sr, sg, sb) > luma(br, bg, bb)) pack(sr, sg, sb) else pack(br, bg, bb)
            BlendMode.HUE, BlendMode.SATURATION, BlendMode.COLOR, BlendMode.LUMINOSITY ->
                nonSeparable(mode, br, bg, bb, sr, sg, sb)
            else -> pack(channel(mode, br, sr), channel(mode, bg, sg), channel(mode, bb, sb))
        }
    }

    /**
     * Pone el pixel fuente (sr, sg, sb) con alfa sa (1..255, ya con la opacidad de la capa) sobre el destino (dr, dg, db) con
     * alfa da, usando el modo de mezcla. Todo con alfa recto (sin premultiplicar). Devuelve ARGB empaquetado: el alfa en los
     * 8 bits de arriba (sacarlo con ushr 24), despues R, G y B.
     */
    fun over(mode: BlendMode, dr: Int, dg: Int, db: Int, da: Int, sr: Int, sg: Int, sb: Int, sa: Int): Int {
        var cr = sr
        var cg = sg
        var cb = sb
        // Donde el fondo tiene algo, el color de la capa se mezcla con el; donde el fondo es transparente se queda tal cual.
        if (mode != BlendMode.NORMAL && da > 0) {
            val m = mix(mode, dr, dg, db, sr, sg, sb)
            cr = (sr * (255 - da) + ((m shr 16) and 0xFF) * da) / 255
            cg = (sg * (255 - da) + ((m shr 8) and 0xFF) * da) / 255
            cb = (sb * (255 - da) + (m and 0xFF) * da) / 255
        }
        if (da == 0) return (sa shl 24) or pack(cr, cg, cb)
        // "Over" con alfa recto: lo de abajo sobrevive en la parte que la capa de arriba no tapa.
        val keep = da * (255 - sa) / 255
        val outA = sa + keep
        val r = (cr * sa + dr * keep) / outA
        val g = (cg * sa + dg * keep) / outA
        val b = (cb * sa + db * keep) / outA
        return (outA shl 24) or pack(r, g, b)
    }

    // ---- Modos por canal ----

    private fun channel(mode: BlendMode, b: Int, s: Int): Int = when (mode) {
        BlendMode.DARKEN -> minOf(b, s)
        BlendMode.MULTIPLY -> b * s / 255
        BlendMode.COLOR_BURN -> burn(b, s)
        BlendMode.LINEAR_BURN -> maxOf(0, b + s - 255)
        BlendMode.LIGHTEN -> maxOf(b, s)
        BlendMode.SCREEN -> b + s - b * s / 255
        BlendMode.COLOR_DODGE -> dodge(b, s)
        BlendMode.LINEAR_DODGE, BlendMode.ADD -> minOf(255, b + s)
        BlendMode.OVERLAY -> hardLight(s, b)
        BlendMode.SOFT_LIGHT -> softLight(b, s)
        BlendMode.HARD_LIGHT -> hardLight(b, s)
        BlendMode.VIVID_LIGHT -> if (s < 128) burn(b, 2 * s) else dodge(b, 2 * (s - 128))
        BlendMode.LINEAR_LIGHT -> (b + 2 * s - 255).coerceIn(0, 255)
        BlendMode.PIN_LIGHT -> if (s < 128) minOf(b, 2 * s) else maxOf(b, 2 * s - 255)
        BlendMode.HARD_MIX -> if (b + s >= 255) 255 else 0
        BlendMode.INVERT -> 255 - b
        BlendMode.DIFFERENCE -> Math.abs(b - s)
        BlendMode.EXCLUSION -> b + s - 2 * b * s / 255
        BlendMode.SUBTRACT -> maxOf(0, b - s)
        BlendMode.DIVIDE -> if (s == 0) 255 else minOf(255, b * 255 / s)
        else -> s
    }

    private fun burn(b: Int, s: Int): Int {
        if (b >= 255) return 255
        if (s <= 0) return 0
        return 255 - minOf(255, (255 - b) * 255 / s)
    }

    private fun dodge(b: Int, s: Int): Int {
        if (b <= 0) return 0
        if (s >= 255) return 255
        return minOf(255, b * 255 / (255 - s))
    }

    private fun hardLight(b: Int, s: Int): Int {
        if (s < 128) return b * (2 * s) / 255
        val s2 = 2 * s - 255
        return b + s2 - b * s2 / 255
    }

    private fun softLight(b: Int, s: Int): Int {
        val cb = b / 255f
        val cs = s / 255f
        val r = if (cs <= 0.5f) {
            cb - (1f - 2f * cs) * cb * (1f - cb)
        } else {
            val d = if (cb <= 0.25f) ((16f * cb - 12f) * cb + 4f) * cb else Math.sqrt(cb.toDouble()).toFloat()
            cb + (2f * cs - 1f) * (d - cb)
        }
        return (r * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    // ---- Modos que mezclan tono, saturacion y luminosidad (no por canal) ----

    private fun nonSeparable(mode: BlendMode, br: Int, bg: Int, bb: Int, sr: Int, sg: Int, sb: Int): Int {
        val c = t
        when (mode) {
            BlendMode.HUE -> {
                // Tono de la capa, saturacion y luminosidad del fondo.
                set(c, sr, sg, sb)
                setSat(c, sat(br, bg, bb))
                setLum(c, lum(br, bg, bb))
            }
            BlendMode.SATURATION -> {
                set(c, br, bg, bb)
                setSat(c, sat(sr, sg, sb))
                setLum(c, lum(br, bg, bb))
            }
            BlendMode.COLOR -> {
                set(c, sr, sg, sb)
                setLum(c, lum(br, bg, bb))
            }
            else -> {
                // Luminosidad: color del fondo con la luminosidad de la capa.
                set(c, br, bg, bb)
                setLum(c, lum(sr, sg, sb))
            }
        }
        return pack(to255(c[0]), to255(c[1]), to255(c[2]))
    }

    private fun set(c: FloatArray, r: Int, g: Int, b: Int) {
        c[0] = r / 255f
        c[1] = g / 255f
        c[2] = b / 255f
    }

    private fun lum(r: Int, g: Int, b: Int): Float = (0.3f * r + 0.59f * g + 0.11f * b) / 255f

    private fun lumF(c: FloatArray): Float = 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]

    private fun sat(r: Int, g: Int, b: Int): Float = (maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b))) / 255f

    private fun setLum(c: FloatArray, l: Float) {
        val d = l - lumF(c)
        c[0] += d
        c[1] += d
        c[2] += d
        clip(c)
    }

    /** Mete el color en 0..1 conservando su luminosidad. */
    private fun clip(c: FloatArray) {
        val l = lumF(c)
        val n = minOf(c[0], minOf(c[1], c[2]))
        val x = maxOf(c[0], maxOf(c[1], c[2]))
        if (n < 0f) {
            for (i in 0..2) c[i] = l + (c[i] - l) * l / (l - n)
        }
        if (x > 1f) {
            for (i in 0..2) c[i] = l + (c[i] - l) * (1f - l) / (x - l)
        }
    }

    private fun setSat(c: FloatArray, s: Float) {
        var maxI = 0
        var minI = 0
        for (i in 1..2) {
            if (c[i] > c[maxI]) maxI = i
            if (c[i] < c[minI]) minI = i
        }
        if (maxI == minI) {
            // Gris (los tres canales iguales): sin tono, no hay saturacion que ajustar.
            c[0] = 0f
            c[1] = 0f
            c[2] = 0f
            return
        }
        val midI = 3 - maxI - minI
        val mx = c[maxI]
        val mn = c[minI]
        val md = c[midI]
        c[midI] = (md - mn) * s / (mx - mn)
        c[maxI] = s
        c[minI] = 0f
    }

    private fun to255(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun luma(r: Int, g: Int, b: Int): Int = 77 * r + 151 * g + 28 * b

    private fun pack(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b
}
