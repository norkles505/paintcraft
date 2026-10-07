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
 * Guarda lo que hace falta para reconstruir la escena tal cual quedo: cada SceneObject
 * (posicion, rotationMatrix/shapeMatrix completas - no solo scale/rot sueltos, ver comentario de
 * esa clase) y, si es un modelo importado, el nombre del binario con su malla (ver writeObjMesh).
 * nextObjectId tambien se guarda, para que los objetos nuevos que se agreguen despues de cargar no
 * choquen ids con los ya guardados.
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

// ---- Capas de pintura ----

/** Una capa lista para guardar o recien leida: sus ajustes y sus pixeles RGBA (PAINT_TEX_SIZE x PAINT_TEX_SIZE, ver PaintLayer). */
class SavedLayer(
    val name: String,
    val visible: Boolean,
    val opacity: Int,
    val locked: Boolean,
    val alphaLock: Boolean,
    val pixels: ByteArray,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val clipToBelow: Boolean = false,
    /** true = es una carpeta (sin pixeles propios: pixels va vacio). */
    val isFolder: Boolean = false,
    /** Posicion, en la lista de capas, de la carpeta que contiene a esta capa, o -1 si esta suelta. */
    val folderIndex: Int = -1,
    val collapsed: Boolean = false
)

/** Copia de la capa guardada con otra posicion de carpeta (se usa al leer, cuando las posiciones cambian porque se omitio alguna capa danada). */
private fun SavedLayer.withFolderIndex(i: Int) = SavedLayer(name, visible, opacity, locked, alphaLock, pixels, blendMode, clipToBelow, isFolder, i, collapsed)

/** Las capas de un modelo, de abajo hacia arriba, y la posicion (en esa lista) de la capa elegida. */
class SavedLayers(val layers: List<SavedLayer>, val selectedIndex: Int, val background: BackgroundKind = BackgroundKind.WHITE)

private const val LAYER_FILE_MAGIC = 0x4C595231 // "LYR1"

/** Nombre del archivo con los pixeles de la capa numero index (de abajo hacia arriba) del objeto con ese id (dentro de la carpeta de capas). */
fun layerFileName(objectId: Int, index: Int): String = "layer_" + objectId + "_" + index + ".lyr"

/** Deja tmp en el lugar de target (reemplazandolo). Asi un guardado interrumpido nunca deja un archivo a medias. */
private fun replaceFile(tmp: File, target: File) {
    if (tmp.renameTo(target)) return
    target.delete()
    if (!tmp.renameTo(target)) throw java.io.IOException("No se pudo guardar " + target.name)
}

/** Escribe texto en el archivo de forma segura: primero a un .tmp y despues se renombra. */
fun writeTextAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    tmp.writeText(text)
    replaceFile(tmp, file)
}

/**
 * Los pixeles de una capa van sin perdida (no PNG: el PNG de Android premultiplica el alfa y deforma los pixeles casi
 * transparentes). Formato: magic, lado de la textura (ints) + los bytes RGBA comprimidos con deflate. Una capa casi
 * vacia pesa unos pocos KB.
 */
private fun writeLayerPixels(pixels: ByteArray, file: File) {
    val tmp = File(file.path + ".tmp")
    java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tmp))).use { out ->
        out.writeInt(LAYER_FILE_MAGIC)
        out.writeInt(PAINT_TEX_SIZE)
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)
        val zip = java.util.zip.DeflaterOutputStream(out, deflater, 64 * 1024)
        zip.write(pixels)
        zip.finish()
        deflater.end()
    }
    replaceFile(tmp, file)
}

/** Devuelve null si el archivo no existe, esta truncado, es de otro tamano de textura o no tiene el formato esperado. */
private fun readLayerPixels(file: File): ByteArray? {
    return try {
        if (!file.exists()) return null
        java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(file))).use { input ->
            if (input.readInt() != LAYER_FILE_MAGIC) return null
            if (input.readInt() != PAINT_TEX_SIZE) return null
            val pixels = ByteArray(PAINT_TEX_SIZE * PAINT_TEX_SIZE * 4)
            java.io.DataInputStream(java.util.zip.InflaterInputStream(input)).readFully(pixels)
            pixels
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Escribe en dir los pixeles de las capas de cada modelo (layersByObjectId: id del objeto -> sus capas) y devuelve el JSON
 * que va dentro del proyecto (clave "layers"): por objeto, la capa elegida y, por capa, su archivo y sus ajustes.
 */
fun writeLayerFiles(dir: File, layersByObjectId: Map<Int, SavedLayers>): JSONObject {
    dir.mkdirs()
    val root = JSONObject()
    for ((objectId, saved) in layersByObjectId) {
        val items = JSONArray()
        for ((index, layer) in saved.layers.withIndex()) {
            val fileName = layerFileName(objectId, index)
            if (!layer.isFolder) writeLayerPixels(layer.pixels, File(dir, fileName))
            val item = JSONObject()
            item.put("file", fileName)
            item.put("name", layer.name)
            item.put("visible", layer.visible)
            item.put("opacity", layer.opacity)
            item.put("locked", layer.locked)
            item.put("alphaLock", layer.alphaLock)
            item.put("blendMode", layer.blendMode.name)
            item.put("clip", layer.clipToBelow)
            item.put("folder", layer.isFolder)
            item.put("parent", layer.folderIndex)
            item.put("collapsed", layer.collapsed)
            items.put(item)
        }
        val entry = JSONObject()
        entry.put("selected", saved.selectedIndex)
        entry.put("background", saved.background.name)
        entry.put("items", items)
        root.put(objectId.toString(), entry)
    }
    return root
}

/** Borra de dir los archivos de capas que ya no usa el proyecto guardado (capas borradas, restos de guardados interrumpidos). */
fun cleanupLayerFiles(dir: File, layersJson: JSONObject) {
    val keep = HashSet<String>()
    val keys = layersJson.keys()
    while (keys.hasNext()) {
        val items = layersJson.getJSONObject(keys.next()).getJSONArray("items")
        for (i in 0 until items.length()) keep.add(items.getJSONObject(i).getString("file"))
    }
    dir.listFiles()?.forEach { f ->
        if (f.name.startsWith("layer_") && (f.name.endsWith(".lyr") || f.name.endsWith(".tmp")) && f.name !in keep) f.delete()
    }
}

/**
 * Lee las capas guardadas en el JSON del proyecto (id del objeto -> sus capas). Una capa cuyo archivo falta o esta danado
 * se omite; si el proyecto no tiene capas (guardado con una version vieja) o el JSON esta mal, devuelve lo que pudo leer (a lo mejor nada).
 */
fun jsonToSavedLayers(json: String, dir: File): Map<Int, SavedLayers> {
    val result = HashMap<Int, SavedLayers>()
    try {
        val layersRoot = JSONObject(json).optJSONObject("layers") ?: return result
        val keys = layersRoot.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val objectId = key.toIntOrNull() ?: continue
            val entry = layersRoot.getJSONObject(key)
            val items = entry.getJSONArray("items")
            val list = ArrayList<SavedLayer>()
            val oldToNew = HashMap<Int, Int>() // posicion en el archivo -> posicion en list (se omiten las capas danadas)
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val isFolder = item.optBoolean("folder", false)
                val pixels = if (isFolder) ByteArray(0) else (readLayerPixels(File(dir, File(item.getString("file")).name)) ?: continue)
                oldToNew[i] = list.size
                list.add(
                    SavedLayer(
                        item.optString("name", (i + 1).toString()),
                        item.optBoolean("visible", true),
                        item.optInt("opacity", 100).coerceIn(0, 100),
                        item.optBoolean("locked", false),
                        item.optBoolean("alphaLock", false),
                        pixels,
                        BlendMode.fromName(item.optString("blendMode", "NORMAL")),
                        item.optBoolean("clip", false),
                        isFolder,
                        if (isFolder) -1 else item.optInt("parent", -1),
                        item.optBoolean("collapsed", false)
                    )
                )
            }
            // Remapeo de carpetas (la carpeta va DESPUES de sus capas en la lista, asi que solo se puede resolver al terminar de leer).
            for (j in list.indices) {
                val l = list[j]
                if (l.isFolder || l.folderIndex < 0) continue
                val np = oldToNew[l.folderIndex]
                list[j] = l.withFolderIndex(if (np != null && list[np].isFolder) np else -1)
            }
            // Tiene que quedar al menos una capa con pixeles.
            if (list.any { !it.isFolder }) result[objectId] = SavedLayers(list, entry.optInt("selected", 0), BackgroundKind.fromName(entry.optString("background", "WHITE")))
        }
    } catch (e: Exception) {
        // JSON con forma inesperada: se queda con lo que ya se leyo.
    }
    return result
}

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
fun sceneObjectsToJson(sceneObjects: List<SceneObject>, nextObjectId: Int, meshDir: File? = null, layersJson: JSONObject? = null): String {
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

        objectsArray.put(o)
    }
    root.put("objects", objectsArray)
    // Capas pintadas de cada modelo importado (sus pixeles van en archivos aparte, ver writeLayerFiles).
    if (layersJson != null) root.put("layers", layersJson)
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

            result.add(SceneObject(id, type, posX, posY, posZ, rotationMatrix, shapeMatrix, selected, importedMesh = importedMesh))
        }

        result to nextObjectId
    } catch (e: Exception) {
        null
    }
}
