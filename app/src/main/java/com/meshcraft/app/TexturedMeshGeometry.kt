package com.meshcraft.app

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.CopyOnWriteArrayList

/** Lado (en texeles) de las baldosas en que se divide la textura para guardar lo que cambia cada trazo (ver StrokeEdit). */
private const val UNDO_TILE = 64

/** Maximo de capas por modelo: cada una pesa 4 MB (textura de 1024x1024 en RGBA). */
const val MAX_LAYERS = 10

/** Maximo de carpetas por modelo (no pesan: no tienen pixeles). No cuentan para MAX_LAYERS. */
const val MAX_FOLDERS = 5

/** Lado (en texeles) de la textura cuadrada de cada modelo y, por lo tanto, de cada capa. */
const val PAINT_TEX_SIZE = 1024

/** Orden de las capas (ids, de abajo hacia arriba) y carpeta de cada una (-1 = suelta): lo que cambia al mover capas o carpetas. Sirve para deshacer/rehacer ese movimiento (ver StrokeEdit.layout). */
class LayoutSnapshot(val ids: IntArray, val parents: IntArray)

/**
 * Una capa de pintura de un modelo: pixeles RGBA (alfa recto, sin premultiplicar) del mismo tamano que la textura, mas
 * sus ajustes. Los ajustes son @Volatile porque la interfaz los cambia y el hilo de render los lee al componer.
 * Despues de cambiar visible, opacity, blendMode o clipToBelow hay que llamar a TexturedMeshGeometry.requestRecomposite.
 */
class PaintLayer(val id: Int, name: String, val pixels: ByteArray) {
    @Volatile var name: String = name
    @Volatile var visible: Boolean = true
    /** 0..100. */
    @Volatile var opacity: Int = 100
    /** Bloqueada: no se puede pintar ni modificar. */
    @Volatile var locked: Boolean = false
    /** Bloqueo alfa: solo se pinta donde la capa ya tiene algo (no cambia la transparencia). */
    @Volatile var alphaLock: Boolean = false
    /** Como se mezcla esta capa con las de abajo (ver BlendMode). */
    @Volatile var blendMode: BlendMode = BlendMode.NORMAL
    /** Recorte: la capa solo se ve donde la capa de abajo (su base) tiene algo pintado (ver LayerCompositor). */
    @Volatile var clipToBelow: Boolean = false
    /** true = es una carpeta: agrupa capas y no tiene pixeles propios (pixels esta vacio). Tiene ojo, opacidad, modo de mezcla y recorte como una capa. */
    @Volatile var isFolder: Boolean = false
    /** Id de la carpeta que contiene esta capa, o -1 si esta suelta. Las carpetas no se anidan (siempre -1). */
    @Volatile var folderId: Int = -1
    /** Carpeta plegada: sus capas no salen en la lista del panel (se siguen componiendo igual). */
    @Volatile var collapsed: Boolean = false

    /**
     * Miniatura cuadrada de size x size pixeles (promedia 9 muestras por casilla, ponderadas por el alfa). Solo lee la
     * capa, asi que se puede llamar desde cualquier hilo (puede ver un trazo a medias, solo es una vista previa).
     */
    fun thumbnail(size: Int, texSize: Int): Bitmap {
        val out = IntArray(size * size)
        val block = texSize / size
        for (ty in 0 until size) {
            for (tx in 0 until size) {
                var sumA = 0
                var sumR = 0
                var sumG = 0
                var sumB = 0
                for (sy in 0 until 3) {
                    for (sx in 0 until 3) {
                        val x = tx * block + (sx * 2 + 1) * block / 6
                        val y = ty * block + (sy * 2 + 1) * block / 6
                        val o = (y * texSize + x) * 4
                        val a = pixels[o + 3].toInt() and 0xFF
                        sumA += a
                        sumR += (pixels[o].toInt() and 0xFF) * a
                        sumG += (pixels[o + 1].toInt() and 0xFF) * a
                        sumB += (pixels[o + 2].toInt() and 0xFF) * a
                    }
                }
                out[ty * size + tx] = if (sumA == 0) 0 else Color.argb(sumA / 9, sumR / sumA, sumG / sumA, sumB / sumA)
            }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, size, 0, 0, size, size)
        return bmp
    }
}

/**
 * Un trazo (o una operacion sobre una capa) para deshacer/rehacer: las baldosas de la textura de UNA capa (layerId) que
 * toco, cada una con los pixeles (RGBA) que hay que poner al aplicarlo (ver TexturedMeshGeometry.applyEdit). Al aplicarlo,
 * los arreglos quedan con lo que habia antes, asi que el mismo StrokeEdit sirve para deshacer y para rehacer. Clave del
 * mapa = fila de baldosa * baldosas por fila + columna.
 */
class StrokeEdit(val geo: TexturedMeshGeometry, val layerId: Int, val tiles: HashMap<Int, ByteArray>, var layout: LayoutSnapshot? = null) {
    fun sizeBytes(): Long = tiles.size.toLong() * UNDO_TILE * UNDO_TILE * 4
}

/**
 * Malla con textura para modelos importados desde OBJ (ver ObjLoader). A diferencia de MeshGeometry usa un VBO entrelazado
 * (posicion, normal, uv) y glDrawArrays, asi que no tiene el limite de 65535 vertices de los indices Short.
 *
 * Iluminacion (estilo visor de ArmorPaint, version simple): la luz esta fija respecto a la CAMARA, no al objeto - las
 * normales se pasan a espacio de vista con uNormalMatrix (ver draw), asi al rotar el modelo los brillos no se mueven
 * con el. Luz ambiente de cielo/suelo + luz principal + luz de relleno + brillo suave. No es PBR (eso va con los materiales).
 *
 * Capas: el modelo tiene una lista de capas (ver PaintLayer), de abajo hacia arriba. Se pinta SIEMPRE en la capa elegida
 * (selectedLayerId). La textura que se ve en el modelo es la composicion de todas las capas visibles sobre un fondo gris
 * liso (204) - ver LayerCompositor - que se guarda en una copia en CPU (composite) y se sube a la GPU por rectangulos.
 * Cada capa se mezcla con las de abajo segun su modo y puede estar recortada a la de abajo.
 * Con USE_UV_CHECKER = true el fondo es el tablero de prueba (el rojo crece con U, el verde con V) para revisar que las
 * UVs de un modelo llegaron bien.
 * IMPORTANTE: crear siempre desde el hilo de render (el constructor llama a OpenGL). Las operaciones que cambian pixeles
 * o la lista de capas (add/clone/delete/merge/move/clear/invert, pintar, deshacer) tambien van en el hilo de render (ver
 * MyGLRenderer.editLayers); los ajustes de una capa (nombre, visible, opacidad, modo, recorte, candado) los puede cambiar la interfaz.
 */
class TexturedMeshGeometry(private val mesh: ObjMesh) {

    private val USE_UV_CHECKER = false

    private companion object {
        /** Hilos para componer la textura entera (ver recompositeRect): hasta 4, sin pasar de los nucleos del telefono. */
        val COMPOSITE_THREADS: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        /** Por debajo de esta cantidad de texeles (un trazo, una baldosa de deshacer) no vale la pena repartir el trabajo entre hilos. */
        const val PARALLEL_MIN_TEXELS = 128 * 128

        /** Los hilos se crean al usarlos por primera vez y no impiden que la app se cierre (daemon). */
        val COMPOSITE_POOL: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(COMPOSITE_THREADS) { r ->
            Thread(r, "composite").apply { isDaemon = true }
        }
    }

    // Lado de la textura (cuadrada).
    val texSize = PAINT_TEX_SIZE

    /** Capas del modelo, de abajo (indice 0) hacia arriba. Lista segura para leerla desde la interfaz mientras el render la cambia. */
    val layers = CopyOnWriteArrayList<PaintLayer>()
    /** Id de la capa donde se pinta. */
    @Volatile var selectedLayerId = -1
    /** Modo Solo: si es >= 0 solo se ve esa capa (las demas se ocultan sin cambiar su visibilidad). -1 = apagado. */
    @Volatile var soloLayerId = -1
    /** Fondo bajo las capas (ver BackgroundKind). Despues de cambiarlo hay que llamar a requestRecomposite. */
    @Volatile var background = BackgroundKind.WHITE
    private var nextLayerId = 0
    private var nextLayerNumber = 1
    /** true cuando hay que volver a componer toda la textura en el proximo cuadro (ver draw). */
    @Volatile private var compositeDirty = false
    @Volatile private var released = false

    // Compositor del hilo de render (recompositeRect). Los demas hilos (exportar) crean el suyo.
    private val renderCompositor = LayerCompositor()
    private val baseColorFn = BaseColor { x, y -> baseColor(x, y) }

    // Copia en CPU de la textura compuesta (RGBA): lo que se ve en el modelo. Solo la zona tocada se sube a la GPU con
    // glTexSubImage2D. paintScratch: buffer temporal para esa subida parcial.
    private var composite: ByteArray? = null
    private var paintScratch: ByteBuffer? = null

    // Trazo en curso (ver beginStroke / paintDab): strokeAlpha = la mayor opacidad aplicada a cada texel en la pasada actual
    // (0 = sin tocar), strokeBase = color RGBA que tenia cada texel de la capa ANTES del trazo. Cada toque se mezcla con ese
    // color original usando el maximo de los alphas, asi los toques seguidos de una misma pasada no acumulan opacidad (una pasada nunca pasa de la opacidad elegida), pero volver a pasar por el mismo lugar suma.
    private val strokeAlpha = FloatArray(texSize * texSize)
    private val strokeBase = ByteArray(texSize * texSize * 4)
    // Rectangulo (en texeles) que toco el trazo, para limpiar strokeAlpha sin recorrer toda la textura.
    private var strokeMinX = texSize
    private var strokeMinY = texSize
    private var strokeMaxX = -1
    private var strokeMaxY = -1
    // Numero de toque dentro del trazo y, por texel, el ultimo toque que lo toco: si pasan mas de STROKE_NEW_PASS_GAP toques
    // sin tocarlo, el trazo se fue y volvio (cruce sobre si mismo) y esa vuelta cuenta como una pasada nueva que se suma.
    private val strokeLastDab = IntArray(texSize * texSize)
    private var strokeDab = 0
    private val STROKE_NEW_PASS_GAP = 3

    // Undo/redo: la textura de cada capa se divide en baldosas de TILE x TILE texeles. La primera vez que un trazo (o una
    // operacion como limpiar) toca una baldosa se guarda una copia de sus pixeles ANTES de cambiarlos (pendingTiles), de la
    // capa pendingLayerId. Cuando termina, el renderer lo recoge con takeStrokeEdit y lo mete en su historial (ver MyGLRenderer.undo/redo).
    private val TILE = UNDO_TILE
    private val TILES_PER_ROW = texSize / TILE
    private var pendingTiles = HashMap<Int, ByteArray>()
    private var pendingLayerId = -1
    // Orden y carpetas de las capas ANTES de mover alguna (ver arrangeLayer): el renderer lo recoge con takeStrokeEdit para el Deshacer.
    private var pendingLayout: LayoutSnapshot? = null
    /** Aviso (desde el hilo de render) de que Deshacer/Rehacer cambio el orden o las carpetas de las capas: la interfaz debe volver a armar la lista. Lo pone el renderer (ver createGeometry). */
    @Volatile var onLayersChanged: (() -> Unit)? = null

    private fun layoutSnapshot(): LayoutSnapshot {
        val list = layers.toList()
        return LayoutSnapshot(IntArray(list.size) { list[it].id }, IntArray(list.size) { list[it].folderId })
    }

    /** Pone las capas en el orden y las carpetas guardadas. false (sin tocar nada) si las capas ya no son las mismas. No toca pixeles. */
    private fun applyLayout(saved: LayoutSnapshot): Boolean {
        val byId = layers.associateBy { it.id }
        if (saved.ids.size != byId.size || saved.ids.any { !byId.containsKey(it) }) return false
        for (k in saved.ids.indices) byId.getValue(saved.ids[k]).folderId = saved.parents[k]
        val ordered = saved.ids.map { byId.getValue(it) }
        layers.clear()
        layers.addAll(ordered)
        compositeDirty = true
        return true
    }

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

        // Capa inicial: transparente (se ve el fondo gris liso), lista para pintar.
        val first = createLayer((nextLayerNumber++).toString(), ByteArray(texSize * texSize * 4))
        layers.add(first)
        selectedLayerId = first.id
    }

    /** Color (0xRRGGBB) del fondo elegido (ver BackgroundKind) en el texel (x, y). Los tableros llevan casillas de 32 texeles. */
    private fun backgroundColor(x: Int, y: Int): Int {
        val even = ((x / 32) + (y / 32)) % 2 == 0
        return when (background) {
            BackgroundKind.WHITE -> 0xFFFFFF
            BackgroundKind.CHECKER, BackgroundKind.NONE -> if (even) 0xFFFFFF else 0xDEDEDE
            BackgroundKind.CHECKER_DARK -> if (even) 0x5A5A5A else 0x373737
        }
    }

    /** Color (0xRRGGBB) del fondo bajo las capas en el texel (x, y): el fondo elegido, o el tablero de prueba de UVs. */
    private fun baseColor(x: Int, y: Int): Int {
        if (!USE_UV_CHECKER) return backgroundColor(x, y)
        val size = texSize
        val cells = 16
        val light = ((x * cells / size) + (y * cells / size)) % 2 == 0
        val k = if (light) 1.0f else 0.55f
        val r = ((60 + 195 * x / (size - 1)) * k).toInt()
        val g = ((60 + 195 * y / (size - 1)) * k).toInt()
        val b = (70 * k).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * Textura base de 1024x1024: el fondo (ver baseColor) sin capas encima, lista para pintar. Tambien deja lista la copia
     * en CPU (composite). Sin mipmaps a proposito: al pintar solo se actualiza el nivel 0 (glTexSubImage2D).
     */
    private fun createBaseTexture(): Int {
        val size = texSize
        val comp = ByteArray(size * size * 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val c = baseColor(x, y)
                val o = (y * size + x) * 4
                comp[o] = ((c shr 16) and 0xFF).toByte()
                comp[o + 1] = ((c shr 8) and 0xFF).toByte()
                comp[o + 2] = (c and 0xFF).toByte()
                comp[o + 3] = 255.toByte()
            }
        }
        composite = comp
        val pixels = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        pixels.put(comp)
        pixels.position(0)

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
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    // ---- Capas ----

    private fun createLayer(name: String, pixels: ByteArray): PaintLayer = PaintLayer(nextLayerId++, name, pixels)

    /** Capa donde se pinta, o null si no hay (por ejemplo si la geometria ya fue liberada). */
    fun selectedLayer(): PaintLayer? {
        val id = selectedLayerId
        return layers.firstOrNull { it.id == id }
    }

    /** Mete la capa justo encima de la elegida (o arriba de todo) y la deja elegida. */
    private fun insertAboveSelected(layer: PaintLayer) {
        val sel = layers.indexOfFirst { it.id == selectedLayerId }
        val selected = if (sel >= 0) layers[sel] else null
        if (selected != null && selected.isFolder && !layer.isFolder) {
            // Con una carpeta elegida, la capa nueva entra en ella (arriba del todo: justo debajo de la carpeta en la lista).
            layer.folderId = selected.id
            layers.add(sel, layer)
        } else {
            // Si la elegida esta dentro de una carpeta, la nueva entra en la misma.
            if (selected != null && !layer.isFolder) layer.folderId = selected.folderId
            layers.add(if (sel < 0) layers.size else sel + 1, layer)
        }
        selectedLayerId = layer.id
    }

    /** Pide volver a componer la textura entera en el proximo cuadro (despues de cambiar visible/opacidad/modo/recorte de una capa, Modo Solo...). */
    fun requestRecomposite() {
        compositeDirty = true
    }

    /** Capa nueva y transparente encima de la elegida. null si ya se llego al maximo de capas (MAX_LAYERS). */
    fun addLayer(): PaintLayer? {
        if (released || layers.count { !it.isFolder } >= MAX_LAYERS) return null
        val layer = createLayer((nextLayerNumber++).toString(), ByteArray(texSize * texSize * 4))
        insertAboveSelected(layer)
        return layer
    }

    /** Copia de la capa elegida encima de ella (mismo contenido, opacidad, modo de mezcla, recorte y visibilidad; desbloqueada). null si no se pudo. */
    fun cloneSelectedLayer(): PaintLayer? {
        val source = selectedLayer() ?: return null
        if (source.isFolder || layers.count { !it.isFolder } >= MAX_LAYERS) return null
        val copy = createLayer(source.name + " copia", source.pixels.copyOf())
        copy.visible = source.visible
        copy.opacity = source.opacity
        copy.blendMode = source.blendMode
        copy.clipToBelow = source.clipToBelow
        insertAboveSelected(copy)
        compositeDirty = true
        return copy
    }

    private var nextFolderNumber = 1

    /**
     * Carpeta nueva y vacia, siempre suelta: encima de la elegida (o de la carpeta que contiene a la elegida). Queda elegida.
     * null si ya se llego al maximo de carpetas (MAX_FOLDERS). Para meter capas en ella se arrastran con el asa, o se elige la
     * carpeta y se anade una capa (entra dentro).
     */
    fun addFolder(): PaintLayer? {
        if (released || layers.count { it.isFolder } >= MAX_FOLDERS) return null
        val folder = createLayer("Carpeta " + (nextFolderNumber++), ByteArray(0))
        folder.isFolder = true
        val sel = layers.firstOrNull { it.id == selectedLayerId }
        val anchorId = if (sel != null && sel.folderId >= 0) sel.folderId else sel?.id
        val at = layers.indexOfFirst { it.id == anchorId }
        layers.add(if (at < 0) layers.size else at + 1, folder)
        selectedLayerId = folder.id
        compositeDirty = true
        return folder
    }

    /**
     * Mueve la capa a la carpeta parentId (-1 = suelta) y a la posicion siblingIndex entre las de su grupo (0 = la de mas abajo;
     * se cuenta sin la capa movida). Una carpeta siempre queda suelta y se lleva sus capas. Al final la lista queda ordenada:
     * cada carpeta con sus capas justo debajo. No toca pixeles, asi que los historiales de Deshacer siguen valiendo (van por id
     * de capa). false si no cambia nada (o si el destino no es valido).
     */
    fun arrangeLayer(layerId: Int, parentId: Int, siblingIndex: Int): Boolean {
        val layer = layers.firstOrNull { it.id == layerId } ?: return false
        val newParentId = if (!layer.isFolder && layers.any { it.id == parentId && it.isFolder }) parentId else -1
        val oldParentId = layer.folderId
        val before = layoutSnapshot()
        val idsBefore = layers.map { it.id }
        layer.folderId = newParentId
        // Grupo de destino sin la capa movida, de abajo hacia arriba, y la capa en su nueva posicion.
        val siblings = layers.filter { it.folderId == newParentId && it.id != layerId }.toMutableList()
        siblings.add(siblingIndex.coerceIn(0, siblings.size), layer)
        val roots = if (newParentId < 0) siblings else layers.filter { it.folderId < 0 }
        val result = ArrayList<PaintLayer>()
        for (item in roots) {
            if (item.isFolder) {
                result.addAll(if (item.id == newParentId) siblings else layers.filter { it.folderId == item.id })
            }
            result.add(item)
        }
        if (result.size != layers.size) {
            layer.folderId = oldParentId
            return false
        }
        val changed = result.map { it.id } != idsBefore || oldParentId != newParentId
        if (!changed) return false
        // Un solo registro de Deshacer por movimiento: si hay otro sin entregar (no deberia), se queda el mas viejo.
        if (pendingLayout == null) pendingLayout = before
        layers.clear()
        layers.addAll(result)
        compositeDirty = true
        return true
    }

    /** Borra la capa elegida (y elige la de abajo). false si es la unica: siempre tiene que quedar una. No se puede deshacer. */
    fun deleteSelectedLayer(): Boolean {
        val selLayer = layers.firstOrNull { it.id == selectedLayerId }
        // Siempre tiene que quedar al menos una capa con pixeles (una carpeta sola no sirve para pintar).
        if (selLayer == null || (!selLayer.isFolder && layers.count { !it.isFolder } <= 1)) return false
        val idx = layers.indexOfFirst { it.id == selectedLayerId }
        if (idx < 0) return false
        val removedId = layers[idx].id
        if (layers[idx].isFolder) {
            // Una carpeta se lleva sus capas; tiene que quedar al menos una capa con pixeles fuera de ella.
            if (layers.none { !it.isFolder && it.folderId != removedId }) return false
            layers.removeAll { it.id == removedId || it.folderId == removedId }
            if (layers.none { it.id == soloLayerId }) soloLayerId = -1
            selectedLayerId = layers[minOf(maxOf(idx - 1, 0), layers.size - 1)].id
            compositeDirty = true
            return true
        }
        layers.removeAt(idx)
        if (soloLayerId == removedId) soloLayerId = -1
        selectedLayerId = layers[minOf(maxOf(idx - 1, 0), layers.size - 1)].id
        compositeDirty = true
        return true
    }

    /**
     * Combina la capa elegida con la de abajo (con el modo de mezcla, el recorte y la opacidad de la de arriba; si la de arriba
     * esta oculta su contenido se pierde) y deja elegida la de abajo, que conserva sus propios ajustes. false si no hay capa
     * debajo. No se puede deshacer.
     */
    fun mergeSelectedDown(): Boolean {
        val idx = layers.indexOfFirst { it.id == selectedLayerId }
        if (idx <= 0) return false
        val upper = layers[idx]
        val lower = layers[idx - 1]
        // Solo se combinan dos capas con pixeles del mismo grupo (las dos sueltas o las dos de la misma carpeta).
        if (upper.isFolder || lower.isFolder || upper.folderId != lower.folderId) return false
        val up = upper.pixels
        val lo = lower.pixels
        val op = if (upper.visible) upper.opacity else 0
        val mode = upper.blendMode
        val clipped = upper.clipToBelow
        val mixer = BlendMixer()
        val count = texSize * texSize
        for (i in 0 until count) {
            val o = i * 4
            // Alfa de la capa de arriba (0..255) ya con su opacidad.
            var ua = (up[o + 3].toInt() and 0xFF) * op / 100
            val la = lo[o + 3].toInt() and 0xFF
            // Recortada: solo cuenta donde la capa de abajo tiene algo.
            if (clipped) ua = ua * la / 255
            if (ua == 0) continue
            val packed = mixer.over(
                mode,
                lo[o].toInt() and 0xFF, lo[o + 1].toInt() and 0xFF, lo[o + 2].toInt() and 0xFF, la,
                up[o].toInt() and 0xFF, up[o + 1].toInt() and 0xFF, up[o + 2].toInt() and 0xFF, ua
            )
            lo[o] = (packed shr 16).toByte()
            lo[o + 1] = (packed shr 8).toByte()
            lo[o + 2] = packed.toByte()
            lo[o + 3] = (packed ushr 24).toByte()
        }
        layers.removeAt(idx)
        if (soloLayerId == upper.id) soloLayerId = -1
        selectedLayerId = lower.id
        compositeDirty = true
        return true
    }

    /** Deja la capa elegida totalmente transparente. Se puede deshacer. */
    fun clearSelectedLayer(): Boolean {
        if (selectedLayer()?.isFolder == true) return false
        val layer = selectedLayer() ?: return false
        saveTilesForUndo(layer, 0, 0, texSize - 1, texSize - 1)
        java.util.Arrays.fill(layer.pixels, 0.toByte())
        compositeDirty = true
        return true
    }

    /** Invierte el color (RGB) de todo lo pintado en la capa elegida; la transparencia no cambia. Se puede deshacer. */
    fun invertSelectedLayer(): Boolean {
        if (selectedLayer()?.isFolder == true) return false
        val layer = selectedLayer() ?: return false
        saveTilesForUndo(layer, 0, 0, texSize - 1, texSize - 1)
        val p = layer.pixels
        var o = 0
        while (o < p.size) {
            if (p[o + 3].toInt() != 0) {
                p[o] = (255 - (p[o].toInt() and 0xFF)).toByte()
                p[o + 1] = (255 - (p[o + 1].toInt() and 0xFF)).toByte()
                p[o + 2] = (255 - (p[o + 2].toInt() and 0xFF)).toByte()
            }
            o += 4
        }
        compositeDirty = true
        return true
    }

    // ---- Seleccion ----

    /** Seleccion: por cada texel, cuanto se puede pintar (0 = nada, 255 = todo). null = sin seleccion (se pinta en todas partes). La crea selectByOpacity. */
    @Volatile var selectionMask: ByteArray? = null

    /**
     * Selecciona lo pintado en la capa elegida: cada texel queda seleccionado en la misma medida que su opacidad (alfa). Despues
     * solo se pinta o borra dentro de la seleccion (ver paintDab). false (sin cambiar nada) si la capa es una carpeta o esta vacia.
     * No se puede deshacer. Hilo de render.
     */
    fun selectByOpacity(): Boolean {
        val layer = selectedLayer() ?: return false
        if (layer.isFolder) return false
        val pix = layer.pixels
        val mask = ByteArray(texSize * texSize)
        var any = false
        for (i in mask.indices) {
            val a = pix[i * 4 + 3]
            mask[i] = a
            if (a.toInt() != 0) any = true
        }
        if (!any) return false
        selectionMask = mask
        return true
    }

    /** Quita la seleccion: se vuelve a pintar en todas partes. */
    fun clearSelection() {
        selectionMask = null
    }

    /** Miniatura cuadrada de la seleccion (naranja, mas opaca donde mas seleccionado), o null si no hay seleccion. Se puede llamar desde cualquier hilo. */
    fun selectionThumbnail(size: Int): Bitmap? {
        val mask = selectionMask ?: return null
        val block = texSize / size
        val out = IntArray(size * size)
        for (ty in 0 until size) {
            for (tx in 0 until size) {
                val a = mask[(ty * block + block / 2) * texSize + tx * block + block / 2].toInt() and 0xFF
                out[ty * size + tx] = Color.argb(a, 242, 128, 26)
            }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, size, 0, 0, size, size)
        return bmp
    }

    // ---- Exportar ----

    /**
     * Textura final lista para un PNG: las capas visibles de abajo hacia arriba (cada una con su modo de mezcla, recorte y
     * opacidad), como bytes RGBA de texSize x texSize con alfa recto. withBase = true las pone sobre el fondo elegido (con "sin fondo" sale transparente), como se
     * ve en el modelo (todo opaco); false deja transparente lo que no esta pintado. Ignora el Modo Solo. No toca OpenGL: se
     * puede llamar desde cualquier hilo.
     */
    fun exportCompositeRgba(withBase: Boolean): ByteArray? {
        if (released) return null
        val out = ByteArray(texSize * texSize * 4)
        LayerCompositor().composite(layers, -1, if (withBase && background != BackgroundKind.NONE) baseColorFn else null, out, texSize, 0, 0, texSize - 1, texSize - 1)
        return out
    }

    /** Copia de los pixeles RGBA de la capa con ese id (tal cual, con su transparencia, sin aplicar opacidad ni visibilidad), o null si ya no existe. */
    fun exportLayerRgba(layerId: Int): ByteArray? = layers.firstOrNull { it.id == layerId && !it.isFolder }?.pixels?.copyOf()

    // ---- Guardado ----

    /**
     * Las capas listas para guardar (ver ProjectSerializer): sus ajustes y una REFERENCIA a sus pixeles (no se copian, asi no
     * hace falta memoria extra). No toca OpenGL, se puede llamar desde cualquier hilo; si justo se esta pintando, el trazo
     * puede quedar a medias en lo guardado. null si la geometria ya fue liberada.
     */
    fun snapshotLayers(): SavedLayers? {
        if (released) return null
        val current = layers.toList()
        if (current.isEmpty()) return null
        val saved = current.map {
            SavedLayer(it.name, it.visible, it.opacity, it.locked, it.alphaLock, it.pixels, it.blendMode, it.clipToBelow, it.isFolder, current.indexOfFirst { f -> f.isFolder && f.id == it.folderId }, it.collapsed)
        }
        val selected = current.indexOfFirst { it.id == selectedLayerId }
        return SavedLayers(saved, maxOf(selected, 0), background)
    }

    /**
     * Reemplaza las capas actuales por las guardadas (al abrir el proyecto, o al recrearse el contexto de OpenGL). Usa los
     * pixeles tal cual, sin copiarlos. La textura se vuelve a componer en el proximo cuadro. Hilo de render.
     */
    fun restoreLayers(saved: SavedLayers) {
        if (released || saved.layers.isEmpty()) return
        layers.clear()
        val created = ArrayList<PaintLayer>()
        for (s in saved.layers.take(MAX_LAYERS + MAX_FOLDERS)) {
            val layer = createLayer(s.name, s.pixels)
            layer.visible = s.visible
            layer.opacity = s.opacity
            layer.locked = s.locked
            layer.alphaLock = s.alphaLock
            layer.blendMode = s.blendMode
            layer.clipToBelow = s.clipToBelow
            layer.isFolder = s.isFolder
            layer.collapsed = s.collapsed
            created.add(layer)
            layers.add(layer)
        }
        // Segunda pasada: la carpeta va despues de sus capas en la lista, asi que los folderId se asignan cuando ya existen todas.
        for ((k, s) in saved.layers.take(created.size).withIndex()) {
            val parent = created.getOrNull(s.folderIndex)
            created[k].folderId = if (!s.isFolder && parent != null && parent.isFolder) parent.id else -1
        }
        // Tiene que quedar al menos una capa con pixeles.
        if (created.none { !it.isFolder }) {
            val base = createLayer("1", ByteArray(texSize * texSize * 4))
            layers.add(0, base)
        }
        nextLayerNumber = layers.count { !it.isFolder } + 1
        nextFolderNumber = layers.count { it.isFolder } + 1
        selectedLayerId = layers[saved.selectedIndex.coerceIn(0, layers.size - 1)].id
        background = saved.background
        soloLayerId = -1
        compositeDirty = true
    }

    // ---- Composicion ----

    /**
     * Vuelve a componer el rectangulo (x0, y0)-(x1, y1) de la textura (limites incluidos): fondo gris + capas visibles de
     * abajo hacia arriba (ver LayerCompositor). Escribe en composite (no sube nada a la GPU, ver uploadRect).
     */
    private fun recompositeRect(x0: Int, y0: Int, x1: Int, y1: Int) {
        val comp = composite ?: return
        val rows = y1 - y0 + 1
        val texels = rows * (x1 - x0 + 1)
        if (COMPOSITE_THREADS <= 1 || texels < PARALLEL_MIN_TEXELS) {
            renderCompositor.composite(layers, soloLayerId, baseColorFn, comp, texSize, x0, y0, x1, y1)
            return
        }
        // Zona grande (cambio de opacidad, ojo, modo de mezcla...): se reparte en franjas de filas entre varios hilos. Cada franja
        // escribe filas distintas de comp y usa su propio compositor (el mezclador no se comparte entre hilos); todas ven la misma lista de capas.
        val snapshot = layers.toList()
        val solo = soloLayerId
        val tasks = ArrayList<java.util.concurrent.Future<*>>()
        for (band in 0 until COMPOSITE_THREADS) {
            val yStart = y0 + rows * band / COMPOSITE_THREADS
            val yEnd = y0 + rows * (band + 1) / COMPOSITE_THREADS - 1
            if (yEnd < yStart) continue
            tasks.add(COMPOSITE_POOL.submit(Runnable {
                LayerCompositor().composite(snapshot, solo, baseColorFn, comp, texSize, x0, yStart, x1, yEnd)
            }))
        }
        // Se espera a que terminen todas antes de subir la textura a la GPU.
        for (task in tasks) task.get()
    }

    /** Sube a la GPU (glTexSubImage2D) el rectangulo (x0, y0)-(x1, y1) de la textura compuesta. Hilo de render. */
    private fun uploadRect(x0: Int, y0: Int, x1: Int, y1: Int) {
        val comp = composite ?: return
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
            buf.put(comp, ((y0 + row) * texSize + x0) * 4, w * 4)
        }
        buf.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, x0, y0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * Dibuja la malla con su textura y, si selected es true, su caja naranja de seleccion (ver drawBounds).
     * normalMatrix: matriz 4x4 (vista * rotacion de camara * rotacion del objeto) para llevar las normales a espacio de
     * vista - solo se usa su parte de rotacion (la escala del objeto no se aplica a las normales).
     */
    fun draw(mvpMatrix: FloatArray, selected: Boolean, normalMatrix: FloatArray) {
        // Cambios de capas (ojo, opacidad, borrar...) piden recomponer todo: se hace una sola vez por cuadro aunque lleguen muchos.
        if (compositeDirty && !released) {
            compositeDirty = false
            recompositeRect(0, 0, texSize - 1, texSize - 1)
            uploadRect(0, 0, texSize - 1, texSize - 1)
        }
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
     * Pinta un toque de pincel redondo en la CAPA ELEGIDA, centrado en (u, v) (0..1). radius va en texeles (textura de
     * texSize x texSize). El borde es suave: opaco en la mitad interior del circulo y se desvanece hacia afuera.
     * erase = true borra (baja el alfa de la capa) en vez de pintar; el color no importa. No hace nada si la capa esta
     * bloqueada u oculta. Con bloqueo alfa solo cambia el color de lo que ya esta pintado. Se pinta en los pixeles de la
     * capa, se vuelve a componer solo el rectangulo tocado y ese se sube a la GPU con glTexSubImage2D.
     * IMPORTANTE: llamar desde el hilo de render (usa OpenGL).
     */
    fun paintDab(u: Float, v: Float, radius: Float, r: Int, g: Int, b: Int, opacity: Float = 1f, brushType: BrushType = BrushType.SOFT, erase: Boolean = false) {
        if (released) return
        val layer = selectedLayer() ?: return
        if (layer.isFolder || layer.locked || !layer.visible) return
        val pix = layer.pixels
        val alphaLock = layer.alphaLock
        // Con seleccion activa, cada toque se multiplica por cuanto esta seleccionado ese texel (ver selectByOpacity).
        val selMask = selectionMask
        val cx = u * (texSize - 1)
        val cy = v * (texSize - 1)
        val x0 = maxOf(0, Math.floor((cx - radius).toDouble()).toInt())
        val x1 = minOf(texSize - 1, Math.ceil((cx + radius).toDouble()).toInt())
        val y0 = maxOf(0, Math.floor((cy - radius).toDouble()).toInt())
        val y1 = minOf(texSize - 1, Math.ceil((cy + radius).toDouble()).toInt())
        if (x1 < x0 || y1 < y0) return
        if (x0 < strokeMinX) strokeMinX = x0
        if (y0 < strokeMinY) strokeMinY = y0
        if (x1 > strokeMaxX) strokeMaxX = x1
        if (y1 > strokeMaxY) strokeMaxY = y1
        strokeDab++
        // Antes de tocar un solo pixel: guarda las baldosas que todavia no se guardaron en este trazo (para deshacerlo).
        saveTilesForUndo(layer, x0, y0, x1, y1)

        for (y in y0..y1) {
            for (x in x0..x1) {
                val ddx = x - cx
                val ddy = y - cy
                val d = Math.sqrt((ddx * ddx + ddy * ddy).toDouble()).toFloat()
                if (d >= radius) continue
                // Borde segun el tipo de pincel: SOFT opaco en la mitad interior y se desvanece; HARD casi sin desvanecer;
                // AIRBRUSH baja de forma cuadratica desde el centro (aerografo).
                val t = 1f - d / radius
                val falloff = brushType.falloff(t)
                if (falloff <= 0f) {
                    continue


                }
                val dabAlpha = falloff * opacity * (if (selMask != null) (selMask[y * texSize + x].toInt() and 0xFF) / 255f else 1f)
                val idx = y * texSize + x
                var prevAlpha = strokeAlpha[idx]
                // Pasada nueva: el trazo se fue de este texel y volvio. Se parte del color que tiene ahora (con lo ya pintado) para que se sume.
                if (prevAlpha > 0f && strokeDab - strokeLastDab[idx] > STROKE_NEW_PASS_GAP) prevAlpha = 0f
                strokeLastDab[idx] = strokeDab
                // Este texel ya tiene al menos esta opacidad en el trazo actual: nada que sumar.
                if (dabAlpha <= prevAlpha) continue
                val o = idx * 4
                if (prevAlpha == 0f) {
                    // Primera vez que el trazo toca este texel: recuerda su color (RGBA) de antes del trazo.
                    strokeBase[o] = pix[o]
                    strokeBase[o + 1] = pix[o + 1]
                    strokeBase[o + 2] = pix[o + 2]
                    strokeBase[o + 3] = pix[o + 3]
                }
                strokeAlpha[idx] = dabAlpha
                val baseA = strokeBase[o + 3].toInt() and 0xFF
                if (erase) {
                    // Borrador: baja el alfa y deja el color como estaba.
                    pix[o + 3] = (baseA * (1f - dabAlpha)).toInt().toByte()
                } else if (alphaLock) {
                    // Bloqueo alfa: solo se tine lo que ya tiene algo; la transparencia no cambia.
                    if (baseA == 0) continue
                    val oldR = strokeBase[o].toInt() and 0xFF
                    val oldG = strokeBase[o + 1].toInt() and 0xFF
                    val oldB = strokeBase[o + 2].toInt() and 0xFF
                    pix[o] = (oldR + (r - oldR) * dabAlpha).toInt().toByte()
                    pix[o + 1] = (oldG + (g - oldG) * dabAlpha).toInt().toByte()
                    pix[o + 2] = (oldB + (b - oldB) * dabAlpha).toInt().toByte()
                } else {
                    // Pintar: el color del pincel (con alfa dabAlpha) sobre lo que habia antes del trazo, modo Normal.
                    // (El modo de mezcla es de la CAPA entera, no del pincel: se aplica al componer, ver LayerCompositor.)
                    val a0 = baseA / 255f
                    val keep = a0 * (1f - dabAlpha)
                    val outA = dabAlpha + keep
                    val oldR = strokeBase[o].toInt() and 0xFF
                    val oldG = strokeBase[o + 1].toInt() and 0xFF
                    val oldB = strokeBase[o + 2].toInt() and 0xFF
                    pix[o] = ((r * dabAlpha + oldR * keep) / outA).toInt().toByte()
                    pix[o + 1] = ((g * dabAlpha + oldG * keep) / outA).toInt().toByte()
                    pix[o + 2] = ((b * dabAlpha + oldB * keep) / outA).toInt().toByte()
                    pix[o + 3] = (outA * 255f + 0.5f).toInt().toByte()
                }
            }
        }

        recompositeRect(x0, y0, x1, y1)
        uploadRect(x0, y0, x1, y1)
    }

    /**
     * Copia (copy-on-write) los pixeles de las baldosas de la capa que cubre el rectangulo (x0, y0)-(x1, y1) y que todavia
     * no se guardaron en el trazo en curso. Debe llamarse ANTES de modificar esos pixeles.
     */
    private fun saveTilesForUndo(layer: PaintLayer, x0: Int, y0: Int, x1: Int, y1: Int) {
        if (pendingTiles.isEmpty()) pendingLayerId = layer.id
        // Un mismo registro de deshacer es de una sola capa (el renderer entrega el anterior antes de cambiar de capa).
        if (pendingLayerId != layer.id) return
        val pix = layer.pixels
        for (ty in (y0 / TILE)..(y1 / TILE)) {
            for (tx in (x0 / TILE)..(x1 / TILE)) {
                val index = ty * TILES_PER_ROW + tx
                if (pendingTiles.containsKey(index)) continue
                val copy = ByteArray(TILE * TILE * 4)
                for (row in 0 until TILE) {
                    System.arraycopy(pix, ((ty * TILE + row) * texSize + tx * TILE) * 4, copy, row * TILE * 4, TILE * 4)
                }
                pendingTiles[index] = copy
            }
        }
    }

    /**
     * Entrega el trazo pintado desde la ultima vez que se llamo (o null si no se pinto nada) y empieza a juntar el siguiente.
     * Lo llama el renderer para meterlo en su historial de Undo/Redo.
     */
    fun takeStrokeEdit(): StrokeEdit? {
        if (pendingTiles.isEmpty()) {
            // Sin trazo pendiente: si se movieron capas o carpetas, ese movimiento es lo que se entrega.
            val layout = pendingLayout ?: return null
            pendingLayout = null
            return StrokeEdit(this, -1, HashMap(), layout)
        }
        val edit = StrokeEdit(this, pendingLayerId, pendingTiles)
        pendingTiles = HashMap()
        return edit
    }

    /**
     * true si el paso (trazo o movimiento de capas) todavia se puede aplicar: su capa sigue existiendo y, si es un movimiento,
     * las capas son las mismas que cuando se guardo (si despues se anadio o borro alguna, ya no encaja). Solo consulta, no cambia nada.
     */
    fun canApply(edit: StrokeEdit): Boolean {
        if (released) return false
        val saved = edit.layout
        if (saved != null) {
            val byId = layers.associateBy { it.id }
            return saved.ids.size == byId.size && saved.ids.all { byId.containsKey(it) }
        }
        return layers.any { it.id == edit.layerId }
    }

    /** Descarta lo que se haya juntado del trazo en curso sin meterlo en ningun historial. */
    fun discardStrokeEdit() {
        pendingLayout = null
        pendingTiles = HashMap()
    }

    /**
     * Intercambia, baldosa por baldosa, los pixeles actuales de la capa del edit con los guardados en el edit, vuelve a
     * componer esas baldosas y las sube a la GPU. Como es un intercambio, el mismo edit sirve para deshacer y despues para
     * rehacer. Devuelve false (sin hacer nada) si la capa ya no existe.
     * IMPORTANTE: llamar desde el hilo de render (usa OpenGL), y con el trazo en curso ya entregado (ver takeStrokeEdit).
     */
    fun applyEdit(edit: StrokeEdit): Boolean {
        if (released) return false
        // Movimiento de capas o carpetas: se intercambia el orden guardado por el actual (asi el mismo registro sirve para rehacer).
        edit.layout?.let { saved ->
            val current = layoutSnapshot()
            if (!applyLayout(saved)) return false
            edit.layout = current
            onLayersChanged?.invoke()
            return true
        }
        val layer = layers.firstOrNull { it.id == edit.layerId } ?: return false
        val pix = layer.pixels
        val len = TILE * TILE * 4
        val current = ByteArray(len)
        for (entry in edit.tiles.entries) {
            val tx = (entry.key % TILES_PER_ROW) * TILE
            val ty = (entry.key / TILES_PER_ROW) * TILE
            val saved = entry.value
            // Lo que hay ahora en la capa, para dejarlo guardado en el edit despues.
            for (row in 0 until TILE) {
                System.arraycopy(pix, ((ty + row) * texSize + tx) * 4, current, row * TILE * 4, TILE * 4)
            }
            // Pone los pixeles guardados en la capa...
            for (row in 0 until TILE) {
                System.arraycopy(saved, row * TILE * 4, pix, ((ty + row) * texSize + tx) * 4, TILE * 4)
            }
            System.arraycopy(current, 0, saved, 0, len)
            // ...y recompone esa baldosa y la sube a la GPU.
            recompositeRect(tx, ty, tx + TILE - 1, ty + TILE - 1)
            uploadRect(tx, ty, tx + TILE - 1, ty + TILE - 1)
        }
        return true
    }

    /**
     * Libera los recursos de GPU de esta geometria (VBO, textura y los dos programas de shaders) y las copias en CPU
     * (capas y textura compuesta). IMPORTANTE: llamar desde el hilo de render, y no volver a usar esta instancia despues.
     */
    fun release() {
        released = true
        GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteProgram(lineProgram)
        layers.clear()
        composite = null
        paintScratch = null
        pendingTiles = HashMap()
    }

    /**
     * Empieza un trazo nuevo: olvida el trazo anterior (limpia strokeAlpha solo en el rectangulo que toco) para que los
     * toques de este trazo se midan contra el color actual de la capa. Llamar al apoyar el dedo, antes del primer toque.
     */
    fun beginStroke() {
        smudgeValid = false
        if (strokeMaxX >= strokeMinX && strokeMaxY >= strokeMinY) {
            for (y in strokeMinY..strokeMaxY) {
                java.util.Arrays.fill(strokeAlpha, y * texSize + strokeMinX, y * texSize + strokeMaxX + 1, 0f)
            }
        }
        strokeMinX = texSize
        strokeMinY = texSize
        strokeMaxX = -1
        strokeMaxY = -1
    }

    /**
     * Color (RGB, 0..255) de la textura compuesta (lo que se ve, con todas las capas) en (u, v) (0..1) - lo usa el
     * cuentagotas. Devuelve null si la geometria ya fue liberada. No llama a OpenGL.
     */
    fun pickColor(u: Float, v: Float): IntArray? {
        val comp = composite ?: return null
        val x = (u * (texSize - 1)).toInt().coerceIn(0, texSize - 1)
        val y = (v * (texSize - 1)).toInt().coerceIn(0, texSize - 1)
        val o = (y * texSize + x) * 4
        return intArrayOf(comp[o].toInt() and 0xFF, comp[o + 1].toInt() and 0xFF, comp[o + 2].toInt() and 0xFF)
    }

    // ---- Relleno ----

    /** Texeles del mapa UV que cubre algun triangulo del modelo (1 = cubierto, con 1 texel de margen). Se calcula la primera vez que se rellena. */
    private var uvCoverage: ByteArray? = null

    private fun coverage(): ByteArray {
        uvCoverage?.let { return it }
        val cov = ByteArray(texSize * texSize)
        val uv = mesh.uvs
        val maxC = (texSize - 1).toFloat()
        var i = 0
        while (i + 2 < vertexCount) {
            val ax = uv[i * 2] * maxC; val ay = uv[i * 2 + 1] * maxC
            val bx = uv[(i + 1) * 2] * maxC; val by = uv[(i + 1) * 2 + 1] * maxC
            val cx = uv[(i + 2) * 2] * maxC; val cy = uv[(i + 2) * 2 + 1] * maxC
            i += 3
            val denom = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (denom > -1e-9f && denom < 1e-9f) continue
            val minX = maxOf(0, Math.floor(minOf(ax, bx, cx).toDouble()).toInt())
            val maxX = minOf(texSize - 1, Math.ceil(maxOf(ax, bx, cx).toDouble()).toInt())
            val minY = maxOf(0, Math.floor(minOf(ay, by, cy).toDouble()).toInt())
            val maxY = minOf(texSize - 1, Math.ceil(maxOf(ay, by, cy).toDouble()).toInt())
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val w0 = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / denom
                    val w1 = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / denom
                    val w2 = 1f - w0 - w1
                    if (w0 >= 0f && w1 >= 0f && w2 >= 0f) cov[y * texSize + x] = 1
                }
            }
        }
        // Margen de 1 texel: cierra las rendijas entre triangulos vecinos y cubre el borde de cada isla.
        val dilated = cov.copyOf()
        for (y in 0 until texSize) {
            for (x in 0 until texSize) {
                if (cov[y * texSize + x].toInt() == 0) continue
                for (ny in maxOf(0, y - 1)..minOf(texSize - 1, y + 1)) {
                    for (nx in maxOf(0, x - 1)..minOf(texSize - 1, x + 1)) dilated[ny * texSize + nx] = 1
                }
            }
        }
        uvCoverage = dilated
        return dilated
    }

    /**
     * Relleno: pinta del color (r, g, b), opaco, la zona de la capa elegida que toca el punto (u, v) (0..1): los texeles
     * conectados que se parecen al texel tocado (color y alfa dentro de tolerance) y que estan dentro de la misma isla del
     * mapa UV (el relleno no se sale de lo que cubre el modelo). Respeta la seleccion (fuera de ella no se pinta) y el
     * bloqueo alfa (solo cambia el color de lo que ya esta pintado). Se puede deshacer. No cruza las costuras entre islas UV.
     * false (sin cambiar nada) si la capa no se puede pintar, el punto cae fuera del modelo o la zona ya es de ese color.
     * IMPORTANTE: llamar desde el hilo de render (usa OpenGL).
     */
    fun fillAt(u: Float, v: Float, r: Int, g: Int, b: Int, tolerance: Int = 32): Boolean {
        if (released) return false
        val layer = selectedLayer() ?: return false
        if (layer.isFolder || layer.locked || !layer.visible) return false
        val pix = layer.pixels
        val cov = coverage()
        val selMask = selectionMask
        val alphaLock = layer.alphaLock
        val n = texSize * texSize
        val startX = (u * (texSize - 1)).toInt().coerceIn(0, texSize - 1)
        val startY = (v * (texSize - 1)).toInt().coerceIn(0, texSize - 1)
        val startIdx = startY * texSize + startX
        if (selMask != null && selMask[startIdx].toInt() == 0) return false
        val so = startIdx * 4
        val tr = pix[so].toInt() and 0xFF
        val tg = pix[so + 1].toInt() and 0xFF
        val tb = pix[so + 2].toInt() and 0xFF
        val ta = pix[so + 3].toInt() and 0xFF
        // Ya es de ese color: nada que hacer.
        if (ta == 255 && tr == r && tg == g && tb == b) return false
        // Con bloqueo alfa no se puede pintar donde no hay nada.
        if (alphaLock && ta == 0) return false
        val alphaOnly = ta == 0
        val visited = ByteArray(n)

        fun canFill(idx: Int): Boolean {
            if (visited[idx].toInt() != 0 || cov[idx].toInt() == 0) return false
            if (selMask != null && selMask[idx].toInt() == 0) return false
            val o = idx * 4
            val a = pix[o + 3].toInt() and 0xFF
            if (Math.abs(a - ta) > tolerance) return false
            if (alphaOnly) return true
            return Math.abs((pix[o].toInt() and 0xFF) - tr) <= tolerance &&
                Math.abs((pix[o + 1].toInt() and 0xFF) - tg) <= tolerance &&
                Math.abs((pix[o + 2].toInt() and 0xFF) - tb) <= tolerance
        }

        if (!canFill(startIdx)) return false

        // Relleno por franjas horizontales (scanline) con una pila de semillas.
        var stack = IntArray(1024)
        var sp = 0
        stack[sp++] = startIdx
        var minX = texSize
        var minY = texSize
        var maxX = -1
        var maxY = -1
        while (sp > 0) {
            val seed = stack[--sp]
            if (!canFill(seed)) continue
            val y = seed / texSize
            var xl = seed % texSize
            var xr = xl
            while (xl > 0 && canFill(y * texSize + xl - 1)) xl--
            while (xr < texSize - 1 && canFill(y * texSize + xr + 1)) xr++
            java.util.Arrays.fill(visited, y * texSize + xl, y * texSize + xr + 1, 1.toByte())
            if (xl < minX) minX = xl
            if (xr > maxX) maxX = xr
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            for (ny in intArrayOf(y - 1, y + 1)) {
                if (ny < 0 || ny >= texSize) continue
                var inRun = false
                for (x in xl..xr) {
                    val ok = canFill(ny * texSize + x)
                    if (ok && !inRun) {
                        if (sp == stack.size) stack = stack.copyOf(sp * 2)
                        stack[sp++] = ny * texSize + x
                    }
                    inRun = ok
                }
            }
        }
        if (maxX < minX) return false

        // Borde suave: los trazos de pincel se desvanecen (el alfa sube poco a poco hacia el centro del trazo), y esos texeles
        // casi transparentes no entran en la zona parecida, asi que quedaria un anillo sin rellenar. Desde el borde de la zona se
        // avanza mientras el alfa siga subiendo (hasta la cima del trazo) y esos texeles se marcan con 2: el relleno se pone DEBAJO
        // de lo que ya tienen (ver mas abajo). Con bloqueo alfa no se hace (no se puede cambiar la transparencia).
        if (!alphaLock) {
            val queue = IntArray(n)
            var qh = 0
            var qt = 0
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val idx = y * texSize + x
                    if (visited[idx].toInt() == 1) queue[qt++] = idx
                }
            }
            while (qh < qt) {
                val cur = queue[qh++]
                val ac = pix[cur * 4 + 3].toInt() and 0xFF
                if (ac == 255) continue
                val cx = cur % texSize
                val cy = cur / texSize
                for (k in 0 until 4) {
                    val nx = cx + (if (k == 0) -1 else if (k == 1) 1 else 0)
                    val ny = cy + (if (k == 2) -1 else if (k == 3) 1 else 0)
                    if (nx < 0 || nx >= texSize || ny < 0 || ny >= texSize) continue
                    val ni = ny * texSize + nx
                    if (visited[ni].toInt() != 0 || cov[ni].toInt() == 0) continue
                    if (selMask != null && selMask[ni].toInt() == 0) continue
                    val an = pix[ni * 4 + 3].toInt() and 0xFF
                    // Solo mientras el alfa sube y sin tocar lo ya opaco: se queda en el desvanecido del borde.
                    if (an <= ac || an == 255) continue
                    visited[ni] = 2
                    queue[qt++] = ni
                    if (nx < minX) minX = nx
                    if (nx > maxX) maxX = nx
                    if (ny < minY) minY = ny
                    if (ny > maxY) maxY = ny
                }
            }
        }

        // Antes de tocar un solo pixel: guarda las baldosas de la zona para poder deshacer.
        saveTilesForUndo(layer, minX, minY, maxX, maxY)
        val rb = r.toByte()
        val gb = g.toByte()
        val bb = b.toByte()
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val idx = y * texSize + x
                val mark = visited[idx].toInt()
                if (mark == 0) continue
                if (mark == 2) {
                    // Borde desvanecido: lo que ya habia queda ENCIMA del relleno (el relleno es opaco y va debajo), asi no queda anillo.
                    val eo = idx * 4
                    val ea = (pix[eo + 3].toInt() and 0xFF) / 255f
                    pix[eo] = (((pix[eo].toInt() and 0xFF) * ea + r * (1f - ea)) + 0.5f).toInt().toByte()
                    pix[eo + 1] = (((pix[eo + 1].toInt() and 0xFF) * ea + g * (1f - ea)) + 0.5f).toInt().toByte()
                    pix[eo + 2] = (((pix[eo + 2].toInt() and 0xFF) * ea + b * (1f - ea)) + 0.5f).toInt().toByte()
                    pix[eo + 3] = 255.toByte()
                    continue
                }
                val o = idx * 4
                if (alphaLock && pix[o + 3].toInt() == 0) continue
                pix[o] = rb
                pix[o + 1] = gb
                pix[o + 2] = bb
                if (!alphaLock) pix[o + 3] = 255.toByte()
            }
        }
        recompositeRect(minX, minY, maxX, maxY)
        uploadRect(minX, minY, maxX, maxY)
        return true
    }

    // ---- Difuminar y Borrosidad ----

    // Borrosidad (smudge): colores que "arrastra" el dedo, uno por texel de un cuadrado del tamano del pincel (alfa premultiplicado, 0..255).
    // Se llena con el primer toque del trazo y se va mezclando con lo que el trazo pisa (ver smudgeDab).
    private var smudgeBuf: FloatArray? = null
    private var smudgeRad = 0
    private var smudgeValid = false
    /** Cuanto del color pisado se suma a lo que se arrastra en cada toque (0..1): mas bajo = la mancha llega mas lejos. */
    private val SMUDGE_PICKUP = 0.15f

    /** Peso (0..1) del toque a distancia d del centro segun el tipo de pincel (igual que en paintDab). */
    private fun brushFalloff(brushType: BrushType, d: Float, radius: Float): Float {
        val t = 1f - d / radius
        return if (t <= 0f) 0f else {
            brushType.falloff(t)


        }
    }

    /**
     * Pone en el texel (posicion o dentro de pix) el color dado en alfa premultiplicado: pr, pg, pb ya multiplicados por el alfa
     * (0..255) y pa = alfa (0..255). Con bloqueo alfa solo cambia el color de lo que ya tiene algo; la transparencia no se toca.
     */
    private fun writePremultiplied(pix: ByteArray, o: Int, pr: Float, pg: Float, pb: Float, pa: Float, alphaLock: Boolean) {
        if (alphaLock) {
            if ((pix[o + 3].toInt() and 0xFF) == 0 || pa < 0.5f) return
        } else if (pa < 0.5f) {
            pix[o] = 0
            pix[o + 1] = 0
            pix[o + 2] = 0
            pix[o + 3] = 0
            return
        }
        val inv = 255f / pa
        pix[o] = (pr * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
        pix[o + 1] = (pg * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
        pix[o + 2] = (pb * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
        if (!alphaLock) pix[o + 3] = (pa + 0.5f).toInt().coerceIn(0, 255).toByte()
    }

    /**
     * Difuminar: un toque que suaviza la CAPA ELEGIDA dentro del circulo de radio radius (texeles) centrado en (u, v). Cada texel
     * se acerca al promedio de sus vecinos (3x3, ponderado por el alfa) en la medida strength (0..1) por el borde del pincel,
     * asi pasar varias veces lo difumina mas. Respeta la seleccion, el bloqueo alfa y las capas bloqueadas u ocultas. Se puede deshacer.
     * IMPORTANTE: llamar desde el hilo de render (usa OpenGL).
     */
    fun blurDab(u: Float, v: Float, radius: Float, strength: Float, brushType: BrushType = BrushType.SOFT) {
        if (released) return
        val layer = selectedLayer() ?: return
        if (layer.isFolder || layer.locked || !layer.visible) return
        val pix = layer.pixels
        val alphaLock = layer.alphaLock
        val selMask = selectionMask
        val cx = u * (texSize - 1)
        val cy = v * (texSize - 1)
        val x0 = maxOf(0, Math.floor((cx - radius).toDouble()).toInt())
        val x1 = minOf(texSize - 1, Math.ceil((cx + radius).toDouble()).toInt())
        val y0 = maxOf(0, Math.floor((cy - radius).toDouble()).toInt())
        val y1 = minOf(texSize - 1, Math.ceil((cy + radius).toDouble()).toInt())
        if (x1 < x0 || y1 < y0) return
        // Antes de tocar un solo pixel: guarda las baldosas de la zona para poder deshacer.
        saveTilesForUndo(layer, x0, y0, x1, y1)
        // Copia de la zona (con 1 texel de margen) para promediar siempre con los pixeles de antes del toque.
        val sx0 = maxOf(0, x0 - 1)
        val sy0 = maxOf(0, y0 - 1)
        val sx1 = minOf(texSize - 1, x1 + 1)
        val sy1 = minOf(texSize - 1, y1 + 1)
        val sw = sx1 - sx0 + 1
        val src = ByteArray(sw * (sy1 - sy0 + 1) * 4)
        for (row in sy0..sy1) {
            System.arraycopy(pix, (row * texSize + sx0) * 4, src, (row - sy0) * sw * 4, sw * 4)
        }
        for (y in y0..y1) {
            for (x in x0..x1) {
                val ddx = x - cx
                val ddy = y - cy
                val d = Math.sqrt((ddx * ddx + ddy * ddy).toDouble()).toFloat()
                if (d >= radius) continue
                val sel = if (selMask != null) (selMask[y * texSize + x].toInt() and 0xFF) / 255f else 1f
                val k = brushFalloff(brushType, d, radius) * strength * sel
                if (k <= 0f) continue
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var sumA = 0
                var count = 0
                for (ny in maxOf(sy0, y - 1)..minOf(sy1, y + 1)) {
                    for (nx in maxOf(sx0, x - 1)..minOf(sx1, x + 1)) {
                        val so = ((ny - sy0) * sw + (nx - sx0)) * 4
                        val a = src[so + 3].toInt() and 0xFF
                        sumA += a
                        sumR += (src[so].toInt() and 0xFF) * a
                        sumG += (src[so + 1].toInt() and 0xFF) * a
                        sumB += (src[so + 2].toInt() and 0xFF) * a
                        count++
                    }
                }
                val o = (y * texSize + x) * 4
                val oa = pix[o + 3].toInt() and 0xFF
                // Nada que suavizar: el texel y sus vecinos estan vacios.
                if (oa == 0 && sumA == 0) continue
                val oldR = (pix[o].toInt() and 0xFF) * oa / 255f
                val oldG = (pix[o + 1].toInt() and 0xFF) * oa / 255f
                val oldB = (pix[o + 2].toInt() and 0xFF) * oa / 255f
                val tr = sumR / (255f * count)
                val tg = sumG / (255f * count)
                val tb = sumB / (255f * count)
                val ta = sumA.toFloat() / count
                writePremultiplied(pix, o, oldR + (tr - oldR) * k, oldG + (tg - oldG) * k, oldB + (tb - oldB) * k, oa + (ta - oa) * k, alphaLock)
            }
        }
        recompositeRect(x0, y0, x1, y1)
        uploadRect(x0, y0, x1, y1)
    }

    /**
     * Borrosidad (smudge): arrastra el color de la CAPA ELEGIDA a lo largo del trazo, como un dedo sobre pintura fresca. El primer
     * toque del trazo (despues de beginStroke) solo recoge los colores bajo el pincel; los siguientes los depositan en el circulo
     * de radio radius centrado en (u, v), en la medida strength (0..1) por el borde del pincel, y recogen un poco de lo que pisan.
     * Respeta la seleccion, el bloqueo alfa y las capas bloqueadas u ocultas. Se puede deshacer.
     * IMPORTANTE: llamar desde el hilo de render (usa OpenGL).
     */
    fun smudgeDab(u: Float, v: Float, radius: Float, strength: Float, brushType: BrushType = BrushType.SOFT) {
        if (released) return
        val layer = selectedLayer() ?: return
        if (layer.isFolder || layer.locked || !layer.visible) return
        val pix = layer.pixels
        val alphaLock = layer.alphaLock
        val selMask = selectionMask
        val cx = u * (texSize - 1)
        val cy = v * (texSize - 1)
        val rad = Math.ceil(radius.toDouble()).toInt().coerceAtLeast(1)
        val side = rad * 2 + 1
        val left = Math.round(cx) - rad
        val top = Math.round(cy) - rad
        var buf = smudgeBuf
        if (buf == null || smudgeRad != rad) {
            buf = FloatArray(side * side * 4)
            smudgeBuf = buf
            smudgeRad = rad
            smudgeValid = false
        }
        val x0 = maxOf(0, left)
        val x1 = minOf(texSize - 1, left + side - 1)
        val y0 = maxOf(0, top)
        val y1 = minOf(texSize - 1, top + side - 1)
        if (x1 < x0 || y1 < y0) return
        val first = !smudgeValid
        // Antes de tocar un solo pixel: guarda las baldosas de la zona para poder deshacer (el primer toque solo recoge, no cambia nada).
        if (!first) saveTilesForUndo(layer, x0, y0, x1, y1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val o = (y * texSize + x) * 4
                val i = ((y - top) * side + (x - left)) * 4
                val ua = pix[o + 3].toInt() and 0xFF
                val ur = (pix[o].toInt() and 0xFF) * ua / 255f
                val ug = (pix[o + 1].toInt() and 0xFF) * ua / 255f
                val ub = (pix[o + 2].toInt() and 0xFF) * ua / 255f
                if (first) {
                    buf[i] = ur
                    buf[i + 1] = ug
                    buf[i + 2] = ub
                    buf[i + 3] = ua.toFloat()
                    continue
                }
                val ddx = x - cx
                val ddy = y - cy
                val d = Math.sqrt((ddx * ddx + ddy * ddy).toDouble()).toFloat()
                if (d < radius) {
                    val sel = if (selMask != null) (selMask[y * texSize + x].toInt() and 0xFF) / 255f else 1f
                    val k = brushFalloff(brushType, d, radius) * strength * sel
                    if (k > 0f) {
                        writePremultiplied(
                            pix, o,
                            ur + (buf[i] - ur) * k, ug + (buf[i + 1] - ug) * k, ub + (buf[i + 2] - ub) * k, ua + (buf[i + 3] - ua) * k,
                            alphaLock
                        )
                    }
                }
                // El dedo recoge un poco de lo que pisa (con los pixeles de antes del toque).
                buf[i] += (ur - buf[i]) * SMUDGE_PICKUP
                buf[i + 1] += (ug - buf[i + 1]) * SMUDGE_PICKUP
                buf[i + 2] += (ub - buf[i + 2]) * SMUDGE_PICKUP
                buf[i + 3] += (ua - buf[i + 3]) * SMUDGE_PICKUP
            }
        }
        smudgeValid = true
        if (first) return
        recompositeRect(x0, y0, x1, y1)
        uploadRect(x0, y0, x1, y1)
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
