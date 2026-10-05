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
    val hasUvs: Boolean
) {
    val vertexCount: Int get() = positions.size / 3
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
 * y calcula normales planas si el archivo no las trae. Ignora materiales, grupos y suavizado.
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

    fun load(input: InputStream, fitSize: Float = 2f, yUp: Boolean = true): ObjMesh {
        val pos = FloatBuf()
        val tex = FloatBuf()
        val nor = FloatBuf()
        val outP = FloatBuf()
        val outN = FloatBuf()
        val outT = FloatBuf()
        var anyUv = false
        var lineNo = 0

        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                lineNo++
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(WS)
                when (parts[0]) {
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

        return ObjMesh(outP.data.copyOf(outP.size), outN.data.copyOf(outN.size), outT.data.copyOf(outT.size), anyUv)
    }
}
