package com.meshcraft.app

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Dibuja el resaltado visual de vertices/aristas/caras dentro de Edit Mode: vertices y aristas se
 * pueden pintar en cualquier color (negro para la malla base sin seleccionar, naranja para lo
 * seleccionado - ver DynamicMeshGeometry.drawBaseWireframe/drawSelectionHighlights), mismo patron
 * visual que Blender (ver charla con el usuario y su captura de referencia: puntos/aristas negros
 * por defecto, naranja SOLO en lo tocado).
 *
 * A proposito NO se deshabilita GL_DEPTH_TEST aca (a diferencia de una version anterior de este
 * archivo) - se dibuja con el mismo pipeline con profundidad normal que ya usa MeshGeometry para
 * su contorno de seleccion (ver esa clase), para que puntos/aristas queden ocultos detras del
 * lado lejano del objeto en vez de verse a traves de el (comportamiento de Blender en shading
 * solido, sin X-Ray). El z-fighting contra las caras (misma posicion exacta) se evita empujando
 * las posiciones levemente hacia afuera antes de pasarlas aca - ver DynamicMeshGeometry.EDGE_OFFSET_SCALE,
 * mismo truco y mismo valor que ya usaba Cube.kt (edgeScale = 1.003f) para su propio contorno.
 *
 * Object (singleton) compartido por TODOS los DynamicMeshGeometry de la escena, no uno por
 * objeto - es solo un par de programas de shader chicos (mismo criterio que Gizmo3D, instancia
 * unica en MyGLRenderer). Los shaders se compilan la primera vez que se llama a cualquiera de las
 * 3 funciones (init perezoso de un `object` de Kotlin) - eso SOLO es seguro si esa primera
 * llamada pasa en el hilo de render de OpenGL, que es el caso: las 3 funciones se llaman
 * unicamente desde DynamicMeshGeometry.draw(), que a su vez solo se llama desde
 * MyGLRenderer.onDrawFrame().
 */
object SelectionHighlight {
    private val vertexShaderPoints = """
        uniform mat4 uMVPMatrix;
        uniform float uPointSize;
        attribute vec4 vPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            gl_PointSize = uPointSize;
        }
    """.trimIndent()

    private val vertexShaderPlain = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
        }
    """.trimIndent()

    private val fragmentShaderSolidColor = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """.trimIndent()

    private val pointProgram by lazy { GLUtils.buildProgram(vertexShaderPoints, fragmentShaderSolidColor) }
    private val plainProgram by lazy { GLUtils.buildProgram(vertexShaderPlain, fragmentShaderSolidColor) }

    val ORANGE = floatArrayOf(0.95f, 0.5f, 0.1f, 1f)
    val BLACK = floatArrayOf(0.05f, 0.05f, 0.05f, 1f)
    private val orangeFill = floatArrayOf(0.95f, 0.5f, 0.1f, 0.35f)

    private fun makeFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply { put(data); position(0) }
        }
    }

    /** Puntos (vertices) como GL_POINTS - color y tamano configurables (ver BLACK/ORANGE) para diferenciar la malla base de lo seleccionado. Tamano fijo en pixeles (no escala con el zoom - igual de facil de ver/tocar siempre). */
    fun drawPoints(mvpMatrix: FloatArray, points: List<FloatArray>, color: FloatArray = ORANGE, pointSize: Float = 18f) {
        if (points.isEmpty()) return
        val data = FloatArray(points.size * 3)
        points.forEachIndexed { i, p -> data[i * 3] = p[0]; data[i * 3 + 1] = p[1]; data[i * 3 + 2] = p[2] }
        val buffer = makeFloatBuffer(data)

        GLES20.glUseProgram(pointProgram)
        val posHandle = GLES20.glGetAttribLocation(pointProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(pointProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(pointProgram, "uColor")
        val sizeHandle = GLES20.glGetUniformLocation(pointProgram, "uPointSize")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glUniform1f(sizeHandle, pointSize)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, points.size)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    /** Aristas como GL_LINES - color y grosor configurables (ver BLACK/ORANGE). Cada segmento es un FloatArray de 6 floats (x1,y1,z1, x2,y2,z2). */
    fun drawEdgeSegments(mvpMatrix: FloatArray, segments: List<FloatArray>, color: FloatArray = ORANGE, lineWidth: Float = 6f) {
        if (segments.isEmpty()) return
        val data = FloatArray(segments.size * 6)
        segments.forEachIndexed { i, seg -> for (k in 0 until 6) data[i * 6 + k] = seg[k] }
        val buffer = makeFloatBuffer(data)

        GLES20.glUseProgram(plainProgram)
        val posHandle = GLES20.glGetAttribLocation(plainProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(plainProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(plainProgram, "uColor")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glLineWidth(lineWidth)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, segments.size * 2)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    /** Relleno semitransparente de caras seleccionadas - siempre naranja, sin equivalente "base" (Blender tampoco tinta las caras no seleccionadas, solo se ven con su sombreado normal). Cada triangulo es un FloatArray de 9 floats (3 vertices x,y,z). Este si deshabilita depth test: es un overlay de alpha sobre la MISMA superficie de la cara (sin offset hacia afuera, a diferencia de vertices/aristas), asi que necesita ganarle al z-fighting contra el propio triangulo de la cara. */
    fun drawFaceFill(mvpMatrix: FloatArray, triangles: List<FloatArray>) {
        if (triangles.isEmpty()) return
        val data = FloatArray(triangles.size * 9)
        triangles.forEachIndexed { i, tri -> for (k in 0 until 9) data[i * 9 + k] = tri[k] }
        val buffer = makeFloatBuffer(data)

        GLES20.glUseProgram(plainProgram)
        val posHandle = GLES20.glGetAttribLocation(plainProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(plainProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(plainProgram, "uColor")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, orangeFill, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triangles.size * 3)
        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}
