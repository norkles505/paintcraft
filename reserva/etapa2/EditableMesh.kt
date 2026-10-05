package com.meshcraft.app

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Representacion editable de una malla: vertices/aristas/caras individuales con su propio id, a
 * diferencia de los buffers estaticos de MeshGeometry (pensados solo para dibujar rapido, no para
 * editar - ver comentario de esa clase). Un SceneObject solo tiene un EditableMesh cuando entro a
 * Edit Mode al menos una vez (ver SceneObject.editableMesh) - se genera on-demand a partir de su
 * MeshType (ver MeshType.toEditableMesh) y a partir de ahi vive independiente de la primitiva
 * original: mover un vertice de este objeto no afecta a otros objetos del mismo tipo (a
 * diferencia de la geometria estatica COMPARTIDA por tipo que usa MyGLRenderer hoy - un solo
 * Cube() para todos los cubos de la escena).
 *
 * Fase 0 del plan de Edit Mode (ver charla con el usuario): arranco solo con Cube (ver
 * cubeEditableMesh). Paso 3 del plan (ver charla con el supervisor - "que el resto de las
 * primitivas sean editables"): se suman las 8 restantes (Plane/Circle/UvSphere/IcoSphere/
 * Cylinder/Cone/Torus/Grid/Monkey), cada una con su propia funcion de conversion mas abajo -
 * mismo criterio de "una por vez, on-demand" que ya se uso para las primitivas reales de
 * Add > Mesh (Cube primero, las demas despues).
 *
 * Criterio de topologia usado en TODAS las conversiones nuevas (a diferencia del dibujo, que
 * siempre triangula): las caras se guardan como n-gons (vertexIds en orden, tantos como haga
 * falta), NO explotadas en triangulos - MeshFace ya soporta esto (ver raycastFaceAt/
 * DynamicMeshGeometry, que trianguian en abanico desde el primer vertice para dibujar, "valido
 * para cualquier n-gon convexo"). Esto da una malla editable mas parecida a la real de Blender
 * (una tapa de cilindro es 1 cara de N lados, no N triangulos separados) y evita vertices/aristas
 * duplicados que solo existian en las clases de dibujo (Cube.kt, Circle.kt, etc.) para poder
 * asignarle una normal propia a cada cara (necesario para el sombreado plano del dibujo, pero
 * irrelevante aca - EditableMesh no guarda normales, se recalculan al dibujar via
 * DynamicMeshGeometry). Todas las funciones reusan los mismos parametros default (segments,
 * radius, etc.) que ya usa cada clase de dibujo (Circle, UvSphere, etc.) cuando MyGLRenderer las
 * instancia sin argumentos (ver onSurfaceCreated) - mismo tamano y nivel de detalle visual.
 */
data class MeshVertex(val id: Int, var x: Float, var y: Float, var z: Float, var selected: Boolean = false)

/** v1/v2 son ids de MeshVertex, no indices de lista - buscar por id, no por posicion. */
data class MeshEdge(val id: Int, val v1: Int, val v2: Int, var selected: Boolean = false)

/** vertexIds en orden (forman el contorno de la cara, no una bolsa desordenada) - ids de MeshVertex. */
data class MeshFace(val id: Int, val vertexIds: List<Int>, var selected: Boolean = false)

class EditableMesh(
    val vertices: MutableList<MeshVertex>,
    val edges: MutableList<MeshEdge>,
    val faces: MutableList<MeshFace>
) {
    /**
     * Copia profunda real (no solo de la lista - cada MeshVertex/MeshEdge/MeshFace tambien se
     * copia, al ser data class con .copy()) - mismo motivo que SceneObject.rotationMatrix/
     * shapeMatrix necesitan copyOf() en vez de copy() a secas: sin esto, restaurar un snapshot de
     * Undo o duplicar un objeto compartiria las mismas instancias mutables entre el original y la
     * copia, y editar una moveria la otra tambien.
     */
    fun deepCopy(): EditableMesh = EditableMesh(
        vertices.map { it.copy() }.toMutableList(),
        edges.map { it.copy() }.toMutableList(),
        faces.map { it.copy() }.toMutableList()
    )
}

/**
 * Conversion de una primitiva ya existente (ver MeshType) a su representacion editable. Null si
 * esa primitiva todavia no tiene conversion implementada (ver comentario de clase) - el llamador
 * (SceneObject al entrar a Edit Mode) trata null como "esta primitiva todavia no es editable".
 * Con el Paso 3 del plan cerrado, ya no queda ningun MeshType sin conversion (de ahi que el when
 * de abajo ya no necesite un `else -> null`).
 */
fun MeshType.toEditableMesh(): EditableMesh? {
    return when (this) {
        MeshType.CUBE -> cubeEditableMesh()
        MeshType.PLANE -> planeEditableMesh()
        MeshType.CIRCLE -> circleEditableMesh()
        MeshType.UV_SPHERE -> uvSphereEditableMesh()
        MeshType.ICO_SPHERE -> icoSphereEditableMesh()
        MeshType.CYLINDER -> cylinderEditableMesh()
        MeshType.CONE -> coneEditableMesh()
        MeshType.TORUS -> torusEditableMesh()
        MeshType.GRID -> gridEditableMesh()
        MeshType.MONKEY -> monkeyEditableMesh()
    }
}

/**
 * Topologia real del cubo: 8 vertices (esquinas), 12 aristas, 6 caras (cuads) - a diferencia de
 * los 24 vertices duplicados que usa Cube.kt/MeshGeometry para el dibujo (una copia por cara,
 * necesaria ahi para que cada cara tenga su propia normal plana - ver comentario de Cube.kt). Los
 * ids y posiciones de vertices coinciden con los 8 puntos unicos que ya usa Cube.edgeVertices
 * (antes del escalado edgeScale, que es solo cosmetico para que el contorno no compita en el
 * z-buffer con las caras).
 */
private fun cubeEditableMesh(): EditableMesh {
    val vertices = mutableListOf(
        MeshVertex(0, -0.5f, -0.5f, 0.5f),
        MeshVertex(1, 0.5f, -0.5f, 0.5f),
        MeshVertex(2, 0.5f, 0.5f, 0.5f),
        MeshVertex(3, -0.5f, 0.5f, 0.5f),
        MeshVertex(4, -0.5f, -0.5f, -0.5f),
        MeshVertex(5, 0.5f, -0.5f, -0.5f),
        MeshVertex(6, 0.5f, 0.5f, -0.5f),
        MeshVertex(7, -0.5f, 0.5f, -0.5f)
    )
    val edges = mutableListOf(
        MeshEdge(0, 0, 1), MeshEdge(1, 1, 2), MeshEdge(2, 2, 3), MeshEdge(3, 3, 0),
        MeshEdge(4, 4, 5), MeshEdge(5, 5, 6), MeshEdge(6, 6, 7), MeshEdge(7, 7, 4),
        MeshEdge(8, 0, 4), MeshEdge(9, 1, 5), MeshEdge(10, 2, 6), MeshEdge(11, 3, 7)
    )
    val faces = mutableListOf(
        MeshFace(0, listOf(0, 1, 2, 3)),  // Front (+Z)
        MeshFace(1, listOf(5, 4, 7, 6)),  // Back (-Z)
        MeshFace(2, listOf(1, 5, 6, 2)),  // Right (+X)
        MeshFace(3, listOf(4, 0, 3, 7)),  // Left (-X)
        MeshFace(4, listOf(3, 2, 6, 7)),  // Top (+Y)
        MeshFace(5, listOf(4, 5, 1, 0))   // Bottom (-Y)
    )
    return EditableMesh(vertices, edges, faces)
}

/** Igual que Plane.kt (un solo quad chato sobre XY) pero como 1 cara real de 4 lados, no 2 triangulos de dibujo. */
private fun planeEditableMesh(): EditableMesh {
    val vertices = mutableListOf(
        MeshVertex(0, -0.5f, -0.5f, 0f),
        MeshVertex(1, 0.5f, -0.5f, 0f),
        MeshVertex(2, 0.5f, 0.5f, 0f),
        MeshVertex(3, -0.5f, 0.5f, 0f)
    )
    val edges = mutableListOf(
        MeshEdge(0, 0, 1), MeshEdge(1, 1, 2), MeshEdge(2, 2, 3), MeshEdge(3, 3, 0)
    )
    val faces = mutableListOf(MeshFace(0, listOf(0, 1, 2, 3)))
    return EditableMesh(vertices, edges, faces)
}

/**
 * Igual que Circle.kt (mismos defaults: 32 segments, radio 0.5) pero SIN vertice de centro ni
 * radios internos - el circulo relleno es UNA sola cara de `segments` lados (el perimetro
 * completo como n-gon) - mas fiel a como Blender arma su Circle real.
 */
private fun circleEditableMesh(segments: Int = 32, radius: Float = 0.5f): EditableMesh {
    val vertices = mutableListOf<MeshVertex>()
    for (i in 0 until segments) {
        val angle = (i.toFloat() / segments) * (2f * Math.PI.toFloat())
        vertices.add(MeshVertex(i, radius * cos(angle), radius * sin(angle), 0f))
    }
    val edges = mutableListOf<MeshEdge>()
    for (i in 0 until segments) {
        edges.add(MeshEdge(i, i, (i + 1) % segments))
    }
    val faces = mutableListOf(MeshFace(0, (0 until segments).toList()))
    return EditableMesh(vertices, edges, faces)
}

/**
 * Igual que UvSphere.kt (mismos defaults: 32 segments, 16 rings, radio 0.5) pero sin la columna
 * de seam duplicada que usa esa clase para el dibujo - la columna `segments` cierra con modulo
 * contra la columna 0, mismo criterio que Torus. Caras: quads entre filas consecutivas (incluidas
 * las filas de polo, que degeneran en quads de area chica pero no rota).
 */
private fun uvSphereEditableMesh(segments: Int = 32, rings: Int = 16, radius: Float = 0.5f): EditableMesh {
    fun idx(i: Int, j: Int) = i * segments + j
    val vertices = mutableListOf<MeshVertex>()
    for (i in 0..rings) {
        val theta = (i.toFloat() / rings) * Math.PI.toFloat()
        val sinTheta = sin(theta)
        val cosTheta = cos(theta)
        for (j in 0 until segments) {
            val phi = (j.toFloat() / segments) * (2f * Math.PI.toFloat())
            vertices.add(MeshVertex(idx(i, j), radius * sinTheta * cos(phi), radius * sinTheta * sin(phi), radius * cosTheta))
        }
    }
    val edgeSet = HashSet<Long>()
    val edges = mutableListOf<MeshEdge>()
    var nextEdgeId = 0
    fun addEdge(a: Int, b: Int) {
        val key = if (a < b) a.toLong() * 100000L + b else b.toLong() * 100000L + a
        if (edgeSet.add(key)) edges.add(MeshEdge(nextEdgeId++, a, b))
    }
    val faces = mutableListOf<MeshFace>()
    var nextFaceId = 0
    for (i in 0 until rings) {
        for (j in 0 until segments) {
            val jn = (j + 1) % segments
            val v00 = idx(i, j); val v01 = idx(i, jn); val v10 = idx(i + 1, j); val v11 = idx(i + 1, jn)
            addEdge(v00, v01); addEdge(v00, v10); addEdge(v01, v11); addEdge(v10, v11)
            faces.add(MeshFace(nextFaceId++, listOf(v00, v01, v11, v10)))
        }
    }
    return EditableMesh(vertices, edges, faces)
}

/**
 * Igual que IcoSphere.kt (mismos defaults: 2 subdivisions, radio 0.5) - replica el mismo algoritmo
 * de generacion (icosaedro base + subdivision por punto medio) pero usando los vertices UNICOS
 * directamente (sin "explotar" cada triangulo en 3 vertices propios).
 */
private fun icoSphereEditableMesh(subdivisions: Int = 2, radius: Float = 0.5f): EditableMesh {
    val uniqueVerts = mutableListOf<FloatArray>()
    var faceIdx = mutableListOf<IntArray>()

    fun addVertex(x: Float, y: Float, z: Float): Int {
        val len = sqrt(x * x + y * y + z * z)
        uniqueVerts.add(floatArrayOf(x / len * radius, y / len * radius, z / len * radius))
        return uniqueVerts.size - 1
    }

    val t = (1f + sqrt(5f)) / 2f
    val v0 = addVertex(-1f, t, 0f)
    val v1 = addVertex(1f, t, 0f)
    val v2 = addVertex(-1f, -t, 0f)
    val v3 = addVertex(1f, -t, 0f)
    val v4 = addVertex(0f, -1f, t)
    val v5 = addVertex(0f, 1f, t)
    val v6 = addVertex(0f, -1f, -t)
    val v7 = addVertex(0f, 1f, -t)
    val v8 = addVertex(t, 0f, -1f)
    val v9 = addVertex(t, 0f, 1f)
    val v10 = addVertex(-t, 0f, -1f)
    val v11 = addVertex(-t, 0f, 1f)

    faceIdx.addAll(listOf(
        intArrayOf(v0, v11, v5), intArrayOf(v0, v5, v1), intArrayOf(v0, v1, v7),
        intArrayOf(v0, v7, v10), intArrayOf(v0, v10, v11),
        intArrayOf(v1, v5, v9), intArrayOf(v5, v11, v4), intArrayOf(v11, v10, v2),
        intArrayOf(v10, v7, v6), intArrayOf(v7, v1, v8),
        intArrayOf(v3, v9, v4), intArrayOf(v3, v4, v2), intArrayOf(v3, v2, v6),
        intArrayOf(v3, v6, v8), intArrayOf(v3, v8, v9),
        intArrayOf(v4, v9, v5), intArrayOf(v2, v4, v11), intArrayOf(v6, v2, v10),
        intArrayOf(v8, v6, v7), intArrayOf(v9, v8, v1)
    ))

    repeat(subdivisions) {
        val midpointCache = HashMap<Long, Int>()
        fun midpoint(iA: Int, iB: Int): Int {
            val key = if (iA < iB) iA.toLong() * 100000L + iB else iB.toLong() * 100000L + iA
            midpointCache[key]?.let { return it }
            val a = uniqueVerts[iA]
            val b = uniqueVerts[iB]
            val mid = addVertex((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)
            midpointCache[key] = mid
            return mid
        }
        val newFaces = mutableListOf<IntArray>()
        for (face in faceIdx) {
            val a = face[0]; val b = face[1]; val c = face[2]
            val ab = midpoint(a, b)
            val bc = midpoint(b, c)
            val ca = midpoint(c, a)
            newFaces.add(intArrayOf(a, ab, ca))
            newFaces.add(intArrayOf(b, bc, ab))
            newFaces.add(intArrayOf(c, ca, bc))
            newFaces.add(intArrayOf(ab, bc, ca))
        }
        faceIdx = newFaces
    }

    val vertices = uniqueVerts.mapIndexed { id, p -> MeshVertex(id, p[0], p[1], p[2]) }.toMutableList()

    val edgeSet = HashSet<Long>()
    val edges = mutableListOf<MeshEdge>()
    var nextEdgeId = 0
    fun addEdge(iA: Int, iB: Int) {
        val key = if (iA < iB) iA.toLong() * 100000L + iB else iB.toLong() * 100000L + iA
        if (edgeSet.add(key)) edges.add(MeshEdge(nextEdgeId++, iA, iB))
    }
    for (face in faceIdx) {
        addEdge(face[0], face[1]); addEdge(face[1], face[2]); addEdge(face[2], face[0])
    }

    val faces = faceIdx.mapIndexed { id, f -> MeshFace(id, f.toList()) }.toMutableList()
    return EditableMesh(vertices, edges, faces)
}

/**
 * Igual que Cylinder.kt (mismos defaults: 32 segments, radio 0.5, altura 1) pero cada tapa es UNA
 * cara n-gon (el aro completo), no un fan de triangulos - mismo criterio que circleEditableMesh.
 * El costado si son quads - topologia real de un cilindro en Blender: 2 n-gons + N quads.
 */
private fun cylinderEditableMesh(segments: Int = 32, radius: Float = 0.5f, height: Float = 1f): EditableMesh {
    val halfHeight = height / 2f
    val vertices = mutableListOf<MeshVertex>()
    for (i in 0 until segments) {
        val angle = (i.toFloat() / segments) * (2f * Math.PI.toFloat())
        vertices.add(MeshVertex(i, radius * cos(angle), radius * sin(angle), halfHeight))
    }
    for (i in 0 until segments) {
        val angle = (i.toFloat() / segments) * (2f * Math.PI.toFloat())
        vertices.add(MeshVertex(segments + i, radius * cos(angle), radius * sin(angle), -halfHeight))
    }
    val edges = mutableListOf<MeshEdge>()
    var nextEdgeId = 0
    for (i in 0 until segments) edges.add(MeshEdge(nextEdgeId++, i, (i + 1) % segments))
    for (i in 0 until segments) edges.add(MeshEdge(nextEdgeId++, segments + i, segments + (i + 1) % segments))
    for (i in 0 until segments) edges.add(MeshEdge(nextEdgeId++, i, segments + i))

    val faces = mutableListOf<MeshFace>()
    var nextFaceId = 0
    faces.add(MeshFace(nextFaceId++, (0 until segments).toList()))
    faces.add(MeshFace(nextFaceId++, (segments until 2 * segments).toList().reversed()))
    for (i in 0 until segments) {
        val next = (i + 1) % segments
        faces.add(MeshFace(nextFaceId++, listOf(segments + i, segments + next, next, i)))
    }
    return EditableMesh(vertices, edges, faces)
}

/**
 * Igual que Cone.kt (mismos defaults: 32 segments, radio 0.5, altura 1) - base como UNA cara n-gon
 * y el costado como `segments` triangulos reales hacia el vertice de la punta.
 */
private fun coneEditableMesh(segments: Int = 32, radius: Float = 0.5f, height: Float = 1f): EditableMesh {
    val halfHeight = height / 2f
    val vertices = mutableListOf<MeshVertex>()
    for (i in 0 until segments) {
        val angle = (i.toFloat() / segments) * (2f * Math.PI.toFloat())
        vertices.add(MeshVertex(i, radius * cos(angle), radius * sin(angle), -halfHeight))
    }
    val apexId = segments
    vertices.add(MeshVertex(apexId, 0f, 0f, halfHeight))

    val edges = mutableListOf<MeshEdge>()
    var nextEdgeId = 0
    for (i in 0 until segments) edges.add(MeshEdge(nextEdgeId++, i, (i + 1) % segments))
    for (i in 0 until segments) edges.add(MeshEdge(nextEdgeId++, i, apexId))

    val faces = mutableListOf<MeshFace>()
    var nextFaceId = 0
    faces.add(MeshFace(nextFaceId++, (0 until segments).toList().reversed()))
    for (i in 0 until segments) {
        val next = (i + 1) % segments
        faces.add(MeshFace(nextFaceId++, listOf(i, next, apexId)))
    }
    return EditableMesh(vertices, edges, faces)
}

/**
 * Igual que Torus.kt (mismos defaults: 32/16 segments, radios 0.4/0.1) - grilla doblemente
 * periodica, vertices UNICOS y una cara quad real por celda de la grilla.
 */
private fun torusEditableMesh(
    majorSegments: Int = 32,
    minorSegments: Int = 16,
    majorRadius: Float = 0.4f,
    minorRadius: Float = 0.1f
): EditableMesh {
    fun idx(i: Int, j: Int) = i * minorSegments + j
    val vertices = mutableListOf<MeshVertex>()
    for (i in 0 until majorSegments) {
        val theta = (i.toFloat() / majorSegments) * (2f * Math.PI.toFloat())
        val radDirX = cos(theta)
        val radDirY = sin(theta)
        for (j in 0 until minorSegments) {
            val phi = (j.toFloat() / minorSegments) * (2f * Math.PI.toFloat())
            val tubeOffset = minorRadius * cos(phi)
            val zOffset = minorRadius * sin(phi)
            vertices.add(MeshVertex(
                idx(i, j),
                majorRadius * radDirX + tubeOffset * radDirX,
                majorRadius * radDirY + tubeOffset * radDirY,
                zOffset
            ))
        }
    }
    val edges = mutableListOf<MeshEdge>()
    var nextEdgeId = 0
    for (i in 0 until majorSegments) {
        for (j in 0 until minorSegments) {
            edges.add(MeshEdge(nextEdgeId++, idx(i, j), idx(i, (j + 1) % minorSegments)))
        }
    }
    for (j in 0 until minorSegments) {
        for (i in 0 until majorSegments) {
            edges.add(MeshEdge(nextEdgeId++, idx(i, j), idx((i + 1) % majorSegments, j)))
        }
    }
    val faces = mutableListOf<MeshFace>()
    var nextFaceId = 0
    for (i in 0 until majorSegments) {
        val ni = (i + 1) % majorSegments
        for (j in 0 until minorSegments) {
            val nj = (j + 1) % minorSegments
            faces.add(MeshFace(nextFaceId++, listOf(idx(i, j), idx(i, nj), idx(ni, nj), idx(ni, j))))
        }
    }
    return EditableMesh(vertices, edges, faces)
}

/** Igual que GridMesh.kt (mismos defaults: 10x10 subdivisiones, tamano 1) - vertices compartidos y una cara quad real por celda. */
private fun gridEditableMesh(xSubdivisions: Int = 10, ySubdivisions: Int = 10, size: Float = 1f): EditableMesh {
    val half = size / 2f
    val xCount = xSubdivisions + 1
    val yCount = ySubdivisions + 1
    fun idx(ix: Int, iy: Int) = ix * yCount + iy

    val vertices = mutableListOf<MeshVertex>()
    for (ix in 0 until xCount) {
        for (iy in 0 until yCount) {
            vertices.add(MeshVertex(
                idx(ix, iy),
                -half + (ix.toFloat() / xSubdivisions) * size,
                -half + (iy.toFloat() / ySubdivisions) * size,
                0f
            ))
        }
    }
    val edges = mutableListOf<MeshEdge>()
    var nextEdgeId = 0
    for (iy in 0 until yCount) {
        for (ix in 0 until xSubdivisions) edges.add(MeshEdge(nextEdgeId++, idx(ix, iy), idx(ix + 1, iy)))
    }
    for (ix in 0 until xCount) {
        for (iy in 0 until ySubdivisions) edges.add(MeshEdge(nextEdgeId++, idx(ix, iy), idx(ix, iy + 1)))
    }
    val faces = mutableListOf<MeshFace>()
    var nextFaceId = 0
    for (ix in 0 until xSubdivisions) {
        for (iy in 0 until ySubdivisions) {
            faces.add(MeshFace(nextFaceId++, listOf(idx(ix, iy), idx(ix + 1, iy), idx(ix + 1, iy + 1), idx(ix, iy + 1))))
        }
    }
    return EditableMesh(vertices, edges, faces)
}

/**
 * A diferencia del resto (geometria por formula), el mono viene de datos de malla reales (.obj) -
 * ver Monkey.kt. Lee directo de MonkeyMeshData (objeto sin OpenGL, ver Monkey.kt) en vez de
 * instanciar Monkey(): esa clase SI construye un MeshGeometry real en su init (compila shaders),
 * lo cual solo es seguro desde el hilo de render - instanciarla aca (UI thread, al entrar a
 * Modeling) fue exactamente el bug que crasheaba la app con el Monkey.
 *
 * 507 vertices unicos, 967 caras triangulares (el .obj ya viene triangulado) - a diferencia de
 * las otras conversiones (que arman n-gons cuando la forma real lo permite), aca se mantienen los
 * triangulos tal cual el .obj: no hay forma generica de "fusionar" triangulos vecinos coplanares
 * en n-gons mas grandes sin un algoritmo aparte, y esta malla no tiene la regularidad de una
 * primitiva parametrica para hacerlo a mano.
 */
private fun monkeyEditableMesh(): EditableMesh {
    val raw = MonkeyMeshData.rawVertices
    val idx = MonkeyMeshData.faceIndices

    val vertices = mutableListOf<MeshVertex>()
    for (i in raw.indices step 3) {
        vertices.add(MeshVertex(i / 3, raw[i], raw[i + 1], raw[i + 2]))
    }

    val edgeSet = HashSet<Long>()
    val edges = mutableListOf<MeshEdge>()
    var nextEdgeId = 0
    val faces = mutableListOf<MeshFace>()

    val triCount = idx.size / 3
    for (t in 0 until triCount) {
        val ia = idx[t * 3].toInt()
        val ib = idx[t * 3 + 1].toInt()
        val ic = idx[t * 3 + 2].toInt()
        faces.add(MeshFace(t, listOf(ia, ib, ic)))
        for (pair in listOf(ia to ib, ib to ic, ic to ia)) {
            val a = minOf(pair.first, pair.second)
            val b = maxOf(pair.first, pair.second)
            val key = a.toLong() * 1000000L + b
            if (edgeSet.add(key)) edges.add(MeshEdge(nextEdgeId++, a, b))
        }
    }
    return EditableMesh(vertices, edges, faces)
}
