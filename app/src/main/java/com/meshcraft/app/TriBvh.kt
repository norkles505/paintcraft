package com.meshcraft.app

/**
 * Acelerador de rayos (BVH: jerarquia de cajas) para una malla de triangulos SIN indices: cada 3 vertices consecutivos de
 * positions (x, y, z por vertice) son un triangulo, igual que en ObjMesh.
 *
 * Sin esto, TexturedMeshGeometry.pickUv probaba el rayo contra TODOS los triangulos del modelo en cada toque, y un solo
 * movimiento del dedo hace decenas de rayos: con un modelo pesado la pintura se atrasaba. Con la jerarquia cada rayo solo
 * prueba los triangulos de las cajas que atraviesa (del orden de log(N) cajas en vez de N triangulos).
 *
 * Se arma una sola vez (puede tardar un poco en modelos de cientos de miles de triangulos, por eso TexturedMeshGeometry lo
 * arma en un hilo aparte) y despues solo se lee: pick() se puede llamar desde cualquier hilo y varios a la vez. No usa OpenGL.
 * El resultado de pick() es el mismo que el del recorrido completo (triangulo mas cercano, sin descartar caras traseras).
 */
class TriBvh(private val positions: FloatArray, private val triCount: Int) {

    private companion object {
        /** Maximo de triangulos por hoja. */
        const val LEAF_SIZE = 4
        /** Holgura de cada caja, para no perder triangulos que rozan el borde por errores de redondeo. */
        const val PAD = 1e-5f
        const val MISS = Float.MAX_VALUE
    }

    /** Cajas de los nodos: 6 floats por nodo (minX, minY, minZ, maxX, maxY, maxZ). */
    private var bounds = FloatArray(0)
    /** Nodo interno: indice del hijo izquierdo (el derecho es el siguiente). Hoja: posicion de su primer triangulo en triIndex. */
    private var first = IntArray(0)
    /** Cantidad de triangulos de la hoja, o 0 si el nodo es interno. */
    private var count = IntArray(0)
    /** Numero de triangulo en el orden de la jerarquia: cada hoja ocupa un tramo seguido. */
    private val triIndex = IntArray(triCount) { it }
    private var nodeCount = 0

    init {
        build()
    }

    private fun ensureNodes(needed: Int) {
        if (needed <= first.size) return
        val cap = maxOf(needed, first.size * 2, 16)
        bounds = bounds.copyOf(cap * 6)
        first = first.copyOf(cap)
        count = count.copyOf(cap)
    }

    /** Arma la jerarquia sin recursion: cada nodo se parte por la mitad (por la mediana) a lo largo del eje donde mas se separan los centros de sus triangulos. */
    private fun build() {
        if (triCount == 0) return
        val p = positions
        val cx = FloatArray(triCount)
        val cy = FloatArray(triCount)
        val cz = FloatArray(triCount)
        for (t in 0 until triCount) {
            val a = t * 9
            cx[t] = (p[a] + p[a + 3] + p[a + 6]) / 3f
            cy[t] = (p[a + 1] + p[a + 4] + p[a + 7]) / 3f
            cz[t] = (p[a + 2] + p[a + 5] + p[a + 8]) / 3f
        }
        ensureNodes(1)
        nodeCount = 1
        // Pila de trabajo: (nodo, desde, hasta) por entrada. Su profundidad maxima es la de la jerarquia (unos 20 niveles).
        val stack = IntArray(3 * 128)
        var sp = 0
        stack[0] = 0
        stack[1] = 0
        stack[2] = triCount
        sp = 1
        while (sp > 0) {
            sp--
            val node = stack[sp * 3]
            val start = stack[sp * 3 + 1]
            val end = stack[sp * 3 + 2]

            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            var cMinX = Float.MAX_VALUE; var cMinY = Float.MAX_VALUE; var cMinZ = Float.MAX_VALUE
            var cMaxX = -Float.MAX_VALUE; var cMaxY = -Float.MAX_VALUE; var cMaxZ = -Float.MAX_VALUE
            for (i in start until end) {
                val t = triIndex[i]
                val a = t * 9
                for (v in 0 until 3) {
                    val x = p[a + v * 3]
                    val y = p[a + v * 3 + 1]
                    val z = p[a + v * 3 + 2]
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (z < minZ) minZ = z
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    if (z > maxZ) maxZ = z
                }
                val x = cx[t]
                val y = cy[t]
                val z = cz[t]
                if (x < cMinX) cMinX = x
                if (y < cMinY) cMinY = y
                if (z < cMinZ) cMinZ = z
                if (x > cMaxX) cMaxX = x
                if (y > cMaxY) cMaxY = y
                if (z > cMaxZ) cMaxZ = z
            }
            val b = node * 6
            bounds[b] = minX - PAD
            bounds[b + 1] = minY - PAD
            bounds[b + 2] = minZ - PAD
            bounds[b + 3] = maxX + PAD
            bounds[b + 4] = maxY + PAD
            bounds[b + 5] = maxZ + PAD

            val n = end - start
            if (n <= LEAF_SIZE) {
                first[node] = start
                count[node] = n
                continue
            }
            val ex = cMaxX - cMinX
            val ey = cMaxY - cMinY
            val ez = cMaxZ - cMinZ
            val key = if (ex >= ey && ex >= ez) cx else if (ey >= ez) cy else cz
            val mid = (start + end) ushr 1
            select(key, start, end, mid)

            val left = nodeCount
            nodeCount += 2
            ensureNodes(nodeCount)
            first[node] = left
            count[node] = 0
            // Se apila primero el derecho y despues el izquierdo, asi el izquierdo se arma antes.
            stack[sp * 3] = left + 1
            stack[sp * 3 + 1] = mid
            stack[sp * 3 + 2] = end
            sp++
            stack[sp * 3] = left
            stack[sp * 3 + 1] = start
            stack[sp * 3 + 2] = mid
            sp++
        }
    }

    /** Deja en triIndex[k] el triangulo que quedaria en esa posicion si [from, to) estuviera ordenado por key (los de antes tienen key menor o igual, los de despues mayor o igual). */
    private fun select(key: FloatArray, from: Int, to: Int, k: Int) {
        val idx = triIndex
        var lo = from
        var hi = to - 1
        while (lo < hi) {
            val pivot = key[idx[(lo + hi) ushr 1]]
            var i = lo
            var j = hi
            while (i <= j) {
                while (key[idx[i]] < pivot) i++
                while (key[idx[j]] > pivot) j--
                if (i <= j) {
                    val tmp = idx[i]
                    idx[i] = idx[j]
                    idx[j] = tmp
                    i++
                    j--
                }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return
        }
    }

    /**
     * Distancia a la que el rayo (origen o, direccion d; ix, iy, iz = 1/d, o 0 si esa componente es casi 0) entra en la caja del nodo,
     * o MISS si no la toca por delante o si la caja queda mas lejos que maxT.
     */
    private fun boxEntry(
        node: Int,
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        ix: Float, iy: Float, iz: Float,
        maxT: Float
    ): Float {
        val bb = bounds
        val b = node * 6
        var tMin = 0f
        var tMax = maxT
        if (dx > -1e-12f && dx < 1e-12f) {
            if (ox < bb[b] || ox > bb[b + 3]) return MISS
        } else {
            var t1 = (bb[b] - ox) * ix
            var t2 = (bb[b + 3] - ox) * ix
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return MISS
        }
        if (dy > -1e-12f && dy < 1e-12f) {
            if (oy < bb[b + 1] || oy > bb[b + 4]) return MISS
        } else {
            var t1 = (bb[b + 1] - oy) * iy
            var t2 = (bb[b + 4] - oy) * iy
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return MISS
        }
        if (dz > -1e-12f && dz < 1e-12f) {
            if (oz < bb[b + 2] || oz > bb[b + 5]) return MISS
        } else {
            var t1 = (bb[b + 2] - oz) * iz
            var t2 = (bb[b + 5] - oz) * iz
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return MISS
        }
        return tMin
    }

    /**
     * Rayo (origen o, direccion d, en el mismo espacio que positions) contra la malla: [t, u, v] del impacto mas cercano - t a lo largo del
     * rayo, (u, v) = la coordenada de textura interpolada con las UVs (uvs: u, v por vertice) de los 3 vertices del triangulo - o null si
     * el rayo no toca nada. Mismo resultado que recorrer todos los triangulos (Moller-Trumbore, sin descartar caras traseras).
     */
    fun pick(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, uvs: FloatArray): FloatArray? {
        if (triCount == 0 || nodeCount == 0) return null
        val p = positions
        val ix = if (dx > -1e-12f && dx < 1e-12f) 0f else 1f / dx
        val iy = if (dy > -1e-12f && dy < 1e-12f) 0f else 1f / dy
        val iz = if (dz > -1e-12f && dz < 1e-12f) 0f else 1f / dz

        var bestT = Float.MAX_VALUE
        var bestTri = -1
        var bestBu = 0f
        var bestBv = 0f

        val rootT = boxEntry(0, ox, oy, oz, dx, dy, dz, ix, iy, iz, bestT)
        if (rootT == MISS) return null
        val stackNode = IntArray(128)
        val stackT = FloatArray(128)
        stackNode[0] = 0
        stackT[0] = rootT
        var sp = 1
        while (sp > 0) {
            sp--
            if (stackT[sp] > bestT) continue
            val node = stackNode[sp]
            val c = count[node]
            if (c > 0) {
                val s = first[node]
                for (k in s until s + c) {
                    val t = triIndex[k]
                    val a = t * 9
                    val e1x = p[a + 3] - p[a]; val e1y = p[a + 4] - p[a + 1]; val e1z = p[a + 5] - p[a + 2]
                    val e2x = p[a + 6] - p[a]; val e2y = p[a + 7] - p[a + 1]; val e2z = p[a + 8] - p[a + 2]
                    // h = d x e2
                    val hx = dy * e2z - dz * e2y
                    val hy = dz * e2x - dx * e2z
                    val hz = dx * e2y - dy * e2x
                    val det = e1x * hx + e1y * hy + e1z * hz
                    if (det > -1e-9f && det < 1e-9f) continue
                    val f = 1f / det
                    val sx = ox - p[a]; val sy = oy - p[a + 1]; val sz = oz - p[a + 2]
                    val bu = f * (sx * hx + sy * hy + sz * hz)
                    if (bu < 0f || bu > 1f) continue
                    // q = s x e1
                    val qx = sy * e1z - sz * e1y
                    val qy = sz * e1x - sx * e1z
                    val qz = sx * e1y - sy * e1x
                    val bv = f * (dx * qx + dy * qy + dz * qz)
                    if (bv < 0f || bu + bv > 1f) continue
                    val tt = f * (e2x * qx + e2y * qy + e2z * qz)
                    if (tt > 1e-5f && tt < bestT) {
                        bestT = tt
                        bestTri = t
                        bestBu = bu
                        bestBv = bv
                    }
                }
                continue
            }
            val l = first[node]
            val tl = boxEntry(l, ox, oy, oz, dx, dy, dz, ix, iy, iz, bestT)
            val tr = boxEntry(l + 1, ox, oy, oz, dx, dy, dz, ix, iy, iz, bestT)
            // Se apila primero la caja mas lejana: la mas cercana se prueba antes y acorta bestT para descartar la otra.
            if (tl <= tr) {
                if (tr != MISS) { stackNode[sp] = l + 1; stackT[sp] = tr; sp++ }
                if (tl != MISS) { stackNode[sp] = l; stackT[sp] = tl; sp++ }
            } else {
                if (tl != MISS) { stackNode[sp] = l; stackT[sp] = tl; sp++ }
                if (tr != MISS) { stackNode[sp] = l + 1; stackT[sp] = tr; sp++ }
            }
        }
        if (bestTri < 0) return null
        val w0 = 1f - bestBu - bestBv
        val i = bestTri * 6
        val u = w0 * uvs[i] + bestBu * uvs[i + 2] + bestBv * uvs[i + 4]
        val v = w0 * uvs[i + 1] + bestBu * uvs[i + 3] + bestBv * uvs[i + 5]
        return floatArrayOf(bestT, u, v)
    }
}
