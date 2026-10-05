package com.meshcraft.app

import kotlin.math.sqrt

/**
 * Puente entre EditableMesh (datos indexados, pensados para edición - ver EditableMesh.kt) y
 * MeshGeometry (buffers de dibujo, pensados para render rapido - ver MeshGeometry.kt). La malla
 * editable cambia con el tiempo (Fase 2 en adelante: mover vertices en vivo, extrude, etc.), asi
 * que cada update() reconstruye los datos de dibujo a partir del estado actual - pero SIN
 * recompilar shaders: la primera vez crea un MeshGeometry (que si compila shaders, una unica vez),
 * y las siguientes llamadas reusan esa misma instancia via MeshGeometry.updateGeometry() (solo
 * reemplaza los buffers de posicion/indices, ver esa funcion) - resuelto asi el problema de
 * arrastre en vivo (Fase 2): antes cada update() recreaba MeshGeometry entero, lo que recompilaba
 * shaders (glCreateProgram, nunca liberado) en cada frame de un drag - con esto, arrastrar un
 * vertice llama a update() 60 veces por segundo sin filtrar programs de OpenGL.
 */
class DynamicMeshGeometry {

    private var geometry: MeshGeometry? = null
    /**
     * Referencia (NO copia) a la ultima EditableMesh pasada a update() - draw() la lee cada frame
     * para saber que vertices/aristas/caras estan seleccionados AHORA MISMO (ver
     * drawSelectionHighlights) sin necesidad de llamar a update() de nuevo: selected es un campo
     * mutable de MeshVertex/MeshEdge/MeshFace (ver EditableMesh.kt), asi que cambios de seleccion
     * hechos afuera (ver MyGLRenderer.selectMeshElementAt) se reflejan solos en el proximo frame,
     * sin pasar por el costo de reconstruir buffers de OpenGL (ver comentario de clase sobre por
     * que update() no se puede llamar en cada frame).
     */
    private var lastMesh: EditableMesh? = null

    /**
     * Multiplicador aplicado a la posicion LOCAL de cada vertice antes de dibujar wireframe/puntos
     * (nunca a las caras) - mismo truco y mismo valor que ya usaba Cube.kt (edgeScale = 1.003f)
     * para su propio contorno de seleccion: empuja el punto/arista levemente hacia afuera de la
     * superficie para que no compita en el z-buffer con la cara que esta exactamente en la misma
     * posicion (sin este offset, wireframe y caras parpadean/se tapan al azar - z-fighting). Valido
     * mientras las primitivas esten centradas en su origen local (caso de todas las de hoy) - si
     * mas adelante aparece una primitiva no centrada, este offset radial dejaria de ser correcto y
     * habria que empujar a lo largo de la normal de cada vertice en vez de escalar la posicion.
     */
    private val EDGE_OFFSET_SCALE = 1.003f
    private fun offsetLocal(v: MeshVertex): FloatArray = floatArrayOf(v.x * EDGE_OFFSET_SCALE, v.y * EDGE_OFFSET_SCALE, v.z * EDGE_OFFSET_SCALE)

    /**
     * Reconstruye la geometria de dibujo a partir del estado actual de EditableMesh.
     *
     * Caras: fan simple (0,1,2 / 0,2,3 / ... desde el primer vertice de la cara) - funciona para
     * cualquier n-gon convexo, no hace falta casear por tamano (hoy el Cube solo tiene cuads, pero
     * esto ya sirve tal cual si mas adelante aparece una cara triangular o de mas lados). Cada cara
     * duplica sus vertices con su propia normal plana (cross product de dos aristas de la cara) -
     * mismo criterio de flat shading que ya usa Cube.kt a mano.
     *
     * Aristas: un vertice de buffer por cada MeshVertex distinto referenciado (no uno por MeshEdge,
     * ver edgeVertexIndexById) - evita duplicar posiciones cuando varias aristas comparten vertice.
     * Este buffer interno de MeshGeometry ya NO se usa para dibujar el wireframe en Edit Mode (ver
     * drawBaseWireframe, que dibuja directo desde EditableMesh con su propio color) - sigue
     * existiendo porque MeshGeometry.draw() lo necesita para el contorno naranja de objeto
     * completo que se usa en Layout (ver draw(), parametro editModeDisplay).
     */
    fun update(mesh: EditableMesh) {
        lastMesh = mesh

        val vertexById = mesh.vertices.associateBy { it.id }

        val faceVerticesList = mutableListOf<Float>()
        val faceNormalsList = mutableListOf<Float>()
        val faceDrawOrderList = mutableListOf<Short>()
        var nextFaceVertexIndex = 0

        for (face in mesh.faces) {
            val corners = face.vertexIds.mapNotNull { vertexById[it] }
            if (corners.size < 3) continue // cara degenerada (vertices borrados/invalidos) - se salta

            val normal = faceNormal(corners)
            val baseIndex = nextFaceVertexIndex
            for (corner in corners) {
                faceVerticesList.add(corner.x)
                faceVerticesList.add(corner.y)
                faceVerticesList.add(corner.z)
                faceNormalsList.add(normal[0])
                faceNormalsList.add(normal[1])
                faceNormalsList.add(normal[2])
                nextFaceVertexIndex++
            }
            for (i in 1 until corners.size - 1) {
                faceDrawOrderList.add(baseIndex.toShort())
                faceDrawOrderList.add((baseIndex + i).toShort())
                faceDrawOrderList.add((baseIndex + i + 1).toShort())
            }
        }

        val edgeVerticesList = mutableListOf<Float>()
        val edgeDrawOrderList = mutableListOf<Short>()
        val edgeVertexIndexById = mutableMapOf<Int, Short>()
        var nextEdgeVertexIndex: Short = 0
        for (edge in mesh.edges) {
            val v1 = vertexById[edge.v1] ?: continue
            val v2 = vertexById[edge.v2] ?: continue
            val i1 = edgeVertexIndexById.getOrPut(edge.v1) {
                edgeVerticesList.add(v1.x); edgeVerticesList.add(v1.y); edgeVerticesList.add(v1.z)
                nextEdgeVertexIndex++
            }
            val i2 = edgeVertexIndexById.getOrPut(edge.v2) {
                edgeVerticesList.add(v2.x); edgeVerticesList.add(v2.y); edgeVerticesList.add(v2.z)
                nextEdgeVertexIndex++
            }
            edgeDrawOrderList.add(i1)
            edgeDrawOrderList.add(i2)
        }

        val faceVerticesArray = faceVerticesList.toFloatArray()
        val faceNormalsArray = faceNormalsList.toFloatArray()
        val faceDrawOrderArray = faceDrawOrderList.toShortArray()
        val edgeVerticesArray = edgeVerticesList.toFloatArray()
        val edgeDrawOrderArray = edgeDrawOrderList.toShortArray()

        val existing = geometry
        if (existing != null) {
            existing.updateGeometry(faceVerticesArray, faceNormalsArray, faceDrawOrderArray, edgeVerticesArray, edgeDrawOrderArray)
        } else {
            geometry = MeshGeometry(faceVerticesArray, faceNormalsArray, faceDrawOrderArray, edgeVerticesArray, edgeDrawOrderArray)
        }
    }

    private fun faceNormal(corners: List<MeshVertex>): FloatArray {
        val a = corners[0]
        val b = corners[1]
        val c = corners[2]
        val ux = b.x - a.x; val uy = b.y - a.y; val uz = b.z - a.z
        val vx = c.x - a.x; val vy = c.y - a.y; val vz = c.z - a.z
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 1e-8f) { nx /= len; ny /= len; nz /= len }
        return floatArrayOf(nx, ny, nz)
    }

    /**
     * No dibuja nada si todavia no se llamo a update() al menos una vez (ver
     * MyGLRenderer.refreshDynamicGeometry, que llama update() antes de que esto entre en uso).
     *
     * editModeDisplay: si es false (objeto ya editado, pero visto en Layout), dibuja como
     * cualquier primitiva normal - caras + el contorno naranja de "objeto completo" cuando esta
     * seleccionado (mismo `geometry.draw(mvpMatrix, selected)` de siempre, sin wireframe/puntos:
     * Blender tampoco muestra los vertices de Edit Mode en Object Mode). Si es true (Modeling,
     * viendo el objeto que se esta editando), el contorno naranja de objeto completo NO tiene
     * sentido (fue reemplazado por resaltado de sub-elemento) - en su lugar dibuja wireframe +
     * puntos negros SIEMPRE visibles, y encima el resaltado naranja de lo que este seleccionado
     * (ver drawBaseWireframe/drawSelectionHighlights), mismo criterio visual que Blender (ver
     * charla con el usuario y su captura de referencia).
     */
    fun draw(mvpMatrix: FloatArray, selected: Boolean, editModeDisplay: Boolean) {
        if (!editModeDisplay) {
            geometry?.draw(mvpMatrix, selected)
            return
        }
        geometry?.draw(mvpMatrix, false)
        drawBaseWireframe(mvpMatrix)
        drawSelectionHighlights(mvpMatrix)
    }

    /**
     * Wireframe negro SIEMPRE visible en Edit Mode (vertices/aristas que NO estan seleccionados -
     * los seleccionados los pinta drawSelectionHighlights encima, en naranja; se excluyen aca para
     * no dibujar negro y naranja en la misma posicion exacta, que con profundidad normal podria
     * ganar cualquiera de los dos segun el orden de dibujo). Mismo offset (EDGE_OFFSET_SCALE) que
     * el resaltado naranja, para que ambos queden a la misma distancia de la superficie.
     */
    private fun drawBaseWireframe(mvpMatrix: FloatArray) {
        val mesh = lastMesh ?: return
        val vertexById = mesh.vertices.associateBy { it.id }

        val basePoints = mesh.vertices.filter { !it.selected }.map { offsetLocal(it) }
        SelectionHighlight.drawPoints(mvpMatrix, basePoints, SelectionHighlight.BLACK, pointSize = 12f)

        val baseEdgeSegments = mesh.edges.filter { !it.selected }.mapNotNull { edge ->
            val v1 = vertexById[edge.v1] ?: return@mapNotNull null
            val v2 = vertexById[edge.v2] ?: return@mapNotNull null
            val p1 = offsetLocal(v1)
            val p2 = offsetLocal(v2)
            floatArrayOf(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2])
        }
        SelectionHighlight.drawEdgeSegments(mvpMatrix, baseEdgeSegments, SelectionHighlight.BLACK, lineWidth = 3f)
    }

    /**
     * Resaltado naranja de vertices/aristas/caras seleccionados dentro de Edit Mode (Fase 1 - ver
     * SelectionHighlight.kt). Recorre lastMesh en vez de guardar listas aparte - son pocos
     * elementos (decenas, no miles, mientras la app trabaje con primitivas chicas), y mantener una
     * sola fuente de verdad (el .selected de cada MeshVertex/MeshEdge/MeshFace) evita sincronizar
     * un cache aparte cada vez que cambia la seleccion.
     */
    private fun drawSelectionHighlights(mvpMatrix: FloatArray) {
        val mesh = lastMesh ?: return
        val vertexById = mesh.vertices.associateBy { it.id }

        val selectedPoints = mesh.vertices.filter { it.selected }.map { offsetLocal(it) }
        SelectionHighlight.drawPoints(mvpMatrix, selectedPoints, SelectionHighlight.ORANGE, pointSize = 18f)

        val selectedEdgeSegments = mesh.edges.filter { it.selected }.mapNotNull { edge ->
            val v1 = vertexById[edge.v1] ?: return@mapNotNull null
            val v2 = vertexById[edge.v2] ?: return@mapNotNull null
            val p1 = offsetLocal(v1)
            val p2 = offsetLocal(v2)
            floatArrayOf(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2])
        }
        SelectionHighlight.drawEdgeSegments(mvpMatrix, selectedEdgeSegments, SelectionHighlight.ORANGE, lineWidth = 5f)

        val selectedFaceTriangles = mutableListOf<FloatArray>()
        for (face in mesh.faces) {
            if (!face.selected) continue
            val corners = face.vertexIds.mapNotNull { vertexById[it] }
            if (corners.size < 3) continue
            for (i in 1 until corners.size - 1) {
                val a = corners[0]; val b = corners[i]; val c = corners[i + 1]
                selectedFaceTriangles.add(floatArrayOf(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z))
            }
        }
        SelectionHighlight.drawFaceFill(mvpMatrix, selectedFaceTriangles)
    }
}
