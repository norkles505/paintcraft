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
import android.view.View.MeasureSpec
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

enum class AppMode { LAYOUT }
enum class LayoutTool { SELECT, MOVE, ROTATE, SCALE, PAINT }
/** Se mantiene solo porque MyGLRenderer todavia tiene funciones de Modeling que lo usan (se borra junto con ellas en la etapa 2). */
enum class EditSelectMode { VERTEX, EDGE, FACE }

private const val REQ_IMPORT_OBJ = 4101

class MainActivity : Activity() {

    private lateinit var glView: MyGLSurfaceView
    private lateinit var gizmoView: GizmoView
    private lateinit var gizmoLabelView: GizmoLabelView

    private lateinit var handButton: ImageView
    private lateinit var lockButton: ImageView

    private lateinit var fileButton: ImageView
    private lateinit var layoutTab: ImageView

    private lateinit var leftToolColumn: LinearLayout
    private lateinit var selectToolBtn: ImageView
    private lateinit var moveToolBtn: ImageView
    private lateinit var rotateToolBtn: ImageView
    private lateinit var scaleToolBtn: ImageView
    /** Herramienta Paint de Layout (ver LayoutTool.PAINT y MyGLRenderer.paintStart/paintMove): pinta sobre los modelos importados. */
    private lateinit var paintToolBtn: ImageView
    /** Selector Global/Local del gizmo activo (ver TransformOrientation en MyGLRenderer) - solo visible con Move/Rotate/Scale, no con Select ni Paint (ver updateOrientationToggleVisibility). */
    private lateinit var orientationToggleBtn: ImageView

    private var currentLayoutTool: LayoutTool = LayoutTool.SELECT

    /**
     * Eje al que quedo restringido el arrastre actual (X/Y/Z), si empezo tocando el gizmo (ver
     * onViewportDragStart) - null si el arrastre es libre. Aplica con Move (flechas, ver
     * hitTestGizmoAxis), Rotate (anillos, ver hitTestGizmoRotateAxis) y Scale (cubitos, ver
     * hitTestGizmoScaleAxis) activos.
     */
    private var axisLocked: Char? = null

    private var modeMenuPopup: PopupWindow? = null

    // Categorias del menu de Layout (estilo Blender: View / Select / Add / Object).
    private val layoutMenuCategories = listOf("View", "Select", "Add", "Object")

    private val selectModeSubmenuItems = listOf("Set", "Extend", "Subtract", "Difference", "Intersect")
    private val selectMoreLessSubmenuItems = listOf("More", "Less", "Parent", "Child")
    /** Mismos iconos que las categorias de Add > Mesh/Curve/Surface/etc, ya que representan los mismos tipos de objeto. */
    private val selectAllByTypeEntries = listOf(
        AddMenuEntry("Mesh", R.drawable.ic_add_mesh),
        AddMenuEntry("Curve", R.drawable.ic_add_curve),
        AddMenuEntry("Surface", R.drawable.ic_add_surface),
        AddMenuEntry("Metaball", R.drawable.ic_add_metaball),
        AddMenuEntry("Text", R.drawable.ic_add_text),
        AddMenuEntry("Grease Pencil", R.drawable.ic_add_grease_pencil),
        AddMenuEntry("Armature", R.drawable.ic_add_armature),
        AddMenuEntry("Lattice", R.drawable.ic_add_lattice),
        AddMenuEntry("Empty", R.drawable.ic_add_empty)
    )

    // Items simples de Layout > View (placeholder por ahora, no dependen del modelo de escena).
    private val viewSimpleActionItems = listOf(
        "Toolbar", "Sidebar", "Tool Settings", "Adjust Last Operation",
        "Frame Selected", "Frame All", "Perspective/Orthographic", "Local View"
    )
    private val viewTrailingActionItems = listOf("Area")

    /**
     * Navigation: 15 items acordados con el supervisor. Fly/Walk Navigation quedan afuera (pensados
     * para mouse+teclado). Zoom Camera 1:1 no entra porque depende de Camera, fuera de alcance.
     */
    private val viewNavigationSubmenuItems = listOf(
        "Orbit Left", "Orbit Right", "Orbit Up", "Orbit Down", "Orbit Opposite",
        "Roll Left", "Roll Right",
        "Pan Left", "Pan Right", "Pan Up", "Pan Down",
        "Zoom In", "Zoom Out", "Zoom Region",
        "Dolly View"
    )

    /** Viewpoint reutiliza los mismos angulos que ya usa el gizmo de ejes (ver GizmoView / animateCameraTo). */
    private data class ViewpointOption(val label: String, val angleX: Float, val angleY: Float, val planeAxis: Char)
    private val viewpointOptions = listOf(
        ViewpointOption("Top", 90f, 0f, 'Z'),
        ViewpointOption("Bottom", -90f, 0f, 'Z'),
        ViewpointOption("Front", 0f, 0f, 'Y'),
        ViewpointOption("Back", 0f, 180f, 'Y'),
        ViewpointOption("Right", 0f, -90f, 'X'),
        ViewpointOption("Left", 0f, 90f, 'X')
    )

    /** Categorias de Layout > Add, con su icono propio. Solo Mesh tiene contenido real. */
    private data class AddMenuEntry(val label: String, val iconRes: Int)
    private val addMenuEntries = listOf(
        AddMenuEntry("Mesh", R.drawable.ic_add_mesh),
        AddMenuEntry("Curve", R.drawable.ic_add_curve),
        AddMenuEntry("Surface", R.drawable.ic_add_surface),
        AddMenuEntry("Text", R.drawable.ic_add_text),
        AddMenuEntry("Metaball", R.drawable.ic_add_metaball),
        AddMenuEntry("Grease Pencil", R.drawable.ic_add_grease_pencil),
        AddMenuEntry("Armature", R.drawable.ic_add_armature),
        AddMenuEntry("Lattice", R.drawable.ic_add_lattice),
        AddMenuEntry("Empty", R.drawable.ic_add_empty),
        AddMenuEntry("Image", R.drawable.ic_add_image)
    )

    private val meshPrimitiveEntries = listOf(
        AddMenuEntry("Plane", R.drawable.ic_mesh_plane),
        AddMenuEntry("Cube", R.drawable.ic_mesh_cube),
        AddMenuEntry("Circle", R.drawable.ic_mesh_circle),
        AddMenuEntry("UV Sphere", R.drawable.ic_mesh_uv_sphere),
        AddMenuEntry("Ico Sphere", R.drawable.ic_mesh_ico_sphere),
        AddMenuEntry("Cylinder", R.drawable.ic_mesh_cylinder),
        AddMenuEntry("Cone", R.drawable.ic_mesh_cone),
        AddMenuEntry("Torus", R.drawable.ic_mesh_torus),
        AddMenuEntry("Grid", R.drawable.ic_mesh_grid),
        AddMenuEntry("Monkey", R.drawable.ic_mesh_monkey)
    )

    private val curvePrimitiveEntries = listOf(
        AddMenuEntry("Bézier", R.drawable.ic_curve_bezier),
        AddMenuEntry("Circle", R.drawable.ic_curve_circle),
        AddMenuEntry("Nurbs Curve", R.drawable.ic_curve_nurbs_curve),
        AddMenuEntry("Nurbs Circle", R.drawable.ic_curve_nurbs_circle),
        AddMenuEntry("Path", R.drawable.ic_curve_path)
    )

    /**
     * Nurbs Curve / Nurbs Circle aca son objetos distintos a los del menu Curve (mismo nombre,
     * pero flavor Surface) - por eso usan sus propios recursos ic_surface_nurbs_*.
     */
    private val surfacePrimitiveEntries = listOf(
        AddMenuEntry("Nurbs Curve", R.drawable.ic_surface_nurbs_curve),
        AddMenuEntry("Nurbs Circle", R.drawable.ic_surface_nurbs_circle),
        AddMenuEntry("Nurbs Surface", R.drawable.ic_surface_nurbs_surface),
        AddMenuEntry("Nurbs Cylinder", R.drawable.ic_surface_nurbs_cylinder),
        AddMenuEntry("Nurbs Sphere", R.drawable.ic_surface_nurbs_sphere),
        AddMenuEntry("Nurbs Torus", R.drawable.ic_surface_nurbs_torus)
    )

    private val metaballPrimitiveEntries = listOf(
        AddMenuEntry("Ball", R.drawable.ic_metaball_ball),
        AddMenuEntry("Capsule", R.drawable.ic_metaball_capsule),
        AddMenuEntry("Plane", R.drawable.ic_metaball_plane),
        AddMenuEntry("Ellipsoid", R.drawable.ic_metaball_ellipsoid),
        AddMenuEntry("Cube", R.drawable.ic_metaball_cube)
    )

    private val greasePencilPrimitiveEntries = listOf(
        AddMenuEntry("Blank", R.drawable.ic_grease_pencil_blank),
        AddMenuEntry("Stroke", R.drawable.ic_grease_pencil_stroke),
        AddMenuEntry("Monkey", R.drawable.ic_grease_pencil_monkey)
    )

    private val emptyPrimitiveEntries = listOf(
        AddMenuEntry("Plain Axes", R.drawable.ic_empty_plain_axes),
        AddMenuEntry("Arrows", R.drawable.ic_empty_arrows),
        AddMenuEntry("Single Arrow", R.drawable.ic_empty_single_arrow),
        AddMenuEntry("Circle", R.drawable.ic_empty_circle),
        AddMenuEntry("Cube", R.drawable.ic_empty_cube),
        AddMenuEntry("Sphere", R.drawable.ic_empty_sphere),
        AddMenuEntry("Cone", R.drawable.ic_empty_cone)
    )

    private val imagePrimitiveEntries = listOf(
        AddMenuEntry("Reference", R.drawable.ic_image_reference),
        AddMenuEntry("Background", R.drawable.ic_image_background),
        AddMenuEntry("Mesh Plane", R.drawable.ic_image_mesh_plane),
        AddMenuEntry("Empty Image", R.drawable.ic_image_empty_image)
    )

    /** Contenido de Layout > Object: filas simples, sin submenu (decision del usuario). */
    private val objectMenuItems = listOf(
        "Transform", "Set Origin", "Mirror", "Clear", "Apply", "Snap",
        "Duplicate Objects", "Duplicate Linked", "Join", "Copy Objects", "Paste Objects",
        "Collection", "Relations", "Parent", "Modifiers",
        "Link/Transfer Data", "Shade Smooth", "Shade Auto Smooth", "Shade Flat",
        "Convert", "Show/Hide", "Delete"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        glView = MyGLSurfaceView(this)
        gizmoView = GizmoView(this)
        gizmoLabelView = GizmoLabelView(this)

        gizmoView.angleXProvider = { glView.renderer.angleX }
        gizmoView.angleYProvider = { glView.renderer.angleY }
        glView.onRotationChanged = { gizmoView.invalidate() }
        gizmoView.onAxisSelected = { targetX, targetY, axisChar -> animateCameraTo(targetX, targetY, axisChar) }
        glView.onTap = { x, y -> onViewportTap(x, y) }
        glView.onDragMove = { dx, dy, x, y -> onViewportDragMove(dx, dy, x, y) }
        glView.onDragStart = { x, y -> onViewportDragStart(x, y) }
        glView.onDragEnd = { onViewportDragEnd() }

        val root = FrameLayout(this)
        root.addView(
            glView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val density = resources.displayMetrics.density
        root.addView(gizmoLabelView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val gizmoSize = (68 * density).toInt()
        val margin = (16 * density).toInt()
        val gizmoParams = FrameLayout.LayoutParams(gizmoSize, gizmoSize)
        gizmoParams.gravity = Gravity.TOP or Gravity.END
        gizmoParams.topMargin = margin
        gizmoParams.rightMargin = margin
        root.addView(gizmoView, gizmoParams)

        root.addView(buildToolButtonColumn(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = margin
            bottomMargin = margin
        })

        leftToolColumn = buildLeftToolColumn()
        root.addView(leftToolColumn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = margin
        })

        root.addView(buildTopBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            topMargin = margin
        })

        setContentView(root)
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

        // Layout: boton centrado que abre el menu View / Select / Add / Object.
        layoutTab = createIconButton(R.drawable.ic_layout)
        layoutTab.setOnClickListener { toggleModeMenu(AppMode.LAYOUT, layoutTab) }
        layoutTab.background = circleBackground(true)

        val tabsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        tabsParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        bar.addView(layoutTab, tabsParams)

        return bar
    }

    private fun toggleModeMenu(mode: AppMode, anchor: View) {
        val existing = modeMenuPopup
        if (existing != null && existing.isShowing) {
            existing.dismiss()
            return
        }
        showModeMenu(mode, anchor)
    }

    private fun showModeMenu(mode: AppMode, anchor: View) {
        val density = resources.displayMetrics.density
        val menuColumn = LinearLayout(this)
        menuColumn.orientation = LinearLayout.VERTICAL

        val scrollContainer = maxHeightScrollView(360)
        scrollContainer.background = menuBackground()
        val vPad = (6 * density).toInt()
        scrollContainer.setPadding(vPad, vPad, vPad, vPad)
        scrollContainer.addView(menuColumn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val popup = PopupWindow(
            scrollContainer,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.isOutsideTouchable = true
        popup.elevation = 12 * density

        leftToolColumn.visibility = View.GONE
        popup.setOnDismissListener {
            modeMenuPopup = null
            leftToolColumn.visibility = View.VISIBLE
        }

        fillModeMenuWithCategories(menuColumn, mode, popup)

        modeMenuPopup = popup
        popup.showAsDropDown(anchor, 0, (8 * density).toInt())
    }

    /** ScrollView que nunca crece mas alla de maxHeightDp, para que menus largos no se salgan de la pantalla. */
    private fun maxHeightScrollView(maxHeightDp: Int): ScrollView {
        val maxHeightPx = (maxHeightDp * resources.displayMetrics.density).toInt()
        return object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val mode = MeasureSpec.getMode(heightMeasureSpec)
                val newHeightSpec = if (mode == MeasureSpec.UNSPECIFIED) {
                    MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
                } else {
                    val capped = minOf(MeasureSpec.getSize(heightMeasureSpec), maxHeightPx)
                    MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST)
                }
                super.onMeasure(widthMeasureSpec, newHeightSpec)
            }
        }
    }

    private fun fillModeMenuWithCategories(menuColumn: LinearLayout, mode: AppMode, popup: PopupWindow) {
        menuColumn.removeAllViews()
        for (category in layoutMenuCategories) {
            menuColumn.addView(buildSimpleMenuRow(category) {
                fillModeMenuWithCategoryContent(menuColumn, mode, category, popup)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Dispatcher: decide que render function usar segun la categoria elegida. */
    private fun fillModeMenuWithCategoryContent(menuColumn: LinearLayout, mode: AppMode, category: String, popup: PopupWindow) {
        when (category) {
            "Select" -> renderLayoutSelectMenu(menuColumn, popup)
            "View" -> renderViewMenu(menuColumn, mode, popup)
            "Add" -> renderLayoutAddMenu(menuColumn, popup)
            "Object" -> renderLayoutObjectMenu(menuColumn, popup)
            else -> {
                menuColumn.removeAllViews()
                menuColumn.addView(buildSimpleMenuRow("← Volver") {
                    fillModeMenuWithCategories(menuColumn, mode, popup)
                })
                menuColumn.addView(buildSimpleMenuRow("Próximamente") { })
                if (popup.isShowing) {
                    popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
            }
        }
    }

    /** Contenido de Layout > Select, tal cual la estructura confirmada por el usuario. */
    private fun renderLayoutSelectMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.LAYOUT, popup)
        })

        addSelectActionRow(menuColumn, popup, "All")
        addSelectActionRow(menuColumn, popup, "None")
        addSelectActionRow(menuColumn, popup, "Invert")
        menuColumn.addView(buildSimpleMenuRow("Box Select") {
            renderSelectSubmenu(menuColumn, popup, selectModeSubmenuItems)
        })
        menuColumn.addView(buildSimpleMenuRow("Circle Select") {
            renderSelectSubmenu(menuColumn, popup, selectModeSubmenuItems)
        })
        menuColumn.addView(buildSimpleMenuRow("Lasso Select") {
            renderSelectSubmenu(menuColumn, popup, selectModeSubmenuItems)
        })
        addSelectActionRow(menuColumn, popup, "Select Active Camera")
        addSelectActionRow(menuColumn, popup, "Select Mirror")
        addSelectActionRow(menuColumn, popup, "Select Random")
        menuColumn.addView(buildSimpleMenuRow("More/Less") {
            renderSelectSubmenu(menuColumn, popup, selectMoreLessSubmenuItems)
        })
        menuColumn.addView(buildSimpleMenuRow("Select All by Type") {
            renderSelectAllByTypeSubmenu(menuColumn, popup)
        })
        addSelectActionRow(menuColumn, popup, "Select Grouped")
        addSelectActionRow(menuColumn, popup, "Select Linked")
        addSelectActionRow(menuColumn, popup, "Select Pattern")

        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu generico dentro de Select (Box/Circle/Lasso, More/Less), solo texto. */
    private fun renderSelectSubmenu(menuColumn: LinearLayout, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutSelectMenu(menuColumn, popup)
        })
        for (item in items) {
            addSelectActionRow(menuColumn, popup, item)
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu de Select All by Type, con icono por tipo (mismos recursos que Add > Mesh/Curve/etc). */
    private fun renderSelectAllByTypeSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutSelectMenu(menuColumn, popup)
        })
        for (entry in selectAllByTypeEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onSelectMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun addSelectActionRow(menuColumn: LinearLayout, popup: PopupWindow, label: String) {
        menuColumn.addView(buildSimpleMenuRow(label) {
            popup.dismiss()
            onSelectMenuAction(label)
        })
    }

    private fun onSelectMenuAction(action: String) {
        // Solo "None" tiene logica real: deselecciona objetos via el mismo sistema que el tap en el viewport.
        // All/Invert quedan pendientes a proposito: la app solo soporta un objeto seleccionado a la vez.
        if (action == "None") {
            glView.renderer.deselectAll()
            glView.requestRender()
            return
        }
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
    }

    /**
     * Contenido de Layout > View, en el orden acordado con el usuario:
     * items simples -> Viewpoint (funcional, reusa el gizmo) -> Navigation -> Align View -> items simples finales.
     */
    private fun renderViewMenu(menuColumn: LinearLayout, mode: AppMode, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, mode, popup)
        })

        for (item in viewSimpleActionItems) {
            addViewActionRow(menuColumn, popup, item)
        }

        menuColumn.addView(buildSimpleMenuRow("Viewpoint") {
            renderViewpointSubmenu(menuColumn, mode, popup)
        })
        menuColumn.addView(buildSimpleMenuRow("Navigation") {
            renderViewSubmenu(menuColumn, mode, popup, viewNavigationSubmenuItems)
        })
        addViewActionRow(menuColumn, popup, "Align View to Active")

        for (item in viewTrailingActionItems) {
            addViewActionRow(menuColumn, popup, item)
        }

        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * Submenu de Viewpoint: unico contenido de View con logica real, ya que reusa
     * animateCameraTo con los mismos angulos que el gizmo de ejes (ver viewpointOptions).
     */
    private fun renderViewpointSubmenu(menuColumn: LinearLayout, mode: AppMode, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderViewMenu(menuColumn, mode, popup)
        })
        for (option in viewpointOptions) {
            menuColumn.addView(buildSimpleMenuRow(option.label) {
                popup.dismiss()
                animateCameraTo(option.angleX, option.angleY, option.planeAxis)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu generico dentro de View (Navigation, Align View), todos placeholder por ahora. */
    private fun renderViewSubmenu(menuColumn: LinearLayout, mode: AppMode, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderViewMenu(menuColumn, mode, popup)
        })
        for (item in items) {
            addViewActionRow(menuColumn, popup, item)
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun addViewActionRow(menuColumn: LinearLayout, popup: PopupWindow, label: String) {
        menuColumn.addView(buildSimpleMenuRow(label) {
            popup.dismiss()
            onViewMenuAction(label)
        })
    }

    private fun onViewMenuAction(action: String) {
        // TODO: conectar cada accion a su logica real (toggles de UI, camara, area, etc.) mas adelante.
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
    }

    /**
     * Contenido de Layout > Add: categorias con icono propio. Solo Mesh abre su submenu de
     * primitivas; el resto sigue como placeholder (se limpia en una etapa posterior).
     */
    private fun renderLayoutAddMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.LAYOUT, popup)
        })

        for (entry in addMenuEntries) {
            val submenu: ((LinearLayout, PopupWindow) -> Unit)? = when (entry.label) {
                "Mesh" -> { c, p -> renderMeshPrimitivesSubmenu(c, p) }
                "Curve" -> { c, p -> renderPrimitivesSubmenu(c, p, curvePrimitiveEntries) }
                "Surface" -> { c, p -> renderPrimitivesSubmenu(c, p, surfacePrimitiveEntries) }
                "Metaball" -> { c, p -> renderPrimitivesSubmenu(c, p, metaballPrimitiveEntries) }
                "Grease Pencil" -> { c, p -> renderPrimitivesSubmenu(c, p, greasePencilPrimitiveEntries) }
                "Empty" -> { c, p -> renderPrimitivesSubmenu(c, p, emptyPrimitiveEntries) }
                "Image" -> { c, p -> renderPrimitivesSubmenu(c, p, imagePrimitiveEntries) }
                else -> null
            }
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                if (submenu != null) {
                    submenu(menuColumn, popup)
                } else {
                    popup.dismiss()
                    onAddMenuAction(entry.label)
                }
            })
        }

        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Fila de menu con icono + texto, mismo estilo que buildFileMenuItem, reusada para Add. */
    private fun buildAddMenuItem(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
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

    /** Submenu de primitivas dentro de Add > Mesh: todas crean geometria real via MyGLRenderer.addXxx(). */
    private fun renderMeshPrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in meshPrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                val r = glView.renderer
                when (entry.label) {
                    "Plane" -> r.addPlane()
                    "Ico Sphere" -> r.addIcoSphere()
                    "UV Sphere" -> r.addUvSphere()
                    "Circle" -> r.addCircle()
                    "Cylinder" -> r.addCylinder()
                    "Cone" -> r.addCone()
                    "Grid" -> r.addGrid()
                    "Torus" -> r.addTorus()
                    "Monkey" -> r.addMonkey()
                    "Cube" -> r.addCube()
                    else -> onAddMenuAction(entry.label)
                }
                glView.requestRender()
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu generico de primitivas (Curve, Surface, Metaball, Grease Pencil, Empty, Image): todo placeholder. */
    private fun renderPrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow, entries: List<AddMenuEntry>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in entries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun onAddMenuAction(action: String) {
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
    }

    private fun renderLayoutObjectMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.LAYOUT, popup)
        })

        for (item in objectMenuItems) {
            menuColumn.addView(buildSimpleMenuRow(item) {
                popup.dismiss()
                onObjectMenuAction(item)
            })
        }

        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun onObjectMenuAction(action: String) {
        when (action) {
            "Delete" -> {
                val hadSelection = glView.renderer.deleteSelectedObject()
                glView.requestRender()
                if (!hadSelection) Toast.makeText(this, "No hay objeto seleccionado", Toast.LENGTH_SHORT).show()
            }
            "Show/Hide" -> {
                val didSomething = glView.renderer.toggleShowHideSelected()
                glView.requestRender()
                if (!didSomething) Toast.makeText(this, "No hay objeto seleccionado ni oculto", Toast.LENGTH_SHORT).show()
            }
            "Set Origin" -> {
                val hadSelection = glView.renderer.setOriginToGeometrySelected()
                glView.requestRender()
                if (!hadSelection) Toast.makeText(this, "No hay objeto seleccionado", Toast.LENGTH_SHORT).show()
            }
            "Apply" -> {
                val hadSelection = glView.renderer.applySelectedObjectTransform()
                glView.requestRender()
                if (!hadSelection) Toast.makeText(this, "No hay objeto seleccionado", Toast.LENGTH_SHORT).show()
            }
            "Clear" -> {
                val hadSelection = glView.renderer.clearSelectedObjectTransform()
                glView.requestRender()
                if (!hadSelection) Toast.makeText(this, "No hay objeto seleccionado", Toast.LENGTH_SHORT).show()
            }
            "Duplicate Objects" -> {
                val duplicate = glView.renderer.duplicateSelectedObject()
                glView.requestRender()
                if (duplicate == null) Toast.makeText(this, "No hay objeto seleccionado", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun buildLeftToolColumn(): LinearLayout {
        val density = resources.displayMetrics.density
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        selectToolBtn = createIconButton(R.drawable.ic_select_box)
        moveToolBtn = createIconButton(R.drawable.ic_move)
        rotateToolBtn = createIconButton(R.drawable.ic_rotate)
        scaleToolBtn = createIconButton(R.drawable.ic_scale)
        paintToolBtn = createIconButton(R.drawable.ic_paint)
        // Icono inicial Local (ver default de TransformOrientation en MyGLRenderer) - se actualiza
        // en cada toggle (ver toggleTransformOrientation) para reflejar siempre el estado actual.
        orientationToggleBtn = createIconButton(R.drawable.ic_orientation_local)

        selectToolBtn.setOnClickListener { setLayoutTool(LayoutTool.SELECT) }
        moveToolBtn.setOnClickListener { setLayoutTool(LayoutTool.MOVE) }
        rotateToolBtn.setOnClickListener { setLayoutTool(LayoutTool.ROTATE) }
        scaleToolBtn.setOnClickListener { setLayoutTool(LayoutTool.SCALE) }
        paintToolBtn.setOnClickListener { setLayoutTool(LayoutTool.PAINT) }
        orientationToggleBtn.setOnClickListener { toggleTransformOrientation() }

        val spacing = (8 * density).toInt()
        for (btn in listOf(selectToolBtn, moveToolBtn, rotateToolBtn, scaleToolBtn, paintToolBtn)) {
            (btn.layoutParams as LinearLayout.LayoutParams).topMargin = spacing
            column.addView(btn)
        }
        (selectToolBtn.layoutParams as LinearLayout.LayoutParams).topMargin = 0
        // Separado con el doble de margen, para marcar que es una propiedad de las herramientas de transformacion y no una herramienta mas.
        (orientationToggleBtn.layoutParams as LinearLayout.LayoutParams).topMargin = spacing * 2
        column.addView(orientationToggleBtn)
        updateOrientationToggleVisibility()

        updateLayoutToolHighlight()

        return column
    }

    /**
     * Actualiza la visibilidad de orientationToggleBtn: visible solo con Move/Rotate/Scale (donde
     * hay gizmo dibujado), oculto con Select y Paint (no habria nada en pantalla que el boton afecte).
     */
    private fun updateOrientationToggleVisibility() {
        orientationToggleBtn.visibility = if (currentLayoutTool == LayoutTool.SELECT || currentLayoutTool == LayoutTool.PAINT) View.GONE else View.VISIBLE
    }

    /**
     * Alterna transformOrientation entre GLOBAL y LOCAL (ver enum en MyGLRenderer), actualiza el
     * icono del boton y muestra un Toast corto confirmando "Global" o "Local".
     */
    private fun toggleTransformOrientation() {
        val renderer = glView.renderer
        renderer.transformOrientation = if (renderer.transformOrientation == TransformOrientation.GLOBAL) {
            TransformOrientation.LOCAL
        } else {
            TransformOrientation.GLOBAL
        }
        val isGlobal = renderer.transformOrientation == TransformOrientation.GLOBAL
        orientationToggleBtn.setImageResource(
            if (isGlobal) R.drawable.ic_orientation_global else R.drawable.ic_orientation_local
        )
        Toast.makeText(this, if (isGlobal) "Global" else "Local", Toast.LENGTH_SHORT).show()
        glView.requestRender()
    }

    private fun setLayoutTool(tool: LayoutTool) {
        // El gizmo se muestra con Move (flechas), Rotate (anillos) y Scale (cubitos).
        glView.renderer.gizmoMode = when (tool) {
            LayoutTool.MOVE -> GizmoMode.MOVE
            LayoutTool.ROTATE -> GizmoMode.ROTATE
            LayoutTool.SCALE -> GizmoMode.SCALE
            else -> null
        }
        currentLayoutTool = tool
        // Con Paint se oculta la grilla del piso para que no estorbe sobre el modelo.
        glView.renderer.showGrid = (tool != LayoutTool.PAINT)
        updateOrientationToggleVisibility()
        updateLayoutToolHighlight()
    }

    private fun updateLayoutToolHighlight() {
        selectToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.SELECT)
        moveToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.MOVE)
        rotateToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.ROTATE)
        scaleToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.SCALE)
        paintToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.PAINT)
    }

    /**
     * ACTION_DOWN en el viewport. Con Paint: pinta en el hilo de render. Con Move, Rotate o Scale
     * y un objeto seleccionado: guarda un snapshot de Undo y hace el hit-test contra el gizmo
     * correspondiente (flechas via hitTestGizmoAxis, anillos via hitTestGizmoRotateAxis, cubitos
     * via hitTestGizmoScaleAxis). Si el dedo toco el gizmo, el arrastre queda restringido a ese eje
     * (ver onViewportDragMove); si no, cae al gesto libre.
     */
    private fun onViewportDragStart(x: Float, y: Float) {
        axisLocked = null
        if (currentLayoutTool == LayoutTool.PAINT) {
            // Paint: el toque pinta en el hilo de render (usa OpenGL), ver MyGLRenderer.paintStart.
            glView.queueEvent { glView.renderer.paintStart(x, y) }
            return
        }
        if (currentLayoutTool == LayoutTool.MOVE || currentLayoutTool == LayoutTool.ROTATE || currentLayoutTool == LayoutTool.SCALE) {
            if (glView.renderer.sceneObjects.any { it.selected }) {
                glView.renderer.pushUndoSnapshot()
            }
        }
        axisLocked = when (currentLayoutTool) {
            LayoutTool.MOVE -> glView.renderer.hitTestGizmoAxis(x, y)
            LayoutTool.ROTATE -> glView.renderer.hitTestGizmoRotateAxis(x, y)
            LayoutTool.SCALE -> glView.renderer.hitTestGizmoScaleAxis(x, y)
            else -> null
        }
        glView.renderer.activeRotateAxis = null
        glView.renderer.activeMoveAxis = if (currentLayoutTool == LayoutTool.MOVE) axisLocked else null
        glView.renderer.activeScaleAxis = if (currentLayoutTool == LayoutTool.SCALE) axisLocked else null
        gizmoLabelView.labelText = null
        if (currentLayoutTool == LayoutTool.ROTATE && axisLocked != null) {
            val axisNow = axisLocked!!
            glView.renderer.activeRotateAxis = axisNow
            val anchor = glView.renderer.computeRotateLabelAnchor()
            if (anchor != null) {
                gizmoLabelView.labelText = axisNow.toString()
                gizmoLabelView.labelX = anchor[0]
                gizmoLabelView.labelY = anchor[1]
            }
        }
        gizmoLabelView.invalidate()
    }

    /** ACTION_UP en el viewport: suelta el eje bloqueado y limpia el resaltado del eje agarrado y la etiqueta de texto. */
    private fun onViewportDragEnd() {
        axisLocked = null
        glView.renderer.activeRotateAxis = null
        glView.renderer.activeMoveAxis = null
        glView.renderer.activeScaleAxis = null
        gizmoLabelView.labelText = null
        gizmoLabelView.invalidate()
    }

    /** Tap en el viewport 3D: con la herramienta Select activa, selecciona el objeto tocado (o deselecciona todo si el tap cae en espacio vacio). */
    private fun onViewportTap(x: Float, y: Float) {
        if (currentLayoutTool != LayoutTool.SELECT) return
        glView.renderer.selectObjectAt(x, y)
        glView.requestRender()
    }

    /**
     * Arrastre en el viewport 3D. Con Paint pinta y NO rota la camara (devolver true consume el
     * gesto, ver MyGLSurfaceView). Con Move/Rotate/Scale transforma el objeto seleccionado (libre,
     * o restringido a eje si el arrastre empezo tocando el gizmo). Con Select devuelve false y el
     * gesto rota la camara.
     */
    private fun onViewportDragMove(dx: Float, dy: Float, x: Float, y: Float): Boolean {
        if (currentLayoutTool == LayoutTool.PAINT) {
            glView.queueEvent { glView.renderer.paintMove(x, y) }
            return true
        }
        return when (currentLayoutTool) {
            LayoutTool.MOVE -> {
                val axis = axisLocked
                if (axis != null) {
                    glView.renderer.moveSelectedObjectOnAxis(dx, dy, axis)
                } else {
                    glView.renderer.moveSelectedObject(dx, dy)
                }
                true
            }
            LayoutTool.ROTATE -> {
                val axis = axisLocked
                if (axis != null) {
                    glView.renderer.updateActiveRotateCurrentDir(x, y, axis)
                    glView.renderer.rotateSelectedObjectOnAxis(dx, dy, axis)
                } else {
                    glView.renderer.rotateSelectedObject(dx, dy)
                }
                true
            }
            LayoutTool.SCALE -> {
                val axis = axisLocked
                if (axis != null) {
                    glView.renderer.scaleSelectedObjectOnAxis(dx, dy, axis)
                } else {
                    glView.renderer.scaleSelectedObject(dy)
                }
                true
            }
            else -> false
        }
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

        menuColumn.addView(buildFileMenuItem(R.drawable.ic_new, "New") {
            popup.dismiss()
            onFileMenuAction("New")
        })
        menuColumn.addView(buildFileMenuItem(R.drawable.ic_save, "Save") {
            popup.dismiss()
            onFileMenuAction("Save")
        })
        menuColumn.addView(buildFileMenuItem(R.drawable.ic_import, "Import") {
            popup.dismiss()
            onFileMenuAction("Import")
        })
        menuColumn.addView(buildFileMenuItem(R.drawable.ic_export, "Export") {
            popup.dismiss()
            onFileMenuAction("Export")
        })

        popup.showAsDropDown(anchor, 0, (8 * density).toInt())
    }

    private fun buildFileMenuItem(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        return buildAddMenuItem(iconRes, label, onClick)
    }

    /**
     * File > New/Save/Import (ver MyGLRenderer.newProject/saveProjectToFile - un solo slot fijo,
     * con auto-carga al abrir la app). Export (sacar la textura pintada) todavia es un Toast.
     */
    private fun onFileMenuAction(action: String) {
        if (action == "New") {
            glView.renderer.newProject()
            onViewportDragEnd()
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

        // Undo/Redo: pila de snapshots completos de sceneObjects (ver MyGLRenderer.undo/redo).
        undoBtn.setOnClickListener {
            if (glView.renderer.undo()) {
                onViewportDragEnd()
                glView.requestRender()
            } else {
                Toast.makeText(this, "Nada para deshacer", Toast.LENGTH_SHORT).show()
            }
        }
        redoBtn.setOnClickListener {
            if (glView.renderer.redo()) {
                onViewportDragEnd()
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
                android.content.res.ColorStateList.valueOf(Color.argb(235, 242, 128, 26))
            } else {
                android.content.res.ColorStateList.valueOf(Color.argb(150, 40, 40, 40))
            }
        }
    }

    private fun animateCameraTo(targetAngleX: Float, targetAngleY: Float, axisChar: Char) {
        val renderer = glView.renderer
        renderer.isOrthographic = true
        renderer.gridPlaneAxis = axisChar
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
