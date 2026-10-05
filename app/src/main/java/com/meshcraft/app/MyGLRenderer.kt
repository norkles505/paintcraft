package com.meshcraft.app

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.sqrt
import android.content.Context
import java.io.File

/**
 * Orientacion del gizmo de transformacion activo (Move/Rotate/Scale) - selector tipo Blender
 * (ahi existen 6: Global/Local/Normal/Gimbal/View/Cursor; aca solo las 2 que tienen sentido con
 * lo que la app soporta hoy, ver charla con el usuario). Afecta a los 3 gizmos por igual (antes
 * Scale estaba SIEMPRE forzado a Local, sin opcion - ver comentario viejo en onDrawFrame,
 * reemplazado por este selector).
 *
 * GLOBAL: los 3 ejes quedan siempre alineados al mundo, sin importar como este rotado el objeto -
 * el gizmo se ve "prolijo" (Z siempre arriba), pero escalar por eje con el objeto rotado deforma
 * en diagonal (shear) - la razon de fondo (y por que Blender tiene el mismo comportamiento, no es
 * un bug de esta app) esta charlada con el usuario: con un solo float de escala por eje, un
 * escalado no-uniforme en espacio mundo sobre un objeto rotado no es representable sin deformar.
 * Esta app SI reproduce ese shear correctamente (ver SceneObject.shapeMatrix y
 * MyGLRenderer.applyLocalDirScale) - lo que antes fallaba era que el resultado se escribia
 * siempre en el eje LOCAL del objeto en vez de deformarlo, ya corregido.
 *
 * LOCAL: los 3 ejes rotan junto con el objeto - el gizmo se ve "girado" si el objeto esta rotado,
 * pero mover/rotar/escalar por eje siempre da el resultado esperado, sin deformar. Es el
 * comportamiento que ya tenia Scale a secas antes de este selector (ver fix documentado en
 * rotatedAxisDirection) - ahora tambien disponible (y default) para Move y Rotate.
 */
enum class TransformOrientation { GLOBAL, LOCAL }

class MyGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private lateinit var cubeGeometry: Cube
    private lateinit var planeGeometry: Plane
    private lateinit var circleGeometry: Circle
    private lateinit var uvSphereGeometry: UvSphere
    private lateinit var icoSphereGeometry: IcoSphere
    private lateinit var cylinderGeometry: Cylinder
    private lateinit var coneGeometry: Cone
    private lateinit var torusGeometry: Torus
    private lateinit var gridMeshGeometry: GridMesh
    private lateinit var monkeyGeometry: Monkey
    private lateinit var gridXY: Grid
    private lateinit var gridXZ: Grid
    private lateinit var gridYZ: Grid
    private lateinit var gizmo: Gizmo3D

    /**
     * Objetos en la escena. Arranca con un solo cubo en el origen, ya seleccionado
     * (mismo comportamiento que Blender al abrir un archivo nuevo).
     */
    val sceneObjects = mutableListOf<SceneObject>()
    private var nextObjectId = 0

    /** Geometria con textura de los modelos importados (ver addImportedMesh), por id de objeto. Se crea en onDrawFrame (hilo de render). */
    private val importedGeometries = mutableMapOf<ObjMesh, TexturedMeshGeometry>()

    /**
     * Mallas importadas que siguen "vivas" (en la escena o en el historial de Undo/Redo), calculadas en el hilo que
     * pidio el barrido (ver requestGeometrySweep) y entregadas al hilo de render, que es el unico que puede liberar
     * recursos de OpenGL (ver sweepImportedGeometries). AtomicReference: se escribe desde la UI y se lee en el render.
     */
    private val pendingLiveMeshes = java.util.concurrent.atomic.AtomicReference<Set<ObjMesh>?>(null)

    /**
     * Pide liberar la geometria (VBO, textura, shaders) de las mallas importadas que ya no usa nadie. Una malla sigue
     * viva mientras algun objeto de la escena, o algun snapshot de Undo/Redo, la referencie - asi deshacer un Delete
     * restaura el modelo con su pintura intacta. No toca OpenGL aca (puede llamarse desde cualquier hilo).
     */
    private fun requestGeometrySweep() {
        val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ObjMesh, Boolean>())
        for (obj in sceneObjects) obj.importedMesh?.let { live.add(it) }
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
     * Pilas de Undo/Redo: cada entrada es una foto completa de sceneObjects (deep copy, ver
     * snapshotSceneObjects) tomada ANTES de que la accion correspondiente modifique el estado real -
     * mismo criterio que dejo planteado el companero (snapshot del estado completo, mas simple y
     * confiable que deshacer cada operacion individualmente al reves). Limitada a MAX_UNDO_STEPS
     * para no crecer sin limite en sesiones largas - al llegar al tope se descarta la mas vieja
     * (FIFO), igual que el historial de Undo de Blender.
     */
    private val undoStack = ArrayDeque<List<SceneObject>>()
    private val redoStack = ArrayDeque<List<SceneObject>>()
    private val MAX_UNDO_STEPS = 50

    /**
     * Copia profunda de sceneObjects: SceneObject es un data class, pero rotationMatrix y
     * shapeMatrix son FloatArray (tipo referencia) - copy() por si solo comparte el mismo array de
     * fondo entre el original y la copia (mismo motivo por el que duplicateSelectedObject ya
     * clonaba estos dos campos a mano con copyOf()). Sin esto, restaurar un snapshot viejo
     * mutaria en vivo tambien el estado "actual" que se guardo antes, arruinando el historial.
     */
    private fun snapshotSceneObjects(): List<SceneObject> =
        sceneObjects.map { it.copy(rotationMatrix = it.rotationMatrix.copyOf(), shapeMatrix = it.shapeMatrix.copyOf()) }

    /**
     * Guarda el estado actual de la escena en la pila de Undo - se llama SIEMPRE antes de que una
     * accion modifique sceneObjects (Add/Delete/Duplicate: ver cada funcion; Move/Rotate/Scale: ver
     * MainActivity.onViewportDragStart, una vez por gesto de arrastre, no por frame). Cualquier
     * accion nueva despues de esto invalida el Redo pendiente (mismo comportamiento que Blender/
     * cualquier editor: no tiene sentido "rehacer" algo que ya quedo pisado por una accion distinta).
     */
    fun pushUndoSnapshot() {
        undoStack.addLast(snapshotSceneObjects())
        if (undoStack.size > MAX_UNDO_STEPS) undoStack.removeFirst()
        redoStack.clear()
        // Al recortar el historial (o descartar el Redo) alguna malla importada puede quedar sin duenio: se libera en el proximo frame.
        requestGeometrySweep()
    }

    /**
     * Deshace la ultima accion: guarda el estado actual en Redo (para poder rehacer despues) y
     * restaura el snapshot mas reciente de Undo. Devuelve false (sin hacer nada) si no hay nada
     * para deshacer - el llamador (MainActivity) usa esto para avisar con un Toast, mismo criterio
     * que deleteSelectedObject/duplicateSelectedObject.
     */
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

    /**
     * File > Save/New (ver charla con el usuario, item 1 del roadmap tras cerrar Fase 4): un solo
     * slot fijo en el storage interno de la app - se sobreescribe entero en cada Save y se carga
     * solo (ver loadProjectFromFile, llamada desde onSurfaceCreated) al abrir la app. Simplificacion
     * deliberada, mismo criterio que "un objeto seleccionado a la vez" o "un solo Undo stack": no
     * hay todavia ningun sistema de dialogos de archivos (listar/elegir/nombrar) en la app, asi que
     * multi-proyecto queda para mas adelante si hace falta.
     */
    private val projectFile: File
        get() = File(context.filesDir, "current_project.json")

    /** Carpeta de los binarios de mallas importadas (OBJ) del proyecto guardado (ver ProjectSerializer.writeObjMesh). */
    private val meshDir: File
        get() = File(context.filesDir, "imported_meshes")

    /**
     * Escribe la escena completa (ver sceneObjectsToJson) al slot fijo, pisando lo que hubiera.
     * Devuelve false (sin lanzar) si algo sale mal (IO, permisos) - el llamador (MainActivity)
     * usa esto para avisar con un Toast, mismo criterio que el resto de las acciones que pueden
     * fallar silenciosamente en la app.
     */
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
     * Auto-carga (ver onSurfaceCreated, se llama despues de dejar la escena default de un cubo ya
     * armada) - si existe un slot guardado y parsea bien (ver jsonToSceneObjects), reemplaza esa
     * escena default por la guardada. Si el archivo no existe (primera vez que se abre la app) o
     * esta corrupto, no hace nada y la escena default de un cubo queda como esta - mismo criterio
     * de "fallar en silencio hacia un estado usable" que el resto de la app.
     *
     * nextObjectId se recalcula como el maximo entre el guardado y (mayor id existente + 1) - a
     * prueba de un archivo viejo/editado a mano donde ese contador haya quedado desincronizado de
     * los objetos reales, para que un objeto nuevo despues de cargar nunca choque id con uno ya
     * cargado.
     */
    private fun loadProjectFromFile(): Boolean {
        if (!projectFile.exists()) return false
        val json = try { projectFile.readText() } catch (e: Exception) { return false }
        val parsed = jsonToSceneObjects(json, meshDir) ?: return false
        val (loadedObjects, loadedNextId) = parsed

        sceneObjects.clear()
        sceneObjects.addAll(loadedObjects)
        nextObjectId = maxOf(loadedNextId, (loadedObjects.maxOfOrNull { it.id } ?: -1) + 1)

        importedGeometries.clear()
        return true
    }

    /**
     * File > New: vuelve a la escena inicial de un solo cubo (mismo estado que onSurfaceCreated
     * al abrir la app por primera vez), pasando por Undo primero (mismo criterio que Delete/
     * Duplicate/Add: cualquier accion que borre estado del usuario se puede deshacer) - asi un New
     * accidental no es irreversible mientras no se haya cerrado la app. A proposito NO borra el
     * archivo del slot guardado (ver projectFile) - New solo afecta la escena en memoria; el
     * usuario decide si quiere Save despues para que ese vaciado tambien quede guardado.
     */
    fun newProject(): Boolean {
        pushUndoSnapshot()
        sceneObjects.clear()
        nextObjectId = 0
        sceneObjects.add(SceneObject(id = nextObjectId++, selected = true))
        return true
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

    // Like Blender: axis-aligned gizmo views snap to orthographic; free orbiting uses perspective.
    @Volatile var isOrthographic = false

    // Which axis the camera is currently looking down: 'Z' -> ground (XY) grid, 'Y' -> XZ wall, 'X' -> YZ wall.
    @Volatile var gridPlaneAxis = 'Z'

    /**
     * Que gizmo de transformacion por eje se dibuja sobre el objeto seleccionado - null si ninguno.
     * MainActivity lo setea segun la herramienta activa (ver setLayoutTool): Move -> GizmoMode.MOVE,
     * Rotate -> GizmoMode.ROTATE, Scale -> GizmoMode.SCALE.
     */
    @Volatile var gizmoMode: GizmoMode? = null

    /**
     * Eje (X/Y/Z) del anillo de rotacion que se esta arrastrando ahora mismo - null si no hay
     * arrastre en curso o si el arrastre es libre (no empezo tocando un anillo). MainActivity lo
     * sincroniza en onViewportDragStart/onViewportDragEnd (mismo valor que axisLocked alla, solo
     * que ahi es privado). Solo tiene efecto visual con gizmoMode == GizmoMode.ROTATE - ver
     * onDrawFrame y Gizmo3D.draw.
     */
    @Volatile var activeRotateAxis: Char? = null

    /**
     * Eje (X/Y/Z) de la flecha de Move que se esta arrastrando ahora mismo - null si no hay
     * arrastre en curso o si el arrastre es libre (no empezo tocando una flecha). Mismo criterio
     * que activeRotateAxis pero para GizmoMode.MOVE: MainActivity lo sincroniza en
     * onViewportDragStart/onViewportDragEnd (mismo valor que axisLocked alla). Solo tiene efecto
     * visual con gizmoMode == GizmoMode.MOVE - ver onDrawFrame y Gizmo3D.draw.
     */
    @Volatile var activeMoveAxis: Char? = null

    /**
     * Eje (X/Y/Z) del cubito de Scale que se esta arrastrando ahora mismo - null si no hay
     * arrastre en curso o si el arrastre es libre (no empezo tocando un cubito, ver
     * hitTestGizmoScaleAxis). Mismo criterio que activeMoveAxis/activeRotateAxis pero para
     * GizmoMode.SCALE: MainActivity lo sincroniza en onViewportDragStart/onViewportDragEnd. Solo
     * tiene efecto visual con gizmoMode == GizmoMode.SCALE - ver onDrawFrame y Gizmo3D.draw.
     */
    @Volatile var activeScaleAxis: Char? = null

    /**
     * Orientacion actual del gizmo activo (Move/Rotate/Scale) - ver enum TransformOrientation.
     * MainActivity la togglea desde el boton nuevo (ver toggleTransformOrientation) y la usa para
     * decidir el texto/resaltado de ese boton. Default LOCAL: es el comportamiento sin sorpresas
     * (mismo que ya tenia Scale antes de este selector) - el usuario puede pasar a GLOBAL cuando
     * quiera el gizmo siempre alineado al mundo, sabiendo el trade-off (ver comentario del enum).
     */
    @Volatile var transformOrientation: TransformOrientation = TransformOrientation.LOCAL

    /**
     * Direccion (mundo, normalizada) desde el centro del objeto hacia el punto donde el dedo tocO
     * el anillo al empezar el arrastre - se calcula una sola vez en hitTestGizmoRotateAxis cuando
     * el hit-test encuentra un eje, y se queda fija durante todo el gesto (no se actualiza en cada
     * frame). Ya NO la usa el dibujo de la marca de angulo (ver activeRotateCurrentDir, que la
     * reemplazo para que esa marca siga al dedo en vivo, como en Blender real - ver charla con el
     * usuario y su video de referencia) - queda solo como valor inicial de activeRotateCurrentDir
     * y para computeRotateLabelAnchor, que ubica la etiqueta de texto del eje una unica vez al
     * empezar el arrastre (no hace falta que se mueva en vivo, es secundario a la marca).
     */
    @Volatile var activeRotateStartDir: FloatArray? = null
    /** Igual que activeRotateStartDir pero recalculada en cada ACTION_MOVE (ver updateActiveRotateCurrentDir) para que la marca de angulo siga al dedo en vivo, como en Blender real - ver video de referencia del usuario. Arranca igual a activeRotateStartDir (ver onViewportDragStart) para no quedar en null antes del primer move. */
    @Volatile var activeRotateCurrentDir: FloatArray? = null

    // Camera distance from the origin (zoom).
    @Volatile var cameraDistance = 7.47f
        set(value) {
            field = value.coerceIn(2f, 20f)
        }

    // Pan offset: shifts the camera + its look-at target together, sideways on screen (world X / world Z).
    @Volatile var panX = 0.05f
    @Volatile var panZ = 0.24f

    /**
     * Tamano del gizmo en unidades de mundo, recalculado por distancia de camara para que se vea
     * de tamano constante en pantalla sin importar el zoom (mismo criterio que orthoSize en
     * onDrawFrame). Unica fuente de verdad para el dibujo (onDrawFrame) y los hit-test
     * (hitTestGizmoAxis, hitTestGizmoRotateAxis, hitTestGizmoScaleAxis) - si diverge, el gizmo se
     * ve en un lugar y se toca en otro.
     */
    private val gizmoScreenScale: Float
        get() = cameraDistance * 0.15f

    /**
     * Agrega un cubo nuevo a la escena y lo deja seleccionado (mismo criterio que selectObjectAt:
     * un solo objeto seleccionado a la vez). Por ahora es la unica primitiva con geometria real
     * (ver Cube.kt), asi que es la unica que Add > Mesh puede crear de verdad todavia.
     * Sin Cursor 3D (fuera de alcance por ahora), se agrega siempre en el origen - igual que
     * Blender agregaria en un 3D cursor que nunca se movio de (0,0,0).
     */
    fun addCube(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Agrega un modelo importado desde OBJ (ver ObjLoader) como objeto nuevo, ya seleccionado. */
    fun addImportedMesh(mesh: ObjMesh): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, selected = true, importedMesh = mesh)
        sceneObjects.add(newObject)
        return newObject
    }

    fun addPlane(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.PLANE, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addPlane, pero para Circle (ver Circle.kt / MeshType.CIRCLE). */
    fun addCircle(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.CIRCLE, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addCircle, pero para UV Sphere (ver UvSphere.kt / MeshType.UV_SPHERE). */
    fun addUvSphere(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.UV_SPHERE, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addUvSphere, pero para Ico Sphere (ver IcoSphere.kt / MeshType.ICO_SPHERE). */
    fun addIcoSphere(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.ICO_SPHERE, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addIcoSphere, pero para Cylinder (ver Cylinder.kt / MeshType.CYLINDER). */
    fun addCylinder(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.CYLINDER, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addCylinder, pero para Cone (ver Cone.kt / MeshType.CONE). */
    fun addCone(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.CONE, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addCone, pero para Torus (ver Torus.kt / MeshType.TORUS). */
    fun addTorus(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.TORUS, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /** Igual que addTorus, pero para Grid (ver GridMesh.kt / MeshType.GRID). */
    fun addGrid(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.GRID, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /**
     * Object > Show/Hide (Layout, ver charla con el usuario - acción única en vez del H/Alt+H/
     * Shift+H de Blender): toggle simple. Si hay un objeto seleccionado, lo oculta (deja de
     * dibujarse - ver onDrawFrame - y de ser alcanzable por raycast - ver selectObjectAt) y lo
     * deselecciona (un objeto oculto no puede quedar "seleccionado", mismo criterio que Blender).
     * Si no hay ninguno seleccionado pero existe al menos un objeto oculto, los muestra TODOS de
     * nuevo (equivalente simplificado de Alt+H - sin el Shift+H de "ocultar todo lo no
     * seleccionado", que no aporta con el modelo actual de un solo objeto seleccionado a la vez).
     * Devuelve false si no hay nada para hacer (nada seleccionado y nada oculto).
     */
    fun toggleShowHideSelected(): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected }
        if (selected != null) {
            pushUndoSnapshot()
            selected.visible = false
            selected.selected = false
            return true
        }
        if (sceneObjects.any { !it.visible }) {
            pushUndoSnapshot()
            for (obj in sceneObjects) obj.visible = true
            return true
        }
        return false
    }

    fun clearSelectedObjectTransform(): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        pushUndoSnapshot()
        selected.posX = 0f
        selected.posY = 0f
        selected.posZ = 0f
        Matrix.setIdentityM(selected.rotationMatrix, 0)
        Matrix.setIdentityM(selected.shapeMatrix, 0)
        return true
    }

    /**
     * Borra el objeto seleccionado (Object > Delete). Si no hay ninguno seleccionado, no hace
     * nada y devuelve false - el llamador (MainActivity.onObjectMenuAction) usa esto para avisar
     * si hizo falta. No hay confirmacion (a diferencia de Blender, que tampoco pide confirmacion
     * para Delete via el menu Object).
     */
    fun deleteSelectedObject(): Boolean {
        val index = sceneObjects.indexOfFirst { it.selected }
        if (index == -1) return false
        pushUndoSnapshot()
        sceneObjects.removeAt(index)
        return true
    }

    /**
     * Cuanto se corre en X el duplicado respecto del original (ver duplicateSelectedObject) -
     * unidades de mundo, mismo orden de magnitud que el tamano tipico de un objeto (0.5 de medio
     * lado en las primitivas base) para que se note claramente que hay dos objetos separados.
     */
    private val DUPLICATE_OFFSET_X = 0.6f
    /**
     * Duplica el objeto seleccionado (Object > Duplicate Objects): mismo tipo, misma rotacion y
     * forma (rotationMatrix/shapeMatrix CLONADAS con copyOf - si se copiara solo la referencia del
     * FloatArray, mover o escalar una copia moveria/escalaria la otra tambien, ya que
     * compartirian el mismo array por debajo). El duplicado nace con un offset chico en X para que
     * se vea que hay dos objetos separados (Blender en cambio deja el duplicado exactamente
     * encima y lo manda directo a modo Grab con el mouse - no tenemos ese gesto encadenado todavia,
     * asi que el offset fijo es la forma mas simple de que el resultado sea utilizable de una).
     * Deja el original deseleccionado y el duplicado seleccionado (igual que Blender). Devuelve
     * null (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun duplicateSelectedObject(): SceneObject? {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return null
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val duplicate = selected.copy(
            id = nextObjectId++,
            posX = selected.posX + DUPLICATE_OFFSET_X,
            rotationMatrix = selected.rotationMatrix.copyOf(),
            shapeMatrix = selected.shapeMatrix.copyOf(),
            selected = true
        )
        sceneObjects.add(duplicate)
        return duplicate
    }

    /** Igual que addGrid, pero para Monkey (ver Monkey.kt / MeshType.MONKEY). */
    fun addMonkey(): SceneObject {
        pushUndoSnapshot()
        for (obj in sceneObjects) obj.selected = false
        val newObject = SceneObject(id = nextObjectId++, type = MeshType.MONKEY, selected = true)
        sceneObjects.add(newObject)
        return newObject
    }

    /**
     * Calcula, para un delta de arrastre en pantalla, el delta equivalente en espacio mundo.
     * Extraido de moveSelectedObject para reusarlo tambien en moveSelectedObjectOnAxis (arrastre
     * restringido a un eje del gizmo) - misma logica en los dos casos, cambia solo que se hace
     * despues con el resultado.
     */
    private fun computeWorldDragDelta(dxScreen: Float, dyScreen: Float): FloatArray {
        // Misma escala que el pan de camara (ver MyGLSurfaceView), para que el movimiento se
        // sienta igual de "rapido" en pantalla sin importar el nivel de zoom actual.
        val moveScale = 0.01f * (cameraDistance / 6.5f)
        val rightAmount = dxScreen * moveScale
        val upAmount = -dyScreen * moveScale

        val rotation = FloatArray(16)
        Matrix.setIdentityM(rotation, 0)
        Matrix.rotateM(rotation, 0, angleX, 1f, 0f, 0f)
        Matrix.rotateM(rotation, 0, angleY, 0f, 0f, 1f)

        val inverseRotation = FloatArray(16)
        Matrix.transposeM(inverseRotation, 0, rotation, 0)

        val screenDelta = floatArrayOf(rightAmount, 0f, upAmount, 1f)
        val worldDelta = FloatArray(4)
        Matrix.multiplyMV(worldDelta, 0, inverseRotation, 0, screenDelta, 0)
        return worldDelta
    }

    /**
     * Direccion (en espacio mundo) hacia donde mira la camara, calculada de la misma forma que
     * computeWorldDragDelta: la camara nunca rota de verdad (lo que rota es el contenido via
     * rotationMatrix), asi que su forward local (+Y, ver setLookAtM en onDrawFrame) se lleva a
     * espacio mundo con la inversa de esa rotacion. Compartido por computeScreenTangentForAxis
     * para saber "desde donde se esta mirando" el gizmo de rotacion.
     */
    private fun computeWorldViewDirection(): FloatArray {
        val rotation = FloatArray(16)
        Matrix.setIdentityM(rotation, 0)
        Matrix.rotateM(rotation, 0, angleX, 1f, 0f, 0f)
        Matrix.rotateM(rotation, 0, angleY, 0f, 0f, 1f)
        val inverseRotation = FloatArray(16)
        Matrix.transposeM(inverseRotation, 0, rotation, 0)
        val localForward = floatArrayOf(0f, 1f, 0f, 0f)
        val worldForward = FloatArray(4)
        Matrix.multiplyMV(worldForward, 0, inverseRotation, 0, localForward, 0)
        return floatArrayOf(worldForward[0], worldForward[1], worldForward[2])
    }

    /**
     * Mueve el objeto seleccionado en el plano de camara (como arrastras en pantalla, igual que
     * G libre en Blender): dxScreen/dyScreen son deltas de pantalla en pixeles. Como la camara
     * nunca rota (lo que rota es el contenido via rotationMatrix, ver onDrawFrame), el eje derecha
     * de camara es siempre el eje X del mundo y el eje arriba de camara siempre el eje Z del mundo;
     * por eso el delta se arma directamente en esos ejes y se lo pasa por la rotacion inversa
     * (transpuesta, al ser una matriz de rotacion pura) para que el objeto - que si esta rotado -
     * termine moviendose en la direccion correcta sobre la pantalla.
     * Devuelve false (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun moveSelectedObject(dxScreen: Float, dyScreen: Float): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        val worldDelta = computeWorldDragDelta(dxScreen, dyScreen)
        selected.posX += worldDelta[0]
        selected.posY += worldDelta[1]
        selected.posZ += worldDelta[2]
        return true
    }

    /**
     * Igual que moveSelectedObject, pero proyecta el delta de mundo sobre un solo eje (X/Y/Z)
     * antes de aplicarlo - se usa cuando el arrastre empezo tocando una flecha del gizmo (ver
     * MainActivity.onViewportDragStart, que llama a hitTestGizmoAxis en ACTION_DOWN).
     * Devuelve false (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun moveSelectedObjectOnAxis(dxScreen: Float, dyScreen: Float, axis: Char): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        val worldDelta = computeWorldDragDelta(dxScreen, dyScreen)

        val axisDir = effectiveAxisDirection(axis, selected)
        val projected = worldDelta[0] * axisDir[0] + worldDelta[1] * axisDir[1] + worldDelta[2] * axisDir[2]

        selected.posX += axisDir[0] * projected
        selected.posY += axisDir[1] * projected
        selected.posZ += axisDir[2] * projected
        return true
    }

    private fun axisDirection(axis: Char): FloatArray = when (axis) {
        'X' -> floatArrayOf(1f, 0f, 0f)
        'Y' -> floatArrayOf(0f, 1f, 0f)
        else -> floatArrayOf(0f, 0f, 1f)
    }

    /**
     * Igual que axisDirection, pero rotada por la orientacion actual del objeto - se usa cuando
     * transformOrientation es LOCAL (ver effectiveAxisDirection) para que Move/Rotate/Scale por
     * eje sigan la orientacion propia del objeto en vez del mundo puro.
     */
    private fun rotatedAxisDirection(axis: Char, obj: SceneObject): FloatArray {
        val local = axisDirection(axis)
        val worldDir = FloatArray(4)
        Matrix.multiplyMV(worldDir, 0, obj.rotationMatrix, 0, floatArrayOf(local[0], local[1], local[2], 0f), 0)
        return floatArrayOf(worldDir[0], worldDir[1], worldDir[2])
    }

    /**
     * Unica fuente de verdad para "que direccion usa el eje X/Y/Z ahora mismo": world pura
     * (axisDirection) si transformOrientation es GLOBAL, rotada con el objeto (rotatedAxisDirection)
     * si es LOCAL. Reemplaza los usos sueltos de axisDirection/rotatedAxisDirection en hit-test,
     * dibujo y logica de arrastre de los 3 gizmos (Move/Rotate/Scale) - antes Move/Rotate estaban
     * hardcodeados a world y Scale a rotada; ahora los 3 respetan el mismo selector (ver enum
     * TransformOrientation). Si los usos no coincidieran en la misma direccion, el gizmo se veria
     * en un lugar, se tocaria en otro, o el arrastre se sentiria invertido - mismo criterio de
     * "unica fuente de verdad" que ya se aplica en gizmoScreenScale.
     */
    private fun effectiveAxisDirection(axis: Char, obj: SceneObject): FloatArray {
        return if (transformOrientation == TransformOrientation.LOCAL) rotatedAxisDirection(axis, obj) else axisDirection(axis)
    }

    /**
     * Rota el objeto seleccionado libre (sin eje restringido), con la misma convencion que la
     * orbita de camara: dx horizontal gira alrededor del eje Z del mundo, dy vertical gira
     * alrededor del eje X del mundo. Ambos deltas se aplican sobre la matriz de rotacion
     * acumulada del objeto (ver applyWorldRotationDelta) en vez de sumarse a angulos sueltos -
     * asi cada rotacion nueva se compone sobre el estado real actual, sin el bug de orden fijo que
     * tenia el esquema anterior de Euler (rotX/rotY/rotZ, ver charla con el usuario). Para rotacion
     * restringida a un solo eje (gizmo de anillos) ver rotateSelectedObjectOnAxis. Este gesto libre
     * es deliberadamente siempre mundo (no depende del selector de orientacion) - mismo criterio
     * que la orbita de camara, a la que imita.
     * Devuelve false (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun rotateSelectedObject(dxScreen: Float, dyScreen: Float): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        applyWorldRotationDelta(selected, dxScreen * 0.5f, 'Z')
        applyWorldRotationDelta(selected, dyScreen * 0.5f, 'X')
        return true
    }

    /**
     * Aplica una rotacion incremental (angleDeg grados alrededor del eje MUNDO dado) sobre la
     * matriz de rotacion acumulada del objeto - atajo de applyRotationDeltaAroundDir para cuando
     * el eje ya se conoce como char de mundo (rotateSelectedObject, gesto libre). Ver
     * applyRotationDeltaAroundDir para la logica real (pre-multiplicacion delta * actual, que
     * evita el bug de orden fijo de Euler ya resuelto - ver charla con el usuario).
     */
    private fun applyWorldRotationDelta(obj: SceneObject, angleDeg: Float, axis: Char) {
        applyRotationDeltaAroundDir(obj, angleDeg, axisDirection(axis))
    }

    /**
     * Aplica una rotacion incremental (angleDeg grados alrededor de la direccion dir dada, ya
     * resuelta) sobre la matriz de rotacion acumulada del objeto (ver SceneObject.rotationMatrix),
     * pre-multiplicando la matriz delta sobre la actual (delta * actual, no actual * delta). Ese
     * orden es lo que garantiza que la rotacion se acumule sobre el estado real actual del objeto,
     * sin el bug de orden fijo que tenia el esquema anterior de Euler (rotX/rotY/rotZ, ver charla
     * con el usuario): rotar en un eje "pisaba" visualmente lo ya rotado en otro, porque el
     * resultado final dependia del orden de composicion y no del orden real en que se toco cada eje.
     *
     * Recibe la direccion ya resuelta (no un char) para poder rotar alrededor del eje LOCAL cuando
     * transformOrientation es LOCAL (ver effectiveAxisDirection, usada por rotateSelectedObjectOnAxis)
     * o del eje MUNDO cuando es GLOBAL o para el gesto libre (ver applyWorldRotationDelta, que
     * siempre pasa axisDirection sin importar el selector - la rotacion libre imita la orbita de
     * camara y no depende de la orientacion elegida).
     */
    private fun applyRotationDeltaAroundDir(obj: SceneObject, angleDeg: Float, dir: FloatArray) {
        if (angleDeg == 0f) return
        val delta = FloatArray(16)
        Matrix.setIdentityM(delta, 0)
        Matrix.rotateM(delta, 0, angleDeg, dir[0], dir[1], dir[2])
        val result = FloatArray(16)
        Matrix.multiplyMM(result, 0, delta, 0, obj.rotationMatrix, 0)
        obj.rotationMatrix = result
    }

    /**
     * Rota el objeto seleccionado restringido a un solo eje (X/Y/Z) - se usa cuando el arrastre
     * empezo tocando un anillo del gizmo (ver MainActivity.onViewportDragStart, que llama a
     * hitTestGizmoRotateAxis en ACTION_DOWN). A diferencia de rotateSelectedObject (que reparte el
     * delta en dos ejes de forma fija), aca el delta de arrastre se proyecta sobre la
     * tangente en pantalla del circulo de rotacion de ese eje, evaluada en la direccion radial
     * actual del dedo (ver computeScreenTangentForRadialDir) en vez de una direccion fija -
     * mismo resultado visual para X/Z que el gesto libre, pero generalizado para que Y (que no
     * tiene una convencion fija de antes) tambien funcione, sin tener que casear por eje.
     *
     * axisDir se resuelve UNA vez via effectiveAxisDirection y se reusa tanto para la tangente
     * como para aplicar la rotacion (applyRotationDeltaAroundDir) - asi el anillo que ves (mundo o
     * local, segun transformOrientation) es exactamente el eje alrededor del cual se rota.
     * Devuelve true si el arrastre fue consumido (haya rotado algo o no - p.ej. si el eje quedo de
     * canto respecto de la camara, caso degenerado sin tangente definida).
     */
    fun rotateSelectedObjectOnAxis(dxScreen: Float, dyScreen: Float, axis: Char): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return true
        val center = floatArrayOf(selected.posX, selected.posY, selected.posZ)
        val radialDir = activeRotateCurrentDir ?: activeRotateStartDir ?: return true
        val axisDir = effectiveAxisDirection(axis, selected)
        val screenTangent = computeScreenTangentForRadialDir(center, axisDir, radialDir) ?: return true

        // Misma sensibilidad que el rotate libre (dx/dy * 0.5).
        val delta = (dxScreen * screenTangent[0] + dyScreen * screenTangent[1]) * 0.5f
        applyRotationDeltaAroundDir(selected, delta, axisDir)
        return true
    }

    /**
     * Direccion (normalizada, en pixeles de pantalla) en la que hay que arrastrar para rotar el
     * objeto alrededor de axisDir, evaluada en el punto radial actual bajo el dedo (ver
     * rotacion es perpendicular tanto a la direccion de vista como al eje (cross product), y se
     * proyecta a pantalla comparando dos puntos cercanos en esa direccion (ver
     * projectWorldToScreen). Null en el caso degenerado de que el eje quede de canto respecto de
     * la camara (viewDir paralelo a axisDir - el circulo se ve como una linea, sin tangente
     * definida) o si la proyeccion a pantalla no esta disponible todavia (primer frame).
     */
    private fun computeScreenTangentForRadialDir(center: FloatArray, axisDir: FloatArray, radialDir: FloatArray): FloatArray? {

        val tangent = cross(axisDir, radialDir)
        val tangentLen = sqrt(tangent[0] * tangent[0] + tangent[1] * tangent[1] + tangent[2] * tangent[2])
        if (tangentLen < 1e-4f) return null
        val tangentUnit = floatArrayOf(tangent[0] / tangentLen, tangent[1] / tangentLen, tangent[2] / tangentLen)

        val p0 = projectWorldToScreen(center[0], center[1], center[2]) ?: return null
        val p1 = projectWorldToScreen(
            center[0] + tangentUnit[0] * 0.05f,
            center[1] + tangentUnit[1] * 0.05f,
            center[2] + tangentUnit[2] * 0.05f
        ) ?: return null

        val screenDx = p1[0] - p0[0]
        val screenDy = p1[1] - p0[1]
        val screenLen = sqrt(screenDx * screenDx + screenDy * screenDy)
        if (screenLen < 1e-4f) return null
        return floatArrayOf(screenDx / screenLen, screenDy / screenLen)
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
    }

    /**
     * Proyecta un punto de mundo a coordenadas de pantalla (pixeles, mismo sistema y-hacia-abajo
     * que usan los eventos de touch) - inverso de screenPointToRay. Usa la matriz camara+orbita
     * del ultimo frame dibujado (scratch), igual que screenPointToRay/hitTestGizmoAxis.
     */
    private fun projectWorldToScreen(worldX: Float, worldY: Float, worldZ: Float): FloatArray? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, scratch, 0)
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, vpMatrix, 0, floatArrayOf(worldX, worldY, worldZ, 1f), 0)
        if (abs(clip[3]) < 1e-6f) return null
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        val screenX = (ndcX + 1f) * 0.5f * viewportWidth
        val screenY = (1f - ndcY) * 0.5f * viewportHeight
        return floatArrayOf(screenX, screenY)
    }

    /**
     * Punto de pantalla donde debe anclarse la etiqueta de texto (X/Y/Z) del anillo de rotacion
     * activo (ver GizmoLabelView) - el extremo de la linea punteada de angulo de arranque (centro
     * del objeto + direccion de arranque * radio del anillo, un poco mas afuera para no pisarse
     * con la linea). Se llama una sola vez al empezar el arrastre (ver
     * MainActivity.onViewportDragStart) porque esa direccion (activeRotateStartDir) queda fija
     * durante todo el gesto - no hace falta recalcular cada frame. Null si no hay objeto
     * seleccionado, no hay direccion de arranque guardada, o la proyeccion a pantalla no esta
     * disponible todavia.
     */
    fun computeRotateLabelAnchor(): FloatArray? {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return null
        val dir = activeRotateStartDir ?: return null
        val pivot = gizmoPivotWorld(selected); val ringWorldRadius = gizmoScreenScale * Gizmo3D.RING_RADIUS * 1.15f
        return projectWorldToScreen(
            pivot[0] + dir[0] * ringWorldRadius,
            pivot[1] + dir[1] * ringWorldRadius,
            pivot[2] + dir[2] * ringWorldRadius
        )
    }

    /**
     * Escala el objeto seleccionado libre (sin eje restringido, ver GizmoMode.SCALE con
     * activeScaleAxis == null - se usa tanto desde el trackball blanco como desde el gesto de
     * Layout sin gizmo): arrastre vertical (dyScreen) cambia la escala uniforme - arriba (dy
     * negativo) agranda, abajo achica. Al ser uniforme, multiplica TODA la matriz de forma del
     * objeto (shapeMatrix) por el mismo factor - mantiene la proporcion (y cualquier shear que ya
     * tuviera) del objeto.
     *
     * El factor se clampea UNA sola vez, antes de aplicarlo (ver clampUniformScaleFactor), en vez
     * de clampear cada eje por separado despues de multiplicar (bug arreglado: si el objeto ya
     * habia quedado no-uniforme por un escalado previo con el gizmo de un solo eje, y un eje
     * estaba mas cerca del limite que los otros, clampear cada eje por separado hacia que ESE eje
     * se "congelara" en el limite mientras los otros seguian cambiando - rompiendo la uniformidad
     * que se supone que este gesto preserva). Clampeando el factor comun antes, o los 3 ejes
     * cambian igual (mientras haya margen) o ninguno cambia (si alguno ya esta en el limite) -
     * nunca una mezcla de las dos cosas.
     * Devuelve false (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun scaleSelectedObject(dyScreen: Float): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        val rawFactor = 1f - dyScreen * 0.005f
        val scaleFactor = clampUniformScaleFactor(rawFactor, selected)
        scaleMatrixLinearPart(selected.shapeMatrix, scaleFactor)
        return true
    }

    /**
     * Multiplica la parte lineal (3x3) de una matriz 4x4 por un escalar comun - deja la fila/
     * columna homogenea sin tocar (shapeMatrix nunca lleva traslacion propia, ver SceneObject).
     * Escalado uniforme: multiplicar TODA la matriz por igual conmuta con cualquier shear que ya
     * hubiera (a diferencia del escalado por eje, que si depende de la direccion - ver
     * applyLocalDirScale), asi que no hace falta descomponer nada.
     */
    private fun scaleMatrixLinearPart(mat: FloatArray, factor: Float) {
        for (col in 0 until 3) {
            for (row in 0 until 3) {
                mat[col * 4 + row] *= factor
            }
        }
    }

    /**
     * Recorta rawFactor (el multiplicador que se aplicaria por igual a toda la matriz de forma)
     * para que, aplicado, ningun eje termine fuera de [0.1, 10] - sin importar que tan desparejos
     * esten los 3 ejes entre si ya de antes (columnLength generaliza lo que antes eran
     * scaleX/Y/Z sueltos). Agrandando (rawFactor > 1): el eje mas grande es el que primero
     * llegaria a 10, asi que el factor maximo permitido es el minimo de 10/largoEje entre los 3.
     * Achicando (rawFactor < 1): el eje mas chico es el que primero llegaria a 0.1, asi que el
     * factor minimo permitido es el maximo de 0.1/largoEje entre los 3.
     * Ver scaleSelectedObject para por que esto reemplaza el clamp por eje de antes.
     */
    private fun clampUniformScaleFactor(rawFactor: Float, obj: SceneObject): Float {
        val lx = columnLength(obj.shapeMatrix, 0)
        val ly = columnLength(obj.shapeMatrix, 1)
        val lz = columnLength(obj.shapeMatrix, 2)
        return when {
            rawFactor > 1f -> minOf(rawFactor, 10f / lx, 10f / ly, 10f / lz)
            rawFactor < 1f -> maxOf(rawFactor, 0.1f / lx, 0.1f / ly, 0.1f / lz)
            else -> rawFactor
        }
    }

    /**
     * Largo de una columna (0=X, 1=Y, 2=Z) de una matriz 4x4 - generaliza scaleX/scaleY/scaleZ
     * (que antes eran exactamente este valor, al ser shapeMatrix diagonal pura): cada columna es
     * donde termina el eje local correspondiente despues de aplicar la matriz, asi que su largo es
     * "cuanto se estira" el objeto en esa direccion. Compartido por los clamps de escala y por
     * intersectAABB (bounding box aproximado para seleccion).
     */
    private fun columnLength(mat: FloatArray, col: Int): Float {
        val x = mat[col * 4 + 0]
        val y = mat[col * 4 + 1]
        val z = mat[col * 4 + 2]
        return sqrt(x * x + y * y + z * z)
    }

    /**
     * Igual que scaleSelectedObject, pero aplica el factor SOLO a lo largo de la direccion del eje
     * dado - se usa cuando el arrastre empezo tocando el cubito de un eje del gizmo de Scale (ver
     * MainActivity.onViewportDragStart, que llama a hitTestGizmoScaleAxis en ACTION_DOWN).
     * Se proyecta el arrastre completo (dx, dy) sobre la direccion en pantalla del eje (ver
     * projectDragOntoAxisScreenDir) - mismo criterio que moveSelectedObjectOnAxis (proyeccion en
     * espacio mundo) y rotateSelectedObjectOnAxis (proyeccion sobre la tangente en pantalla):
     * "arrastrar en la direccion en que apunta el cubito" agranda, en la direccion contraria
     * achica, sin importar si el eje se ve horizontal, vertical o en diagonal en pantalla para el
     * angulo de camara actual.
     *
     * La direccion de escalado (effectiveAxisDirection: eje mundo puro en Global, eje rotado en
     * Local) se convierte a espacio local del objeto (worldDirToLocalDir) antes de aplicarse sobre
     * shapeMatrix (ver applyLocalDirScale) - shapeMatrix es una matriz completa, no 3 floats
     * sueltos, asi que SI puede representar el resultado real: en Local (o con el objeto sin
     * rotar) da lo mismo que antes (escala pura por eje, verificado a mano); en Global con el
     * objeto rotado, el eje mundo no coincide con ningun eje propio del objeto y el resultado es
     * un shear real - mismo comportamiento que Blender (ver charla con el usuario y su video de
     * referencia, donde se confirmo el bug: antes esto se escribia siempre en el eje LOCAL,
     * como si el objeto no estuviera rotado).
     * Devuelve false (y no hace nada) si no hay ningun objeto seleccionado.
     */
    fun scaleSelectedObjectOnAxis(dxScreen: Float, dyScreen: Float, axis: Char): Boolean {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return false
        val dragAmount = projectDragOntoAxisScreenDir(dxScreen, dyScreen, axis, selected)
        val rawFactor = 1f + dragAmount * 0.005f
        val localDir = worldDirToLocalDir(effectiveAxisDirection(axis, selected), selected)
        val factor = clampAxisScaleFactor(rawFactor, localDir, selected)
        applyLocalDirScale(selected, localDir, factor)
        return true
    }

    /**
     * Convierte una direccion de MUNDO a espacio LOCAL del objeto, deshaciendo su rotationMatrix
     * (rotacion pura - la inversa es la transpuesta). Usada por scaleSelectedObjectOnAxis: la
     * direccion por la que se escala (effectiveAxisDirection, ya sea eje mundo puro en Global o
     * eje rotado en Local) tiene que pasarse a espacio local antes de aplicarla sobre shapeMatrix,
     * que vive ANTES de rotationMatrix en la composicion (ver SceneObject).
     */
    private fun worldDirToLocalDir(worldDir: FloatArray, obj: SceneObject): FloatArray {
        val inverseRotation = FloatArray(16)
        Matrix.transposeM(inverseRotation, 0, obj.rotationMatrix, 0)
        val result = FloatArray(4)
        Matrix.multiplyMV(result, 0, inverseRotation, 0, floatArrayOf(worldDir[0], worldDir[1], worldDir[2], 0f), 0)
        return floatArrayOf(result[0], result[1], result[2])
    }

    /**
     * Cuanto esta estirado el objeto ahora mismo en una direccion LOCAL dada (unitaria): aplica
     * shapeMatrix a esa direccion y mide el largo del resultado - generaliza columnLength (que es
     * el caso particular de una direccion que coincide exactamente con un eje local) a cualquier
     * direccion, incluida una diagonal entre varios ejes locales (caso Global con el objeto
     * rotado). Usada por clampAxisScaleFactor para poner los mismos limites [0.1, 10] que ya
     * existian, ahora tambien cuando la direccion de escalado no es un eje local puro.
     */
    private fun directionalScaleMagnitude(shape: FloatArray, localDir: FloatArray): Float {
        var vx = 0f
        var vy = 0f
        var vz = 0f
        for (col in 0 until 3) {
            vx += shape[col * 4 + 0] * localDir[col]
            vy += shape[col * 4 + 1] * localDir[col]
            vz += shape[col * 4 + 2] * localDir[col]
        }
        return sqrt(vx * vx + vy * vy + vz * vz)
    }

    /** Mismo criterio de clamp [0.1, 10] que clampUniformScaleFactor, pero medido a lo largo de una direccion local especifica (ver directionalScaleMagnitude) en vez de por columna pura - equivalente exacto cuando localDir es un eje puro. */
    private fun clampAxisScaleFactor(rawFactor: Float, localDir: FloatArray, obj: SceneObject): Float {
        val currentMag = directionalScaleMagnitude(obj.shapeMatrix, localDir)
        if (currentMag < 1e-6f) return rawFactor
        return when {
            rawFactor > 1f -> minOf(rawFactor, 10f / currentMag)
            rawFactor < 1f -> maxOf(rawFactor, 0.1f / currentMag)
            else -> rawFactor
        }
    }

    /**
     * Aplica un escalado con factor `factor` a lo largo de una direccion LOCAL unitaria dada
     * (localDir, ya convertida via worldDirToLocalDir), modificando shapeMatrix del objeto -
     * formula estandar de escalado direccional: S' = S + (factor - 1) * localDir * (localDir^T * S).
     *
     * Si localDir coincide con un eje local puro (Local, o Global con el objeto sin rotar), esto
     * da EXACTAMENTE lo mismo que escalar ese eje a secas (equivalente matematico al viejo
     * `selected.scaleX *= factor`, verificado a mano) - cero cambio de comportamiento ahi.
     *
     * Si localDir queda repartida entre varios ejes locales (Global con el objeto rotado, ver
     * worldDirToLocalDir), la formula mete terminos fuera de la diagonal en shapeMatrix (shear) -
     * es lo que reproduce el comportamiento real de Blender al escalar en Global un objeto rotado
     * (ver charla con el usuario, video de referencia): el objeto se deforma en diagonal en vez de
     * estirarse derecho, porque el eje mundo que se esta arrastrando no coincide con ningun eje
     * propio del objeto.
     */
    private fun applyLocalDirScale(obj: SceneObject, localDir: FloatArray, factor: Float) {
        val shape = obj.shapeMatrix
        // rowProjected[col] = localDir . (columna `col` de shape) = localDir^T * S, fila 1x3.
        val rowProjected = FloatArray(3)
        for (col in 0 until 3) {
            rowProjected[col] = localDir[0] * shape[col * 4 + 0] + localDir[1] * shape[col * 4 + 1] + localDir[2] * shape[col * 4 + 2]
        }
        val delta = factor - 1f
        for (col in 0 until 3) {
            for (row in 0 until 3) {
                shape[col * 4 + row] += delta * localDir[row] * rowProjected[col]
            }
        }
    }

    /**
     * Proyecta el arrastre completo (dx, dy) sobre la direccion en pantalla en que apunta un eje
     * dado, para el objeto dado - se calcula proyectando el centro del objeto y un punto un poco
     * mas alla en la direccion del eje a pantalla (ver projectWorldToScreen), y comparando
     * esos dos puntos. Fallback a -dyScreen (comportamiento viejo) si la proyeccion no esta
     * disponible todavia (primer frame) o el eje quedo de canto respecto de la camara (proyectado
     * a un solo punto en pantalla, sin direccion definida).
     *
     * Usa effectiveAxisDirection (no axisDirection ni rotatedAxisDirection sueltos) - la direccion
     * en pantalla usada para medir el arrastre tiene que ser la misma que se ve dibujada (ver
     * onDrawFrame), o el arrastre se sentiria invertido o en un angulo que no corresponde al
     * cubito que se ve en pantalla (bug original reportado y confirmado con el usuario, corregido).
     */
    private fun projectDragOntoAxisScreenDir(dxScreen: Float, dyScreen: Float, axis: Char, obj: SceneObject): Float {
        val axisDir = effectiveAxisDirection(axis, obj)
        val p0 = projectWorldToScreen(obj.posX, obj.posY, obj.posZ)
        val p1 = projectWorldToScreen(
            obj.posX + axisDir[0] * 0.05f,
            obj.posY + axisDir[1] * 0.05f,
            obj.posZ + axisDir[2] * 0.05f
        )
        if (p0 == null || p1 == null) return -dyScreen

        val screenDx = p1[0] - p0[0]
        val screenDy = p1[1] - p0[1]
        val screenLen = sqrt(screenDx * screenDx + screenDy * screenDy)
        if (screenLen < 1e-4f) return -dyScreen

        return (dxScreen * (screenDx / screenLen)) + (dyScreen * (screenDy / screenLen))
    }

    fun zoomIn() {
        cameraDistance -= cameraDistance * 0.15f
    }

    fun zoomOut() {
        cameraDistance += cameraDistance * 0.15f
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.11f, 0.11f, 0.11f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        cubeGeometry = Cube()
        planeGeometry = Plane()
        circleGeometry = Circle()
        uvSphereGeometry = UvSphere()
        icoSphereGeometry = IcoSphere()
        cylinderGeometry = Cylinder()
        coneGeometry = Cone()
        torusGeometry = Torus()
        gridMeshGeometry = GridMesh()
        monkeyGeometry = Monkey()
        gridXY = Grid(GridPlane.XY)
        gridXZ = Grid(GridPlane.XZ)
        gridYZ = Grid(GridPlane.YZ)
        gizmo = Gizmo3D()
        importedGeometries.clear()
        // Contexto GL recreado (por ejemplo al volver del selector de archivos): la escena ya existe en memoria, no se recarga ni se reinicia.
        if (sceneObjects.isNotEmpty()) return

        // File > auto-load (ver loadProjectFromFile): al recrearse la superficie GL, intenta
        // cargar el slot guardado ANTES de armar la escena default de un cubo - si hay algo
        // guardado, esta funcion ya deja sceneObjects poblado y el resto de este metodo (mas
        // abajo) se saltea via el return de abajo, para no pisar lo recien cargado con el cubo
        // default. Si no hay nada guardado, sceneObjects sigue vacio aca (todavia no se toco) y
        // el flujo sigue de largo hacia la creacion del cubo default de siempre.
        if (loadProjectFromFile()) return

        sceneObjects.clear()
        sceneObjects.add(SceneObject(id = nextObjectId++, selected = true))
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
            Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 2f, 30f)
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

        val grid = when (gridPlaneAxis) {
            'X' -> gridYZ
            'Y' -> gridXZ
            else -> gridXY
        }
        if (showGrid) grid.draw(mvpMatrix)

        // Cada objeto se dibuja con su propia matriz (mvpMatrix comun de camara + transform propia:
        // traslacion, rotacion acumulada (obj.rotationMatrix, ver SceneObject) y forma (obj.shapeMatrix,
        // escala y, cuando corresponde, shear) - en ese orden, la forma primero para que quede local
        // al objeto antes de rotar/trasladar.
        val translateMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        val shapedModelMatrix = FloatArray(16)
        val objMvpMatrix = FloatArray(16)
        // Normales de modelos importados en espacio de vista (ver TexturedMeshGeometry): vista * rotacion de camara * rotacion del objeto.
        val viewRotationMatrix = FloatArray(16)
        Matrix.multiplyMM(viewRotationMatrix, 0, viewMatrix, 0, rotationMatrix, 0)
        val importedNormalMatrix = FloatArray(16)
        for (obj in sceneObjects) {
            // Object > Show/Hide (ver toggleShowHideSelected): un objeto oculto no se dibuja,
            // mismo criterio que Blender (deja de existir visualmente hasta volver a mostrarlo).
            if (!obj.visible) continue
            Matrix.setIdentityM(translateMatrix, 0)
            Matrix.translateM(translateMatrix, 0, obj.posX, obj.posY, obj.posZ)
            Matrix.multiplyMM(modelMatrix, 0, translateMatrix, 0, obj.rotationMatrix, 0)
            Matrix.multiplyMM(shapedModelMatrix, 0, modelMatrix, 0, obj.shapeMatrix, 0)
            Matrix.multiplyMM(objMvpMatrix, 0, mvpMatrix, 0, shapedModelMatrix, 0)
            // Modelo importado desde OBJ (ver addImportedMesh): su geometria con textura se crea la primera vez que se dibuja
            // (aca, en el hilo de render) y se reutiliza despues, por id de objeto.
            val importedMesh = obj.importedMesh
            if (importedMesh != null) {
                Matrix.multiplyMM(importedNormalMatrix, 0, viewRotationMatrix, 0, obj.rotationMatrix, 0)
                importedGeometries.getOrPut(importedMesh) { TexturedMeshGeometry(importedMesh) }.draw(objMvpMatrix, obj.selected, importedNormalMatrix)
                continue
            }
            when (obj.type) {
                MeshType.CUBE -> cubeGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.PLANE -> planeGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.CIRCLE -> circleGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.UV_SPHERE -> uvSphereGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.ICO_SPHERE -> icoSphereGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.CYLINDER -> cylinderGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.CONE -> coneGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.TORUS -> torusGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.GRID -> gridMeshGeometry.draw(objMvpMatrix, obj.selected)
                MeshType.MONKEY -> monkeyGeometry.draw(objMvpMatrix, obj.selected)
            }
        }

        // Gizmo de transformacion sobre el objeto seleccionado: la orientacion (mundo fijo vs
        // rotado con el objeto) la decide transformOrientation (ver enum TransformOrientation y
        // el selector Global/Local en MainActivity) - aplica por igual a los 3 modos (Move/Rotate/
        // Scale), no solo a Scale como antes de agregar el selector. gizmoMode decide si se
        // dibujan flechas (Move), anillos (Rotate) o cubitos (Scale) - ver MainActivity.setLayoutTool.
        // activeRotateAxis/activeMoveAxis/activeScaleAxis (sincronizados desde MainActivity.axisLocked
        // segun la herramienta activa) deciden, dentro de cada modo, si se resalta un solo eje
        // agarrado o los 3 en su modo normal.
        val mode = gizmoMode
        if (mode != null) {
            val selectedObj = sceneObjects.firstOrNull { it.selected }
            if (selectedObj != null) {
                val gizmoPivot = gizmoPivotWorld(selectedObj)
                val gizmoModel = FloatArray(16)
                Matrix.setIdentityM(gizmoModel, 0)
                Matrix.translateM(gizmoModel, 0, gizmoPivot[0], gizmoPivot[1], gizmoPivot[2])
                // LOCAL: el gizmo rota junto con el objeto, para que lo que ves coincida siempre
                // con lo que va a pasar al arrastrar (ver comentario del enum TransformOrientation
                // sobre el trade-off con GLOBAL). GLOBAL: se deja el gizmo sin rotar (comportamiento
                // de mas abajo, sin este bloque) - se ve "prolijo" pero, para Scale con el objeto
                // rotado, el resultado real puede deformar en diagonal (charlado con el usuario).
                if (transformOrientation == TransformOrientation.LOCAL) {
                    val rotatedGizmoModel = FloatArray(16)
                    Matrix.multiplyMM(rotatedGizmoModel, 0, gizmoModel, 0, selectedObj.rotationMatrix, 0)
                    System.arraycopy(rotatedGizmoModel, 0, gizmoModel, 0, 16)
                }
                Matrix.scaleM(gizmoModel, 0, gizmoScreenScale, gizmoScreenScale, gizmoScreenScale)
                val gizmoMvpMatrix = FloatArray(16)
                Matrix.multiplyMM(gizmoMvpMatrix, 0, mvpMatrix, 0, gizmoModel, 0)
                val activeAxisForMode = when (mode) {
                    GizmoMode.ROTATE -> activeRotateAxis
                    GizmoMode.MOVE -> activeMoveAxis
                    GizmoMode.SCALE -> activeScaleAxis
                }
                gizmo.draw(
                    gizmoMvpMatrix,
                    mode,
                    if (mode == GizmoMode.ROTATE) computeWorldViewDirection() else null,
                    activeAxisForMode
                )

                // Anillo trackball (blanco, en Rotate y Scale, solo sin eje activo - con un eje
                // agarrado el trackball se oculta, igual que Blender oculta el resto del gizmo
                // cuando estas arrastrando un eje puntual): billboard, siempre de cara a la camara
                // sin importar la orbita - se logra multiplicando por la inversa de rotationMatrix
                // (transpuesta, al ser una rotacion pura) antes de escalar. En Rotate representa la
                // rotacion libre (rotateSelectedObject); en Scale representa el escalado uniforme
                // libre (scaleSelectedObject) - mismo anillo reusado como referencia visual para
                // los dos gestos "sin eje" (ver charla con el usuario). El trackball es rotation-
                // invariant (billboard de camara, no de objeto) porque el escalado uniforme libre
                // no tiene el problema de Scale por eje (escalar los 3 ejes por igual conmuta con
                // cualquier rotacion), asi que no necesita el mismo ajuste que el bloque de arriba.
                val showTrackball = (mode == GizmoMode.ROTATE && activeRotateAxis == null) ||
                    (mode == GizmoMode.SCALE && activeScaleAxis == null)
                if (showTrackball) {
                    val inverseOrbit = FloatArray(16)
                    Matrix.transposeM(inverseOrbit, 0, rotationMatrix, 0)

                    val translatePart = FloatArray(16)
                    Matrix.setIdentityM(translatePart, 0)
                    Matrix.translateM(translatePart, 0, gizmoPivot[0], gizmoPivot[1], gizmoPivot[2])

                    val trackballModel = FloatArray(16)
                    Matrix.multiplyMM(trackballModel, 0, translatePart, 0, inverseOrbit, 0)
                    Matrix.scaleM(trackballModel, 0, gizmoScreenScale, gizmoScreenScale, gizmoScreenScale)

                    val trackballMvpMatrix = FloatArray(16)
                    Matrix.multiplyMM(trackballMvpMatrix, 0, mvpMatrix, 0, trackballModel, 0)
                    gizmo.drawTrackballRing(trackballMvpMatrix)
                }

                // Con un anillo agarrado: linea infinita del eje + marca de angulo en vivo (sigue
                // al dedo) + crucecita del pivote - las 3 piezas extra que replican la referencia
                // visual de Blender (ver video de referencia del usuario). Todo en el color propio
                // del eje activo. La marca de angulo usa activeRotateCurrentDir (recalculada en
                // cada ACTION_MOVE, ver MainActivity.onViewportDragMove) en vez del viejo
                // activeRotateStartDir fijo, con fallback a este ultimo para el primer frame del
                // gesto (antes del primer move) o en el caso degenerado de que
                // updateActiveRotateCurrentDir no haya podido calcular una direccion ese frame.
                if (mode == GizmoMode.ROTATE && activeRotateAxis != null) {
                    // (pivot fix aplicado abajo via mutacion de center/segStart, ver hitTestGizmoRotateAxis)
                    val axisChar = activeRotateAxis!!
                    val axisColor = gizmo.colorForAxis(axisChar)

                    // Linea infinita: traslacion al objeto SIN el escalado de gizmoScreenScale
                    // (su longitud se maneja en unidades de mundo reales dentro de Gizmo3D).
                    val translateOnlyModel = FloatArray(16)
                    Matrix.setIdentityM(translateOnlyModel, 0)
                    Matrix.translateM(translateOnlyModel, 0, selectedObj.posX, selectedObj.posY, selectedObj.posZ)
                    val lineMvpMatrix = FloatArray(16)
                    Matrix.multiplyMM(lineMvpMatrix, 0, mvpMatrix, 0, translateOnlyModel, 0)
                    gizmo.drawInfiniteAxisLine(lineMvpMatrix, effectiveAxisDirection(axisChar, selectedObj), axisColor)

                    val liveDir = activeRotateCurrentDir ?: activeRotateStartDir
                    if (liveDir != null) {
                        gizmo.drawLiveAngleMarker(gizmoMvpMatrix, liveDir, axisColor)
                    }
                    gizmo.drawCenterCrosshair(gizmoMvpMatrix)
                }

                // Con una flecha de Move agarrada: linea infinita del eje + crucecita del pivote -
                // mismo criterio visual que el bloque de arriba para Rotate, pero sin marca de
                // angulo (esa es propia de una rotacion, no aplica a un movimiento). Ver charla
                // con el usuario - pidio el mismo comportamiento "solo un eje + linea infinita"
                // que ya tenia Rotate, tambien para Move.
                if (mode == GizmoMode.MOVE && activeMoveAxis != null) {
                    val axisChar = activeMoveAxis!!
                    val axisColor = gizmo.colorForAxis(axisChar)

                    val translateOnlyModel = FloatArray(16)
                    Matrix.setIdentityM(translateOnlyModel, 0)
                    Matrix.translateM(translateOnlyModel, 0, selectedObj.posX, selectedObj.posY, selectedObj.posZ)
                    val lineMvpMatrix = FloatArray(16)
                    Matrix.multiplyMM(lineMvpMatrix, 0, mvpMatrix, 0, translateOnlyModel, 0)
                    gizmo.drawInfiniteAxisLine(lineMvpMatrix, effectiveAxisDirection(axisChar, selectedObj), axisColor)

                    gizmo.drawCenterCrosshair(gizmoMvpMatrix)
                }

                // Con un cubito de Scale agarrado: mismo criterio que Move (linea infinita del eje
                // + crucecita del pivote, sin marca de angulo - eso es propio de Rotate). Usa
                // effectiveAxisDirection (no rotatedAxisDirection a secas) - la linea infinita
                // tiene que seguir la misma direccion que el resto del gizmo de Scale en este
                // frame (mundo o local, segun transformOrientation), o quedaria apuntando a un
                // lado distinto del cubito que se ve en pantalla.
                if (mode == GizmoMode.SCALE && activeScaleAxis != null) {
                    val axisChar = activeScaleAxis!!
                    val axisColor = gizmo.colorForAxis(axisChar)

                    val translateOnlyModel = FloatArray(16)
                    Matrix.setIdentityM(translateOnlyModel, 0)
                    Matrix.translateM(translateOnlyModel, 0, selectedObj.posX, selectedObj.posY, selectedObj.posZ)
                    val lineMvpMatrix = FloatArray(16)
                    Matrix.multiplyMM(lineMvpMatrix, 0, mvpMatrix, 0, translateOnlyModel, 0)
                    gizmo.drawInfiniteAxisLine(lineMvpMatrix, effectiveAxisDirection(axisChar, selectedObj), axisColor)

                    gizmo.drawCenterCrosshair(gizmoMvpMatrix)
                }
            }
        }
    }

    /**
     * Convierte un punto de pantalla (coordenadas de vista, no NDC) en un rayo 3D (origen +
     * direccion), usando la matriz camara+orbita del ultimo frame dibujado (scratch). Compartido
     * por selectObjectAt (seleccion de objetos) y los hit-test del gizmo (hitTestGizmoAxis,
     * hitTestGizmoRotateAxis, hitTestGizmoScaleAxis).
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

    // ---- Pintura sobre modelos importados (herramienta Paint, ver MainActivity.LayoutTool.PAINT) ----

    /** false mientras la herramienta Paint esta activa: se oculta la grilla del piso para no estorbar al pintar. */
    var showGrid: Boolean = true

    /** Pincel de color fijo (RGB) y radio en texeles de la textura - por ahora sin selector de color ni de tamano. */
    private val paintColor = intArrayOf(225, 70, 60)
    private val paintRadius = 18f
    /** Ultimo punto de pantalla del trazo en curso, para rellenar los huecos entre eventos de toque (ver paintMove). */
    private var lastPaintX = 0f
    private var lastPaintY = 0f

    /** Empieza un trazo: pinta un toque en (screenX, screenY). IMPORTANTE: llamar desde el hilo de render (glView.queueEvent). */
    fun paintStart(screenX: Float, screenY: Float) {
        lastPaintX = screenX
        lastPaintY = screenY
        paintAt(screenX, screenY)
    }

    /**
     * Continua el trazo hasta (screenX, screenY): rellena el camino desde el ultimo punto con toques cada ~6 px de
     * pantalla, asi un arrastre rapido no deja un punteado. IMPORTANTE: llamar desde el hilo de render.
     */
    fun paintMove(screenX: Float, screenY: Float) {
        val dx = screenX - lastPaintX
        val dy = screenY - lastPaintY
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val steps = maxOf(1, (dist / 6f).toInt())
        for (i in 1..steps) {
            paintAt(lastPaintX + dx * i / steps, lastPaintY + dy * i / steps)
        }
        lastPaintX = screenX
        lastPaintY = screenY
    }

    /**
     * Un toque de pincel en un punto de pantalla: convierte el punto en rayo, lo lleva al espacio local de cada modelo
     * importado visible (inversa de su matriz de modelo), busca el triangulo mas cercano que el rayo atraviesa (ver
     * TexturedMeshGeometry.pickUv) y pinta ahi, en la textura de ese modelo. Ignora los modelos que todavia no se
     * dibujaron ni una vez (su geometria con textura se crea en el primer frame, ver onDrawFrame).
     */
    private fun paintAt(screenX: Float, screenY: Float) {
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return
        var bestT = Float.MAX_VALUE
        var bestGeo: TexturedMeshGeometry? = null
        var bestU = 0f
        var bestV = 0f
        for (obj in sceneObjects) {
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
        bestGeo?.paintDab(bestU, bestV, paintRadius, paintColor[0], paintColor[1], paintColor[2])
    }

    /**
     * Deselecciona todos los objetos (Layout > Select > None) - mismo criterio que selectObjectAt
     * tocando espacio vacio. No pasa por Undo (igual que selectObjectAt/hitTestGizmoRotateAxis):
     * la seleccion se trata como estado de UI, no como una edicion de la escena - Blender tampoco
     * mete los cambios de seleccion en su historial de Undo principal.
     */
    fun deselectAll() {
        for (obj in sceneObjects) obj.selected = false
    }

    /**
     * Convierte un tap en pantalla en un rayo 3D y selecciona el objeto mas cercano que
     * intersecta. Si no hay hit, deselecciona todo (igual que tocar espacio vacio en Blender).
     */
    fun selectObjectAt(screenX: Float, screenY: Float) {
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return

        var hitObject: SceneObject? = null
        var closestT = Float.MAX_VALUE
        for (obj in sceneObjects) {
            if (!obj.visible) continue
            val t = intersectAABB(rayOrigin, rayDir, obj.posX, obj.posY, obj.posZ, obj.shapeMatrix)
            if (t != null && t < closestT) {
                closestT = t
                hitObject = obj
            }
        }

        for (obj in sceneObjects) {
            obj.selected = (obj === hitObject)
        }
    }

    /**
     * Hit-test del gizmo de Move: para el objeto seleccionado, calcula la distancia minima entre
     * el rayo del tap y cada uno de los 3 segmentos de eje (en espacio mundo, con el mismo tamano
     * de pantalla constante que usa el dibujo - ver gizmoScreenScale), y devuelve el eje mas
     * cercano si esta dentro del radio de tolerancia. Devuelve null si no hay objeto seleccionado
     * o si ningun eje esta lo bastante cerca (en ese caso el arrastre cae al gesto libre normal).
     */
    fun hitTestGizmoAxis(screenX: Float, screenY: Float): Char? {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return null
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return null

        // Unica fuente de verdad: Gizmo3D.SHAFT_LENGTH / TIP_LENGTH (companion object alla) - ya
        // no hay numeros duplicados a mano entre dibujo y hit-test.
        val segStart = floatArrayOf(selected.posX, selected.posY, selected.posZ)
        // (ver companion object de Gizmo3D, arriba, para SHAFT_LENGTH/TIP_LENGTH)
        val __pivMove = gizmoPivotWorld(selected); segStart[0] = __pivMove[0]; segStart[1] = __pivMove[1]; segStart[2] = __pivMove[2]; val axisLength = gizmoScreenScale * (Gizmo3D.SHAFT_LENGTH + Gizmo3D.TIP_LENGTH)
        val hitRadius = gizmoScreenScale * 0.18f

        var bestAxis: Char? = null
        var bestDist = Float.MAX_VALUE
        for (axisChar in listOf('X', 'Y', 'Z')) {
            val dist = closestDistanceRayToSegment(rayOrigin, rayDir, segStart, effectiveAxisDirection(axisChar, selected), axisLength)
            if (dist < hitRadius && dist < bestDist) {
                bestDist = dist
                bestAxis = axisChar
            }
        }
        return bestAxis
    }

    /**
     * Hit-test del gizmo de Scale: mismo criterio que hitTestGizmoAxis (distancia minima rayo-
     * segmento, un segmento por eje), pero con el largo y el radio de tolerancia propios de Scale
     * (shaft + cubito, ver Gizmo3D.SCALE_BOX_HALF_SIZE) en vez de shaft + punta de flecha. Usa
     * effectiveAxisDirection (no un axisDirection/rotatedAxisDirection fijo) - el cubito de cada
     * eje se dibuja en la orientacion elegida (ver onDrawFrame), asi que el segmento contra el que
     * se mide la distancia del toque tiene que estar en esa misma orientacion, o el hit-test
     * terminaria probando contra una posicion distinta de donde el cubito realmente se ve.
     * Devuelve null si no hay objeto seleccionado o si ningun eje esta lo bastante cerca (el
     * arrastre cae entonces al escalado uniforme libre, ver scaleSelectedObject).
     */
    fun hitTestGizmoScaleAxis(screenX: Float, screenY: Float): Char? {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return null
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return null

        val segStart = floatArrayOf(selected.posX, selected.posY, selected.posZ)
        val __pivScale = gizmoPivotWorld(selected); segStart[0] = __pivScale[0]; segStart[1] = __pivScale[1]; segStart[2] = __pivScale[2]; val axisLength = gizmoScreenScale * (Gizmo3D.SHAFT_LENGTH + Gizmo3D.SCALE_BOX_HALF_SIZE)
        val hitRadius = gizmoScreenScale * 0.18f

        var bestAxis: Char? = null
        var bestDist = Float.MAX_VALUE
        for (axisChar in listOf('X', 'Y', 'Z')) {
            val dist = closestDistanceRayToSegment(rayOrigin, rayDir, segStart, effectiveAxisDirection(axisChar, selected), axisLength)
            if (dist < hitRadius && dist < bestDist) {
                bestDist = dist
                bestAxis = axisChar
            }
        }
        return bestAxis
    }

    /**
     * Hit-test del gizmo de Rotate: para el objeto seleccionado, y para cada uno de los 3 anillos,
     * interseca el rayo del tap con el plano perpendicular a ese eje que pasa por el centro del
     * objeto (rayo-plano, cerrado, sin iteracion), y compara la distancia del punto de interseccion
     * al centro contra el radio del anillo (Gizmo3D.RING_RADIUS, escalado por gizmoScreenScale -
     * unica fuente de verdad, ya no hay numero duplicado a mano aca). Devuelve el anillo mas cercano dentro de la
     * tolerancia, o null si no hay objeto seleccionado, el eje quedo de canto respecto de la camara
     * (rayo paralelo al plano - interseccion indefinida) o ningun anillo esta lo bastante cerca.
     * De paso, si encuentra un hit, guarda en activeRotateStartDir la direccion (mundo, normalizada)
     * desde el centro hasta el punto tocado - la usa computeRotateLabelAnchor para la etiqueta de
     * texto del eje agarrado (ver MainActivity.onViewportDragStart, que ademas copia este valor
     * como semilla inicial de activeRotateCurrentDir).
     */
    fun hitTestGizmoRotateAxis(screenX: Float, screenY: Float): Char? {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return null
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return null

        val center = floatArrayOf(selected.posX, selected.posY, selected.posZ)
        val __pivRotHit = gizmoPivotWorld(selected); center[0] = __pivRotHit[0]; center[1] = __pivRotHit[1]; center[2] = __pivRotHit[2]; val ringRadius = gizmoScreenScale * Gizmo3D.RING_RADIUS
        val tolerance = gizmoScreenScale * 0.15f

        var bestAxis: Char? = null
        var bestDist = Float.MAX_VALUE
        var bestDir: FloatArray? = null
        for (axisChar in listOf('X', 'Y', 'Z')) {
            val normal = effectiveAxisDirection(axisChar, selected)
            val denom = dot(rayDir, normal)
            if (abs(denom) < 1e-6f) continue

            val diff = floatArrayOf(center[0] - rayOrigin[0], center[1] - rayOrigin[1], center[2] - rayOrigin[2])
            val t = dot(diff, normal) / denom
            if (t < 0f) continue

            val hitPoint = floatArrayOf(
                rayOrigin[0] + rayDir[0] * t,
                rayOrigin[1] + rayDir[1] * t,
                rayOrigin[2] + rayDir[2] * t
            )
            val dx = hitPoint[0] - center[0]
            val dy = hitPoint[1] - center[1]
            val dz = hitPoint[2] - center[2]
            val distFromCenter = sqrt(dx * dx + dy * dy + dz * dz)
            val distFromRing = abs(distFromCenter - ringRadius)

            if (distFromRing < tolerance && distFromRing < bestDist) {
                bestDist = distFromRing
                bestAxis = axisChar
                bestDir = if (distFromCenter > 1e-4f) floatArrayOf(dx / distFromCenter, dy / distFromCenter, dz / distFromCenter) else null
            }
        }
        if (bestAxis != null) activeRotateStartDir = bestDir
        return bestAxis
    }

    /**
     * Interseccion rayo-plano SIN tolerancia de cercania al anillo (a diferencia de
     * hitTestGizmoRotateAxis): aca el dedo puede estar lejos del circulo y la marca de angulo
     * igual tiene que apuntar hacia el, tal cual el gizmo real de Blender (ver video de referencia
     * del usuario). Actualiza activeRotateCurrentDir; no hace nada (deja el valor anterior) en el
     * caso degenerado de eje de canto respecto de la camara, interseccion detras de la camara, o
     * punto de interseccion pegado al centro (direccion indefinida).
     */
    fun updateActiveRotateCurrentDir(screenX: Float, screenY: Float, axis: Char) {
        val selected = sceneObjects.firstOrNull { it.selected } ?: return
        val (rayOrigin, rayDir) = screenPointToRay(screenX, screenY) ?: return
        val center = floatArrayOf(selected.posX, selected.posY, selected.posZ)
        val __pivRotDrag = gizmoPivotWorld(selected); center[0] = __pivRotDrag[0]; center[1] = __pivRotDrag[1]; center[2] = __pivRotDrag[2]; val normal = effectiveAxisDirection(axis, selected)
        val denom = dot(rayDir, normal)
        if (abs(denom) < 1e-6f) return
        val diff = floatArrayOf(center[0] - rayOrigin[0], center[1] - rayOrigin[1], center[2] - rayOrigin[2])
        val t = dot(diff, normal) / denom
        if (t < 0f) return
        val hitPoint = floatArrayOf(rayOrigin[0] + rayDir[0] * t, rayOrigin[1] + rayDir[1] * t, rayOrigin[2] + rayDir[2] * t)
        val dx = hitPoint[0] - center[0]
        val dy = hitPoint[1] - center[1]
        val dz = hitPoint[2] - center[2]
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 1e-4f) return
        activeRotateCurrentDir = floatArrayOf(dx / dist, dy / dist, dz / dist)
    }

    /**
     * Distancia minima entre una recta (rayOrigin + t*rayDir, t libre) y un segmento
     * (segStart + s*segDir, s clampeado a [0, segLength]) - formula estandar de distancia
     * minima entre dos rectas en 3D, resuelta para el punto mas cercano del rayo una vez fijado
     * el parametro clampeado del segmento.
     */
    private fun closestDistanceRayToSegment(
        rayOrigin: FloatArray, rayDir: FloatArray,
        segStart: FloatArray, segDir: FloatArray, segLength: Float
    ): Float {
        val r = FloatArray(3) { rayOrigin[it] - segStart[it] }
        val a = dot(rayDir, rayDir)
        val b = dot(rayDir, segDir)
        val c = dot(rayDir, r)
        val e = dot(segDir, segDir)
        val f = dot(segDir, r)
        val denom = a * e - b * b

        var s = if (denom > 1e-8f) (a * f - b * c) / denom else if (e > 1e-8f) f / e else 0f
        s = s.coerceIn(0f, segLength)
        val t = if (a > 1e-8f) maxOf(0f, (b * s - c) / a) else 0f

        val closestOnRay = FloatArray(3) { rayOrigin[it] + rayDir[it] * t }
        val closestOnSeg = FloatArray(3) { segStart[it] + segDir[it] * s }
        val dx = closestOnRay[0] - closestOnSeg[0]
        val dy = closestOnRay[1] - closestOnSeg[1]
        val dz = closestOnRay[2] - closestOnSeg[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /**
     * Matriz modelo completa (traslacion + rotacion + forma) de un objeto - mismo calculo que se
     * arma en linea en onDrawFrame para cada objeto (translateMatrix * rotationMatrix *
     * shapeMatrix), extraido aca para que los raycast de sub-elementos (raycastVertexAt/EdgeAt/
     * paintAt) puedan llevar un rayo a espacio local del objeto sin duplicar la
     * formula ni arriesgarse a que las dos copias se desincronicen.
     */
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

    /** Punto de pivote (mundo) para el gizmo: el origen del objeto. Unica fuente de verdad usada por onDrawFrame y los 3 hitTestGizmo*. */
    private fun gizmoPivotWorld(obj: SceneObject): FloatArray = floatArrayOf(obj.posX, obj.posY, obj.posZ)

    /**
     * Interseccion rayo-caja axis-aligned (metodo slab), caja de medio-lado 0.5*largoColumna por
     * eje (columnLength generaliza los scaleX/Y/Z sueltos que tenia antes el modelo de datos - ver
     * SceneObject.shapeMatrix) centrada en (objX, objY, objZ). Ignora la rotacion del objeto (y
     * cualquier shear de shapeMatrix) a proposito (bounding box sin rotar, mas grande de lo justo
     * cuando el objeto esta rotado o deformado) - misma simplificacion deliberada que ya existia
     * con scaleX/Y/Z sueltos, suficiente para seleccionar por ahora; afinar esto requeriria un OBB
     * (oriented bounding box) o transformar el rayo al espacio local del objeto.
     */
    private fun intersectAABB(
        rayOrigin: FloatArray, rayDir: FloatArray,
        objX: Float, objY: Float, objZ: Float,
        shapeMatrix: FloatArray
    ): Float? {
        val halfExtent = floatArrayOf(
            0.5f * columnLength(shapeMatrix, 0),
            0.5f * columnLength(shapeMatrix, 1),
            0.5f * columnLength(shapeMatrix, 2)
        )
        val center = floatArrayOf(objX, objY, objZ)
        val minB = FloatArray(3) { center[it] - halfExtent[it] }
        val maxB = FloatArray(3) { center[it] + halfExtent[it] }
        var tMin = -Float.MAX_VALUE
        var tMax = Float.MAX_VALUE

        for (i in 0..2) {
            if (abs(rayDir[i]) < 1e-6f) {
                if (rayOrigin[i] < minB[i] || rayOrigin[i] > maxB[i]) return null
            } else {
                var t1 = (minB[i] - rayOrigin[i]) / rayDir[i]
                var t2 = (maxB[i] - rayOrigin[i]) / rayDir[i]
                if (t1 > t2) {
                    val tmp = t1
                    t1 = t2
                    t2 = tmp
                }
                tMin = maxOf(tMin, t1)
                tMax = minOf(tMax, t2)
                if (tMin > tMax) return null
            }
        }

        return if (tMax >= 0f) maxOf(tMin, 0f) else null
    }
}
