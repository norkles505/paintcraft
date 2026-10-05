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
 * maneja la camara orbital, el proyecto guardado, el Undo/Redo de la escena y la pintura sobre la textura.
 * Ya no hay primitivas (cubo, esfera, etc.), gizmos de transformacion ni seleccion de objetos: solo se pinta.
 */
class MyGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    /** Modelos en la escena (hoy se trabaja con uno a la vez: importar uno nuevo reemplaza al anterior, ver addImportedMesh). */
    val sceneObjects = mutableListOf<SceneObject>()
    private var nextObjectId = 0

    /** true despues de armar la escena la primera vez (ver onSurfaceCreated), para no recargarla si se recrea el contexto GL. */
    private var sceneInitialized = false

    /** Geometria con textura de los modelos importados (ver addImportedMesh), por malla. Se crea en onDrawFrame (hilo de render). */
    private val importedGeometries = mutableMapOf<ObjMesh, TexturedMeshGeometry>()

    /**
     * Mallas importadas que siguen "vivas" (en la escena o en el historial de Undo/Redo), calculadas en el hilo que
     * pidio el barrido (ver requestGeometrySweep) y entregadas al hilo de render, que es el unico que puede liberar
     * recursos de OpenGL (ver sweepImportedGeometries). AtomicReference: se escribe desde la UI y se lee en el render.
     */
    private val pendingLiveMeshes = java.util.concurrent.atomic.AtomicReference<Set<ObjMesh>?>(null)

    /**
     * Pide liberar la geometria (VBO, textura, shaders) de las mallas importadas que ya no usa nadie. Una malla sigue
     * viva mientras algun objeto de la escena, o algun snapshot de Undo/Redo, la referencie - asi deshacer una
     * importacion restaura el modelo con su pintura intacta. No toca OpenGL aca (puede llamarse desde cualquier hilo).
     */
    private fun requestGeometrySweep() {
        val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ObjMesh, Boolean>())
        for (obj in sceneObjects.toList()) obj.importedMesh?.let { live.add(it) }
        for (snapshot in undoStack) for (obj in snapshot) obj.importedMesh?.let { live.add(it) }
        for (snapshot in redoStack) for (obj in snapshot) obj.importedMesh?.let { live.add(it) }
        pendingLiveMeshes.set(live)
    }

    /** Ejecuta el barrido pedido por requestGeometrySweep. SOLO desde el hilo de render (onDrawFrame). */
    private fun sweepImportedGeometries() {
        val live = pendingLiveMeshes.getAndSet(null) ?: return
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
     * Pilas de Undo/Redo: cada entrada es una foto completa de sceneObjects (deep copy, ver snapshotSceneObjects)
     * tomada ANTES de que la accion correspondiente modifique el estado real. Limitada a MAX_UNDO_STEPS (FIFO).
     * Ojo: por ahora solo cubre cambios de la escena (importar, nuevo proyecto); los trazos de pintura no entran.
     */
    private val undoStack = ArrayDeque<List<SceneObject>>()
    private val redoStack = ArrayDeque<List<SceneObject>>()
    private val MAX_UNDO_STEPS = 50

    /** Copia profunda de sceneObjects: los FloatArray de SceneObject son tipo referencia y copy() solos los compartiria. */
    private fun snapshotSceneObjects(): List<SceneObject> =
        sceneObjects.toList().map { it.copy(rotationMatrix = it.rotationMatrix.copyOf(), shapeMatrix = it.shapeMatrix.copyOf()) }

    /** Guarda el estado actual de la escena en la pila de Undo - se llama SIEMPRE antes de que una accion modifique sceneObjects. */
    fun pushUndoSnapshot() {
        undoStack.addLast(snapshotSceneObjects())
        if (undoStack.size > MAX_UNDO_STEPS) undoStack.removeFirst()
        redoStack.clear()
        // Al recortar el historial (o descartar el Redo) alguna malla importada puede quedar sin duenio: se libera en el proximo frame.
        requestGeometrySweep()
    }

    /** Deshace la ultima accion. Devuelve false (sin hacer nada) si no hay nada para deshacer. */
    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(snapshotSceneObjects())
        sceneObjects.clear()
        sceneObjects.addAll(previous)
        return true
    }

    /** Igual que undo() pero al reves: mueve el snapshot actual a Undo y restaura el tope de Redo. */
    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(snapshotSceneObjects())
        sceneObjects.clear()
        sceneObjects.addAll(next)
        return true
    }

    /** Un solo slot fijo en el storage interno de la app: se sobreescribe en cada Save y se carga solo al abrir la app. */
    private val projectFile: File
        get() = File(context.filesDir, "current_project.json")

    /** Carpeta de los binarios de mallas importadas (OBJ) del proyecto guardado (ver ProjectSerializer.writeObjMesh). */
    private val meshDir: File
        get() = File(context.filesDir, "imported_meshes")

    /** Escribe la escena completa al slot fijo. Devuelve false (sin lanzar) si algo sale mal (IO, permisos). */
    fun saveProjectToFile(): Boolean {
        return try {
            projectFile.writeText(sceneObjectsToJson(sceneObjects, nextObjectId, meshDir))
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

        importedGeometries.clear()
        return true
    }

    /**
     * File > New: escena vacia, pasando por Undo primero (un New accidental se puede deshacer). A proposito NO borra el
     * archivo guardado: New solo afecta la escena en memoria; el usuario decide si hace Save despues.
     */
    fun newProject(): Boolean {
        pushUndoSnapshot()
        sceneObjects.clear()
        nextObjectId = 0
        return true
    }

    /** Agrega un modelo importado desde OBJ (ver ObjLoader). Reemplaza al modelo anterior (pasando por Undo para poder recuperarlo). */
    fun addImportedMesh(mesh: ObjMesh): SceneObject {
        pushUndoSnapshot()
        sceneObjects.clear()
        val newObject = SceneObject(id = nextObjectId++, importedMesh = mesh)
        sceneObjects.add(newObject)
        return newObject
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
        // Copia de la lista: la interfaz puede importar o deshacer mientras se dibuja.
        for (obj in sceneObjects.toList()) {
            if (!obj.visible) continue
            val mesh = obj.importedMesh ?: continue
            Matrix.multiplyMM(objMvpMatrix, 0, mvpMatrix, 0, objectModelMatrix(obj), 0)
            Matrix.multiplyMM(importedNormalMatrix, 0, viewRotationMatrix, 0, obj.rotationMatrix, 0)
            // La geometria con textura se crea la primera vez que se dibuja (aca, en el hilo de render) y se reutiliza despues.
            importedGeometries.getOrPut(mesh) { TexturedMeshGeometry(mesh) }.draw(objMvpMatrix, false, importedNormalMatrix)
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
    // Borrador: con paintTool == ERASER se pinta con el gris base de la textura (204) en vez del color del pincel (ver applyPaintHit).
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
        if (eyedropper) {
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

    /** Distancia entre toques de un trazo, en texeles: un cuarto del radio del pincel (minimo 1). */
    private fun paintSpacing(): Float = maxOf(1f, paintRadius * 0.25f)

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
            PaintTool.ERASER -> geo.paintDab(bestU, bestV, paintRadius, 204, 204, 204, paintOpacity, paintBrushType)
            PaintTool.EYEDROPPER -> geo.pickColor(bestU, bestV)?.let { c -> onColorPicked?.invoke(c[0], c[1], c[2]) }
            else -> Unit // Difuminar, Relleno y Borrosidad todavia no estan implementados (ver PaintTool.implemented).
        }
    }
}
