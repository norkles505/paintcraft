package com.meshcraft.app

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * Renderer de la app de pintura de texturas: dibuja los modelos importados desde OBJ (ver TexturedMeshGeometry),
 * maneja la camara orbital, el proyecto guardado, el Undo/Redo de los trazos de pintura y la pintura sobre la textura.
 * Ya no hay primitivas (cubo, esfera, etc.), gizmos de transformacion ni seleccion de objetos: solo se pinta.
 */
class MyGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    /** Modelos en la escena (hoy se trabaja con uno a la vez: importar uno nuevo reemplaza al anterior, ver addImportedMesh). */
    val sceneObjects = mutableListOf<SceneObject>()
    private var nextObjectId = 0

    /** true despues de armar la escena la primera vez (ver onSurfaceCreated), para no recargarla si se recrea el contexto GL. */
    private var sceneInitialized = false

    /** Geometria con textura de los modelos importados (ver addImportedMesh), por malla. Se crea en onDrawFrame (hilo de render). */
    private val importedGeometries = java.util.concurrent.ConcurrentHashMap<ObjMesh, TexturedMeshGeometry>()

    /**
     * Mallas importadas que siguen "vivas" (en la escena), calculadas en el hilo que pidio el barrido (ver
     * requestGeometrySweep) y entregadas al hilo de render, que es el unico que puede liberar recursos de OpenGL
     * (ver sweepImportedGeometries). AtomicReference: se escribe desde la UI y se lee en el render.
     */
    private val pendingLiveMeshes = java.util.concurrent.atomic.AtomicReference<Set<ObjMesh>?>(null)

    /**
     * Pide liberar la geometria (VBO, textura, shaders) de las mallas importadas que ya no usa nadie. Una malla sigue
     * viva mientras algun objeto de la escena la referencie. No toca OpenGL aca (puede llamarse desde cualquier hilo).
     */
    private fun requestGeometrySweep() {
        val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ObjMesh, Boolean>())
        for (obj in sceneObjects.toList()) obj.importedMesh?.let { live.add(it) }
        pendingLiveMeshes.set(live)
    }

    /** Ejecuta el barrido pedido por requestGeometrySweep. SOLO desde el hilo de render (onDrawFrame). */
    private fun sweepImportedGeometries() {
        val live = pendingLiveMeshes.getAndSet(null) ?: return
        // Las capas pendientes de una malla que ya no esta en la escena no se van a usar: se sueltan.
        savedLayersByMesh.keys.retainAll(live)
        val iterator = importedGeometries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!live.contains(entry.key)) {
                entry.value.release()
                iterator.remove()
            }
        }
    }

    /**
     * Historial de Undo/Redo de los trazos de pintura: cada entrada es un StrokeEdit (las baldosas de textura que toco el
     * trazo, ver TexturedMeshGeometry.applyEdit). Limitado a MAX_UNDO_STEPS trazos y a MAX_UNDO_BYTES de memoria (se
     * descartan los mas viejos). Solo se toca desde el hilo de render. El trazo en curso se mete en la pila de forma
     * perezosa: al empezar el siguiente trazo o al pulsar deshacer/rehacer (ver commitPendingStrokes).
     */
    private val undoStack = ArrayDeque<StrokeEdit>()
    private val redoStack = ArrayDeque<StrokeEdit>()
    private val MAX_UNDO_STEPS = 50
    private val MAX_UNDO_BYTES = 64L * 1024 * 1024

    /** Lo pone la interfaz (importar / New) para que el hilo de render vacie el historial: sus trazos eran de un modelo que ya no esta. */
    @Volatile private var historyResetRequested = false

    private fun applyHistoryReset() {
        if (!historyResetRequested) return
        historyResetRequested = false
        undoStack.clear()
        redoStack.clear()
        for (g in importedGeometries.values) g.discardStrokeEdit()
    }

    /** Mete en el historial el trazo que se acaba de pintar (si lo hay). Un trazo nuevo invalida el Redo. */
    private fun commitPendingStrokes() {
        for (g in importedGeometries.values) {
            val edit = g.takeStrokeEdit() ?: continue
            undoStack.addLast(edit)
            redoStack.clear()
        }
        var total = 0L
        for (e in undoStack) total += e.sizeBytes()
        while (undoStack.isNotEmpty() && (undoStack.size > MAX_UNDO_STEPS || total > MAX_UNDO_BYTES)) {
            total -= undoStack.removeFirst().sizeBytes()
        }
    }

    /** Deshace el ultimo trazo. Devuelve false (sin hacer nada) si no hay nada para deshacer. IMPORTANTE: llamar desde el hilo de render (glView.queueEvent). */
    fun undo(): Boolean {
        applyHistoryReset()
        commitPendingStrokes()
        var edit = undoStack.removeLastOrNull() ?: return false
        // Un paso cuya capa o cuyo orden de capas ya no existe (se anadio o borro algo despues) no se puede aplicar: se descarta y se prueba con el anterior.
        while (!edit.geo.canApply(edit)) edit = undoStack.removeLastOrNull() ?: return false
        edit.geo.applyEdit(edit)
        redoStack.addLast(edit)
        return true
    }

    /** Igual que undo() pero al reves: vuelve a aplicar el ultimo trazo deshecho. IMPORTANTE: llamar desde el hilo de render (glView.queueEvent). */
    fun redo(): Boolean {
        applyHistoryReset()
        commitPendingStrokes()
        var edit = redoStack.removeLastOrNull() ?: return false
        // Igual que en undo(): un paso que ya no encaja con las capas actuales se descarta.
        while (!edit.geo.canApply(edit)) edit = redoStack.removeLastOrNull() ?: return false
        edit.geo.applyEdit(edit)
        undoStack.addLast(edit)
        return true
    }

    /** Un solo slot fijo en el storage interno de la app: se sobreescribe en cada Save y se carga solo al abrir la app. */
    private val projectFile: File
        get() = File(context.filesDir, "current_project.json")

    /** Carpeta de los binarios de mallas importadas (OBJ) del proyecto guardado (ver ProjectSerializer.writeObjMesh). */
    private val meshDir: File
        get() = File(context.filesDir, "imported_meshes")

    /** Escribe la escena completa al slot fijo. Devuelve false (sin lanzar) si algo sale mal (IO, permisos). */
    @Synchronized
    fun saveProjectToFile(): Boolean {
        return try {
            val layersJson = writeLayerFiles(layerDir, snapshotAllLayers())
            writeTextAtomically(projectFile, sceneObjectsToJson(sceneObjects.toList(), nextObjectId, meshDir, layersJson))
            cleanupLayerFiles(layerDir, layersJson)
            cleanupImportedMeshFiles(meshDir, sceneObjects)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Auto-carga (ver onSurfaceCreated): si existe un slot guardado y parsea bien, reemplaza la escena por la guardada.
     * Solo se conservan los modelos importados: los proyectos viejos podian tener primitivas (cubo, esfera...) que
     * esta version ya no dibuja. nextObjectId nunca choca con un id ya cargado.
     */
    private fun loadProjectFromFile(): Boolean {
        if (!projectFile.exists()) return false
        val json = try { projectFile.readText() } catch (e: Exception) { return false }
        val parsed = jsonToSceneObjects(json, meshDir) ?: return false
        val (loadedObjects, loadedNextId) = parsed
        val models = loadedObjects.filter { it.importedMesh != null }

        sceneObjects.clear()
        sceneObjects.addAll(models)
        nextObjectId = maxOf(loadedNextId, (loadedObjects.maxOfOrNull { it.id } ?: -1) + 1)

        // Capas pintadas guardadas: esperan a que se cree la geometria de cada modelo (ver createGeometry).
        savedLayersByMesh.clear()
        val savedLayers = jsonToSavedLayers(json, layerDir)
        for (obj in models) {
            val mesh = obj.importedMesh ?: continue
            savedLayers[obj.id]?.let { savedLayersByMesh[mesh] = it }
        }

        importedGeometries.clear()
        return true
    }

    /**
     * File > New: escena vacia. Ya no se puede deshacer (el Undo es solo de trazos) y se pierde la pintura que no se haya
     * guardado. A proposito NO borra el archivo guardado: New solo afecta la escena en memoria; el usuario decide si hace Save despues.
     */
    fun newProject(): Boolean {
        sceneObjects.clear()
        nextObjectId = 0
        historyResetRequested = true
        requestGeometrySweep()
        return true
    }

    /** Agrega un modelo importado desde OBJ (ver ObjLoader). Reemplaza al modelo anterior, que se libera (no se puede deshacer). */
    fun addImportedMesh(mesh: ObjMesh): SceneObject {
        sceneObjects.clear()
        val newObject = SceneObject(id = nextObjectId++, importedMesh = mesh)
        sceneObjects.add(newObject)
        historyResetRequested = true
        requestGeometrySweep()
        return newObject
    }

    // ---- Capas (las usa LayersPanel desde el hilo de la interfaz) ----

    /** Ordenes pendientes para el hilo de render (ver runOnGlThread): se ejecutan al empezar cada cuadro. */
    private val glCommands = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    /** Ejecuta block en el hilo de render, al empezar el proximo cuadro. Se puede llamar desde cualquier hilo. */
    fun runOnGlThread(block: () -> Unit) {
        glCommands.add(block)
    }

    private fun runGlCommands() {
        while (true) {
            val command = glCommands.poll() ?: break
            command()
        }
    }

    /**
     * Modelo cuyas capas muestra el panel: el primero de la escena que ya tiene su geometria creada, o null si no hay
     * modelo (o todavia no se dibujo). Se puede leer desde cualquier hilo.
     */
    val activeGeometry: TexturedMeshGeometry?
        get() {
            for (obj in sceneObjects.toList()) {
                val mesh = obj.importedMesh ?: continue
                val geo = importedGeometries[mesh] ?: continue
                return geo
            }
            return null
        }

    /**
     * Cambia las capas del modelo activo en el hilo de render (edit recibe su geometria) y despues avisa con onDone, tambien
     * en el hilo de render. Antes de empezar entrega al historial el trazo que estuviera pendiente y al terminar entrega
     * lo que haya registrado la operacion (limpiar/invertir quedan en el Deshacer). clearsHistory = true para operaciones
     * que no se pueden deshacer (borrar o combinar capas): vacia el historial de Deshacer/Rehacer.
     */
    fun editLayers(clearsHistory: Boolean, edit: (TexturedMeshGeometry) -> Unit, onDone: () -> Unit) {
        glCommands.add {
            val geo = activeGeometry
            if (geo != null) {
                applyHistoryReset()
                commitPendingStrokes()
                edit(geo)
                commitPendingStrokes()
                if (clearsHistory) {
                    undoStack.clear()
                    redoStack.clear()
                    for (g in importedGeometries.values) g.discardStrokeEdit()
                }
            }
            onDone()
        }
    }

    // ---- Guardado de las capas pintadas ----

    /** Carpeta con los pixeles de las capas del proyecto guardado (ver ProjectSerializer.writeLayerFiles). */
    private val layerDir: File
        get() = File(context.filesDir, "paint_layers")

    /**
     * Capas pintadas que esperan a la geometria de su malla: las del proyecto recien cargado, y las de la geometria vieja
     * cuando se recrea el contexto de OpenGL (la GPU se pierde, las capas en CPU no). Se entregan en createGeometry.
     */
    private val savedLayersByMesh = java.util.concurrent.ConcurrentHashMap<ObjMesh, SavedLayers>()

    /** Aviso (hilo de render) de que Deshacer/Rehacer cambio el orden o las carpetas de las capas: LayersPanel vuelve a armar la lista. */
    @Volatile var onLayersChanged: (() -> Unit)? = null

    /** Crea la geometria de una malla (hilo de render) y le devuelve las capas pintadas que hubiera pendientes. */
    private fun createGeometry(mesh: ObjMesh): TexturedMeshGeometry {
        val geo = TexturedMeshGeometry(mesh)
        geo.onLayersChanged = { onLayersChanged?.invoke() }
        savedLayersByMesh.remove(mesh)?.let { geo.restoreLayers(it) }
        return geo
    }

    /** Las capas de cada modelo de la escena (id del objeto -> capas), listas para guardar. No toca OpenGL: se puede llamar desde cualquier hilo. */
    private fun snapshotAllLayers(): Map<Int, SavedLayers> {
        val result = HashMap<Int, SavedLayers>()
        for (obj in sceneObjects.toList()) {
            val mesh = obj.importedMesh ?: continue
            val saved = importedGeometries[mesh]?.snapshotLayers() ?: savedLayersByMesh[mesh] ?: continue
            result[obj.id] = saved
        }
        return result
    }

    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)
    private val scratch = FloatArray(16)

    private var viewportWidth = 1
    private var viewportHeight = 1

    // angleX = pitch (rotates around world X). angleY = yaw (rotates around world Z, since Z is "up" here, like Blender).
    // Default fijado por el usuario (capturado con el long-press de debug en el gizmo).
    @Volatile var angleX = 19.8f
    @Volatile var angleY = -137.0f

    // Like Blender: axis-aligned views snap to orthographic; free orbiting uses perspective.
    @Volatile var isOrthographic = false

    // Camera distance from the origin (zoom).
    @Volatile var cameraDistance = 7.47f
        set(value) {
            field = value.coerceIn(0.5f, 20f)
        }

    // Pan offset: shifts the camera + its look-at target together, sideways on screen (world X / world Z).
    @Volatile var panX = 0.05f
    @Volatile var panZ = 0.24f

    fun zoomIn() {
        cameraDistance -= cameraDistance * 0.15f
    }

    fun zoomOut() {
        cameraDistance += cameraDistance * 0.15f
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.11f, 0.11f, 0.11f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        // Contexto GL nuevo: lo que habia en la GPU (VBO, texturas) ya no existe, pero las capas pintadas estan en CPU: se
        // guardan para la geometria nueva (ver createGeometry). El historial de Deshacer apuntaba a la geometria vieja, asi que se vacia.
        if (importedGeometries.isNotEmpty()) {
            for ((mesh, geo) in importedGeometries.entries) geo.snapshotLayers()?.let { savedLayersByMesh[mesh] = it }
            historyResetRequested = true
        }
        importedGeometries.clear()
        // Contexto GL recreado (por ejemplo al volver del selector de archivos): la escena ya existe en memoria, no se recarga ni se reinicia.
        if (sceneInitialized) return
        sceneInitialized = true

        // Auto-carga del proyecto guardado; si no hay nada guardado, la escena arranca vacia (se importa un OBJ para pintar).
        loadProjectFromFile()
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(unused: GL10?) {
        // Libera la geometria de mallas importadas que ya no usa nadie (ver requestGeometrySweep) - hilo de render, el unico valido para esto.
        sweepImportedGeometries()
        applyHistoryReset()
        runGlCommands()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val ratio = viewportWidth.toFloat() / viewportHeight.toFloat()
        if (isOrthographic) {
            // Matches the apparent size of the perspective view at the current camera distance
            // (distance * tan(halfFOV) = distance * 0.5), so switching views doesn't feel like a zoom.
            val orthoSize = cameraDistance * 0.5f
            Matrix.orthoM(projectionMatrix, 0, -orthoSize * ratio, orthoSize * ratio, -orthoSize, orthoSize, 0.1f, 30f)
        } else {
            // Plano cercano chico para que el modelo no se recorte al acercar la camara. Los bordes del frustum se miden en el plano
            // cercano, asi que se escalan con el para mantener el mismo campo de vision de antes (tan(mitad) = 0.5).
            val near = 0.1f
            val half = near * 0.5f
            Matrix.frustumM(projectionMatrix, 0, -ratio * half, ratio * half, -half, half, near, 30f)
        }

        // Camera looks along +Y with Z as the up direction (Blender-style Z-up).
        // panX/panZ shift both eye and target together, so it pans without changing the viewing angle.
        Matrix.setLookAtM(
            viewMatrix, 0,
            panX, -cameraDistance, panZ,
            panX, 0f, panZ,
            0f, 0f, 1f
        )

        Matrix.setIdentityM(rotationMatrix, 0)
        Matrix.rotateM(rotationMatrix, 0, angleX, 1f, 0f, 0f)
        Matrix.rotateM(rotationMatrix, 0, angleY, 0f, 0f, 1f)

        Matrix.multiplyMM(scratch, 0, viewMatrix, 0, rotationMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, scratch, 0)

        val objMvpMatrix = FloatArray(16)
        // Normales de modelos importados en espacio de vista (ver TexturedMeshGeometry): vista * rotacion de camara * rotacion del objeto.
        val viewRotationMatrix = FloatArray(16)
        Matrix.multiplyMM(viewRotationMatrix, 0, viewMatrix, 0, rotationMatrix, 0)
        val importedNormalMatrix = FloatArray(16)
        // Copia de la lista: la interfaz puede importar mientras se dibuja.
        for (obj in sceneObjects.toList()) {
            if (!obj.visible) continue
            val mesh = obj.importedMesh ?: continue
            Matrix.multiplyMM(objMvpMatrix, 0, mvpMatrix, 0, objectModelMatrix(obj), 0)
            Matrix.multiplyMM(importedNormalMatrix, 0, viewRotationMatrix, 0, obj.rotationMatrix, 0)
            // La geometria con textura se crea la primera vez que se dibuja (aca, en el hilo de render) y se reutiliza despues.
            importedGeometries.getOrPut(mesh) { createGeometry(mesh) }.draw(objMvpMatrix, false, importedNormalMatrix)
        }
    }

    /**
     * Convierte un punto de pantalla (coordenadas de vista, no NDC) en un rayo 3D (origen + direccion), usando la
     * matriz camara+orbita del ultimo frame dibujado (scratch). Lo usa la pintura (ver findPaintHit).
     */
    private fun screenPointToRay(screenX: Float, screenY: Float): Pair<FloatArray, FloatArray>? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null

        val ndcX = (2f * screenX / viewportWidth) - 1f
        val ndcY = 1f - (2f * screenY / viewportHeight)

        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, scratch, 0)
        val invMatrix = FloatArray(16)
        if (!Matrix.invertM(invMatrix, 0, vpMatrix, 0)) return null

        val nearPoint = floatArrayOf(ndcX, ndcY, -1f, 1f)
        val farPoint = floatArrayOf(ndcX, ndcY, 1f, 1f)
        val nearWorld = FloatArray(4)
        val farWorld = FloatArray(4)
        Matrix.multiplyMV(nearWorld, 0, invMatrix, 0, nearPoint, 0)
        Matrix.multiplyMV(farWorld, 0, invMatrix, 0, farPoint, 0)
        if (nearWorld[3] != 0f) for (i in 0..2) nearWorld[i] /= nearWorld[3]
        if (farWorld[3] != 0f) for (i in 0..2) farWorld[i] /= farWorld[3]

        val rayOrigin = floatArrayOf(nearWorld[0], nearWorld[1], nearWorld[2])
        val rayDir = floatArrayOf(
            farWorld[0] - nearWorld[0],
            farWorld[1] - nearWorld[1],
            farWorld[2] - nearWorld[2]
        )
        return rayOrigin to rayDir
    }

    /** Matriz modelo completa (traslacion * rotacion * forma) de un objeto. Compartida por el dibujo y la pintura (raycast). */
    private fun objectModelMatrix(obj: SceneObject): FloatArray {
        val translateMatrix = FloatArray(16)
        Matrix.setIdentityM(translateMatrix, 0)
        Matrix.translateM(translateMatrix, 0, obj.posX, obj.posY, obj.posZ)
        val modelMatrix = FloatArray(16)
        Matrix.multiplyMM(modelMatrix, 0, translateMatrix, 0, obj.rotationMatrix, 0)
        val shapedModelMatrix = FloatArray(16)
        Matrix.multiplyMM(shapedModelMatrix, 0, modelMatrix, 0, obj.shapeMatrix, 0)
        return shapedModelMatrix
    }

    // ---- Pintura sobre modelos importados ----

    /** Color del pincel (RGB), radio en texeles de la textura y opacidad: los cambia PaintPanel. */
    @Volatile var paintColor = intArrayOf(225, 70, 60)
    @Volatile var paintRadius = 18f
    @Volatile var paintOpacity = 1f
    // Borrador: con paintTool == ERASER se borra (baja el alfa) en la capa elegida en vez de pintar con el color del pincel (ver applyPaintHit).
    /** Herramienta activa del panel de pintura (la elige PaintPanel). Pincel y borrador pintan; el cuentagotas toma color de la textura. */
    @Volatile var paintTool = PaintTool.BRUSH
    /** Tipo de pincel (como se desvanece el borde de cada toque, ver TexturedMeshGeometry.paintDab). */
    @Volatile var paintBrushType = BrushType.SOFT
    /** Aviso del cuentagotas con el color tomado (r, g, b). Se llama desde el hilo de render: quien lo reciba debe pasar al hilo de la interfaz. */
    @Volatile var onColorPicked: ((Int, Int, Int) -> Unit)? = null
    /** Ultimo punto de pantalla del trazo en curso, para rellenar los huecos entre eventos de toque (ver paintMove). */
    private var lastPaintX = 0f
    private var lastPaintY = 0f
    /** Punto de la textura que se pinto en el ultimo punto del trazo (null si ahi no habia modelo), ver strokeSegment. */
    private var lastPaintHit: PaintHit? = null

    /** Punto de una textura (u, v en 0..1) de un modelo importado, hallado bajo un punto de pantalla. */
    private class PaintHit(val geo: TexturedMeshGeometry, val u: Float, val v: Float)

    /** Empieza un trazo: pinta un toque en (screenX, screenY). IMPORTANTE: llamar desde el hilo de render (glView.queueEvent). */
    fun paintStart(screenX: Float, screenY: Float) {
        lastPaintX = screenX
        lastPaintY = screenY
        // El trazo anterior ya termino: pasa al historial de Undo (y un trazo nuevo invalida el Redo).
        applyHistoryReset()
        commitPendingStrokes()
        // Trazo nuevo: cada modelo olvida el trazo anterior, para que la opacidad se mida solo dentro de este trazo.
        for (g in importedGeometries.values) g.beginStroke()
        val startHit = pickPaintHit(screenX, screenY)
        lastPaintHit = startHit
        if (startHit != null) applyPaintHit(startHit)
    }

    /**
     * Continua el trazo hasta (screenX, screenY): rellena el camino desde el ultimo punto con toques separados por una
     * fraccion del radio del pincel medida en la textura (ver strokeSegment), asi un arrastre rapido no deja un punteado.
     * IMPORTANTE: llamar desde el hilo de render.
     */
    fun paintMove(screenX: Float, screenY: Float) {
        // Rellena el trazo desde el ultimo punto hasta el nuevo con toques parejos EN LA TEXTURA (no en pixeles de pantalla):
        // asi el espaciado no depende del zoom (ver strokeSegment). El cuentagotas no rellena: solo toma el punto final.
        val eyedropper = paintTool == PaintTool.EYEDROPPER
        if (paintTool == PaintTool.FILL) {
            // Relleno: un solo toque en el punto donde se apoyo el dedo (ver paintStart); arrastrar no vuelve a rellenar.
            lastPaintHit = null
        } else if (eyedropper) {
            paintAt(screenX, screenY)
            lastPaintHit = null
        } else {
            val endHit = pickPaintHit(screenX, screenY)
            strokeSegment(lastPaintX, lastPaintY, lastPaintHit, screenX, screenY, endHit, 0)
            lastPaintHit = endHit
        }
        lastPaintX = screenX
        lastPaintY = screenY
    }

    /** Un toque de la herramienta activa en un punto de pantalla (ver pickPaintHit y applyPaintHit). */
    private fun paintAt(screenX: Float, screenY: Float) {
        pickPaintHit(screenX, screenY)?.let { applyPaintHit(it) }
    }

    /** true si hay un modelo importado bajo ese punto de pantalla. IMPORTANTE: llamar desde el hilo de render (glView.queueEvent). */
    fun isModelAt(screenX: Float, screenY: Float): Boolean = pickPaintHit(screenX, screenY) != null

    /** Punto de textura bajo un punto de pantalla, o null si ahi no hay ningun modelo importado. */
    private fun pickPaintHit(screenX: Float, screenY: Float): PaintHit? {
        var result: PaintHit? = null
        findPaintHit(screenX, screenY) { result = it }
        return result
    }

    /**
     * Pinta el tramo (x0, y0) -> (x1, y1) del trazo con toques parejos en la TEXTURA: si dos puntos consecutivos quedan a
     * mas de paintSpacing texeles (u, v), parte el tramo por la mitad y repite con cada mitad. Asi el espaciado depende del
     * tamano del pincel y no del zoom ni de que tan rapido se mueva el dedo. hit0/hit1 son los puntos de textura de
     * los extremos (null si ahi no hay modelo); el toque del extremo inicial ya se pinto antes, solo se pinta el final de cada pieza.
     */
    private fun strokeSegment(x0: Float, y0: Float, hit0: PaintHit?, x1: Float, y1: Float, hit1: PaintHit?, depth: Int) {
        val screenDx = x1 - x0
        val screenDy = y1 - y0
        val screenDist = sqrt(screenDx * screenDx + screenDy * screenDy)
        val close = if (hit0 != null && hit1 != null && hit0.geo === hit1.geo) {
            val du = (hit1.u - hit0.u) * hit0.geo.texSize
            val dv = (hit1.v - hit0.v) * hit0.geo.texSize
            sqrt(du * du + dv * dv) <= paintSpacing()
        } else {
            // Un extremo fuera del modelo (o en otro modelo): no hay distancia de textura que medir, se usa la de pantalla.
            screenDist <= 6f
        }
        // Corte de seguridad: pieza de menos de 1 px (cruce de costura UV), o demasiadas subdivisiones.
        if (close || screenDist < 1f || depth >= 8) {
            if (hit1 != null) applyPaintHit(hit1)
            return
        }
        val mx = x0 + screenDx * 0.5f
        val my = y0 + screenDy * 0.5f
        val hitMid = pickPaintHit(mx, my)
        strokeSegment(x0, y0, hit0, mx, my, hitMid, depth + 1)
        strokeSegment(mx, my, hitMid, x1, y1, hit1, depth + 1)
    }

    /** Distancia entre toques de un trazo, en texeles: la fraccion del radio que define el tipo de pincel (minimo 1). */
    private fun paintSpacing(): Float = maxOf(1f, paintRadius * paintBrushType.spacing)

    /**
     * Busca el modelo importado y el punto de su textura que quedan bajo un punto de pantalla: convierte el punto en
     * rayo, lo lleva al espacio local de cada modelo visible (inversa de su matriz de modelo) y se queda con el triangulo
     * mas cercano (ver TexturedMeshGeometry.pickUv). Llama a onHit solo si hay un impacto. Ignora los modelos que
     * todavia no se dibujaron ni una vez (su geometria con textura se crea en el primer frame, ver onDrawFrame).
     */
    private fun findPaintHit(screenX: Float, screenY: Float, onHit: (PaintHit) -> Unit) {
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return
        var bestT = Float.MAX_VALUE
        var bestGeo: TexturedMeshGeometry? = null
        var bestU = 0f
        var bestV = 0f
        for (obj in sceneObjects.toList()) {
            if (!obj.visible) continue
            val mesh = obj.importedMesh ?: continue
            val geo = importedGeometries[mesh] ?: continue
            val inv = FloatArray(16)
            if (!Matrix.invertM(inv, 0, objectModelMatrix(obj), 0)) continue
            val o = FloatArray(4)
            val d = FloatArray(4)
            Matrix.multiplyMV(o, 0, inv, 0, floatArrayOf(rayOrigin[0], rayOrigin[1], rayOrigin[2], 1f), 0)
            Matrix.multiplyMV(d, 0, inv, 0, floatArrayOf(rayDir[0], rayDir[1], rayDir[2], 0f), 0)
            val hit = geo.pickUv(o[0], o[1], o[2], d[0], d[1], d[2]) ?: continue
            if (hit[0] < bestT) {
                bestT = hit[0]
                bestGeo = geo
                bestU = hit[1]
                bestV = hit[2]
            }
        }
        val geo = bestGeo ?: return
        onHit(PaintHit(geo, bestU, bestV))
    }

    /** Aplica la herramienta activa (pincel, borrador o cuentagotas) en un punto de textura. IMPORTANTE: hilo de render (usa OpenGL). */
    private fun applyPaintHit(hit: PaintHit) {
        val geo = hit.geo
        val bestU = hit.u
        val bestV = hit.v
        when (paintTool) {
            PaintTool.BRUSH -> geo.paintDab(bestU, bestV, paintRadius, paintColor[0], paintColor[1], paintColor[2], paintOpacity, paintBrushType)
            PaintTool.ERASER -> geo.paintDab(bestU, bestV, paintRadius, 0, 0, 0, paintOpacity, paintBrushType, true)
            PaintTool.EYEDROPPER -> geo.pickColor(bestU, bestV)?.let { c -> onColorPicked?.invoke(c[0], c[1], c[2]) }
            PaintTool.FILL -> geo.fillAt(bestU, bestV, paintColor[0], paintColor[1], paintColor[2])
            PaintTool.BLUR -> geo.blurDab(bestU, bestV, paintRadius, paintOpacity, paintBrushType)
            PaintTool.SMUDGE -> geo.smudgeDab(bestU, bestV, paintRadius, paintOpacity, paintBrushType)
        }
    }
}
