package com.meshcraft.app

import java.io.InputStream

/**
 * Malla cargada desde un OBJ, ya triangulada y SIN indices (3 vertices por triangulo), lista para subir a OpenGL ES 2.0.
 * positions: x,y,z por vertice. normals: x,y,z por vertice. uvs: u,v por vertice (0,0 si el OBJ no trae UVs, ver hasUvs).
 */
class ObjMesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val hasUvs: Boolean,
    /** Partes del modelo (lineas o/g del OBJ), cada una un tramo seguido de vertices. Vacio si el OBJ no trae partes (o trae una sola). Cubren toda la malla sin huecos. */
    val parts: List<MeshPart> = emptyList()
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = vertexCount / 3

    /**
     * Si cada parte se ve (misma posicion que en parts). La interfaz los cambia (ver MyGLRenderer.setPartVisible) y el hilo de
     * render los lee al dibujar y al pintar: una parte oculta no se dibuja ni se puede pintar.
     */
    val partVisible = BooleanArray(parts.size) { true }

    /**
     * Orden en que salen las partes en la lista del panel: partOrder[posicion en la lista] = indice de la parte. Lo cambia el asa de cada
     * fila. OJO: solo cambia la lista, no el modelo ni la pintura (a diferencia de las capas, el orden de las partes no afecta a nada).
     */
    val partOrder = IntArray(parts.size) { it }
}

/** Una parte de un modelo: un nombre y el tramo de vertices [firstVertex, firstVertex + vertexCount) de la malla (multiplo de 3: triangulos completos). */
class MeshPart(val name: String, val firstVertex: Int, val vertexCount: Int) {
    val triangleCount: Int get() = vertexCount / 3
}

private class FloatBuf {
    var data = FloatArray(1024)
    var size = 0
    fun add(x: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = x
    }
}

/**
 * Lector de archivos Wavefront OBJ (v, vt, vn, f). Triangula poligonos en abanico, soporta indices negativos,
 * y calcula normales planas si el archivo no las trae. Ignora materiales y suavizado. Las lineas "o" y "g" parten el modelo en
 * partes (ver ObjMesh.parts): cada parte es un tramo seguido de la malla, que sigue siendo UNA sola malla con UNA sola textura.
 * fitSize > 0: centra el modelo en el origen y lo escala para que su lado mas largo mida fitSize.
 * yUp = true (por defecto): el OBJ viene con Y arriba (estandar, y lo que exporta Blender por defecto) y se convierte a Z arriba.
 */
object ObjLoader {
    private val WS = Regex("\\s+")

    private fun resolve(idx: Int, count: Int, lineNo: Int): Int {
        val r = if (idx > 0) idx - 1 else count + idx
        if (r < 0 || r >= count) throw IllegalArgumentException("Linea " + lineNo + ": indice fuera de rango")
        return r
    }

    /** Maximo de piezas en que se separa un modelo: cada una lleva su propia textura, capas e historial y pesa varios MB, asi que con mas partes que esto el modelo se queda como una sola malla. */
    const val MAX_PIECES = 12

    /**
     * Parte un modelo con partes (lineas o/g) en una malla por parte, cada una con su nombre y sin partes propias. Cada una se pinta con su
     * PROPIA textura y capas (ver MyGLRenderer.addImportedMeshes), asi las piezas que usan la misma zona del mapa UV no se pisan. Las posiciones
     * no cambian, asi que todas quedan juntas como en el modelo original. Con menos de 2 partes, o mas de MAX_PIECES, devuelve la malla tal cual.
     */
    fun splitParts(mesh: ObjMesh): List<Pair<String, ObjMesh>> {
        if (mesh.parts.size < 2 || mesh.parts.size > MAX_PIECES) return listOf(Pair("Modelo", mesh))
        val result = ArrayList<Pair<String, ObjMesh>>()
        for (part in mesh.parts) {
            val a = part.firstVertex
            val b = part.firstVertex + part.vertexCount
            val piece = ObjMesh(
                mesh.positions.copyOfRange(a * 3, b * 3),
                mesh.normals.copyOfRange(a * 3, b * 3),
                mesh.uvs.copyOfRange(a * 2, b * 2),
                mesh.hasUvs
            )
            result.add(Pair(part.name, piece))
        }
        return result
    }

    fun load(input: InputStream, fitSize: Float = 2f, yUp: Boolean = true): ObjMesh {
        val pos = FloatBuf()
        val tex = FloatBuf()
        val nor = FloatBuf()
        val outP = FloatBuf()
        val outN = FloatBuf()
        val outT = FloatBuf()
        var anyUv = false
        var lineNo = 0
        // Partes: en que vertice de salida empieza cada una (las lineas o/g) y su nombre. Las vacias se descartan al final.
        val partStarts = ArrayList<Int>()
        val partNames = ArrayList<String>()

        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                lineNo++
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(WS)
                when (parts[0]) {
                    "o", "g" -> {
                        val name = parts.drop(1).joinToString(" ").ifEmpty { "Parte " + (partNames.size + 1) }
                        partStarts.add(outP.size / 3)
                        partNames.add(name)
                    }
                    "v" -> {
                        if (parts.size < 4) throw IllegalArgumentException("Linea " + lineNo + ": vertice incompleto")
                        val vx = parts[1].toFloat(); val vy = parts[2].toFloat(); val vz = parts[3].toFloat()
                        // Y arriba (OBJ estandar) -> Z arriba (MeshCraft): (x, y, z) -> (x, -z, y).
                        if (yUp) { pos.add(vx); pos.add(-vz); pos.add(vy) } else { pos.add(vx); pos.add(vy); pos.add(vz) }
                    }
                    "vt" -> {
                        if (parts.size < 3) throw IllegalArgumentException("Linea " + lineNo + ": UV incompleta")
                        tex.add(parts[1].toFloat()); tex.add(parts[2].toFloat())
                    }
                    "vn" -> {
                        if (parts.size < 4) throw IllegalArgumentException("Linea " + lineNo + ": normal incompleta")
                        val nx = parts[1].toFloat(); val ny = parts[2].toFloat(); val nz = parts[3].toFloat()
                        if (yUp) { nor.add(nx); nor.add(-nz); nor.add(ny) } else { nor.add(nx); nor.add(ny); nor.add(nz) }
                    }
                    "f" -> {
                        val n = parts.size - 1
                        if (n < 3) throw IllegalArgumentException("Linea " + lineNo + ": cara con menos de 3 vertices")
                        val vi = IntArray(n)
                        val ti = IntArray(n)
                        val ni = IntArray(n)
                        for (k in 0 until n) {
                            val tok = parts[k + 1].split("/")
                            vi[k] = resolve(tok[0].toInt(), pos.size / 3, lineNo)
                            ti[k] = if (tok.size > 1 && tok[1].isNotEmpty()) resolve(tok[1].toInt(), tex.size / 2, lineNo) else -1
                            ni[k] = if (tok.size > 2 && tok[2].isNotEmpty()) resolve(tok[2].toInt(), nor.size / 3, lineNo) else -1
                        }
                        for (k in 1 until n - 1) {
                            val c = intArrayOf(0, k, k + 1)
                            // Normal plana por si algun vertice de este triangulo no trae normal propia.
                            val a = vi[c[0]] * 3; val b = vi[c[1]] * 3; val e = vi[c[2]] * 3
                            val ux = pos.data[b] - pos.data[a]; val uy = pos.data[b + 1] - pos.data[a + 1]; val uz = pos.data[b + 2] - pos.data[a + 2]
                            val wx = pos.data[e] - pos.data[a]; val wy = pos.data[e + 1] - pos.data[a + 1]; val wz = pos.data[e + 2] - pos.data[a + 2]
                            var fx = uy * wz - uz * wy; var fy = uz * wx - ux * wz; var fz = ux * wy - uy * wx
                            val len = Math.sqrt((fx * fx + fy * fy + fz * fz).toDouble()).toFloat()
                            if (len > 0f) { fx /= len; fy /= len; fz /= len }
                            val allNormals = ni[c[0]] >= 0 && ni[c[1]] >= 0 && ni[c[2]] >= 0
                            for (corner in c) {
                                val p = vi[corner] * 3
                                outP.add(pos.data[p]); outP.add(pos.data[p + 1]); outP.add(pos.data[p + 2])
                                if (allNormals) {
                                    val q = ni[corner] * 3
                                    outN.add(nor.data[q]); outN.add(nor.data[q + 1]); outN.add(nor.data[q + 2])
                                } else {
                                    outN.add(fx); outN.add(fy); outN.add(fz)
                                }
                                if (ti[corner] >= 0) {
                                    val t = ti[corner] * 2
                                    outT.add(tex.data[t]); outT.add(tex.data[t + 1])
                                    anyUv = true
                                } else {
                                    outT.add(0f); outT.add(0f)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (outP.size == 0) throw IllegalArgumentException("El archivo no tiene caras (lineas f)")

        if (fitSize > 0f) {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            var i = 0
            while (i < outP.size) {
                val x = outP.data[i]; val y = outP.data[i + 1]; val z = outP.data[i + 2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                i += 3
            }
            val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f; val cz = (minZ + maxZ) / 2f
            val extent = maxOf(maxX - minX, maxY - minY, maxZ - minZ)
            val s = if (extent > 0f) fitSize / extent else 1f
            i = 0
            while (i < outP.size) {
                outP.data[i] = (outP.data[i] - cx) * s
                outP.data[i + 1] = (outP.data[i + 1] - cy) * s
                outP.data[i + 2] = (outP.data[i + 2] - cz) * s
                i += 3
            }
        }

        // Partes: cada linea o/g abre un tramo que llega hasta la siguiente. Los tramos vacios (un "o" seguido de un "g") se descartan, y las
        // caras que vinieran antes de la primera linea forman una parte propia, asi las partes cubren toda la malla sin huecos.
        val totalVertices = outP.size / 3
        val meshParts = ArrayList<MeshPart>()
        if (partStarts.isNotEmpty()) {
            if (partStarts[0] > 0) meshParts.add(MeshPart("Sin nombre", 0, partStarts[0]))
            for (k in partStarts.indices) {
                val from = partStarts[k]
                val to = if (k + 1 < partStarts.size) partStarts[k + 1] else totalVertices
                if (to > from) meshParts.add(MeshPart(partNames[k], from, to - from))
            }
        }
        // Con una sola parte no hay nada que separar: el modelo se comporta como siempre.
        val finalParts = if (meshParts.size >= 2) meshParts else emptyList()

        return ObjMesh(outP.data.copyOf(outP.size), outN.data.copyOf(outN.size), outT.data.copyOf(outT.size), anyUv, finalParts)
    }
}
