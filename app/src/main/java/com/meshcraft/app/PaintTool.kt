package com.meshcraft.app

/**
 * Herramientas del panel de pintura. implemented = false: el boton existe en el panel pero todavia no hace nada
 * (al tocarlo se avisa "proximamente" y la herramienta activa no cambia).
 */
enum class PaintTool(val label: String, val implemented: Boolean) {
    BRUSH("Pincel", true),
    ERASER("Borrador", true),
    EYEDROPPER("Cuentagotas", true),
    BLUR("Difuminar", false),
    FILL("Relleno", false),
    SMUDGE("Borrosidad", false)
}

/** Tipos de pincel: cambian como se desvanece el borde de cada toque (ver TexturedMeshGeometry.paintDab). */
enum class BrushType(val label: String) {
    SOFT("Suave"),
    HARD("Duro"),
    AIRBRUSH("Aerógrafo")
}
