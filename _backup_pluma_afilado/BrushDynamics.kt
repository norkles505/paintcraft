package com.meshcraft.app

/**
 * Lo que hace distinto a un pincel ademas de la forma de cada toque (ver BrushType.falloff): como cambia el trazo de principio a fin
 * y si tiene grano. Se aplica en MyGLRenderer (desvanecidos, ver paintBrushDab) y en TexturedMeshGeometry.paintDab (grano).
 *
 *  - startSize / startOpacity: grosor y opacidad con que EMPIEZA el trazo, como fraccion de los normales (1 = igual que el resto,
 *    0.01 = casi transparente, 0 = en punta). Suben hasta 1 a lo largo de startLength. endSize / endOpacity: lo mismo al final del trazo.
 *  - startLength / endLength: largo de esos desvanecidos, en diametros del pincel (0 = sin desvanecido).
 *  - opacityScale: multiplica la opacidad de todos los toques (con el dedo no hay presion; ibisPaint usa una presion supuesta).
 *  - grain: fuerza del grano 0..1 (0 = liso). Es un ruido fijo en la textura que modula la opacidad de cada toque, como el carbon.
 */
class BrushDynamics(
    val startSize: Float = 1f,
    val endSize: Float = 1f,
    val startOpacity: Float = 1f,
    val endOpacity: Float = 1f,
    val startLength: Float = 0f,
    val endLength: Float = 0f,
    val opacityScale: Float = 1f,
    val grain: Float = 0f
) {
    /** true si el principio del trazo es distinto del resto (empieza mas fino o mas transparente). */
    val hasStart: Boolean get() = startLength > 0f && (startSize < 1f || startOpacity < 1f)
    /** true si el final del trazo es distinto del resto. Como no se sabe donde termina hasta soltar el dedo, la cola se repinta entonces (ver MyGLRenderer.paintEnd). */
    val hasEnd: Boolean get() = endLength > 0f && (endSize < 1f || endOpacity < 1f)

    companion object {
        /** Sin cambios: todos los toques iguales y lisos (los pinceles propios de paintcraft). */
        val NONE = BrushDynamics()

        /** Largo del desvanecido en diametros del pincel por cada 100 % de "longitud" de ibisPaint (medido en capturas del Carboncillo: 40 % = unos 2.8 diametros). */
        const val FADE_DIAMETERS_PER_UNIT = 7f

        /** Multiplicador de opacidad de los pinceles con grano: con el grano y el maximo por trazo, ibisPaint se ve con una opacidad media de 0.5 en el centro. */
        const val GRAIN_OPACITY_SCALE = 0.7f

        /**
         * Puntas de ibisPaint que dibujan con grano (identificador de IbisBrush.tipId -> fuerza). El QR no trae la imagen de la punta, solo su
         * identificador, asi que el grano se aproxima con un ruido propio y solo se aplica a las puntas que se sabe que lo tienen.
         */
        val GRAINY_TIPS: Map<String, Float> = mapOf(
            // Carboncillo
            "15f82ecdf3343f56de0d932f2c0fd40f" to 1f
        )
    }
}
