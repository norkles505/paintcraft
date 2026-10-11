package com.meshcraft.app

/**
 * Herramientas del panel de pintura. implemented = false: el boton existe en el panel pero todavia no hace nada
 * (al tocarlo se avisa "proximamente" y la herramienta activa no cambia).
 */
enum class PaintTool(val label: String, val implemented: Boolean) {
    BRUSH("Pincel", true),
    ERASER("Borrador", true),
    EYEDROPPER("Cuentagotas", true),
    BLUR("Difuminar", true),
    FILL("Relleno", true),
    SMUDGE("Borrosidad", true)
}

/**
 * Tipos de pincel. Cada uno es un preset con datos: para agregar un pincel propio de paintcraft basta una linea en el companion y sale solo en
 * la lista del panel (PaintPanel), con su trazo de muestra. El orden de aca es el de la lista. Ademas se pueden agregar pinceles mientras la
 * app corre (los importados de ibisPaint, ver fromIbis y addImported), por eso ya no es un enum: BrushType.values() devuelve la lista actual.
 *  - label: nombre en la lista.
 *  - defaultSize: radio inicial en texeles (el slider de tamano va de 1.0 a 100.0).
 *  - spacing: distancia entre toques de un trazo, como fraccion del radio (menor = trazo mas continuo, pero mas lento).
 *  - falloff: peso (0..1) de un toque segun t = 1 - distancia / radio (t = 1 en el centro, 0 en el borde); ver TexturedMeshGeometry.paintDab.
 *    Cuanto mas rapido sube a 1, mas duro es el borde (t * 20 = casi sin desvanecer; t * t = desvanecido desde el centro).
 *  - ibis: el pincel de ibisPaint del que viene (null en los pinceles propios).
 * Dos pinceles son el mismo solo si son el mismo objeto (como con el enum), asi que se pueden usar de clave en un HashMap.
 */
class BrushType(
    val label: String,
    val defaultSize: Float,
    val spacing: Float,
    val falloff: (Float) -> Float,
    val ibis: IbisBrush? = null,
    /** Como cambia el trazo de principio a fin y si tiene grano (ver BrushDynamics). Los pinceles propios no cambian nada. */
    val dynamics: BrushDynamics = BrushDynamics.NONE
) {
    companion object {
        val SOFT = BrushType("Suave", 18f, 0.25f, { t -> minOf(1f, t * 2f) })
        val HARD = BrushType("Duro", 18f, 0.25f, { t -> minOf(1f, t * 8f) })
        val AIRBRUSH = BrushType("Aerógrafo", 18f, 0.25f, { t -> t * t })
        val PENCIL = BrushType("Lápiz", 3f, 0.15f, { t -> minOf(1f, t * 10f) })
        val PENCIL_BLURRED = BrushType("Lápiz (Difuminado)", 48f, 0.2f, { t -> t * t * (3f - 2f * t) })
        val MARKER_SOFT = BrushType("Rotulador (Suave)", 6f, 0.15f, { t -> minOf(1f, t * 4f) })
        val MARKER_HARD = BrushType("Rotulador (Fuerte)", 14f, 0.12f, { t -> minOf(1f, t * 20f) })

        /** Todos los pinceles, en el orden de la lista: primero los de arriba y despues los importados, en el orden en que se importaron. */
        private val all = mutableListOf(SOFT, HARD, AIRBRUSH, PENCIL, PENCIL_BLURRED, MARKER_SOFT, MARKER_HARD)

        /** La lista actual de pinceles (una copia: no cambia aunque despues se importe otro). */
        fun values(): Array<BrushType> = synchronized(all) { all.toTypedArray() }

        /**
         * Agrega a la lista un pincel importado de ibisPaint y lo devuelve. Si ya estaba importado (mismos datos) no se repite: devuelve el que ya estaba.
         */
        fun addImported(brush: IbisBrush): BrushType = synchronized(all) {
            all.firstOrNull { it.ibis != null && it.ibis.raw.contentEquals(brush.raw) } ?: fromIbis(brush).also { all.add(it) }
        }

        /** Saca un pincel importado de la lista (los pinceles propios no se pueden sacar). */
        fun removeImported(bt: BrushType) {
            if (bt.ibis == null) return
            synchronized(all) { all.remove(bt) }
        }

        /**
         * Convierte un pincel de ibisPaint a los datos que usa el motor de paintcraft. Es una aproximacion: lo que paintcraft todavia no sabe
         * hacer (punta con forma propia, textura, temblor, presion, grosor que cambia en el trazo...) se guarda en [ibis] pero no se aplica.
         *  - tamano: el grosor de ibisPaint es un diametro en px; aca el radio en texeles va de 1 a 100.
         *  - espaciado: en ibisPaint es fraccion del diametro, aca del radio (el doble); con minimo para que no sea lentisimo.
         *  - borde: sale del "diseno de opacidad" (0.1 = muy suave como un aerografo, 1.0 = duro). Falta calibrarlo comparando capturas.
         */
        fun fromIbis(b: IbisBrush): BrushType {
            val size = (b.size / 2f).coerceIn(1f, 100f)
            val spacing = (b.spacing * 2f).coerceIn(0.08f, 1f)
            val h = b.opacityDesign.coerceIn(0f, 1f)
            val falloff: (Float) -> Float = if (h >= 0.5f) {
                // Duro: casi sin desvanecer; cuanto mas cerca de 1, mas marcado el borde.
                val k = 8f + (h - 0.5f) * 24f
                ({ t: Float -> minOf(1f, t * k) })
            } else {
                // Suave: de desvanecido desde el centro (t * t) a medio duro (t * 2), segun h.
                val mix = ((h - 0.1f) / 0.4f).coerceIn(0f, 1f)
                ({ t: Float -> val soft = t * t; val firm = minOf(1f, t * 2f); soft + (firm - soft) * mix })
            }
            val grainy = BrushDynamics.GRAINY_TIPS[b.tipId] ?: 0f
            // Largo de los desvanecidos por cada 100 % de "longitud": el medido para ese pincel de fabrica, o el general (ver BrushDynamics).
            val fadeScale = BrushDynamics.FADE_DIAMETERS_PER_UNIT_BY_BRUSH[b.brushId] ?: BrushDynamics.FADE_DIAMETERS_PER_UNIT
            // Desvanecidos del trazo y grano (ver BrushDynamics): el grosor y la opacidad con que empieza y termina, y el largo de esos tramos.
            val dynamics = BrushDynamics(
                startSize = b.startSize.coerceIn(0f, 1f),
                endSize = b.endSize.coerceIn(0f, 1f),
                startOpacity = b.startOpacity.coerceIn(0f, 1f),
                endOpacity = b.endOpacity.coerceIn(0f, 1f),
                startLength = b.fadeStartLength.coerceAtLeast(0f) * fadeScale,
                endLength = b.fadeEndLength.coerceAtLeast(0f) * fadeScale,
                grain = grainy,
                opacityScale = if (grainy > 0f) BrushDynamics.GRAIN_OPACITY_SCALE else 1f
            )
            return BrushType(b.displayName, size, spacing, falloff, b, dynamics)
        }
    }
}
