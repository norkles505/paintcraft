package com.meshcraft.app

/**
 * Color de fondo (0xRRGGBB, opaco) bajo las capas en un texel. Es una interfaz propia y no una lambda de Kotlin `(Int, Int) -> Int`
 * porque esa empaqueta los numeros como objetos Integer en cada llamada, y el compositor la llama una vez por texel (mas de un
 * millon de veces al recomponer toda la textura): llenaba la memoria de basura y el recolector frenaba el cuadro.
 */
fun interface BaseColor {
    fun at(x: Int, y: Int): Int
}

/**
 * Compone las capas de un modelo en una sola textura RGBA (alfa recto). Lo usan tanto la textura que se ve en el modelo
 * (TexturedMeshGeometry.recompositeRect) como la exportacion a PNG (exportCompositeRgba), asi las dos salen igual.
 *
 * Reglas, de abajo hacia arriba:
 * - Una capa oculta (o con opacidad 0) no se dibuja.
 * - Cada capa se mezcla con lo de abajo segun su modo (ver BlendMode / BlendMixer) y su opacidad.
 * - Recorte (PaintLayer.clipToBelow): la capa solo se ve donde la capa base (la ultima capa de abajo, de su mismo grupo, que no
 *   esta recortada) tiene algo pintado. Si la base esta oculta, la capa recortada tampoco se ve. La capa de mas abajo de cada
 *   grupo ignora el recorte.
 * - Carpetas (PaintLayer.isFolder): las capas de una carpeta (folderId) se componen primero entre ellas, sobre un fondo
 *   transparente, y el resultado entra como UNA sola capa con el modo, la opacidad, el ojo y el recorte de la carpeta. Las
 *   carpetas no se anidan.
 * - Modo Solo (soloId >= 0): solo se dibuja esa capa (o, si es una carpeta, todo su contenido), pero su recorte se sigue
 *   calculando contra su base.
 *
 * Cada instancia tiene su propio mezclador de colores, asi que NO se comparte entre hilos: cada hilo crea la suya.
 */
class LayerCompositor {

    private val mixer = BlendMixer()

    /**
     * Compone el rectangulo (x0, y0)-(x1, y1) (limites incluidos) de una textura cuadrada de size x size y lo escribe en out
     * (RGBA, size * size * 4 bytes). base(x, y) da el color de fondo (0xRRGGBB, opaco) bajo las capas en ese texel; null =
     * fondo transparente.
     */
    fun composite(
        layers: List<PaintLayer>,
        soloId: Int,
        base: BaseColor?,
        out: ByteArray,
        size: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int
    ) {
        val list = layers.toList()
        val n = list.size
        val px = Array(n) { list[it].pixels }
        val op = IntArray(n) { list[it].opacity * 255 / 100 }
        val modes = Array(n) { list[it].blendMode }
        val shown = BooleanArray(n) { list[it].visible && list[it].opacity > 0 }
        val isFolder = BooleanArray(n) { list[it].isFolder }

        // Carpeta (posicion en la lista) de cada capa, o -1 si esta suelta (o si su carpeta ya no existe).
        val indexOfId = HashMap<Int, Int>()
        for (k in 0 until n) indexOfId[list[k].id] = k
        val parent = IntArray(n) { k ->
            if (isFolder[k]) {
                -1
            } else {
                val p = indexOfId[list[k].folderId] ?: -1
                if (p >= 0 && isFolder[p]) p else -1
            }
        }
        // Elementos de primer nivel (capas sueltas y carpetas) y capas de cada carpeta, de abajo hacia arriba.
        val roots = (0 until n).filter { parent[it] == -1 }.toIntArray()
        val children = Array(n) { f -> if (isFolder[f]) (0 until n).filter { parent[it] == f }.toIntArray() else IntArray(0) }

        // Recorte: la primera de cada grupo (primer nivel, o dentro de una carpeta) ignora su recorte.
        val clip = BooleanArray(n)
        for ((i, k) in roots.withIndex()) clip[k] = i > 0 && list[k].clipToBelow
        for (f in 0 until n) for ((i, c) in children[f].withIndex()) clip[c] = i > 0 && list[c].clipToBelow

        // Que se dibuja con el Modo Solo. Una carpeta se dibuja si es la elegida en Solo o si la capa de Solo esta dentro de ella.
        val soloIndex = if (soloId >= 0) list.indexOfFirst { it.id == soloId } else -1
        val soloParent = if (soloIndex >= 0) parent[soloIndex] else -1
        val drawn = BooleanArray(n) { k ->
            if (!shown[k]) {
                false
            } else if (soloId < 0) {
                true
            } else if (parent[k] >= 0) {
                list[k].id == soloId || list[parent[k]].id == soloId
            } else if (isFolder[k]) {
                list[k].id == soloId || soloParent == k
            } else {
                list[k].id == soloId
            }
        }

        for (y in y0..y1) {
            var i = y * size + x0
            for (x in x0..x1) {
                var r = 0
                var g = 0
                var b = 0
                var a = 0
                if (base != null) {
                    val c = base.at(x, y)
                    r = (c shr 16) and 0xFF
                    g = (c shr 8) and 0xFF
                    b = c and 0xFF
                    a = 255
                }
                val o = i * 4
                // Alfa (del pixel) de la capa base de los recortes: la ultima de primer nivel que no esta recortada.
                var baseA = 0
                for (k in roots) {
                    var srcR: Int
                    var srcG: Int
                    var srcB: Int
                    var rawA: Int
                    if (isFolder[k]) {
                        if (!shown[k]) {
                            if (!clip[k]) baseA = 0
                            continue
                        }
                        // Compone las capas de la carpeta entre ellas, sobre transparente.
                        var gr = 0
                        var gg = 0
                        var gb = 0
                        var ga = 0
                        var groupBaseA = 0
                        for (c in children[k]) {
                            val pc = px[c]
                            val cRawA = pc[o + 3].toInt() and 0xFF
                            if (!clip[c]) groupBaseA = if (shown[c]) cRawA else 0
                            if (!drawn[c]) continue
                            var csa = cRawA * op[c] / 255
                            if (clip[c]) csa = csa * groupBaseA / 255
                            if (csa == 0) continue
                            val packedChild = mixer.over(
                                modes[c], gr, gg, gb, ga,
                                pc[o].toInt() and 0xFF, pc[o + 1].toInt() and 0xFF, pc[o + 2].toInt() and 0xFF, csa
                            )
                            ga = packedChild ushr 24
                            gr = (packedChild shr 16) and 0xFF
                            gg = (packedChild shr 8) and 0xFF
                            gb = packedChild and 0xFF
                        }
                        srcR = gr
                        srcG = gg
                        srcB = gb
                        rawA = ga
                    } else {
                        val p = px[k]
                        rawA = p[o + 3].toInt() and 0xFF
                        srcR = p[o].toInt() and 0xFF
                        srcG = p[o + 1].toInt() and 0xFF
                        srcB = p[o + 2].toInt() and 0xFF
                    }
                    if (!clip[k]) baseA = if (shown[k]) rawA else 0
                    if (!drawn[k]) continue
                    var sa = rawA * op[k] / 255
                    if (clip[k]) sa = sa * baseA / 255
                    if (sa == 0) continue
                    val packed = mixer.over(modes[k], r, g, b, a, srcR, srcG, srcB, sa)
                    a = packed ushr 24
                    r = (packed shr 16) and 0xFF
                    g = (packed shr 8) and 0xFF
                    b = packed and 0xFF
                }
                out[o] = r.toByte()
                out[o + 1] = g.toByte()
                out[o + 2] = b.toByte()
                out[o + 3] = a.toByte()
                i++
            }
        }
    }
}
