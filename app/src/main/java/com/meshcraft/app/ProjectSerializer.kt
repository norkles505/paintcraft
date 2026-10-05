package com.meshcraft.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializacion a/desde JSON del proyecto (File > Save/New, ver MyGLRenderer.saveProjectToFile/
 * loadProjectFromFile) - formato propio de la app, no compatible con .blend ni ningun estandar
 * externo (eso es tarea de Import/Export, fuera de alcance por ahora, ver charla con el usuario:
 * "un solo slot fijo, con auto-carga al abrir la app" es la simplificacion elegida para File).
 *
 * Guarda TODO lo que hace falta para reconstruir la escena tal cual quedo: cada SceneObject
 * (posicion, rotationMatrix/shapeMatrix completas - no solo scale/rot sueltos, ver comentario de
 * esa clase) y, si el objeto ya entro a Edit Mode alguna vez, su EditableMesh completo (vertices/
 * aristas/caras con sus ids reales - no alcanza con reconstruir la primitiva original via
 * MeshType.toEditableMesh(), porque el usuario pudo haberla editado). nextObjectId tambien se
 * guarda, para que los objetos nuevos que se agreguen despues de cargar no choquen ids con los ya
 * guardados.
 *
 * Usa org.json (JSONObject/JSONArray), incluido en el SDK de Android - no hace falta agregar
 * ninguna dependencia nueva para esto.
 */
private fun FloatArray.toJsonArray(): JSONArray {
    val arr = JSONArray()
    for (v in this) arr.put(v.toDouble())
    return arr
}

/** Lee hasta 16 floats de un JSONArray - si el array vino corto o corrupto, completa el resto con identidad (mismo criterio de FloatArray(16).apply{setIdentityM} que ya usa SceneObject por default), para que un archivo parcialmente daniado no tire toda la carga abajo. */
private fun JSONArray.toFloatArray16(): FloatArray {
    val result = FloatArray(16)
    android.opengl.Matrix.setIdentityM(result, 0)
    for (i in 0 until minOf(16, length())) {
        result[i] = optDouble(i, result[i].toDouble()).toFloat()
    }
    return result
}

private const val MESH_FILE_MAGIC = 0x4F424A31 // "OBJ1"
private const val MESH_FILE_HEADER_BYTES = 12 // magic, vertexCount, hasUvs (3 ints)

/** Nombre del archivo binario donde se guarda la malla importada del objeto con ese id (dentro de meshDir). */
fun importedMeshFileName(objectId: Int): String = "mesh_" + objectId + ".bin"

/**
 * Una malla importada puede tener decenas de miles de triangulos: en JSON pesaria muchisimo, asi que va a un binario
 * propio (un archivo por objeto, ver importedMeshFileName) y el JSON del proyecto solo guarda el nombre del archivo.
 * Formato: magic, vertexCount, hasUvs (ints) + positions (3 floats por vertice) + normals (3) + uvs (2), little endian.
 */
private fun writeObjMesh(mesh: ObjMesh, file: File) {
    val n = mesh.vertexCount
    val buf = ByteBuffer.allocate(MESH_FILE_HEADER_BYTES + n * 8 * 4).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(MESH_FILE_MAGIC)
    buf.putInt(n)
    buf.putInt(if (mesh.hasUvs) 1 else 0)
    val floats = buf.asFloatBuffer()
    floats.put(mesh.positions)
    floats.put(mesh.normals)
    floats.put(mesh.uvs)
    file.parentFile?.mkdirs()
    file.writeBytes(buf.array())
}

/** Devuelve null si el archivo no existe, esta truncado o no tiene el formato esperado. */
private fun readObjMesh(file: File): ObjMesh? {
    return try {
        if (!file.exists()) return null
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < MESH_FILE_HEADER_BYTES) return null
        if (buf.getInt() != MESH_FILE_MAGIC) return null
        val n = buf.getInt()
        val hasUvs = buf.getInt() == 1
        if (n <= 0 || n % 3 != 0 || buf.remaining() != n * 8 * 4) return null
        val floats = buf.asFloatBuffer()
        val positions = FloatArray(n * 3); floats.get(positions)
        val normals = FloatArray(n * 3); floats.get(normals)
        val uvs = FloatArray(n * 2); floats.get(uvs)
        ObjMesh(positions, normals, uvs, hasUvs)
    } catch (e: Exception) {
        null
    }
}

/** Borra de meshDir los binarios de mallas que ya no usa ningun objeto de la escena (objetos borrados, ids reutilizados tras File > New). */
fun cleanupImportedMeshFiles(meshDir: File, sceneObjects: List<SceneObject>) {
    val keep = sceneObjects.filter { it.importedMesh != null }.map { importedMeshFileName(it.id) }.toSet()
    meshDir.listFiles()?.forEach { f ->
        if (f.name.startsWith("mesh_") && f.name.endsWith(".bin") && f.name !in keep) f.delete()
    }
}

/**
 * meshDir: carpeta donde se escriben los binarios de las mallas importadas (ver writeObjMesh). Si es null, los
 * objetos importados no se pueden guardar (se guardarian como un cubo falso), asi que el llamador siempre debe pasarla.
 */
fun sceneObjectsToJson(sceneObjects: List<SceneObject>, nextObjectId: Int, meshDir: File? = null): String {
    val root = JSONObject()
    root.put("nextObjectId", nextObjectId)
    val objectsArray = JSONArray()
    for (obj in sceneObjects) {
        val o = JSONObject()
        o.put("id", obj.id)
        o.put("type", obj.type.name)
        o.put("posX", obj.posX.toDouble())
        o.put("posY", obj.posY.toDouble())
        o.put("posZ", obj.posZ.toDouble())
        o.put("rotationMatrix", obj.rotationMatrix.toJsonArray())
        o.put("shapeMatrix", obj.shapeMatrix.toJsonArray())
        o.put("selected", obj.selected)

        val importedMesh = obj.importedMesh
        if (importedMesh != null) {
            requireNotNull(meshDir) { "Falta meshDir para guardar un modelo importado" }
            val fileName = importedMeshFileName(obj.id)
            writeObjMesh(importedMesh, File(meshDir, fileName))
            o.put("importedMeshFile", fileName)
        }

        val mesh = obj.editableMesh
        if (mesh != null) {
            val meshJson = JSONObject()

            val vertsArr = JSONArray()
            for (v in mesh.vertices) {
                val vj = JSONObject()
                vj.put("id", v.id)
                vj.put("x", v.x.toDouble())
                vj.put("y", v.y.toDouble())
                vj.put("z", v.z.toDouble())
                vj.put("selected", v.selected)
                vertsArr.put(vj)
            }
            meshJson.put("vertices", vertsArr)

            val edgesArr = JSONArray()
            for (e in mesh.edges) {
                val ej = JSONObject()
                ej.put("id", e.id)
                ej.put("v1", e.v1)
                ej.put("v2", e.v2)
                ej.put("selected", e.selected)
                edgesArr.put(ej)
            }
            meshJson.put("edges", edgesArr)

            val facesArr = JSONArray()
            for (f in mesh.faces) {
                val fj = JSONObject()
                fj.put("id", f.id)
                val idsArr = JSONArray()
                for (vid in f.vertexIds) idsArr.put(vid)
                fj.put("vertexIds", idsArr)
                fj.put("selected", f.selected)
                facesArr.put(fj)
            }
            meshJson.put("faces", facesArr)

            o.put("editableMesh", meshJson)
        }

        objectsArray.put(o)
    }
    root.put("objects", objectsArray)
    return root.toString()
}

/**
 * Devuelve null (en vez de tirar la excepcion) si el JSON esta corrupto o con una forma
 * inesperada - el llamador (MyGLRenderer.loadProjectFromFile) trata null como "no se pudo
 * cargar, dejar la escena default como esta" en vez de crashear la app al abrir.
 */
fun jsonToSceneObjects(json: String, meshDir: File? = null): Pair<MutableList<SceneObject>, Int>? {
    return try {
        val root = JSONObject(json)
        val nextObjectId = root.optInt("nextObjectId", 0)
        val objectsArray = root.getJSONArray("objects")
        val result = mutableListOf<SceneObject>()

        for (i in 0 until objectsArray.length()) {
            val o = objectsArray.getJSONObject(i)
            val id = o.getInt("id")
            val type = MeshType.valueOf(o.getString("type"))
            val posX = o.getDouble("posX").toFloat()
            val posY = o.getDouble("posY").toFloat()
            val posZ = o.getDouble("posZ").toFloat()
            val rotationMatrix = o.getJSONArray("rotationMatrix").toFloatArray16()
            val shapeMatrix = o.getJSONArray("shapeMatrix").toFloatArray16()
            val selected = o.optBoolean("selected", false)

            // Modelo importado: su malla vive en un binario aparte (ver writeObjMesh). Si falta o esta danado, se omite el
            // objeto en vez de cargarlo como un cubo falso (el resto de la escena si se carga).
            var importedMesh: ObjMesh? = null
            val meshFileName = o.optString("importedMeshFile", "")
            if (meshFileName.isNotEmpty()) {
                importedMesh = if (meshDir != null) readObjMesh(File(meshDir, File(meshFileName).name)) else null
                if (importedMesh == null) continue
            }

            var editableMesh: EditableMesh? = null
            if (o.has("editableMesh") && !o.isNull("editableMesh")) {
                val meshJson = o.getJSONObject("editableMesh")

                val vertsArr = meshJson.getJSONArray("vertices")
                val vertices = mutableListOf<MeshVertex>()
                for (j in 0 until vertsArr.length()) {
                    val vj = vertsArr.getJSONObject(j)
                    vertices.add(MeshVertex(
                        vj.getInt("id"),
                        vj.getDouble("x").toFloat(),
                        vj.getDouble("y").toFloat(),
                        vj.getDouble("z").toFloat(),
                        vj.optBoolean("selected", false)
                    ))
                }

                val edgesArr = meshJson.getJSONArray("edges")
                val edges = mutableListOf<MeshEdge>()
                for (j in 0 until edgesArr.length()) {
                    val ej = edgesArr.getJSONObject(j)
                    edges.add(MeshEdge(ej.getInt("id"), ej.getInt("v1"), ej.getInt("v2"), ej.optBoolean("selected", false)))
                }

                val facesArr = meshJson.getJSONArray("faces")
                val faces = mutableListOf<MeshFace>()
                for (j in 0 until facesArr.length()) {
                    val fj = facesArr.getJSONObject(j)
                    val idsArr = fj.getJSONArray("vertexIds")
                    val ids = mutableListOf<Int>()
                    for (k in 0 until idsArr.length()) ids.add(idsArr.getInt(k))
                    faces.add(MeshFace(fj.getInt("id"), ids, fj.optBoolean("selected", false)))
                }

                editableMesh = EditableMesh(vertices, edges, faces)
            }

            result.add(SceneObject(id, type, posX, posY, posZ, rotationMatrix, shapeMatrix, selected, editableMesh, importedMesh = importedMesh))
        }

        result to nextObjectId
    } catch (e: Exception) {
        null
    }
}
