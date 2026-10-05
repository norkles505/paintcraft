package com.meshcraft.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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

/**
 * Pantalla unica de la app de pintura: vista 3D (un dedo pinta, dos dedos mueven la camara), panel de pintura abajo,
 * columna de botones a la derecha (zoom, mano, candado, deshacer/rehacer), menu File arriba a la izquierda y menu de
 * puntos de vista (Top, Front...) arriba al centro.
 */
class MainActivity : Activity() {

    private lateinit var glView: MyGLSurfaceView
    private lateinit var gizmoView: GizmoView

    private lateinit var handButton: ImageView
    private lateinit var lockButton: ImageView

    private lateinit var fileButton: ImageView
    private lateinit var layoutTab: ImageView

    private lateinit var rightToolColumn: LinearLayout
    private lateinit var paintPanel: PaintPanel

    /** true mientras el panel de color de PaintPanel esta abierto (ver PaintPanel.onColorPanelVisible). */
    private var colorPanelOpen = false

    private var viewMenuPopup: PopupWindow? = null

    /** Puntos de vista: reutilizan los mismos angulos que el gizmo de ejes (ver GizmoView / animateCameraTo). */
    private data class ViewpointOption(val label: String, val angleX: Float, val angleY: Float)
    private val viewpointOptions = listOf(
        ViewpointOption("Top", 90f, 0f),
        ViewpointOption("Bottom", -90f, 0f),
        ViewpointOption("Front", 0f, 0f),
        ViewpointOption("Back", 0f, 180f),
        ViewpointOption("Right", 0f, -90f),
        ViewpointOption("Left", 0f, 90f)
    )

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

        rightToolColumn = buildToolButtonColumn()
        root.addView(rightToolColumn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = margin
            bottomMargin = margin
        })

        root.addView(buildTopBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            topMargin = margin
        })

        // Panel de pintura (estilo ibisPaint): se agrega al final para quedar encima de todo. Siempre visible (con su triangulo para ocultarlo).
        paintPanel = PaintPanel(this, glView.renderer)
        // Con el panel de color abierto se esconde la columna de botones de la derecha; al cerrarlo vuelve.
        paintPanel.onColorPanelVisible = { open ->
            colorPanelOpen = open
            updateSideColumnsVisibility()
        }
        // El panel cambia de alto al abrir/cerrar sus paneles desplegables: la columna de la derecha se acomoda despues de cada layout.
        paintPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) paintPanel.post { updateRightColumnInset() }
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
        updateRightColumnInset()
    }

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

        // Layout: boton centrado que abre la lista de puntos de vista.
        layoutTab = createIconButton(R.drawable.ic_layout)
        layoutTab.setOnClickListener { toggleViewMenu(layoutTab) }
        layoutTab.background = circleBackground(true)

        val tabsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        tabsParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        bar.addView(layoutTab, tabsParams)

        return bar
    }

    private fun toggleViewMenu(anchor: View) {
        val existing = viewMenuPopup
        if (existing != null && existing.isShowing) {
            existing.dismiss()
            return
        }
        showViewMenu(anchor)
    }

    /** Menu de los 6 puntos de vista (Top, Bottom, Front, Back, Right, Left) - reusan animateCameraTo, igual que el gizmo de ejes. */
    private fun showViewMenu(anchor: View) {
        val density = resources.displayMetrics.density
        val menuColumn = LinearLayout(this)
        menuColumn.orientation = LinearLayout.VERTICAL
        menuColumn.background = menuBackground()
        val vPad = (6 * density).toInt()
        menuColumn.setPadding(vPad, vPad, vPad, vPad)

        val popup = PopupWindow(
            menuColumn,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.isOutsideTouchable = true
        popup.elevation = 12 * density
        popup.setOnDismissListener { viewMenuPopup = null }

        for (option in viewpointOptions) {
            menuColumn.addView(buildSimpleMenuRow(option.label) {
                popup.dismiss()
                animateCameraTo(option.angleX, option.angleY)
            })
        }

        viewMenuPopup = popup
        popup.showAsDropDown(anchor, 0, (8 * density).toInt())
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

    private fun buildSimpleMenuRow(label: String, onClick: () -> Unit): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val hPad = (12 * density).toInt()
        val vPad = (10 * density).toInt()
        row.setPadding(hPad, vPad, hPad, vPad)
        row.isClickable = true
        row.background = menuItemPressBackground()
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val text = TextView(this)
        text.text = label
        text.setTextColor(Color.WHITE)
        text.textSize = 14f
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
     * File > New/Save/Import (ver MyGLRenderer.newProject/saveProjectToFile - un solo slot fijo,
     * con auto-carga al abrir la app). Export (sacar la textura pintada) todavia es un Toast.
     */
    private fun onFileMenuAction(action: String) {
        if (action == "New") {
            glView.renderer.newProject()
            glView.requestRender()
            Toast.makeText(this, "Nuevo proyecto", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Save") {
            val saved = glView.renderer.saveProjectToFile()
            Toast.makeText(this, if (saved) "Proyecto guardado" else "No se pudo guardar", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Import") {
            openObjPicker()
            return
        }
        // TODO: Export de la textura pintada.
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
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

    private fun buildToolButtonColumn(): LinearLayout {
        val density = resources.displayMetrics.density
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        val zoomInBtn = createIconButton(R.drawable.ic_zoom_in)
        val zoomOutBtn = createIconButton(R.drawable.ic_zoom_out)
        handButton = createIconButton(R.drawable.ic_hand)
        lockButton = createIconButton(R.drawable.ic_lock_rotation)
        val undoBtn = createIconButton(R.drawable.ic_undo)
        val redoBtn = createIconButton(R.drawable.ic_redo)

        zoomInBtn.setOnClickListener { glView.renderer.zoomIn() }
        zoomOutBtn.setOnClickListener { glView.renderer.zoomOut() }

        handButton.setOnClickListener {
            glView.touchMode = if (glView.touchMode == TouchMode.ROTATE) TouchMode.PAN else TouchMode.ROTATE
            handButton.background = circleBackground(glView.touchMode == TouchMode.PAN)
        }

        lockButton.setOnClickListener {
            glView.isLocked = !glView.isLocked
            lockButton.background = circleBackground(glView.isLocked)
        }

        // Undo/Redo: pila de snapshots de la escena (ver MyGLRenderer.undo/redo). Todavia no cubre los trazos de pintura.
        undoBtn.setOnClickListener {
            if (glView.renderer.undo()) {
                glView.requestRender()
            } else {
                Toast.makeText(this, "Nada para deshacer", Toast.LENGTH_SHORT).show()
            }
        }
        redoBtn.setOnClickListener {
            if (glView.renderer.redo()) {
                glView.requestRender()
            } else {
                Toast.makeText(this, "Nada para rehacer", Toast.LENGTH_SHORT).show()
            }
        }

        val spacing = (8 * density).toInt()
        for (btn in listOf(zoomInBtn, zoomOutBtn, handButton, lockButton, undoBtn, redoBtn)) {
            (btn.layoutParams as LinearLayout.LayoutParams).topMargin = spacing
            column.addView(btn)
        }

        return column
    }

    /** Esconde la columna de botones de la derecha mientras el panel de color esta abierto; al cerrarlo vuelve. */
    private fun updateSideColumnsVisibility() {
        rightToolColumn.visibility = if (colorPanelOpen) View.GONE else View.VISIBLE
    }

    /** Sube la columna de botones de la derecha (zoom, mano, undo...) para que el panel de pintura no la tape. */
    private fun updateRightColumnInset() {
        val density = resources.displayMetrics.density
        val panelDp = if (paintPanel.height > 0) (paintPanel.height / density).toInt() + 8 else 72
        val lp = rightToolColumn.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = (16 * density).toInt() + (panelDp * density).toInt()
        rightToolColumn.layoutParams = lp
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

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }
}
