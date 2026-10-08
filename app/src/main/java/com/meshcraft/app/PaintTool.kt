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
 * Tipos de pincel. Cada uno es un preset con datos: para agregar un pincel nuevo basta una linea aca y sale solo en la lista del
 * panel (PaintPanel), con su trazo de muestra. El orden de aca es el de la lista.
 *  - label: nombre en la lista.
 *  - defaultSize: radio inicial en texeles (el slider de tamano va de 1.0 a 100.0).
 *  - spacing: distancia entre toques de un trazo, como fraccion del radio (menor = trazo mas continuo, pero mas lento).
 *  - falloff: peso (0..1) de un toque segun t = 1 - distancia / radio (t = 1 en el centro, 0 en el borde); ver TexturedMeshGeometry.paintDab.
 *    Cuanto mas rapido sube a 1, mas duro es el borde (t * 20 = casi sin desvanecer; t * t = desvanecido desde el centro).
 */
enum class BrushType(val label: String, val defaultSize: Float, val spacing: Float, val falloff: (Float) -> Float) {
    SOFT("Suave", 18f, 0.25f, { t -> minOf(1f, t * 2f) }),
    HARD("Duro", 18f, 0.25f, { t -> minOf(1f, t * 8f) }),
    AIRBRUSH("Aerógrafo", 18f, 0.25f, { t -> t * t }),
    PENCIL("Lápiz", 3f, 0.15f, { t -> minOf(1f, t * 10f) }),
    PENCIL_BLURRED("Lápiz (Difuminado)", 48f, 0.2f, { t -> t * t * (3f - 2f * t) }),
    MARKER_SOFT("Rotulador (Suave)", 6f, 0.15f, { t -> minOf(1f, t * 4f) }),
    MARKER_HARD("Rotulador (Fuerte)", 14f, 0.12f, { t -> minOf(1f, t * 20f) })
}
