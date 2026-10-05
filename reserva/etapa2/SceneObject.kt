package com.meshcraft.app

import android.opengl.Matrix

/**
 * Que geometria (Cube.kt, Plane.kt, etc.) le corresponde dibujar a un SceneObject - ver
 * MyGLRenderer.onDrawFrame, que elige la instancia de geometria (cubeGeometry/planeGeometry)
 * segun este campo en vez de tener cubeGeometry hardcodeado como antes de agregar Plane. Cada
 * primitiva nueva del menu Add > Mesh que ya tenga su propia clase de geometria (ver Cube.kt/
 * Plane.kt, ambas sobre MeshGeometry.kt) suma un caso aca.
 */
enum class MeshType { CUBE, PLANE, CIRCLE, UV_SPHERE, ICO_SPHERE, CYLINDER, CONE, TORUS, GRID, MONKEY }

/**
 * Objeto en la escena. type decide que geometria le corresponde dibujar (ver MeshType); antes de
 * agregar Plane, todos los objetos eran cubos y esto no hacia falta (ver MyGLRenderer.onDrawFrame).
 *
 * rotationMatrix: rotacion acumulada del objeto, como matriz 4x4 (identidad = sin rotar).
 * Reemplaza los 3 angulos sueltos que tenia antes (rotX/rotY/rotZ): guardarlos por separado y
 * recombinarlos cada frame en un orden fijo (Rz*Rx*Ry) es el clasico problema de angulos de Euler
 * - el resultado depende del orden de composicion, no del orden real en que el usuario roto cada
 * eje, asi que una segunda rotacion podia "cancelar" visualmente el efecto de la primera (bug
 * reportado y confirmado - ver charla con el usuario). Guardando una sola matriz acumulada y
 * multiplicando cada rotacion nueva (delta) sobre el estado actual real (ver
 * MyGLRenderer.applyWorldRotationDelta) el bug desaparece, sin importar que eje se toque en que
 * orden.
 *
 * shapeMatrix: escala (y, cuando corresponde, shear) del objeto, como matriz 4x4 aplicada ANTES
 * de rotationMatrix (modelo = Translate * rotationMatrix * shapeMatrix - ver
 * MyGLRenderer.onDrawFrame). Reemplaza los 3 floats sueltos que tenia antes (scaleX/scaleY/scaleZ,
 * escala diagonal pura). Con solo 3 floats no habia forma de representar el comportamiento real
 * de Blender al escalar en orientacion Global con el objeto rotado: el resultado visual es un
 * shear (el cubo se deforma en diagonal, no se estira derecho) - ver charla con el usuario y su
 * video de referencia, donde se confirmo que el bug real era que el escalado terminaba aplicandose
 * siempre al eje LOCAL del objeto (como si no estuviera rotado) en vez de al eje mundo que se
 * arrastraba. Una matriz 4x4 completa si puede tener esos terminos fuera de la diagonal.
 * Identidad = tamano original. Sigue siendo diagonal pura (equivalente a los 3 floats de antes,
 * mismo resultado numerico) mientras solo se escale en orientacion Local o el objeto no este
 * rotado (ver MyGLRenderer.applyLocalDirScale) - shapeMatrix generaliza el caso anterior sin
 * cambiar su resultado. El shear real solo aparece escalando por eje en Global con el objeto
 * rotado, que es el caso que antes no se podia representar.
 */
data class SceneObject(
    val id: Int,
    var type: MeshType = MeshType.CUBE,
    var posX: Float = 0f,
    var posY: Float = 0f,
    var posZ: Float = 0f,
    var rotationMatrix: FloatArray = FloatArray(16).apply { Matrix.setIdentityM(this, 0) },
    var shapeMatrix: FloatArray = FloatArray(16).apply { Matrix.setIdentityM(this, 0) },
    var selected: Boolean = false,
    var editableMesh: EditableMesh? = null,
    /**
     * Object > Show/Hide (ver MyGLRenderer.toggleShowHideSelected) - objetos ocultos no se dibujan
     * (ver onDrawFrame) ni son alcanzables por raycast (ver selectObjectAt), mismo criterio que
     * Blender: un objeto oculto no se puede seleccionar tocandolo, solo mostrando todo de nuevo.
     */
    var visible: Boolean = true,
    /** Modelo importado desde OBJ (ver ObjLoader y TexturedMeshGeometry): si no es null, se dibuja en vez de la geometria de type. */
    var importedMesh: ObjMesh? = null

)
