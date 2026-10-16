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
        activeObjectId = models.firstOrNull()?.id ?: -1

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

    /**
     * Agrega un modelo separado en piezas (ver ObjLoader.splitParts): una por parte del OBJ, cada una con su propia malla, textura, capas e
     * historial. Reemplaza al modelo anterior, que se libera (no se puede deshacer). La primera pieza queda activa.
     */
    fun addImportedMeshes(pieces: List<Pair<String, ObjMesh>>) {
        sceneObjects.clear()
        for ((name, mesh) in pieces) {
            sceneObjects.add(SceneObject(id = nextObjectId++, importedMesh = mesh, name = name))
        }
        activeObjectId = sceneObjects.firstOrNull()?.id ?: -1
        historyResetRequested = true
        requestGeometrySweep()
    }

    /** Malla del modelo que hay en la escena (la de la pieza activa), o null si no hay. Se puede leer desde cualquier hilo. */
    val activeMesh: ObjMesh?
        get() = activeObject()?.importedMesh

    /**
     * Id de la pieza activa: la que muestra el panel de capas y la que se exporta. Cambia al apoyar el pincel sobre otra pieza (ver paintStart)
     * o al elegirla en el panel de Partes. Cada pieza tiene su propia textura y capas (ver addImportedMeshes).
     */
    @Volatile var activeObjectId = -1

    /** Piezas del modelo: los objetos de la escena con malla importada, en orden. Se puede leer desde cualquier hilo. */
    fun pieces(): List<SceneObject> = sceneObjects.toList().filter { it.importedMesh != null }

    /** Pieza activa (ver activeObjectId) o, si no hay o ya no existe, la primera con malla. null si no hay modelo. */
    fun activeObject(): SceneObject? {
        val list = pieces()
        return list.firstOrNull { it.id == activeObjectId } ?: list.firstOrNull()
    }

    /** Muestra u oculta una pieza (no se dibuja ni se puede pintar). No toca sus pixeles ni el historial. Se puede llamar desde cualquier hilo. */
    fun setObjectVisible(objectId: Int, visible: Boolean) {
        sceneObjects.toList().firstOrNull { it.id == objectId }?.visible = visible
    }

    /**
     * Muestra u oculta una parte del modelo (ver ObjMesh.parts): una parte oculta no se dibuja ni se puede pintar. No toca los pixeles
     * ni el historial de Deshacer. Se puede llamar desde cualquier hilo; despues hay que pedir un redibujado (requestRender).
     */
    fun setPartVisible(mesh: ObjMesh, index: Int, visible: Boolean) {
        if (index < 0 || index >= mesh.partVisible.size) return
        mesh.partVisible[index] = visible
        importedGeometries[mesh]?.refreshPartVisibility()
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
            val mesh = activeObject()?.importedMesh ?: return null
            return importedGeometries[mesh]
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

        val invMatrix = cachedInverseViewProjection() ?: return null
        // La inversa de proyeccion*camara sale del cache de este evento (ver cachedInverseViewProjection);
        // si no se pudiera invertir (no deberia pasar), no hay rayo.
        // Con esa inversa se arman los dos puntos del rayo (el cercano y el lejano) que corresponden al punto de pantalla.

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
    private class PaintHit(val geo: TexturedMeshGeometry, val u: Float, val v: Float, val objId: Int = -1)

    /** Empieza un trazo: pinta un toque en (screenX, screenY). IMPORTANTE: llamar desde el hilo de render (glView.queueEvent). */
    fun paintStart(screenX: Float, screenY: Float) {
        lastPaintX = screenX
        lastPaintY = screenY
        resetPickCache()
        // Por si el trazo anterior no llego a avisar que termino (paintEnd): pinta sus ultimos toques antes de empezar otro, y reinicia la cuenta del desvanecido.
        paintEnd()
        resetStrokeDynamics()
        // El trazo anterior ya termino: pasa al historial de Undo (y un trazo nuevo invalida el Redo).
        applyHistoryReset()
        commitPendingStrokes()
        // Trazo nuevo: cada modelo olvida el trazo anterior, para que la opacidad se mida solo dentro de este trazo.
        for (g in importedGeometries.values) g.beginStroke()
        val startHit = pickPaintHit(screenX, screenY)
        lastPaintHit = startHit
        // Si el trazo empieza sobre otra pieza, esa pasa a ser la activa: el panel de capas muestra sus capas (cada pieza tiene su textura).
        if (startHit != null && startHit.objId >= 0 && startHit.objId != activeObjectId) {
            activeObjectId = startHit.objId
            onLayersChanged?.invoke()
        }
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
        resetPickCache()
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

    // ---- Cache de los rayos de un evento de pintura ----
    // Dentro de un mismo evento (paintStart, paintMove, isModelAt) la camara no cambia, y un movimiento del dedo hace decenas de rayos:
    // la inversa de proyeccion*camara y la de la matriz de cada modelo se calculan una vez y se reusan. Se vacia al entrar a cada
    // evento (ver resetPickCache), porque entre un evento y otro se dibujan cuadros y la camara puede haberse movido.
    private val invViewProjection = FloatArray(16)
    private var invViewProjectionState = 0 // 0 = sin calcular, 1 = lista, 2 = no se pudo invertir
    private val modelInverseCache = java.util.IdentityHashMap<SceneObject, FloatArray?>()

    private fun resetPickCache() {
        invViewProjectionState = 0
        modelInverseCache.clear()
    }

    private fun cachedInverseViewProjection(): FloatArray? {
        if (invViewProjectionState == 0) {
            val vp = FloatArray(16)
            Matrix.multiplyMM(vp, 0, projectionMatrix, 0, scratch, 0)
            invViewProjectionState = if (Matrix.invertM(invViewProjection, 0, vp, 0)) 1 else 2
        }
        return if (invViewProjectionState == 1) invViewProjection else null
    }

    private fun inverseModel(obj: SceneObject): FloatArray? {
        if (modelInverseCache.containsKey(obj)) return modelInverseCache[obj]
        val inv = FloatArray(16)
        val result = if (Matrix.invertM(inv, 0, objectModelMatrix(obj), 0)) inv else null
        modelInverseCache[obj] = result
        return result
    }

    /** Un toque de la herramienta activa en un punto de pantalla (ver pickPaintHit y applyPaintHit). */
    private fun paintAt(screenX: Float, screenY: Float) {
        pickPaintHit(screenX, screenY)?.let { applyPaintHit(it) }
    }

    /** true si hay un modelo importado bajo ese punto de pantalla. IMPORTANTE: llamar desde el hilo de render (glView.queueEvent). */
    fun isModelAt(screenX: Float, screenY: Float): Boolean {
        resetPickCache()
        return pickPaintHit(screenX, screenY) != null
    }

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
        // El tramo se parte por la mitad hasta que sus extremos quedan a un espaciado de toque o menos (en la textura).
        // Corte de seguridad: pieza casi sin largo (cruce de costura UV), o demasiadas subdivisiones. Antes se cortaba en 1 px y en 8 niveles
        // (256 piezas): con un pincel que en pantalla mide menos de 1 px, o un arrastre muy rapido, dejaba huecos (punteado). Ahora se puede
        // partir hasta 0.05 px y 12 niveles (4096 piezas); sale barato porque cada rayo ya no recorre todo el modelo (ver TriBvh).
        if (close || screenDist < 0.05f || depth >= 12) {
            if (hit1 != null) applyPaintHit(hit1)
            return
        }
        val mx = x0 + screenDx * 0.5f
        val my = y0 + screenDy * 0.5f
        val hitMid = pickPaintHit(mx, my)
        strokeSegment(x0, y0, hit0, mx, my, hitMid, depth + 1)
        strokeSegment(mx, my, hitMid, x1, y1, hit1, depth + 1)
    }

    // Espaciado de los toques de un trazo (lo usa strokeSegment).
    /** Distancia entre toques de un trazo, en texeles: la fraccion del radio que define el tipo de pincel (minimo medio texel, para que un pincel de radio 1 tampoco deje huecos). */
    private fun paintSpacing(): Float = maxOf(0.5f, paintRadius * paintBrushType.spacing)

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
        var bestObjId = -1
        for (obj in sceneObjects.toList()) {
            if (!obj.visible) continue
            val mesh = obj.importedMesh ?: continue
            val geo = importedGeometries[mesh] ?: continue
            val inv = inverseModel(obj) ?: continue
            // (la inversa de la matriz del modelo sale del cache de este evento, ver inverseModel)
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
                bestObjId = obj.id
            }
        }
        val geo = bestGeo ?: return
        onHit(PaintHit(geo, bestU, bestV, bestObjId))
    }

    // ---- Desvanecidos del trazo (ver BrushDynamics) ----

    /**
     * Un toque del pincel del trazo en curso: donde cayo (hit), la distancia en texeles que llevaba el trazo (dist), su numero de toque en la
     * geometria (serial: el grano depende de el, asi se repinta igual) y el tamano y la opacidad con que se pinto por el desvanecido del principio.
     */
    private class StrokeDab(val hit: PaintHit, val dist: Float, val serial: Int, val sizeF: Float, val opacityF: Float)

    /** Ultimos toques del trazo en curso, solo con pinceles que tienen desvanecido al final: al soltar el dedo se repintan con ese desvanecido (ver applyEndFade). */
    private val recentDabs = ArrayList<StrokeDab>()
    /** Distancia recorrida por el trazo hasta su ultimo toque, y ese toque (para medir la distancia al siguiente). */
    private var strokeDist = 0f
    private var strokeLastDab: PaintHit? = null

    /** Empieza de cero el seguimiento del trazo para los desvanecidos. */
    private fun resetStrokeDynamics() {
        recentDabs.clear()
        strokeDist = 0f
        strokeLastDab = null
    }

    /**
     * El trazo termino (se solto el dedo, llego un segundo dedo o se cancelo el gesto). Los pinceles con desvanecido al final pintan cada toque
     * de inmediato, sin esperar, y aca repintan la cola con el desvanecido: hasta este momento no se sabe donde termina el trazo. Sin efecto con
     * los demas pinceles. IMPORTANTE: llamar desde el hilo de render (glView.queueEvent).
     */
    fun paintEnd() {
        val dyn = paintBrushType.dynamics
        if (dyn.hasEnd && recentDabs.isNotEmpty()) applyEndFade(dyn)
        recentDabs.clear()
    }

    /** Toque del pincel normal: se pinta de inmediato (con el desvanecido del principio, que ya se conoce) y, si el pincel tiene desvanecido al final, se recuerda para repintarlo al soltar el dedo. */
    private fun paintBrushDab(hit: PaintHit) {
        val dyn = paintBrushType.dynamics
        if (!dyn.hasStart && !dyn.hasEnd) {
            drawBrushDab(hit, dyn, 1f, 1f)
            return
        }
        val prev = strokeLastDab
        if (prev != null && prev.geo === hit.geo) {
            val du = (hit.u - prev.u) * hit.geo.texSize
            val dv = (hit.v - prev.v) * hit.geo.texSize
            strokeDist += sqrt(du * du + dv * dv)
        }
        strokeLastDab = hit
        val diameter = paintRadius * 2f
        val startLen = if (dyn.hasStart) dyn.startLength * diameter else 0f
        val sizeF = ramp(dyn.startSize, strokeDist, startLen)
        val opacityF = ramp(dyn.startOpacity, strokeDist, startLen)
        drawBrushDab(hit, dyn, sizeF, opacityF)
        if (dyn.hasEnd) {
            recentDabs.add(StrokeDab(hit, strokeDist, hit.geo.lastDabSerial(), sizeF, opacityF))
            // Solo se guardan los toques que un desvanecido puede cambiar: los de los dos extremos (un trazo corto, hasta entero) y los que los tocan.
            val keep = startLen + dyn.endLength * diameter + 4f * paintRadius
            while (recentDabs.size > 1 && strokeDist - recentDabs[0].dist > keep) recentDabs.removeAt(0)
        }
    }

    /** Factor de un desvanecido: vale from al inicio (dist = 0) y sube a 1 a la distancia len. Con len <= 0 no hay desvanecido (vale 1). */
    private fun ramp(from: Float, dist: Float, len: Float): Float {
        if (len <= 0f) return 1f
        val t = (dist / len).coerceIn(0f, 1f)
        return from + (1f - from) * t
    }

    /**
     * Al soltar el dedo: repinta el final del trazo con su desvanecido, sin que se haya notado retraso mientras se dibujaba.
     *  - A = los toques que cambian: los del final (o todos si el trazo es mas corto que los dos desvanecidos juntos; entonces cada desvanecido se
     *    acorta en proporcion, y un toque simple sale con todo su tamano y opacidad).
     *  - S = los toques anteriores cuyo circulo toca el de alguno de A: comparten texeles con ellos, y como cada texel guarda el MAXIMO de los toques
     *    que lo tocaron, hay que repintarlos tambien.
     * Se vuelve al color de antes del trazo en los circulos de A y se repiten, en el orden de siempre, los toques de S y A: los de S como se pintaron
     * y los de A con el desvanecido. Da el mismo resultado que si el final del trazo se hubiera conocido de antemano.
     */
    private fun applyEndFade(dyn: BrushDynamics) {
        val dabs = recentDabs
        val geo = dabs[dabs.size - 1].hit.geo
        val total = strokeDist
        val diameter = paintRadius * 2f
        var startLen = if (dyn.hasStart) dyn.startLength * diameter else 0f
        var endLen = dyn.endLength * diameter
        val short = startLen + endLen > total
        if (short) {
            val k = total / (startLen + endLen)
            startLen *= k
            endLen *= k
        }
        val n = dabs.size
        val inA = BooleanArray(n) { dabs[it].hit.geo === geo && (short || dabs[it].dist > total - endLen) }
        if (inA.none { it }) return
        val texSize = geo.texSize
        val replay = BooleanArray(n)
        for (j in 0 until n) {
            if (inA[j]) {
                replay[j] = true
                continue
            }
            val dj = dabs[j]
            if (dj.hit.geo !== geo) continue
            val rj = maxOf(0.5f, paintRadius * dj.sizeF) + dyn.edgeAa * 0.5f
            for (i in 0 until n) {
                if (!inA[i]) continue
                val di = dabs[i]
                val du = (dj.hit.u - di.hit.u) * texSize
                val dv = (dj.hit.v - di.hit.v) * texSize
                val reach = rj + maxOf(0.5f, paintRadius * di.sizeF) + dyn.edgeAa * 0.5f
                if (du * du + dv * dv <= reach * reach) {
                    replay[j] = true
                    break
                }
            }
        }
        val aList = (0 until n).filter { inA[it] }
        geo.revertStrokeCircles(
            FloatArray(aList.size) { dabs[aList[it]].hit.u },
            FloatArray(aList.size) { dabs[aList[it]].hit.v },
            FloatArray(aList.size) { maxOf(0.5f, paintRadius * dabs[aList[it]].sizeF) + dyn.edgeAa * 0.5f }
        )
        val lastSerial = geo.lastDabSerial()
        for (k in 0 until n) {
            if (!replay[k]) continue
            val d = dabs[k]
            var sizeF = d.sizeF
            var opacityF = d.opacityF
            if (inA[k]) {
                sizeF = ramp(dyn.startSize, d.dist, startLen) * ramp(dyn.endSize, total - d.dist, endLen)
                opacityF = ramp(dyn.startOpacity, d.dist, startLen) * ramp(dyn.endOpacity, total - d.dist, endLen)
            }
            // Mismo numero de toque que tuvo: mismo grano y misma cuenta de pasadas.
            geo.setDabSerial(d.serial - 1)
            drawBrushDab(d.hit, dyn, sizeF, opacityF)
        }
        geo.setDabSerial(lastSerial)
    }

    /** Pinta el toque del pincel con el tamano y la opacidad multiplicados por sizeF y opacityF (desvanecidos) y con el grano del pincel. */
    private fun drawBrushDab(hit: PaintHit, dyn: BrushDynamics, sizeF: Float, opacityF: Float) {
        val radius = maxOf(0.5f, paintRadius * sizeF)
        hit.geo.paintDab(
            hit.u, hit.v, radius, paintColor[0], paintColor[1], paintColor[2],
            paintOpacity * dyn.opacityScale * opacityF, paintBrushType, false, dyn.grain, paintRadius, dyn.edgeAa
        )
    }

    /** Aplica la herramienta activa (pincel, borrador o cuentagotas) en un punto de textura. IMPORTANTE: hilo de render (usa OpenGL). */
    private fun applyPaintHit(hit: PaintHit) {
        val geo = hit.geo
        val bestU = hit.u
        val bestV = hit.v
        when (paintTool) {
            PaintTool.BRUSH -> paintBrushDab(hit)
            PaintTool.ERASER -> geo.paintDab(bestU, bestV, paintRadius, 0, 0, 0, paintOpacity, paintBrushType, true)
            PaintTool.EYEDROPPER -> geo.pickColor(bestU, bestV)?.let { c -> onColorPicked?.invoke(c[0], c[1], c[2]) }
            PaintTool.FILL -> geo.fillAt(bestU, bestV, paintColor[0], paintColor[1], paintColor[2])
            PaintTool.BLUR -> geo.blurDab(bestU, bestV, paintRadius, paintOpacity, paintBrushType)
            PaintTool.SMUDGE -> geo.smudgeDab(bestU, bestV, paintRadius, paintOpacity, paintBrushType)
        }
    }
}
