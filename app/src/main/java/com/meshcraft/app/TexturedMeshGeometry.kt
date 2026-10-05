package com.meshcraft.app

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Malla con textura para modelos importados desde OBJ (ver ObjLoader). A diferencia de MeshGeometry usa un VBO entrelazado
 * (posicion, normal, uv) y glDrawArrays, asi que no tiene el limite de 65535 vertices de los indices Short.
 *
 * Iluminacion (estilo visor de ArmorPaint, version simple): la luz esta fija respecto a la CAMARA, no al objeto - las
 * normales se pasan a espacio de vista con uNormalMatrix (ver draw), asi al rotar el modelo los brillos no se mueven
 * con el. Luz ambiente de cielo/suelo + luz principal + luz de relleno + brillo suave. No es PBR (eso va con los materiales).
 *
 * Textura: gris claro liso de 1024x1024 (base para pintar). Con USE_UV_CHECKER = true vuelve el tablero de prueba (el
 * rojo crece con U, el verde con V) para revisar que las UVs de un modelo llegaron bien.
 * IMPORTANTE: crear siempre desde el hilo de render (el constructor llama a OpenGL).
 */
class TexturedMeshGeometry(private val mesh: ObjMesh) {

    private val USE_UV_CHECKER = false

    // Lado de la textura (cuadrada) y copia en CPU de sus pixeles RGBA (ver paintDab): se pinta en esta copia y solo
    // la zona tocada se sube a la GPU con glTexSubImage2D. paintScratch: buffer temporal para esa subida parcial.
    private val texSize = 1024
    private var paintPixels: ByteBuffer? = null
    private var paintScratch: ByteBuffer? = null

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uNormalMatrix;
        attribute vec4 vPosition;
        attribute vec3 vNormal;
        attribute vec2 vTexCoord;
        varying vec3 fNormal;
        varying vec2 fTexCoord;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            fNormal = (uNormalMatrix * vec4(vNormal, 0.0)).xyz;
            fTexCoord = vTexCoord;
        }
    """.trimIndent()

    // fNormal llega en espacio de vista: +X derecha, +Y arriba, +Z hacia quien mira la pantalla.
    private val fragmentShaderCode = """
        precision mediump float;
        uniform sampler2D uTexture;
        varying vec3 fNormal;
        varying vec2 fTexCoord;
        void main() {
            vec3 n = normalize(fNormal);
            // Ambiente: mas claro hacia arriba, mas oscuro hacia abajo.
            float ambient = mix(0.42, 0.78, n.y * 0.5 + 0.5);
            // Luz principal arriba-izquierda-frente, y relleno suave desde la derecha-abajo.
            vec3 keyDir = normalize(vec3(-0.45, 0.65, 0.75));
            vec3 fillDir = normalize(vec3(0.70, -0.25, 0.50));
            float key = max(dot(n, keyDir), 0.0) * 0.50;
            float fill = max(dot(n, fillDir), 0.0) * 0.14;
            // Brillo suave (Blinn-Phong) con el ojo en +Z.
            vec3 halfDir = normalize(keyDir + vec3(0.0, 0.0, 1.0));
            float spec = pow(max(dot(n, halfDir), 0.0), 28.0) * 0.12;
            vec3 base = texture2D(uTexture, fTexCoord).rgb;
            vec3 color = base * (ambient + key + fill) + vec3(spec);
            gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    private val program: Int = GLUtils.buildProgram(vertexShaderCode, fragmentShaderCode)
    private val posHandle = GLES20.glGetAttribLocation(program, "vPosition")
    private val normalHandle = GLES20.glGetAttribLocation(program, "vNormal")
    private val texHandle = GLES20.glGetAttribLocation(program, "vTexCoord")
    private val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
    private val normalMatrixHandle = GLES20.glGetUniformLocation(program, "uNormalMatrix")
    private val samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")

    private val vertexCount: Int = mesh.vertexCount
    private val vbo: Int
    private val textureId: Int

    // Contorno de seleccion: caja naranja alrededor de la malla (sus 12 aristas). Una malla importada puede tener
    // decenas de miles de triangulos, asi que dibujar todas sus aristas (como hacen las primitivas) la taparia entera.
    private val lineVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
        }
    """.trimIndent()

    private val lineFragmentShaderCode = """
        precision mediump float;
        void main() {
            gl_FragColor = vec4(0.95, 0.5, 0.1, 1.0);
        }
    """.trimIndent()

    private val lineProgram: Int = GLUtils.buildProgram(lineVertexShaderCode, lineFragmentShaderCode)
    private val linePosHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
    private val lineMvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
    private val boundsVertexBuffer: FloatBuffer = GLUtils.makeFloatBuffer(computeBoundsCorners(mesh))
    // Esquina i: bit 0 = x max, bit 1 = y max, bit 2 = z max. Aristas: 4 en X, 4 en Y, 4 en Z.
    private val boundsIndexBuffer: ShortBuffer = GLUtils.makeShortBuffer(
        shortArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7)
    )

    /** 8 esquinas (x,y,z) de la caja que envuelve la malla, en el orden descrito en boundsIndexBuffer. */
    private fun computeBoundsCorners(mesh: ObjMesh): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < mesh.positions.size) {
            val x = mesh.positions[i]; val y = mesh.positions[i + 1]; val z = mesh.positions[i + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            i += 3
        }
        val corners = FloatArray(24)
        for (c in 0 until 8) {
            corners[c * 3] = if (c and 1 != 0) maxX else minX
            corners[c * 3 + 1] = if (c and 2 != 0) maxY else minY
            corners[c * 3 + 2] = if (c and 4 != 0) maxZ else minZ
        }
        return corners
    }

    private fun drawBounds(mvpMatrix: FloatArray) {
        GLES20.glUseProgram(lineProgram)
        GLES20.glEnableVertexAttribArray(linePosHandle)
        GLES20.glVertexAttribPointer(linePosHandle, 3, GLES20.GL_FLOAT, false, 0, boundsVertexBuffer)
        GLES20.glUniformMatrix4fv(lineMvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glLineWidth(4f)
        GLES20.glDrawElements(GLES20.GL_LINES, 24, GLES20.GL_UNSIGNED_SHORT, boundsIndexBuffer)
        GLES20.glDisableVertexAttribArray(linePosHandle)
    }

    init {
        // VBO entrelazado: x y z nx ny nz u v por vertice (8 floats = 32 bytes).
        val data = FloatArray(vertexCount * 8)
        for (i in 0 until vertexCount) {
            val o = i * 8
            data[o] = mesh.positions[i * 3]
            data[o + 1] = mesh.positions[i * 3 + 1]
            data[o + 2] = mesh.positions[i * 3 + 2]
            data[o + 3] = mesh.normals[i * 3]
            data[o + 4] = mesh.normals[i * 3 + 1]
            data[o + 5] = mesh.normals[i * 3 + 2]
            data[o + 6] = mesh.uvs[i * 2]
            data[o + 7] = mesh.uvs[i * 2 + 1]
        }
        val buffer = GLUtils.makeFloatBuffer(data)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        textureId = createBaseTexture()
    }

    /**
     * Textura base de 1024x1024: gris claro liso (lista para pintar encima), o el tablero de prueba de UVs si
     * USE_UV_CHECKER esta en true (16x16 celdas; el rojo crece con U y el verde con V, asi se ve la orientacion de las UVs).
     */
    private fun createBaseTexture(): Int {
        val size = texSize
        val cells = 16
        val pixels = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (USE_UV_CHECKER) {
                    val light = ((x * cells / size) + (y * cells / size)) % 2 == 0
                    val k = if (light) 1.0f else 0.55f
                    val r = (60 + 195 * x / (size - 1)) * k
                    val g = (60 + 195 * y / (size - 1)) * k
                    val b = 70 * k
                    pixels.put(r.toInt().toByte())
                    pixels.put(g.toInt().toByte())
                    pixels.put(b.toInt().toByte())
                } else {
                    pixels.put(204.toByte())
                    pixels.put(204.toByte())
                    pixels.put(204.toByte())
                }
                pixels.put(255.toByte())
            }
        }
        pixels.position(0)
        paintPixels = pixels

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        // Sin mipmaps a proposito: al pintar solo se actualiza el nivel 0 de la textura (glTexSubImage2D), y los niveles
        // menores quedarian desactualizados al alejar la camara.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        // (sin glGenerateMipmap, ver el filtro de reduccion mas arriba)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    /**
     * Dibuja la malla con su textura y, si selected es true, su caja naranja de seleccion (ver drawBounds).
     * normalMatrix: matriz 4x4 (vista * rotacion de camara * rotacion del objeto) para llevar las normales a espacio de
     * vista - solo se usa su parte de rotacion (la escala del objeto no se aplica a las normales).
     */
    fun draw(mvpMatrix: FloatArray, selected: Boolean, normalMatrix: FloatArray) {
        drawFaces(mvpMatrix, normalMatrix)
        if (selected) drawBounds(mvpMatrix)
    }

    /**
     * Rayo (en espacio LOCAL del modelo) contra todos los triangulos de la malla (Moller-Trumbore, sin descartar caras
     * traseras). Devuelve [t, u, v] del impacto mas cercano - t a lo largo del rayo, (u, v) = coordenada de textura
     * interpolada con las UVs de los 3 vertices del triangulo - o null si el rayo no toca la malla. Solo usa la malla en
     * CPU (no llama a OpenGL), asi que se puede probar en cualquier hilo.
     */
    fun pickUv(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): FloatArray? {
        val p = mesh.positions
        val uv = mesh.uvs
        var bestT = Float.MAX_VALUE
        var bestU = 0f
        var bestV = 0f
        var i = 0
        while (i + 2 < vertexCount) {
            val a = i * 3
            val b = (i + 1) * 3
            val c = (i + 2) * 3
            val e1x = p[b] - p[a]; val e1y = p[b + 1] - p[a + 1]; val e1z = p[b + 2] - p[a + 2]
            val e2x = p[c] - p[a]; val e2y = p[c + 1] - p[a + 1]; val e2z = p[c + 2] - p[a + 2]
            // h = d x e2
            val hx = dy * e2z - dz * e2y
            val hy = dz * e2x - dx * e2z
            val hz = dx * e2y - dy * e2x
            val det = e1x * hx + e1y * hy + e1z * hz
            if (det > -1e-9f && det < 1e-9f) { i += 3; continue }
            val f = 1f / det
            val sx = ox - p[a]; val sy = oy - p[a + 1]; val sz = oz - p[a + 2]
            val bu = f * (sx * hx + sy * hy + sz * hz)
            if (bu < 0f || bu > 1f) { i += 3; continue }
            // q = s x e1
            val qx = sy * e1z - sz * e1y
            val qy = sz * e1x - sx * e1z
            val qz = sx * e1y - sy * e1x
            val bv = f * (dx * qx + dy * qy + dz * qz)
            if (bv < 0f || bu + bv > 1f) { i += 3; continue }
            val t = f * (e2x * qx + e2y * qy + e2z * qz)
            if (t > 1e-5f && t < bestT) {
                bestT = t
                val w0 = 1f - bu - bv
                bestU = w0 * uv[i * 2] + bu * uv[(i + 1) * 2] + bv * uv[(i + 2) * 2]
                bestV = w0 * uv[i * 2 + 1] + bu * uv[(i + 1) * 2 + 1] + bv * uv[(i + 2) * 2 + 1]
            }
            i += 3
        }
        return if (bestT == Float.MAX_VALUE) null else floatArrayOf(bestT, bestU, bestV)
    }

    /**
     * Pinta un toque de pincel redondo de color fijo en la textura, centrado en (u, v) (0..1). radius va en texeles
     * (textura de texSize x texSize). El borde es suave: opaco en la mitad interior del circulo y se desvanece hacia
     * afuera. Se pinta en la copia en CPU (paintPixels) y solo el rectangulo tocado se sube a la GPU con
     * glTexSubImage2D. IMPORTANTE: llamar desde el hilo de render (usa OpenGL).
     */
    fun paintDab(u: Float, v: Float, radius: Float, r: Int, g: Int, b: Int) {
        val pix = paintPixels ?: return
        val cx = u * (texSize - 1)
        val cy = v * (texSize - 1)
        val x0 = maxOf(0, Math.floor((cx - radius).toDouble()).toInt())
        val x1 = minOf(texSize - 1, Math.ceil((cx + radius).toDouble()).toInt())
        val y0 = maxOf(0, Math.floor((cy - radius).toDouble()).toInt())
        val y1 = minOf(texSize - 1, Math.ceil((cy + radius).toDouble()).toInt())
        if (x1 < x0 || y1 < y0) return

        for (y in y0..y1) {
            for (x in x0..x1) {
                val ddx = x - cx
                val ddy = y - cy
                val d = Math.sqrt((ddx * ddx + ddy * ddy).toDouble()).toFloat()
                if (d >= radius) continue
                val alpha = minOf(1f, (1f - d / radius) * 2f)
                val o = (y * texSize + x) * 4
                val oldR = pix.get(o).toInt() and 0xFF
                val oldG = pix.get(o + 1).toInt() and 0xFF
                val oldB = pix.get(o + 2).toInt() and 0xFF
                pix.put(o, (oldR + (r - oldR) * alpha).toInt().toByte())
                pix.put(o + 1, (oldG + (g - oldG) * alpha).toInt().toByte())
                pix.put(o + 2, (oldB + (b - oldB) * alpha).toInt().toByte())
            }
        }

        // Subida parcial: copia las filas del rectangulo tocado a un buffer compacto y lo sube con glTexSubImage2D.
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        var scratch = paintScratch
        if (scratch == null || scratch.capacity() < w * h * 4) {
            scratch = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            paintScratch = scratch
        }
        val buf: ByteBuffer = scratch!!
        buf.clear()
        for (row in 0 until h) {
            val src = pix.duplicate()
            val start = ((y0 + row) * texSize + x0) * 4
            src.position(start)
            src.limit(start + w * 4)
            buf.put(src)
        }
        buf.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, x0, y0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * Libera los recursos de GPU de esta geometria (VBO, textura y los dos programas de shaders) y la copia de la
     * textura en CPU. IMPORTANTE: llamar desde el hilo de render, y no volver a usar esta instancia despues.
     */
    fun release() {
        GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteProgram(lineProgram)
        paintPixels = null
        paintScratch = null
    }

    private fun drawFaces(mvpMatrix: FloatArray, normalMatrix: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val stride = 32
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, stride, 12)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, stride, 24)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(normalMatrixHandle, 1, false, normalMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        // Importante: soltar el VBO, las demas geometrias usan buffers de cliente y se romperian con un VBO enlazado.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
