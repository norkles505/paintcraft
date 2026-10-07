package com.meshcraft.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast

private const val REQ_IMPORT_OBJ = 4101
private const val REQ_EXPORT_PNG = 4102

/**
 * Pantalla unica de la app de pintura: vista 3D (un dedo pinta, dos dedos mueven la camara y el pellizco hace zoom),
 * panel de pintura abajo (su panel de herramientas trae tambien mano y bloqueo de la camara, ver PaintPanel) y arriba
 * a la izquierda el menu File con los botones de deshacer/rehacer trazos.
 * Los puntos de vista (Top, Front...) se eligen con el gizmo de ejes de arriba a la derecha.
 */
class MainActivity : Activity() {

    private lateinit var glView: MyGLSurfaceView
    private lateinit var gizmoView: GizmoView

    private lateinit var fileButton: ImageView

    private lateinit var paintPanel: PaintPanel

    /** Lo que se esta exportando mientras el selector de archivos elige el destino (ver startExport / finishExport), y la capa si es una sola. */
    private var pendingExport: ExportKind? = null
    private var pendingExportLayerId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        glView = MyGLSurfaceView(this)
        gizmoView = GizmoView(this)

        gizmoView.angleXProvider = { glView.renderer.angleX }
        gizmoView.angleYProvider = { glView.renderer.angleY }
        glView.onRotationChanged = { gizmoView.invalidate() }
        gizmoView.onAxisSelected = { targetX, targetY, _ -> animateCameraTo(targetX, targetY) }
        // Pintar usa OpenGL: los eventos de la vista pasan al hilo de render.
        glView.onPaintStart = { x, y -> glView.queueEvent { glView.renderer.paintStart(x, y) } }
        glView.onPaintMove = { x, y -> glView.queueEvent { glView.renderer.paintMove(x, y) } }

        val root = FrameLayout(this)
        root.addView(
            glView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val density = resources.displayMetrics.density
        val gizmoSize = (68 * density).toInt()
        val margin = (16 * density).toInt()
        val gizmoParams = FrameLayout.LayoutParams(gizmoSize, gizmoSize)
        gizmoParams.gravity = Gravity.TOP or Gravity.END
        gizmoParams.topMargin = margin
        gizmoParams.rightMargin = margin
        root.addView(gizmoView, gizmoParams)

        root.addView(buildTopBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            topMargin = margin
        })

        // Panel de pintura (estilo ibisPaint): se agrega al final para quedar encima de todo. Siempre visible (con su triangulo para ocultarlo).
        paintPanel = PaintPanel(this, glView.renderer)
        // Mano y Bloqueo viven en el panel de herramientas de PaintPanel; la camara se controla desde aqui.
        // Cada callback devuelve true si el boton debe quedar resaltado (modo desplazar / camara bloqueada).
        paintPanel.onHandToggle = {
            val toPan = glView.touchMode == TouchMode.ROTATE
            glView.touchMode = if (toPan) TouchMode.PAN else TouchMode.ROTATE
            toPan
        }
        paintPanel.onLockToggle = {
            val locked = !glView.isLocked
            glView.isLocked = locked
            locked
        }
        root.addView(paintPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = (8 * density).toInt()
            rightMargin = (8 * density).toInt()
            bottomMargin = (8 * density).toInt()
        })

        setContentView(root)
    }

    /** Barra de arriba: File en la esquina y, a su lado, deshacer y rehacer trazos. */
    private fun buildTopBar(): FrameLayout {
        val density = resources.displayMetrics.density
        val margin = (16 * density).toInt()
        val bar = FrameLayout(this)

        // File: icono suelto en la esquina, abre un menu propio (New / Save / Import / Export).
        fileButton = createIconButton(R.drawable.ic_file)
        fileButton.setOnClickListener { showFileMenu(it) }
        val fileParams = FrameLayout.LayoutParams(
            fileButton.layoutParams.width,
            fileButton.layoutParams.height
        )
        fileParams.gravity = Gravity.TOP or Gravity.START
        fileParams.leftMargin = margin
        bar.addView(fileButton, fileParams)

        // Deshacer/Rehacer a la derecha de File (el gizmo queda en la esquina opuesta, sin chocar).
        val undoRedoParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        undoRedoParams.gravity = Gravity.TOP or Gravity.START
        undoRedoParams.leftMargin = margin + fileButton.layoutParams.width + (8 * density).toInt()
        bar.addView(buildUndoRedoRow(), undoRedoParams)

        return bar
    }

    /**
     * Fila con Deshacer y Rehacer de trazos de pintura (ver MyGLRenderer.undo/redo). Restaurar pixeles usa OpenGL, asi que se hace en
     * el hilo de render y el resultado (redibujar o avisar que no hay nada) vuelve al hilo de la interfaz.
     */
    private fun buildUndoRedoRow(): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val undoBtn = createIconButton(R.drawable.ic_undo)
        val redoBtn = createIconButton(R.drawable.ic_redo)

        undoBtn.setOnClickListener {
            glView.queueEvent {
                val done = glView.renderer.undo()
                runOnUiThread {
                    if (done) glView.requestRender()
                    else Toast.makeText(this, "Nada para deshacer", Toast.LENGTH_SHORT).show()
                }
            }
        }
        redoBtn.setOnClickListener {
            glView.queueEvent {
                val done = glView.renderer.redo()
                runOnUiThread {
                    if (done) glView.requestRender()
                    else Toast.makeText(this, "Nada para rehacer", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val spacing = (8 * density).toInt()
        for ((i, btn) in listOf(undoBtn, redoBtn).withIndex()) {
            if (i > 0) (btn.layoutParams as LinearLayout.LayoutParams).leftMargin = spacing
            row.addView(btn)
        }
        return row
    }

    /** Fila de menu con icono + texto, usada por el menu File. */
    private fun buildIconMenuItem(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val hPad = (12 * density).toInt()
        val vPad = (9 * density).toInt()
        row.setPadding(hPad, vPad, hPad, vPad)
        row.isClickable = true
        row.background = menuItemPressBackground()
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val icon = ImageView(this)
        icon.setImageResource(iconRes)
        val iconSize = (18 * density).toInt()
        icon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        row.addView(icon)

        val text = TextView(this)
        text.text = label
        text.setTextColor(Color.WHITE)
        text.textSize = 13f
        val textParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        textParams.leftMargin = (10 * density).toInt()
        text.layoutParams = textParams
        row.addView(text)

        row.setOnClickListener { onClick() }
        return row
    }

    private fun showFileMenu(anchor: View) {
        val density = resources.displayMetrics.density
        val menuColumn = LinearLayout(this)
        menuColumn.orientation = LinearLayout.VERTICAL
        menuColumn.background = menuBackground()
        val vPad = (6 * density).toInt()
        menuColumn.setPadding(vPad, vPad, vPad, vPad)

        fileButton.background = circleBackground(true)

        val popup = PopupWindow(
            menuColumn,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.isOutsideTouchable = true
        popup.elevation = 12 * density
        popup.setOnDismissListener {
            fileButton.background = circleBackground(false)
        }

        menuColumn.addView(buildIconMenuItem(R.drawable.ic_new, "New") {
            popup.dismiss()
            onFileMenuAction("New")
        })
        menuColumn.addView(buildIconMenuItem(R.drawable.ic_save, "Save") {
            popup.dismiss()
            onFileMenuAction("Save")
        })
        menuColumn.addView(buildIconMenuItem(R.drawable.ic_import, "Import") {
            popup.dismiss()
            onFileMenuAction("Import")
        })
        menuColumn.addView(buildIconMenuItem(R.drawable.ic_export, "Export") {
            popup.dismiss()
            onFileMenuAction("Export")
        })

        popup.showAsDropDown(anchor, 0, (8 * density).toInt())
    }

    /**
     * File > New/Save/Import (ver MyGLRenderer.newProject/saveProjectToFile - un solo slot fijo, con auto-carga al abrir
     * la app) y Export (sacar la textura pintada como PNG, ver showExportDialog).
     */
    private fun onFileMenuAction(action: String) {
        if (action == "New") {
            glView.renderer.newProject()
            glView.requestRender()
            Toast.makeText(this, "Nuevo proyecto", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Save") {
            Toast.makeText(this, "Guardando...", Toast.LENGTH_SHORT).show()
            // Escribir las capas puede tardar (hasta unos 40 MB): va en un hilo aparte para no congelar la interfaz.
            Thread {
                val saved = glView.renderer.saveProjectToFile()
                runOnUiThread {
                    Toast.makeText(this, if (saved) "Proyecto guardado" else "No se pudo guardar", Toast.LENGTH_SHORT).show()
                }
            }.start()
            return
        }
        if (action == "Import") {
            openObjPicker()
            return
        }
        // Export: elige que sacar (ver showExportDialog) y despues el destino (ver startExport).
        showExportDialog()
    }

    // ---- Exportar PNG ----

    /** File > Export: pregunta que sacar (textura completa con o sin fondo, o solo la capa elegida). */
    private fun showExportDialog() {
        if (glView.renderer.activeGeometry == null) {
            Toast.makeText(this, "Importa un modelo para poder exportar", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = arrayOf(
            "Textura completa (con el fondo elegido)",
            "Textura completa (transparente)",
            "Solo la capa elegida (transparente)"
        )
        val kinds = arrayOf(ExportKind.TEXTURE_WITH_BASE, ExportKind.TEXTURE_TRANSPARENT, ExportKind.LAYER)
        AlertDialog.Builder(this)
            .setTitle("Exportar PNG")
            .setItems(labels) { _, which -> startExport(kinds[which]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Abre el selector de archivos del sistema para elegir donde guardar el PNG (ver onActivityResult / finishExport).
     * Tambien lo usa LayersPanel para "Guardar capa como PNG" (ExportKind.LAYER).
     */
    fun startExport(kind: ExportKind) {
        val geo = glView.renderer.activeGeometry
        if (geo == null) {
            Toast.makeText(this, "Importa un modelo para poder exportar", Toast.LENGTH_SHORT).show()
            return
        }
        // Una carpeta no tiene pixeles propios: se avisa ahora y no despues de elegir el destino (que dejaria un archivo vacio).
        if (kind == ExportKind.LAYER && geo.selectedLayer()?.isFolder != false) {
            Toast.makeText(this, "Elige una capa (no una carpeta) para exportarla", Toast.LENGTH_SHORT).show()
            return
        }
        pendingExport = kind
        pendingExportLayerId = if (kind == ExportKind.LAYER) geo.selectedLayerId else -1
        val fileName = if (kind == ExportKind.LAYER) "paintcraft_capa.png" else "paintcraft_textura.png"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/png"
        intent.putExtra(Intent.EXTRA_TITLE, fileName)
        startActivityForResult(intent, REQ_EXPORT_PNG)
    }

    /** Ya hay destino (uri): arma los pixeles y escribe el PNG en un hilo aparte (son 4 MB de pixeles, no se debe congelar la interfaz). */
    private fun finishExport(uri: Uri) {
        val kind = pendingExport ?: return
        val layerId = pendingExportLayerId
        pendingExport = null
        pendingExportLayerId = -1
        val geo = glView.renderer.activeGeometry
        if (geo == null) {
            Toast.makeText(this, "No hay modelo para exportar", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Exportando...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val rgba = when (kind) {
                    ExportKind.TEXTURE_WITH_BASE -> geo.exportCompositeRgba(true)
                    ExportKind.TEXTURE_TRANSPARENT -> geo.exportCompositeRgba(false)
                    ExportKind.LAYER -> geo.exportLayerRgba(layerId)
                } ?: throw IllegalStateException("No hay nada que exportar")
                val stream = contentResolver.openOutputStream(uri) ?: throw IllegalArgumentException("No se pudo abrir el destino")
                stream.use { PngExport.write(it, geo.texSize, rgba) }
                runOnUiThread { Toast.makeText(this, "PNG guardado", Toast.LENGTH_SHORT).show() }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, "Error al exportar: " + ex.message, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun menuBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * resources.displayMetrics.density
            color = ColorStateList.valueOf(Color.argb(245, 32, 32, 32))
        }
    }

    private fun menuItemPressBackground(): StateListDrawable {
        val density = resources.displayMetrics.density
        val pressed = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * density
            color = ColorStateList.valueOf(Color.argb(235, 242, 128, 26))
        }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * density
            color = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun createIconButton(iconRes: Int): ImageView {
        val density = resources.displayMetrics.density
        val sizePx = (40 * density).toInt()
        val paddingPx = (9 * density).toInt()

        val iv = ImageView(this)
        iv.setImageResource(iconRes)
        iv.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        iv.background = circleBackground(false)
        iv.layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        iv.isClickable = true
        return iv
    }

    private fun circleBackground(active: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            color = if (active) {
                // Blender-style orange, matching the selection outline color.
                ColorStateList.valueOf(Color.argb(235, 242, 128, 26))
            } else {
                ColorStateList.valueOf(Color.argb(150, 40, 40, 40))
            }
        }
    }

    private fun animateCameraTo(targetAngleX: Float, targetAngleY: Float) {
        val renderer = glView.renderer
        renderer.isOrthographic = true
        val startX = renderer.angleX
        val startY = renderer.angleY
        val deltaX = shortestDelta(startX, targetAngleX)
        val deltaY = shortestDelta(startY, targetAngleY)

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                renderer.angleX = startX + deltaX * t
                renderer.angleY = startY + deltaY * t
                gizmoView.invalidate()
            }
            start()
        }
    }

    private fun shortestDelta(from: Float, to: Float): Float {
        var diff = (to - from) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    /** File > Import: abre el selector de archivos del sistema para elegir un .obj (ver onActivityResult). */
    private fun openObjPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(intent, REQ_IMPORT_OBJ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EXPORT_PNG) {
            // Si cancelo el selector, se olvida lo que estaba pendiente.
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) {
                pendingExport = null
                pendingExportLayerId = -1
                return
            }
            finishExport(uri)
            return
        }
        if (requestCode != REQ_IMPORT_OBJ || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Toast.makeText(this, "Importando...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val stream = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("No se pudo abrir el archivo")
                val mesh = stream.use { ObjLoader.load(it, 1f) }
                runOnUiThread {
                    glView.renderer.addImportedMesh(mesh)
                    glView.requestRender()
                    val uvInfo = if (mesh.hasUvs) "con UVs" else "SIN UVs"
                    Toast.makeText(this, "Importado: " + mesh.triangleCount + " triangulos, " + uvInfo, Toast.LENGTH_LONG).show()
                }
            } catch (ex: Exception) {
                runOnUiThread { Toast.makeText(this, "Error al importar: " + ex.message, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /**
     * Autoguardado: al salir de la app (o abrir el selector de archivos) escribe el proyecto en el mismo slot que Save, en un
     * hilo aparte y sin avisos. Con la escena vacia (por ejemplo despues de File > New) no guarda, para no pisar el ultimo
     * proyecto guardado con nada. Si coincide con un Save manual, uno espera al otro (ver MyGLRenderer.saveProjectToFile).
     */
    private fun autoSave() {
        val renderer = glView.renderer
        if (renderer.sceneObjects.isEmpty()) return
        Thread { renderer.saveProjectToFile() }.start()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        autoSave()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }
}
