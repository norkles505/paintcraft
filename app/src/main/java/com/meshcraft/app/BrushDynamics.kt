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
 *  - edgeAa: ancho en texeles del suavizado del borde (0 = el borde que define BrushType.falloff, sin tocar). Con 1, el borde de cada toque pasa de
 *    opaco a transparente en un texel: sin esto un pincel duro y fino sale con escalones, porque cada texel queda pintado o no (ver TexturedMeshGeometry.paintDab).
 *    Es el "antialiasing" de ibisPaint (IbisBrush.antialiasing).
 */
class BrushDynamics(
    val startSize: Float = 1f,
    val endSize: Float = 1f,
    val startOpacity: Float = 1f,
    val endOpacity: Float = 1f,
    val startLength: Float = 0f,
    val endLength: Float = 0f,
    val opacityScale: Float = 1f,
    val grain: Float = 0f,
    val edgeAa: Float = 0f
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

        /**
         * Largo del desvanecido (diametros por cada 100 % de "longitud") de los pinceles de fabrica cuya medicion no coincide con
         * FADE_DIAMETERS_PER_UNIT. Clave: IbisBrush.brushId (las copias de un pincel conservan el numero de fabrica). Cada pincel se calibra con
         * su propia medicion: lo del Carboncillo no se asume en otros.
         *  - 1 (Pluma, Fuerte): 30. Medido en 14 puntas de trazos de ibisPaint (grosor 0 al inicio y al final, longitud 40 %): la rampa lineal
         *    que mejor las ajusta mide 11.8 diametros (29.5 por 100 %); el error medio contra las capturas baja de 0.447 (con 7) a 0.139.
         *    HIPOTESIS sin probar: en ibisPaint el largo varia de un trazo a otro (6 a 25 diametros), quiza por la velocidad del trazo.
         *    Tampoco se aplica todavia la forma: en ibisPaint la punta es concava (sube rapido y se aplana), aca la rampa es recta.
         */
        val FADE_DIAMETERS_PER_UNIT_BY_BRUSH: Map<Int, Float> = mapOf(
            1 to 30f
        )

        /**
         * Largo del desvanecido SOLO DEL INICIO del trazo (diametros por cada 100 % de "longitud"), para los pinceles donde no coincide con el del final.
         * Clave: IbisBrush.brushId, igual que FADE_DIAMETERS_PER_UNIT_BY_BRUSH.
         *  - 149 ("Arcane, face, Body +Force Fade", pincel personalizado): 1.6. Medido en 3 trazos de ibisPaint de 56 px de ancho: la opacidad llega a
         *    la mitad a ~11 px y al 90 % a ~32 px en los tres (casi sin variar), o sea ~0.6 diametros con el 40 %. El del final, en cambio, vale lo general
         *    (2.8 diametros) y varia mucho de un trazo a otro (90 % a 99, 129 y 192 px): HIPOTESIS, depende de como el dedo frena.
         */
        val FADE_START_DIAMETERS_PER_UNIT_BY_BRUSH: Map<Int, Float> = mapOf(
            149 to 1.6f
        )

        /** Multiplicador de opacidad de los pinceles con grano: con el grano y el maximo por trazo, ibisPaint se ve con una opacidad media de 0.5 en el centro. */
        const val GRAIN_OPACITY_SCALE = 0.7f

        /** Suavizado del borde, en texeles, de los pinceles de ibisPaint que tienen el antialiasing encendido (ver edgeAa). */
        const val EDGE_AA_TEXELS = 1f

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
