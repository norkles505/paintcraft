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

enum class AppMode { LAYOUT, MODELING, UV_EDITING }
enum class LayoutTool { SELECT, MOVE, ROTATE, SCALE, PAINT }
/** Modo de selección de sub-elemento dentro de Edit Mode (Modeling) - toggle Vertex/Edge/Face, mismo criterio que Blender. */
enum class EditSelectMode { VERTEX, EDGE, FACE }

private const val REQ_IMPORT_OBJ = 4101

class MainActivity : Activity() {

    private lateinit var glView: MyGLSurfaceView
    private lateinit var gizmoView: GizmoView
    private lateinit var gizmoLabelView: GizmoLabelView

    private lateinit var handButton: ImageView
    private lateinit var lockButton: ImageView

    private lateinit var fileButton: ImageView
    private lateinit var generalButton: ImageView
    private lateinit var dataButton: ImageView
    private lateinit var layoutTab: ImageView
    private lateinit var modelingTab: ImageView
    private lateinit var uvEditingTab: ImageView

    private lateinit var leftToolColumn: LinearLayout
    private lateinit var selectToolBtn: ImageView
    private lateinit var moveToolBtn: ImageView
    private lateinit var rotateToolBtn: ImageView
    private lateinit var scaleToolBtn: ImageView
    /** Herramienta Paint de Layout (ver LayoutTool.PAINT y MyGLRenderer.paintStart/paintMove): pinta sobre los modelos importados. */
    private lateinit var paintToolBtn: ImageView
    /** Selector Global/Local del gizmo activo (ver TransformOrientation en MyGLRenderer) - solo visible con Move/Rotate/Scale, no con Select (ver updateOrientationToggleVisibility). */
    private lateinit var orientationToggleBtn: ImageView

    private lateinit var modelingToolWrapper: View
    private lateinit var modelingToolColumnInner: LinearLayout
    private lateinit var modelingSelectBtn: ImageView
    private lateinit var modelingMoveBtn: ImageView
    private lateinit var modelingRotateBtn: ImageView
    private lateinit var modelingScaleBtn: ImageView
    private var currentModelingTool: LayoutTool = LayoutTool.SELECT
    /** Herramienta "extra" activa (Extrude Region, Bevel, etc.) - null si esta activa una de las 4 basicas. */
    private var currentModelingExtraTool: String? = null
    private val modelingExtraToolButtons = mutableMapOf<String, ImageView>()

    private var currentMode: AppMode = AppMode.LAYOUT
    private var currentLayoutTool: LayoutTool = LayoutTool.SELECT

    /**
     * Fila inferior de Modeling (Edit Mode): toggle Vertex/Edge/Face select mode, solo texto/icono
     * por ahora (ver setEditSelectMode) - conectar a raycast real de sub-elementos cuando exista
     * el modelo de datos editable (Fase 1 de Edit Mode).
     */
    private lateinit var editSelectModeRow: LinearLayout
    private lateinit var vertexModeBtn: ImageView
    private lateinit var edgeModeBtn: ImageView
    private lateinit var faceModeBtn: ImageView
    private var currentEditSelectMode: EditSelectMode = EditSelectMode.VERTEX
    private lateinit var extendSelectBtn: ImageView
    /** Ver toggleExtendSelect() para el comportamiento completo (Paso 3 del roadmap - multi-seleccion). */
    private var extendSelectEnabled: Boolean = false

    /**
     * Eje al que quedo restringido el arrastre actual (X/Y/Z), si empezo tocando el gizmo (ver
     * onViewportDragStart) - null si el arrastre es libre. Aplica con Move (flechas, ver
     * hitTestGizmoAxis), Rotate (anillos, ver hitTestGizmoRotateAxis) y Scale (cubitos, ver
     * hitTestGizmoScaleAxis) activos.
     */
    private var axisLocked: Char? = null

    /**
     * Box Select (Modeling > Select > Box Select, ver armBoxSelect): boxSelectActive queda en true
     * mientras la herramienta esta "armada" (esperando el primer arrastre) hasta boxSelectDragging
     * queda en true SOLO durante el gesto de arrastre en si (ACTION_DOWN a ACTION_UP) - se usa para
     * distinguir en onViewportDragMove/End si el gesto actual es de Box Select o el normal de
     * Move/Rotate/Scale. Un solo arrastre alcanza (simplificacion deliberada: a diferencia de
     * Blender, donde B queda armado hasta Escape/otra herramienta, aca se desarma solo despues de
     * completar un rectangulo - mismo criterio "de un solo toque" que ya usan Extrude Region y el
     * resto de las herramientas "extra" de Modeling).
     */
    private var boxSelectActive: Boolean = false
    private var boxSelectDragging: Boolean = false
    private var boxSelectStartX: Float = 0f
    private var boxSelectStartY: Float = 0f
    private var boxSelectCurrentX: Float = 0f
    private var boxSelectCurrentY: Float = 0f
    /** Evita que el ACTION_UP que cierra un Box Select tambien dispare un tap normal (ver MyGLSurfaceView: onDragEnd y onTap se llaman los dos en el mismo ACTION_UP). */
    private var suppressNextTap: Boolean = false
    private lateinit var boxSelectOverlay: BoxSelectOverlayView

    /**
     * Circle Select (Modeling > Select > Circle Select, ver armCircleSelect): mismo criterio de
     * estados que Box Select (circleSelectActive = "armado, esperando el primer arrastre",
     * circleSelectDragging = arrastre en curso), pero el gesto es distinto - ACTION_DOWN fija el
     * CENTRO (ver CircleSelectOverlayView.setCenter) y el radio se recalcula en cada ACTION_MOVE
     * como la distancia centro-dedo (setRadiusToPoint), en vez de dos esquinas cualquiera.
     */
    private var circleSelectActive: Boolean = false
    private var circleSelectDragging: Boolean = false
    private var circleSelectCenterX: Float = 0f
    private var circleSelectCenterY: Float = 0f
    private var circleSelectRadius: Float = 0f
    private lateinit var circleSelectOverlay: CircleSelectOverlayView

    private var lassoSelectActive: Boolean = false
    private var lassoSelectDragging: Boolean = false
    private val lassoPoints = mutableListOf<FloatArray>()
    private lateinit var lassoSelectOverlay: LassoSelectOverlayView

    /**
     * Knife (Modeling > Mesh > Knife, ver armKnifeTool/onKnifeTap): a diferencia de Box/Circle/
     * Lasso Select (un solo arrastre) esto es TAP-based, dos toques - knifeActive queda en true
     * mientras la herramienta esta "armada" esperando toques; knifeFirstEdge/knifeFirstT guardan
     * el punto de entrada (primer toque) hasta que llega el segundo toque (punto de salida), que
     * dispara el corte real (ver MyGLRenderer.knifeCutBetween) y desarma la herramienta sola,
     * mismo criterio de "un solo gesto" que ya usan las otras herramientas de Select.
     */
    private var knifeActive: Boolean = false
    private var knifeFirstEdge: MeshEdge? = null
    private var knifeFirstT: Float = 0f

    /**
     * Poly Build (Modeling > barra izquierda > Poly Build, ver MyGLRenderer.polyBuildConnect):
     * a diferencia de Knife (2 toques y se desarma sola), esto queda armado indefinidamente -
     * polyBuildActive se apaga solo al elegir otra herramienta (ver setModelingTool), no despues
     * de cada toque, para poder seguir construyendo una cadena de vertices/aristas sin tener que
     * volver a tocar el boton entre uno y el siguiente (mismo espiritu que el Poly Build real de
     * Blender: una tira continua). polyBuildAnchorVertex es el vertice "punta" de la cadena en
     * este momento: null significa que todavia no se toco el primer vertice (el toque que sigue
     * fija el punto de partida, ver onPolyBuildTap); una vez fijado, cada toque siguiente conecta
     * desde ahi y el resultado (vertice existente o nuevo) pasa a ser el nuevo ancla.
     */
    private var polyBuildActive: Boolean = false
    private var polyBuildAnchorVertex: Int? = null

    private var modeMenuPopup: PopupWindow? = null

    // Categorias del menu de cada modo (estilo Blender: View / Select / Add / Object).
    // Por ahora solo Layout tiene contenido definido; Modeling y UV Editing quedan pendientes.
    private val layoutMenuCategories = listOf("View", "Select", "Add", "Object")

    /**
     * Categorias de Modeling (Edit Mode), ya cerradas con el supervisor. View se reutiliza tal cual
     * de Layout. Select/Add/Mesh/Vertex/Edge/Face/UV son contenido nuevo, todavia sin cargar (placeholder).
     */
    private val modelingMenuCategories = listOf("View", "Select", "Add", "Mesh", "Vertex", "Edge", "Face", "UV")

    /** Categorias de UV Editing, ya cerradas con el supervisor. Contenido nuevo, no reutiliza nada de Layout/Modeling. */
    private val uvEditingMenuCategories = listOf("View", "Select", "Image", "UV")


    /**
     * Barra de herramientas izquierda de Modeling (Edit Mode), 16 items confirmados con el supervisor.
     * Select Box/Move/Rotate/Scale reusan los iconos que ya existen en Layout. Los otros 12 todavia no
     * tienen icono diseñado, asi que van como fila de solo texto por ahora (buildSimpleMenuRow) - el dia
     * que existan los iconos, cambiar esas filas por createIconButton es un cambio quirurgico, sin tocar
     * el resto de la logica.
     */
    private val modelingToolEntries = listOf(
        AddMenuEntry("Extrude Region", R.drawable.ic_modeling_extrude_region),
        AddMenuEntry("Inset Faces", R.drawable.ic_modeling_inset_faces),
        AddMenuEntry("Bevel", R.drawable.ic_modeling_bevel),
        AddMenuEntry("Loop Cut", R.drawable.ic_modeling_loop_cut),
        AddMenuEntry("Knife", R.drawable.ic_modeling_knife),
        AddMenuEntry("Poly Build", R.drawable.ic_modeling_poly_build),
        AddMenuEntry("Spin", R.drawable.ic_modeling_spin),
        AddMenuEntry("Smooth", R.drawable.ic_modeling_smooth),
        AddMenuEntry("Edge Slide", R.drawable.ic_modeling_edge_slide),
        AddMenuEntry("Shrink/Fatten", R.drawable.ic_modeling_shrink_fatten),
        AddMenuEntry("Shear", R.drawable.ic_modeling_shear),
        AddMenuEntry("Rip Region", R.drawable.ic_modeling_rip_region)
    )


    private val selectModeSubmenuItems = listOf("Set", "Extend", "Subtract", "Difference", "Intersect")
    private val selectMoreLessSubmenuItems = listOf("More", "Less", "Parent", "Child")
    /** Sin Parent/Child (jerarquia de objetos) - no aplica seleccionando geometria en Edit Mode. */
    private val modelingMoreLessSubmenuItems = listOf("More", "Less")
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
    // Nota: Asset Shelf, Cameras y View Regions se sacaron a proposito - dependen de sistemas
    // que la app todavia no tiene (biblioteca de assets, objetos Camara, layout configurable).
    private val viewSimpleActionItems = listOf(
        "Toolbar", "Sidebar", "Tool Settings", "Adjust Last Operation",
        "Frame Selected", "Frame All", "Perspective/Orthographic", "Local View"
    )
    private val viewTrailingActionItems = listOf("Area")

    /**
     * Navigation: 15 items acordados con el supervisor. Se sacaron "Center View to Cursor"
     * (pertenece a Align View) y "Center View to Selected" (no existe en el menu real de Blender).
     * Fly/Walk Navigation quedan afuera: no dependen de un sistema faltante sino de definir como
     * se controlarian por touch (son modos pensados para mouse+teclado/WASD). Zoom Camera 1:1 no
     * entra porque depende de Camera, fuera de alcance.
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

    /**
     * Categorias de Layout > Add, con su icono propio (a diferencia de Select/View que son solo texto).
     * Todas placeholder por ahora, incluida Mesh (su submenu de primitivas queda pendiente hasta
     * que existan los iconos de cada primitiva). Camera / Collection Instance / Monkey quedan afuera
     * por ahora, sin icono todavia.
     */
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

    /**
     * Contenido de Layout > Object: todo como filas simples, sin submenu (decisión del usuario).
     * Se sacó "Quick Effects" (dependia de Particles, fuera de alcance) y ademas Asset, Constraints,
     * Clean Up y Delete Global - todos dependen de sistemas que la app no tiene todavia (biblioteca
     * de assets, constraints pensados para animacion, historial de datos para limpiar, objetos
     * enlazados entre escenas). Mismo criterio que se aplico en Layout > View.
     */
    private val objectMenuItems = listOf(
        "Transform", "Set Origin", "Mirror", "Clear", "Apply", "Snap",
        "Duplicate Objects", "Duplicate Linked", "Join", "Copy Objects", "Paste Objects",
        "Collection", "Relations", "Parent", "Modifiers",
        "Link/Transfer Data", "Shade Smooth", "Shade Auto Smooth", "Shade Flat",
        "Convert", "Show/Hide", "Delete"
    )

    /**
     * Contenido de Modeling > Mesh/Vertex/Edge/Face/UV: 5 listas cerradas con el supervisor.
     * Todo como filas planas por ahora (estructura primero); Snap y Face Data entran como categoria
     * pero su submenu interno queda pendiente de detallar mas adelante (items dependientes del
     * Cursor 3D en el caso de Snap). Se excluyen a proposito: Set Attribute (Mesh, deshabilitado en
     * Blender - depende de Geometry Nodes), Extrude to Cursor or Add y Hooks (Vertex, dependen de
     * Cursor 3D y del modificador Hook respectivamente), Mark/Clear Freestyle Edge (Edge, depende de
     * Freestyle). Pendientes sin decidir todavia, tambien afuera: Weights (Mesh), Vertex Groups,
     * Blend from Shape y Propagate to Shapes (Vertex) - se resuelven junto con Armature/Shape Keys.
     */
    private val modelingMeshMenuItems = listOf(
        "Transform", "Mirror", "Snap", "Duplicate", "Extrude", "Merge", "Split", "Separate",
        "Bisect", "Knife Project", "Knife Topology Tool", "Convex Hull",
        "Symmetrize", "Snap to Symmetry", "Normals", "Shading", "Sort Elements",
        "Show/Hide", "Clean Up", "Delete"
    )
    private val modelingVertexMenuItems = listOf(
        "Extrude Vertices", "Bevel Vertices", "New Edge/Face from Vertices",
        "Connect Vertex Path", "Connect Vertex Pairs",
        "Rip Vertices", "Rip Vertices and Fill", "Rip Vertices and Extend",
        "Slide Vertices", "Smooth Vertices", "Smooth Vertices (Laplacian)",
        "Vertex Crease", "Make Vertex Parent"
    )
    private val modelingEdgeMenuItems = listOf(
        "Extrude Edges", "Bevel Edges", "Bridge Edge Loops", "Screw",
        "Subdivide", "Subdivide Edge-Ring", "Un-Subdivide",
        "Rotate Edge CW", "Rotate Edge CCW",
        "Edge Slide", "Loop Cut and Slide", "Offset Edge Slide",
        "Edge Bevel Weight", "Edge Crease",
        "Mark Seam", "Clear Seam",
        "Mark Sharp", "Clear Sharp", "Mark Sharp from Vertices", "Clear Sharp from Vertices",
        "Set Sharpness by Angle"
    )
    private val modelingFaceMenuItems = listOf(
        "Extrude Faces", "Extrude Faces Along Normals", "Extrude Individual Faces",
        "Inset Faces", "Poke Faces",
        "Triangulate Faces", "Triangles to Quads", "Solidify Faces", "Wireframe",
        "Fill", "Grid Fill", "Beautify Faces",
        "Intersect (Knife)", "Intersect (Boolean)", "Weld Edges into Faces",
        "Shade Smooth", "Shade Flat",
        "Face Data"
    )
    /** UV Editing ya esta 100% confirmado en el alcance - los 14 entran sin excepcion. */
    private val modelingUvMenuItems = listOf(
        "Unwrap Angle Based", "Unwrap Conformal", "Unwrap Minimum Stretch",
        "Smart UV Project", "Lightmap Pack", "Follow Active Quads",
        "Cube Projection", "Cylinder Projection", "Sphere Projection",
        "Project from View", "Project from View (Bounds)",
        "Mark Seam", "Clear Seam", "Reset"
    )

    /**
     * Contenido de UV Editing > View/Select/Image/UV: 4 listas cerradas con el supervisor. Se saca
     * "Asset Shelf" (deshabilitado en Blender, depende de biblioteca de assets), "Center View to
     * Cursor" (depende de un Cursor 2D propio del editor UV, mismo criterio que el Cursor 3D: se
     * pospone) y "Open Cached Render" (depende de Render, fuera de alcance). Zoom, Area, Select All
     * by Trait y Snap entran como categoria pero su contenido interno queda pendiente de detallar.
     */
    private val uvViewSimpleItems = listOf("Toolbar", "Sidebar", "Tool Settings", "Adjust Last Operation", "Update Automatically", "Show Metadata", "Frame Selected", "Frame All")
    /**
     * Ojo al implementar: a diferencia de Layout/Modeling, aca solo Lasso Select tiene submenu propio
     * (reusa selectModeSubmenuItems) - Box Select y Circle Select son acciones directas. Box Select
     * Pinned depende de que exista "pin" de vertices UV (ver Pin/Unpin/Invert Pins en el menu UV).
     */
    private val uvSelectSimpleItems = listOf("All", "None", "Invert", "Box Select", "Box Select Pinned", "Circle Select")
    private val uvSelectTrailingItems = listOf("More", "Less", "Select Similar", "Select Linked", "Select Split")
    private val uvImageMenuItems = listOf("New...", "Open...", "Copy", "Paste", "Save All Images")
    private val uvUvMenuItems = listOf("Transform", "Mirror", "Snap", "Round to Pixels", "Constrain to Image Bounds", "Merge", "Split", "Rip Move UVs", "Live Unwrap", "Unwrap", "Pin", "Unpin", "Invert Pins", "Mark Seam", "Clear Seam", "Seams from Islands", "Pack Islands", "Average Islands Scale", "Arrange/Align Islands", "Set User Region", "Custom Region", "Minimize Stretch", "Stitch", "Align", "Align Rotation", "Move on Axis", "Copy UVs", "Paste UVs", "Show/Hide Faces", "Reset")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        glView = MyGLSurfaceView(this)
        gizmoView = GizmoView(this)
        gizmoLabelView = GizmoLabelView(this)
        boxSelectOverlay = BoxSelectOverlayView(this)
        circleSelectOverlay = CircleSelectOverlayView(this)
        lassoSelectOverlay = LassoSelectOverlayView(this)

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
        root.addView(boxSelectOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(circleSelectOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(lassoSelectOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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

        modelingToolWrapper = maxHeightScrollView(500)
        (modelingToolWrapper as ScrollView).isVerticalScrollBarEnabled = false
        modelingToolColumnInner = buildModelingToolColumn()
        (modelingToolWrapper as ScrollView).addView(modelingToolColumnInner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        modelingToolWrapper.visibility = View.GONE
        root.addView(modelingToolWrapper, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = margin
        })

        editSelectModeRow = buildEditSelectModeRow()
        editSelectModeRow.visibility = View.GONE
        root.addView(editSelectModeRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = margin
            bottomMargin = margin
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

        // Layout / Modeling / UV Editing: pestañas de modo, centradas.
        val tabsRow = LinearLayout(this)
        tabsRow.orientation = LinearLayout.HORIZONTAL

        layoutTab = createIconButton(R.drawable.ic_layout)
        modelingTab = createIconButton(R.drawable.ic_modeling)
        uvEditingTab = createIconButton(R.drawable.ic_uv_editing)

        layoutTab.setOnClickListener { onModeTabClicked(AppMode.LAYOUT, layoutTab) }
        modelingTab.setOnClickListener { onModeTabClicked(AppMode.MODELING, modelingTab) }
        uvEditingTab.setOnClickListener { onModeTabClicked(AppMode.UV_EDITING, uvEditingTab) }

        // General / Data: no son un AppMode mas (no cambian herramientas ni gizmo, ver charla con
        // el usuario) - dos botones independientes, mismo estilo que los tabs de modo, que abren
        // su propio popup (mismo patron que showFileMenu). Por ahora solo "Proximamente"
        // (renderPendingSubmenu) - el plan acordado para cuando se retome esto es sumar el
        // Outliner (lista de objetos) y un tab Object con transform numerico como primer
        // contenido real, ya que ninguno de los dos depende de sistemas todavia sin construir
        // (a diferencia de Modifier/Material/Data de malla, que si).
        generalButton = createIconButton(R.drawable.ic_general)
        dataButton = createIconButton(R.drawable.ic_data)
        generalButton.setOnClickListener { showGeneralOrDataMenu(it, "General") }
        dataButton.setOnClickListener { showGeneralOrDataMenu(it, "Data") }

        val spacing = (8 * density).toInt()
        for (tab in listOf(layoutTab, generalButton, dataButton)) {
            (tab.layoutParams as LinearLayout.LayoutParams).leftMargin = spacing
            tabsRow.addView(tab)
        }
        // El primer tab no necesita margen izquierdo extra.
        (layoutTab.layoutParams as LinearLayout.LayoutParams).leftMargin = 0

        val tabsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        tabsParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        bar.addView(tabsRow, tabsParams)

        updateModeHighlight()

        return bar
    }

    /**
     * Tocar el tab del modo en el que ya estas parado abre/cierra su menu (View/Select/Add/Object).
     * Tocar un tab distinto cambia de modo y cierra cualquier menu abierto.
     */
    private fun onModeTabClicked(mode: AppMode, anchor: View) {
        if (mode == currentMode) {
            toggleModeMenu(mode, anchor)
        } else {
            modeMenuPopup?.dismiss()
            setMode(mode)
        }
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
        modelingToolWrapper.visibility = View.GONE
        popup.setOnDismissListener {
            modeMenuPopup = null
            if (currentMode == AppMode.LAYOUT) {
                leftToolColumn.visibility = View.VISIBLE
            }
            if (currentMode == AppMode.MODELING) {
                modelingToolWrapper.visibility = View.VISIBLE
            }
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
        val categories = when (mode) {
            AppMode.LAYOUT -> layoutMenuCategories
            AppMode.MODELING -> modelingMenuCategories
            AppMode.UV_EDITING -> uvEditingMenuCategories
        }
        if (categories.isEmpty()) {
            menuColumn.addView(buildSimpleMenuRow("Próximamente") { })
        } else {
            for (category in categories) {
                menuColumn.addView(buildSimpleMenuRow(category) {
                    fillModeMenuWithCategoryContent(menuColumn, mode, category, popup)
                })
            }
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * Dispatcher central: decide que render function usar segun modo+categoria. El chequeo de
     * "View" para UV_EDITING va ANTES del generico "category == View" (que sirve a Layout/Modeling),
     * porque si no, el generico lo intercepta primero y el contenido especifico de UV_EDITING > View
     * (renderUvEditingViewMenu) nunca se alcanza.
     */
    private fun fillModeMenuWithCategoryContent(menuColumn: LinearLayout, mode: AppMode, category: String, popup: PopupWindow) {
        if (mode == AppMode.MODELING && category == "Select") {
            renderModelingSelectMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.LAYOUT && category == "Select") {
            renderLayoutSelectMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.UV_EDITING && category == "View") {
            renderUvEditingViewMenu(menuColumn, popup)
            return
        }
        if (category == "View") {
            renderViewMenu(menuColumn, mode, popup)
            return
        }
        if (mode == AppMode.MODELING && category == "Add") {
            renderModelingAddMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.LAYOUT && category == "Add") {
            renderLayoutAddMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.MODELING && category == "Mesh") {
            renderModelingFlatMenu(menuColumn, popup, modelingMeshMenuItems)
            return
        }
        if (mode == AppMode.MODELING && category == "Vertex") {
            renderModelingFlatMenu(menuColumn, popup, modelingVertexMenuItems)
            return
        }
        if (mode == AppMode.MODELING && category == "Edge") {
            renderModelingFlatMenu(menuColumn, popup, modelingEdgeMenuItems)
            return
        }
        if (mode == AppMode.MODELING && category == "Face") {
            renderModelingFlatMenu(menuColumn, popup, modelingFaceMenuItems)
            return
        }
        if (mode == AppMode.MODELING && category == "UV") {
            renderModelingFlatMenu(menuColumn, popup, modelingUvMenuItems)
            return
        }
        if (mode == AppMode.UV_EDITING && category == "Select") {
            renderUvEditingSelectMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.UV_EDITING && category == "Image") {
            renderUvEditingFlatMenu(menuColumn, popup, uvImageMenuItems)
            return
        }
        if (mode == AppMode.UV_EDITING && category == "UV") {
            renderUvEditingUvMenu(menuColumn, popup)
            return
        }
        if (mode == AppMode.LAYOUT && category == "Object") {
            renderLayoutObjectMenu(menuColumn, popup)
            return
        }
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, mode, popup)
        })
        menuColumn.addView(buildSimpleMenuRow("Próximamente") { })
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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

    /**
     * Contenido de Modeling > Select: lista nueva confirmada por el supervisor (no reutiliza Layout,
     * en Edit Mode se selecciona geometria, no objetos). Se saca "By Attribute" (aparece deshabilitado
     * en Blender mismo, depende de Geometry Nodes). Box/Circle/Lasso Select reusan el mismo submenu
     * de modo de seleccion (Set/Extend/Subtract/Difference/Intersect) que ya usa Layout > Select.
     */
    private fun renderModelingSelectMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.MODELING, popup)
        })

        addModelingSelectActionRow(menuColumn, popup, "All")
        addModelingSelectActionRow(menuColumn, popup, "None")
        addModelingSelectActionRow(menuColumn, popup, "Invert")
        menuColumn.addView(buildSimpleMenuRow("Box Select") {
            popup.dismiss()
            armBoxSelect()
        })
        menuColumn.addView(buildSimpleMenuRow("Circle Select") {
            popup.dismiss()
            armCircleSelect()
        })
        menuColumn.addView(buildSimpleMenuRow("Lasso Select") {
            popup.dismiss(); armLassoSelect()
        })
        addModelingSelectActionRow(menuColumn, popup, "Select Mirror")
        addModelingSelectActionRow(menuColumn, popup, "Select Random")
        addModelingSelectActionRow(menuColumn, popup, "Checker Deselect")
        menuColumn.addView(buildSimpleMenuRow("More/Less") {
            renderModelingSelectSubmenu(menuColumn, popup, modelingMoreLessSubmenuItems)
        })
        addModelingSelectActionRow(menuColumn, popup, "Select Similar")
        addModelingSelectActionRow(menuColumn, popup, "Select All by Trait")
        addModelingSelectActionRow(menuColumn, popup, "Select Linked")
        addModelingSelectActionRow(menuColumn, popup, "Select Loops")
        addModelingSelectActionRow(menuColumn, popup, "Sharp Edges")
        addModelingSelectActionRow(menuColumn, popup, "Side of Active")

        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu generico dentro de Modeling > Select (Box/Circle/Lasso, More/Less), solo texto. */
    private fun renderModelingSelectSubmenu(menuColumn: LinearLayout, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderModelingSelectMenu(menuColumn, popup)
        })
        for (item in items) {
            addModelingSelectActionRow(menuColumn, popup, item)
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun addModelingSelectActionRow(menuColumn: LinearLayout, popup: PopupWindow, label: String) {
        menuColumn.addView(buildSimpleMenuRow(label) {
            popup.dismiss()
            onSelectMenuAction(label)
        })
    }

    private fun onSelectMenuAction(action: String) {
        // Solo Layout (Object Mode): None deselecciona objetos reales via el mismo sistema de
        // seleccion que ya usa el tap en el viewport. Modeling comparte esta misma funcion para
        // su propio menu Select, pero ahi "None" todavia no tiene nada real que hacer (no existe
        // geometria editable en Edit Mode todavia) - sigue en Toast para ese caso.
        if (currentMode == AppMode.MODELING) {
            when (action) {
                "All" -> { glView.renderer.selectAllMeshElements(currentEditSelectMode); glView.requestRender(); return }
                "None" -> { glView.renderer.deselectAllMeshElements(currentEditSelectMode); glView.requestRender(); return }
                "Invert" -> { glView.renderer.invertMeshElementSelection(currentEditSelectMode); glView.requestRender(); return }
            }
        }
        if (currentMode == AppMode.LAYOUT && action == "None") {
            glView.renderer.deselectAll()
            glView.requestRender()
            return
        }
        // All/Invert quedan pendientes a proposito: la app solo soporta un objeto seleccionado a
        // la vez (ver moveSelectedObject/rotateSelectedObject/etc, todos usan firstOrNull), asi
        // que seleccionar "todos" dejaria varios objetos con el contorno naranja pero Move/Rotate/
        // Scale/Delete solo afectarian al primero - resultado inconsistente. Implementarlos bien
        // requiere soporte real de multi-seleccion (gizmo compartido, transformar varios objetos
        // a la vez, etc.) - ver charla con el usuario, queda como su propio hito futuro.
        // TODO: conectar el resto de las acciones una vez que exista ese soporte (o Edit Mode, para Modeling).
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
     * Contenido de Layout > Add: categorias con icono propio (Mesh/Curve/Surface/Text/Metaball/
     * Grease Pencil/Armature/Lattice/Empty/Image), todas placeholder. Mesh todavia no tiene submenu
     * de primitivas (Plane, Cube, UV Sphere, etc.) porque esos iconos quedan pendientes de diseño.
     * Camera / Collection Instance / Monkey quedan afuera por ahora, sin icono todavia.
     */
    private fun renderLayoutAddMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.LAYOUT, popup)
        })

        for (entry in addMenuEntries) {
            if (entry.label == "Mesh") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderMeshPrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            if (entry.label == "Curve") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderCurvePrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            if (entry.label == "Surface") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderSurfacePrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            if (entry.label == "Metaball") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderMetaballPrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            if (entry.label == "Grease Pencil") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderGreasePencilPrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            if (entry.label == "Empty") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderEmptyPrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            if (entry.label == "Image") {
                menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                    renderImagePrimitivesSubmenu(menuColumn, popup)
                })
                continue
            }
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
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

    /**
     * Submenu de primitivas dentro de Add > Mesh. "Cube" ya crea geometria real (unica primitiva
     * que existe hoy, ver Cube.kt) - el resto sigue como placeholder (onAddMenuAction) hasta que
     * tengan su propia geometria.
     */
    private fun renderMeshPrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in meshPrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                if (entry.label == "Plane") {
                    addPlaneObject()
                } else if (entry.label == "Ico Sphere") {
                    addIcoSphereObject()
                } else if (entry.label == "UV Sphere") {
                    addUvSphereObject()
                } else if (entry.label == "Circle") {
                    addCircleObject()
                } else if (entry.label == "Cylinder") {
                    addCylinderObject()
                } else if (entry.label == "Cone") {
                    addConeObject()
                } else if (entry.label == "Grid") {
                    addGridObject()
                } else if (entry.label == "Torus") {
                    addTorusObject()
                } else if (entry.label == "Monkey") {
                    addMonkeyObject()
                } else if (entry.label == "Cube") {
                    addCubeObject()
                } else {
                    onAddMenuAction(entry.label)
                }
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * Add > Mesh > Cube real: agrega un SceneObject nuevo via MyGLRenderer.addCube() (que ya
     * deja todo lo demas deseleccionado) y pide un redraw. La seleccion visual (contorno naranja)
     * ya reacciona sola porque Cube.kt lee SceneObject.selected en cada draw().
     */
    private fun addCubeObject() {
        glView.renderer.addCube()
        glView.requestRender()
    }

    /** Add > Mesh > Plane real, mismo patron que addCubeObject pero via renderer.addPlane(). */
    private fun addPlaneObject() {
        glView.renderer.addPlane()
        glView.requestRender()
    }

    /** Add > Mesh > Circle real, mismo patron que addPlaneObject pero via renderer.addCircle(). */
    private fun addCircleObject() {
        glView.renderer.addCircle()
        glView.requestRender()
    }

    /** Add > Mesh > UV Sphere real, mismo patron que addCircleObject pero via renderer.addUvSphere(). */
    private fun addUvSphereObject() {
        glView.renderer.addUvSphere()
        glView.requestRender()
    }

    /** Add > Mesh > Ico Sphere real, mismo patron que addUvSphereObject pero via renderer.addIcoSphere(). */
    private fun addIcoSphereObject() {
        glView.renderer.addIcoSphere()
        glView.requestRender()
    }

    /** Add > Mesh > Cylinder real, mismo patron que addIcoSphereObject pero via renderer.addCylinder(). */
    private fun addCylinderObject() {
        glView.renderer.addCylinder()
        glView.requestRender()
    }

    /** Add > Mesh > Cone real, mismo patron que addCylinderObject pero via renderer.addCone(). */
    private fun addConeObject() {
        glView.renderer.addCone()
        glView.requestRender()
    }

    /** Add > Mesh > Torus real, mismo patron que addConeObject pero via renderer.addTorus(). */
    private fun addTorusObject() {
        glView.renderer.addTorus()
        glView.requestRender()
    }

    /** Add > Mesh > Grid real, mismo patron que addTorusObject pero via renderer.addGrid(). */
    private fun addGridObject() {
        glView.renderer.addGrid()
        glView.requestRender()
    }

    /** Add > Mesh > Monkey real, mismo patron que addGridObject pero via renderer.addMonkey(). */
    private fun addMonkeyObject() {
        glView.renderer.addMonkey()
        glView.requestRender()
    }

    /** Submenu de primitivas dentro de Add > Curve, mismo patron que renderMeshPrimitivesSubmenu. */
    private fun renderCurvePrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in curvePrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu de primitivas dentro de Add > Surface, mismo patron que renderMeshPrimitivesSubmenu. */
    private fun renderSurfacePrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in surfacePrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu de primitivas dentro de Add > Metaball, mismo patron que renderMeshPrimitivesSubmenu. */
    private fun renderMetaballPrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in metaballPrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu de primitivas dentro de Add > Grease Pencil, mismo patron que renderMeshPrimitivesSubmenu. */
    private fun renderGreasePencilPrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in greasePencilPrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu de primitivas dentro de Add > Empty, mismo patron que renderMeshPrimitivesSubmenu. */
    private fun renderEmptyPrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in emptyPrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Submenu de primitivas dentro de Add > Image, mismo patron que renderMeshPrimitivesSubmenu. */
    private fun renderImagePrimitivesSubmenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderLayoutAddMenu(menuColumn, popup)
        })
        for (entry in imagePrimitiveEntries) {
            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {
                popup.dismiss()
                onAddMenuAction(entry.label)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * Contenido de Modeling > Add: va directo a las primitivas de malla (mismas 10 que Layout > Add >
     * Mesh, mismos iconos), sin categoria intermedia - en Edit Mode "Add" siempre crea geometria.
     */
    private fun renderModelingAddMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.MODELING, popup)
        })
        for (entry in meshPrimitiveEntries) {
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
        // TODO: conectar a la logica real de creacion de objetos una vez que exista el modelo de escena editable.
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
    }

    /**
     * Render generico y compartido para Modeling > Mesh/Vertex/Edge/Face/UV: todos son listas planas
     * sin submenu por ahora, mismo patron que renderLayoutObjectMenu pero apuntando a Modeling.
     */
    private fun renderModelingFlatMenu(menuColumn: LinearLayout, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.MODELING, popup)
        })
        for (item in items) {
            menuColumn.addView(buildSimpleMenuRow(item) {
                popup.dismiss()
                onModelingMenuAction(item)
            })
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Placeholder generico para categorias marcadas "pendiente de detallar" (Zoom, Area, Select All by Trait, Snap). */
    private fun renderPendingSubmenu(menuColumn: LinearLayout, popup: PopupWindow, onBack: () -> Unit) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") { onBack() })
        menuColumn.addView(buildSimpleMenuRow("Próximamente") { })
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun renderUvEditingViewMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.UV_EDITING, popup)
        })
        for (item in uvViewSimpleItems) {
            addUvEditingActionRow(menuColumn, popup, item)
        }
        menuColumn.addView(buildSimpleMenuRow("Zoom") {
            renderPendingSubmenu(menuColumn, popup) { renderUvEditingViewMenu(menuColumn, popup) }
        })
        menuColumn.addView(buildSimpleMenuRow("Area") {
            renderPendingSubmenu(menuColumn, popup) { renderUvEditingViewMenu(menuColumn, popup) }
        })
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Ojo: aca solo Lasso Select abre submenu (reusa selectModeSubmenuItems) - Box/Circle Select son directas. */
    private fun renderUvEditingSelectMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.UV_EDITING, popup)
        })
        for (item in uvSelectSimpleItems) {
            addUvEditingActionRow(menuColumn, popup, item)
        }
        menuColumn.addView(buildSimpleMenuRow("Lasso Select") {
            renderUvEditingSelectSubmenu(menuColumn, popup, selectModeSubmenuItems)
        })
        for (item in uvSelectTrailingItems) {
            addUvEditingActionRow(menuColumn, popup, item)
        }
        menuColumn.addView(buildSimpleMenuRow("Select All by Trait") {
            renderPendingSubmenu(menuColumn, popup) { renderUvEditingSelectMenu(menuColumn, popup) }
        })
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun renderUvEditingSelectSubmenu(menuColumn: LinearLayout, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            renderUvEditingSelectMenu(menuColumn, popup)
        })
        for (item in items) {
            addUvEditingActionRow(menuColumn, popup, item)
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Contenido de UV Editing > UV: lista plana, salvo "Snap" que abre submenu pendiente de detallar. */
    private fun renderUvEditingUvMenu(menuColumn: LinearLayout, popup: PopupWindow) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.UV_EDITING, popup)
        })
        for (item in uvUvMenuItems) {
            if (item == "Snap") {
                menuColumn.addView(buildSimpleMenuRow(item) {
                    renderPendingSubmenu(menuColumn, popup) { renderUvEditingUvMenu(menuColumn, popup) }
                })
            } else {
                addUvEditingActionRow(menuColumn, popup, item)
            }
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Render generico para categorias de UV Editing sin excepciones (por ahora, Image). */
    private fun renderUvEditingFlatMenu(menuColumn: LinearLayout, popup: PopupWindow, items: List<String>) {
        menuColumn.removeAllViews()
        menuColumn.addView(buildSimpleMenuRow("← Volver") {
            fillModeMenuWithCategories(menuColumn, AppMode.UV_EDITING, popup)
        })
        for (item in items) {
            addUvEditingActionRow(menuColumn, popup, item)
        }
        if (popup.isShowing) {
            popup.update(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun addUvEditingActionRow(menuColumn: LinearLayout, popup: PopupWindow, label: String) {
        menuColumn.addView(buildSimpleMenuRow(label) {
            popup.dismiss()
            onUvEditingMenuAction(label)
        })
    }

    private fun onUvEditingMenuAction(action: String) {
        // TODO: conectar a la logica real de UV Editing una vez que exista el modelo editable.
        Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
    }

    private fun onModelingMenuAction(action: String) {
        // Modeling > Mesh > Delete: ya tiene logica real (ver MyGLRenderer.deleteSelectedMeshElements),
        // a diferencia del resto de los items de este menu (todavia Toast placeholder). Opera sobre
        // el modo de sub-elemento activo (currentEditSelectMode), mismo criterio que Select All/None/Invert.
        if (action == "Delete") {
            val deleted = glView.renderer.deleteSelectedMeshElements(currentEditSelectMode)
            glView.requestRender()
            if (!deleted) Toast.makeText(this, "Selecciona algo para borrar", Toast.LENGTH_SHORT).show()
            return
        }
        // Modeling > Mesh > Merge (At Center, ver MyGLRenderer.mergeSelectedVertices): mismo
        // patron que Delete arriba (unica accion real de este menu por ahora, junto con Delete).
        // Funciona sin importar el EditSelectMode activo (Vertex/Edge/Face), ya que
        // mergeSelectedVertices usa verticesAffectedBySelection para juntar los vertices
        // implicados por lo que este seleccionado, sea vertices, aristas o caras.
        if (action == "Merge") {
            val merged = glView.renderer.mergeSelectedVertices()
            glView.requestRender()
            if (!merged) Toast.makeText(this, "Selecciona al menos 2 vertices para fusionar", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Bevel" || action == "Bevel Vertices") {
            val beveled = glView.renderer.bevelSelectedVertices()
            glView.requestRender()
            if (!beveled) Toast.makeText(this, "Selecciona al menos un vertice para biselar", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Loop Cut" || action == "Loop Cut and Slide") {
            val cut = glView.renderer.loopCutSelectedEdges()
            glView.requestRender()
            if (!cut) Toast.makeText(this, "Selecciona una arista con caras cuadrangulares a los lados", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Shrink/Fatten") {
            val shrunk = glView.renderer.shrinkFattenSelected()
            glView.requestRender()
            if (!shrunk) Toast.makeText(this, "Selecciona al menos un vertice con una cara adyacente", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Shear") {
            val sheared = glView.renderer.shearSelected()
            glView.requestRender()
            if (!sheared) Toast.makeText(this, "Selecciona al menos un vertice", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Rip Region") {
            val ripped = glView.renderer.ripSelectedFaces()
            glView.requestRender()
            if (!ripped) Toast.makeText(this, "Selecciona al menos una cara para separar", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Smooth" || action == "Smooth Vertices") {
            val smoothed = glView.renderer.smoothSelectedVertices()
            glView.requestRender()
            if (!smoothed) Toast.makeText(this, "Selecciona al menos un vertice con vecinos", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Edge Slide") {
            val slid = glView.renderer.slideSelectedEdges()
            glView.requestRender()
            if (!slid) Toast.makeText(this, "Selecciona una arista con una cara cuadrangular adyacente", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Inset Faces") {
            val inset = glView.renderer.insetSelectedFaces()
            glView.requestRender()
            if (!inset) Toast.makeText(this, "Selecciona al menos una cara para insetear", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Spin") {
            val spun = glView.renderer.spinSelected()
            glView.requestRender()
            if (!spun) Toast.makeText(this, "Selecciona al menos un vértice para revolucionar", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == "Subdivide") {
            val subdivided = glView.renderer.subdivideSelected()
            glView.requestRender()
            if (!subdivided) Toast.makeText(this, "Selecciona al menos una arista para subdividir", Toast.LENGTH_SHORT).show()
            return
        }
        // TODO: conectar a la logica real de edicion de malla una vez que exista el modelo editable (Edit Mode).
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
        // Separado con el doble de margen de las 4 herramientas basicas, para marcar visualmente
        // que es una propiedad de esas herramientas y no una herramienta mas (ver charla con el usuario).
        (orientationToggleBtn.layoutParams as LinearLayout.LayoutParams).topMargin = spacing * 2
        column.addView(orientationToggleBtn)
        updateOrientationToggleVisibility()

        updateLayoutToolHighlight()

        return column
    }

    /**
     * Actualiza la visibilidad de orientationToggleBtn: visible solo con Move/Rotate/Scale (donde
     * hay gizmo dibujado), oculto con Select (no habria nada en pantalla que el boton afecte).
     */
    private fun updateOrientationToggleVisibility() {
        orientationToggleBtn.visibility = if (currentLayoutTool == LayoutTool.SELECT || currentLayoutTool == LayoutTool.PAINT) View.GONE else View.VISIBLE
    }

    /**
     * Alterna transformOrientation entre GLOBAL y LOCAL (ver enum en MyGLRenderer) y actualiza el
     * icono del boton para reflejar el estado nuevo - mismo criterio que el resto de los toggles
     * (handButton/lockButton), pero con icono variable en vez de resaltado, ya que el icono mismo
     * ya comunica en cual de los dos estados esta. Ademas muestra un Toast corto (ver charla con
     * el usuario) confirmando "Global" o "Local" - el icono ya lo comunica visualmente, pero el
     * toast lo deja explicito en el momento justo de tocar el boton.
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

    /**
     * Columna izquierda de Modeling (Edit Mode): 4 botones con icono (reusa los de Layout) + 12 filas
     * de solo texto (todavia sin icono diseñado). Se envuelve en un ScrollView con altura maxima
     * (ver modelingToolWrapper en onCreate) porque 16 items no entran completos en pantallas chicas.
     */
    private fun buildModelingToolColumn(): LinearLayout {
        val density = resources.displayMetrics.density
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        modelingSelectBtn = createIconButton(R.drawable.ic_select_box)
        modelingMoveBtn = createIconButton(R.drawable.ic_move)
        modelingRotateBtn = createIconButton(R.drawable.ic_rotate)
        modelingScaleBtn = createIconButton(R.drawable.ic_scale)

        modelingSelectBtn.setOnClickListener { setModelingTool(LayoutTool.SELECT) }
        modelingMoveBtn.setOnClickListener { setModelingTool(LayoutTool.MOVE) }
        modelingRotateBtn.setOnClickListener { setModelingTool(LayoutTool.ROTATE) }
        modelingScaleBtn.setOnClickListener { setModelingTool(LayoutTool.SCALE) }

        val spacing = (8 * density).toInt()
        for (btn in listOf(modelingSelectBtn, modelingMoveBtn, modelingRotateBtn, modelingScaleBtn)) {
            (btn.layoutParams as LinearLayout.LayoutParams).topMargin = spacing
            column.addView(btn)
        }
        (modelingSelectBtn.layoutParams as LinearLayout.LayoutParams).topMargin = 0

        for (entry in modelingToolEntries) {
            val btn = createIconButton(entry.iconRes)
            if (entry.label == "Extrude Region") {
                btn.setOnClickListener { onExtrudeRegionClicked() }
            } else if (entry.label == "Knife") {
                btn.setOnClickListener { armKnifeTool() }
            } else if (entry.label == "Poly Build") {
                btn.setOnClickListener { armPolyBuildTool() }
            } else if (false) { // rama muerta sin efecto (condicion nunca verdadera) - limpieza pendiente
                btn.setOnClickListener { onExtrudeRegionClicked() }
            } else {
                btn.setOnClickListener { setModelingExtraTool(entry.label) }
            }
            (btn.layoutParams as LinearLayout.LayoutParams).topMargin = spacing
            modelingExtraToolButtons[entry.label] = btn
            column.addView(btn)
        }


        updateModelingToolHighlight()
        return column
    }

    /**
     * Prende el gizmo correspondiente (flechas/anillos/cubitos) al elegir Move/Rotate/Scale en
     * Modeling (Edit Mode) - igual que en Layout.
     */
    private fun setModelingTool(tool: LayoutTool) {
        currentModelingTool = tool
        currentModelingExtraTool = null
        polyBuildActive = false
        polyBuildAnchorVertex = null
        updateModelingToolHighlight()
        glView.renderer.gizmoMode = when (tool) {
            LayoutTool.MOVE -> GizmoMode.MOVE
            LayoutTool.ROTATE -> GizmoMode.ROTATE
            LayoutTool.SCALE -> GizmoMode.SCALE
            else -> null
        }
    }

    /**
     * Modeling > Extrude Region (boton de la barra izquierda, Fase 3 del plan de Edit Mode - ver
     * charla con el supervisor): a diferencia del resto de las herramientas "extra" (Bevel, Loop
     * Cut, etc, que por ahora solo resaltan el boton y muestran un Toast, ver setModelingExtraTool),
     * esta ya tiene logica real (ver MyGLRenderer.extrudeSelectedFaces). Al extruir con exito,
     * encadena automaticamente el modo Move (arrastre libre) - mismo flujo que "E" seguido de "G"
     * implicito en Blender real, para que el usuario pueda "tirar" la extrusion de una sola vez sin
     * tener que tocar el boton de Move aparte.
     */
    private fun onExtrudeRegionClicked() {
        val extruded = glView.renderer.extrudeSelectedFaces()
        if (extruded) {
            setModelingTool(LayoutTool.MOVE)
            glView.requestRender()
        } else {
            Toast.makeText(this, "Selecciona al menos una cara para extruir", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Arma Box Select (Modeling > Select > Box Select): fuerza la herramienta Select (para que
     * el proximo arrastre en el viewport no quede interceptado por Move/Rotate/Scale, ver
     * setModelingTool) y prende boxSelectActive - el primer arrastre que siga (ver
     * onViewportDragStart/Move/End) dibuja el rectangulo y selecciona lo que quede adentro,
     * despues se desarma solo (ver comentario de boxSelectActive). Reusa el mismo toggle
     * extendSelectEnabled que ya existe para el tap individual (ver toggleExtendSelect) en vez de
     * los 5 sub-modos de Blender (Set/Extend/Subtract/Difference/Intersect) - simplificacion
     * deliberada, documentada en selectModeSubmenuItems.
     */
    private fun armBoxSelect() {
        if (currentMode != AppMode.MODELING) return
        setModelingTool(LayoutTool.SELECT)
        boxSelectActive = true
        Toast.makeText(this, "Arrastrá para seleccionar por caja", Toast.LENGTH_SHORT).show()
    }

    /**
     * Arma Circle Select (Modeling > Select > Circle Select): mismo criterio que armBoxSelect
     * (fuerza la herramienta Select y prende el flag "activo", el primer toque que sigue fija el
     * centro y el arrastre define el radio - ver onViewportDragStart/Move/End). A diferencia de
     * Box Select, el gesto es centro + radio (ver CircleSelectOverlayView), no dos esquinas.
     */
    private fun armCircleSelect() {
        if (currentMode != AppMode.MODELING) return
        setModelingTool(LayoutTool.SELECT)
        circleSelectActive = true
        Toast.makeText(this, "Tocá para fijar el centro y arrastrá para el radio", Toast.LENGTH_SHORT).show()
    }
    private fun armLassoSelect() {
        if (currentMode != AppMode.MODELING) return
        setModelingTool(LayoutTool.SELECT)
        lassoSelectActive = true
        Toast.makeText(this, "Dibujá el contorno para seleccionar", Toast.LENGTH_SHORT).show()
    }

    /**
     * Arma Knife (Modeling > barra izquierda > Knife, ver charla con el usuario - version
     * simplificada de 2 toques): fuerza la herramienta Select (mismo motivo que armBoxSelect - para
     * que el proximo tap en el viewport no quede interceptado por Move/Rotate/Scale) y ademas
     * resalta el boton propio de Knife (a diferencia de armBoxSelect/armCircleSelect/armLassoSelect,
     * que no tienen boton en la barra y por eso terminan resaltando Select) - por eso pisa
     * currentModelingExtraTool despues de llamar a setModelingTool (que lo resetea a null) y vuelve
     * a actualizar el resaltado. knifeActive queda en true hasta que se completen los 2 toques (ver
     * onKnifeTap) - recien ahi se desarma sola, mismo criterio "un solo gesto" que el resto de Fase 4.
     */
    private fun armKnifeTool() {
        if (currentMode != AppMode.MODELING) return
        setModelingTool(LayoutTool.SELECT)
        currentModelingExtraTool = "Knife"
        updateModelingToolHighlight()
        knifeActive = true
        knifeFirstEdge = null
        Toast.makeText(this, "Tocá el punto de entrada del corte", Toast.LENGTH_SHORT).show()
    }

    /**
     * Toque en el viewport mientras Knife esta armado (ver armKnifeTool) - se llama desde
     * onViewportTap ANTES de cualquier otra logica de tap, mientras knifeActive este en true.
     * Primer toque: busca la arista mas cercana (ver MyGLRenderer.raycastEdgeAtWithT) y la guarda
     * como punto de entrada. Segundo toque: busca la arista de salida y dispara el corte real (ver
     * MyGLRenderer.knifeCutBetween) - exista o no una cara en comun entre ambas aristas, Knife se
     * desarma solo despues de este segundo toque (mismo criterio que el resto de Fase 4: un intento
     * fallido no dejar la herramienta "pegada" esperando indefinidamente).
     */
    private fun onKnifeTap(x: Float, y: Float) {
        val hit = glView.renderer.raycastEdgeAtWithT(x, y)
        if (hit == null) {
            Toast.makeText(this, "Tocá más cerca de una arista", Toast.LENGTH_SHORT).show()
            return
        }
        val (edge, t) = hit
        val firstEdge = knifeFirstEdge
        if (firstEdge == null) {
            knifeFirstEdge = edge
            knifeFirstT = t
            Toast.makeText(this, "Tocá el punto de salida del corte", Toast.LENGTH_SHORT).show()
            return
        }
        val cut = glView.renderer.knifeCutBetween(firstEdge, knifeFirstT, edge, t)
        knifeActive = false
        knifeFirstEdge = null
        setModelingTool(LayoutTool.SELECT)
        if (cut) {
            glView.requestRender()
        } else {
            Toast.makeText(this, "Esas aristas no comparten una cara en comun", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Arma Poly Build (Modeling > barra izquierda > Poly Build, ver MyGLRenderer.polyBuildConnect):
     * mismo motivo que armKnifeTool para forzar la herramienta Select y resaltar su propio boton
     * (pisa currentModelingExtraTool despues de setModelingTool, que lo resetea a null). A
     * diferencia de Knife, NO se desarma sola despues de un toque - polyBuildActive queda en true
     * indefinidamente para poder seguir construyendo una cadena (ver comentario de esa variable),
     * hasta que el usuario elija otra herramienta (ver setModelingTool, que la apaga).
     * polyBuildAnchorVertex arranca en null: el primer toque todavia no tiene desde donde conectar.
     */
    private fun armPolyBuildTool() {
        if (currentMode != AppMode.MODELING) return
        setModelingTool(LayoutTool.SELECT)
        currentModelingExtraTool = "Poly Build"
        updateModelingToolHighlight()
        polyBuildActive = true
        polyBuildAnchorVertex = null
        Toast.makeText(this, "Tocá el vértice inicial de la cadena", Toast.LENGTH_SHORT).show()
    }

    /**
     * Toque en el viewport mientras Poly Build esta armado (ver armPolyBuildTool) - se llama desde
     * onViewportTap, con la misma prioridad que onKnifeTap (antes que la seleccion normal).
     * Sin ancla todavia (polyBuildAnchorVertex == null): el toque fija el PRIMER vertice de la
     * cadena (ver MyGLRenderer.raycastVertexAt, mismo hit-test que Vertex select mode) - no crea
     * nada nuevo, solo elige desde donde arrancar a construir. Si el toque no cae cerca de ningun
     * vertice, avisa y no hace nada (la herramienta sigue armada, esperando otro intento).
     * Con ancla ya fijada: cada toque llama a MyGLRenderer.polyBuildConnect, que conecta a un
     * vertice existente o crea uno nuevo en espacio vacio (ver esa funcion) - el resultado pasa a
     * ser el nuevo ancla, para poder seguir extendiendo la cadena con el proximo toque sin volver
     * a tocar el boton (ver comentario de polyBuildActive).
     */
    private fun onPolyBuildTap(x: Float, y: Float) {
        val anchor = polyBuildAnchorVertex
        if (anchor == null) {
            val hit = glView.renderer.raycastVertexAt(x, y)
            if (hit == null) {
                Toast.makeText(this, "Tocá más cerca de un vértice para empezar", Toast.LENGTH_SHORT).show()
                return
            }
            polyBuildAnchorVertex = hit.id
            Toast.makeText(this, "Tocá el siguiente punto para conectar", Toast.LENGTH_SHORT).show()
            return
        }
        val nextAnchor = glView.renderer.polyBuildConnect(anchor, x, y)
        if (nextAnchor != null) {
            polyBuildAnchorVertex = nextAnchor
            glView.requestRender()
        } else {
            Toast.makeText(this, "No se pudo conectar ahí", Toast.LENGTH_SHORT).show()
        }
    }

    /** Herramienta "de un solo toque" (Extrude Region, Bevel, etc.) - queda resaltada hasta elegir otra. */
    private fun setModelingExtraTool(label: String) {
        currentModelingExtraTool = label
        updateModelingToolHighlight()
        onModelingMenuAction(label)
    }

    /**
     * Unifica el resaltado de los 16 botones de la barra de Modeling: solo uno activo a la vez
     * (basico o "extra"), igual que en Blender real - no son dos grupos independientes.
     */
    private fun updateModelingToolHighlight() {
        modelingSelectBtn.background = circleBackground(currentModelingExtraTool == null && currentModelingTool == LayoutTool.SELECT)
        modelingMoveBtn.background = circleBackground(currentModelingExtraTool == null && currentModelingTool == LayoutTool.MOVE)
        modelingRotateBtn.background = circleBackground(currentModelingExtraTool == null && currentModelingTool == LayoutTool.ROTATE)
        modelingScaleBtn.background = circleBackground(currentModelingExtraTool == null && currentModelingTool == LayoutTool.SCALE)
        for ((label, btn) in modelingExtraToolButtons) {
            btn.background = circleBackground(currentModelingExtraTool == label)
        }
    }

    /**
     * Fila horizontal Vertex/Edge/Face (a diferencia de las columnas verticales de herramientas):
     * son 3 estados mutuamente excluyentes de un mismo selector, no una lista de acciones - mismo
     * criterio visual que usa Blender (los 3 juntos, uno al lado del otro).
     */
    private fun buildEditSelectModeRow(): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        vertexModeBtn = createIconButton(R.drawable.ic_mode_vertex)
        edgeModeBtn = createIconButton(R.drawable.ic_mode_edge)
        faceModeBtn = createIconButton(R.drawable.ic_mode_face)

        vertexModeBtn.setOnClickListener { setEditSelectMode(EditSelectMode.VERTEX) }
        edgeModeBtn.setOnClickListener { setEditSelectMode(EditSelectMode.EDGE) }
        faceModeBtn.setOnClickListener { setEditSelectMode(EditSelectMode.FACE) }
        extendSelectBtn = createIconButton(R.drawable.ic_select_extend)
        extendSelectBtn.setOnClickListener { toggleExtendSelect() }

        val spacing = (8 * density).toInt()
        for (btn in listOf(vertexModeBtn, edgeModeBtn, faceModeBtn)) {
            (btn.layoutParams as LinearLayout.LayoutParams).leftMargin = spacing
            row.addView(btn)
        }
        (vertexModeBtn.layoutParams as LinearLayout.LayoutParams).leftMargin = 0
        (extendSelectBtn.layoutParams as LinearLayout.LayoutParams).leftMargin = spacing * 2
        row.addView(extendSelectBtn)
        updateExtendSelectHighlight()

        updateEditSelectModeHighlight()
        return row
    }

    /**
     * Cambia el modo de selección de sub-elemento: antes de togglear el modo, convierte la
     * selección actual al modo nuevo via MyGLRenderer.convertSelectionOnModeChange (misma
     * conversión que Blender - una cara seleccionada pasa a Vertex con sus 4 vértices marcados,
     * ver comentario de esa función) en vez de perderla. draw() de DynamicMeshGeometry lee
     * `.selected` en vivo desde la malla (ver comentario de esa clase), asi que no hace falta
     * reconstruir geometria - alcanza con requestRender() para que el cambio se vea en el
     * proximo frame.
     */
    private fun setEditSelectMode(mode: EditSelectMode) {
        glView.renderer.convertSelectionOnModeChange(currentEditSelectMode, mode)
        currentEditSelectMode = mode
        updateEditSelectModeHighlight()
        glView.requestRender()
    }

    private fun updateEditSelectModeHighlight() {
        vertexModeBtn.background = circleBackground(currentEditSelectMode == EditSelectMode.VERTEX)
        edgeModeBtn.background = circleBackground(currentEditSelectMode == EditSelectMode.EDGE)
        faceModeBtn.background = circleBackground(currentEditSelectMode == EditSelectMode.FACE)
    }

    /**
     * Multi-seleccion real (Paso 3 del roadmap, ver charla con el supervisor): toggle que cambia
     * el comportamiento del tap en Modeling con Select activo - apagado (default): tocar un
     * elemento selecciona SOLO ese elemento, deselecciona el resto (mismo comportamiento de
     * siempre, ver MyGLRenderer.selectMeshElementAt con extend=false). Prendido: tocar un
     * elemento AGREGA o QUITA ese elemento de la seleccion actual sin tocar el resto (toggle
     * individual, ver selectMeshElementAt con extend=true) - mismo criterio que Shift+click en
     * Blender. Visible siempre en Modeling (vive en editSelectModeRow, mismo criterio de
     * visibilidad que Vertex/Edge/Face) ya que afecta a la proxima vez que se use Select,
     * independientemente de la herramienta activa ahora mismo.
     */
    private fun toggleExtendSelect() {
        extendSelectEnabled = !extendSelectEnabled
        updateExtendSelectHighlight()
    }

    private fun updateExtendSelectHighlight() {
        extendSelectBtn.background = circleBackground(extendSelectEnabled)
    }

    private fun setLayoutTool(tool: LayoutTool) {
        // El gizmo se muestra con Move (flechas), Rotate (anillos) y Scale (cubitos) - las 3
        // herramientas que ya tienen arrastre restringido a eje implementado (ver
        // hitTestGizmoAxis/hitTestGizmoRotateAxis/hitTestGizmoScaleAxis en MyGLRenderer).
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
        // Move/Rotate/Scale ya funcionan via onViewportDragMove/glView.onDragMove (libre, o
        // restringido a eje si el gizmo esta activo - ver onViewportDragStart/axisLocked);
        // Select via onViewportTap.
    }

    private fun updateLayoutToolHighlight() {
        selectToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.SELECT)
        moveToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.MOVE)
        rotateToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.ROTATE)
        scaleToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.SCALE)
        paintToolBtn.background = circleBackground(currentLayoutTool == LayoutTool.PAINT)
    }

    /**
     * ACTION_DOWN en el viewport. En Modeling: con Move activo, hace el hit-test del gizmo de
     * flechas (ver hitTestGizmoAxis, mismo hit-test que Object Mode) y guarda un snapshot de Undo
     * si hay algo seleccionado; con Scale activo hace el hit-test del gizmo de cubitos
     * (hitTestGizmoScaleAxis); con Rotate activo hace el hit-test del gizmo de anillos
     * (hitTestGizmoRotateAxis) y prepara la etiqueta de eje (X/Y/Z), mismo criterio que Layout.
     *
     * En Layout: si estamos con Move, Rotate o Scale activos y hay un objeto seleccionado, intenta
     * el hit-test contra el gizmo correspondiente (flechas para Move via hitTestGizmoAxis, anillos
     * para Rotate via hitTestGizmoRotateAxis, cubitos para Scale via hitTestGizmoScaleAxis). Si el
     * dedo toco el gizmo, el arrastre que sigue queda restringido a ese eje (ver
     * onViewportDragMove); si no, cae al gesto libre de siempre.
     */
    private fun onViewportDragStart(x: Float, y: Float) {
        if (boxSelectActive) {
            boxSelectDragging = true
            boxSelectStartX = x
            boxSelectStartY = y
            boxSelectCurrentX = x
            boxSelectCurrentY = y
            boxSelectOverlay.setRect(x, y, x, y)
            return
        }
        if (circleSelectActive) {
            circleSelectDragging = true
            circleSelectCenterX = x
            circleSelectCenterY = y
            circleSelectRadius = 0f
            circleSelectOverlay.setCenter(x, y)
            return
        }
        if (lassoSelectActive) {
            lassoSelectDragging = true
            lassoPoints.clear()
            lassoPoints.add(floatArrayOf(x, y))
            lassoSelectOverlay.startPath(x, y)
            return
        }
        axisLocked = null
        if (currentMode == AppMode.MODELING) {
            if (currentModelingTool == LayoutTool.MOVE && glView.renderer.hasSelectedMeshElements()) {
                axisLocked = glView.renderer.hitTestGizmoAxis(x, y)
                glView.renderer.activeMoveAxis = axisLocked
                glView.renderer.pushUndoSnapshot()
            }
            if (currentModelingTool == LayoutTool.SCALE && glView.renderer.hasSelectedMeshElements()) {
                glView.renderer.pushUndoSnapshot()
                axisLocked = glView.renderer.hitTestGizmoScaleAxis(x, y)
                glView.renderer.activeScaleAxis = axisLocked
            }
            if (currentModelingTool == LayoutTool.ROTATE && glView.renderer.hasSelectedMeshElements()) {
                axisLocked = glView.renderer.hitTestGizmoRotateAxis(x, y)
                glView.renderer.activeRotateAxis = axisLocked
                gizmoLabelView.labelText = null
                if (axisLocked != null) {
                    val axisNow = axisLocked!!
                    val anchor = glView.renderer.computeRotateLabelAnchor()
                    if (anchor != null) {
                        gizmoLabelView.labelText = axisNow.toString()
                        gizmoLabelView.labelX = anchor[0]
                        gizmoLabelView.labelY = anchor[1]
                    }
                }
                gizmoLabelView.invalidate()
                glView.renderer.pushUndoSnapshot()
            }
            return
        }
        if (currentMode != AppMode.LAYOUT) return
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
    /** ACTION_UP en el viewport: suelta el eje bloqueado, sea cual sea la herramienta activa - tambien limpia el resaltado del eje agarrado (activeRotateAxis/activeMoveAxis/activeScaleAxis) y la etiqueta de texto, si habia una transformacion restringida en curso. */
    private fun onViewportDragEnd() {
        if (boxSelectDragging) {
            boxSelectDragging = false
            boxSelectActive = false
            boxSelectOverlay.clear()
            suppressNextTap = true
            glView.renderer.selectMeshElementsInBox(
                minOf(boxSelectStartX, boxSelectCurrentX),
                minOf(boxSelectStartY, boxSelectCurrentY),
                maxOf(boxSelectStartX, boxSelectCurrentX),
                maxOf(boxSelectStartY, boxSelectCurrentY),
                currentEditSelectMode,
                extendSelectEnabled
            )
            glView.requestRender()
            return
        }
        if (circleSelectDragging) {
            circleSelectDragging = false
            circleSelectActive = false
            circleSelectOverlay.clear()
            suppressNextTap = true
            glView.renderer.selectMeshElementsInCircle(
                circleSelectCenterX,
                circleSelectCenterY,
                circleSelectRadius,
                currentEditSelectMode,
                extendSelectEnabled
            )
            glView.requestRender()
            return
        }
        axisLocked = null
        glView.renderer.activeRotateAxis = null
        glView.renderer.activeMoveAxis = null
        if (lassoSelectDragging) {
            lassoSelectDragging = false
            lassoSelectActive = false
            lassoSelectOverlay.clear()
            suppressNextTap = true
            glView.renderer.selectMeshElementsInLasso(lassoPoints.toList(), currentEditSelectMode, extendSelectEnabled)
            lassoPoints.clear()
            glView.requestRender()
            return
        }
        glView.renderer.activeScaleAxis = null
        gizmoLabelView.labelText = null
        gizmoLabelView.invalidate()
    }

    /**
     * Tap en el viewport 3D: si estamos en Layout con la herramienta Select activa, intenta
     * seleccionar el objeto tocado (o deselecciona todo si el tap cae en espacio vacio, igual
     * que en Blender). Move/Rotate/Scale ya tienen su propio gesto de arrastre (ver
     * onViewportDragMove).
     */
    private fun onViewportTap(x: Float, y: Float) {
        if (suppressNextTap) {
            suppressNextTap = false
            return
        }
        if (currentMode == AppMode.MODELING) {
            if (currentModelingTool == LayoutTool.SELECT) {
                if (knifeActive) { onKnifeTap(x, y); return }
                if (polyBuildActive) { onPolyBuildTap(x, y); return }
                glView.renderer.selectMeshElementAt(x, y, currentEditSelectMode, extendSelectEnabled)
                glView.requestRender()
            }
            return
        }

        if (currentMode != AppMode.LAYOUT) return
        if (currentLayoutTool != LayoutTool.SELECT) return
        glView.renderer.selectObjectAt(x, y)
        glView.requestRender()
    }

    /**
     * Arrastre en el viewport 3D.
     *
     * En Modeling: con Move activo, mueve los vertices/aristas/caras seleccionados (libre o
     * restringido a eje - ver MyGLRenderer.moveSelectedMeshElements/moveSelectedMeshElementsOnAxis).
     * Con Rotate activo, los rota libre o restringido a eje alrededor del centro de la seleccion
     * (ver MyGLRenderer.rotateSelectedMeshElements/rotateSelectedMeshElementsOnAxis). Con Scale
     * activo, los escala libre (uniforme) o restringido a eje alrededor de ese mismo centro (ver
     * MyGLRenderer.scaleSelectedMeshElements/scaleSelectedMeshElementsOnAxis). No cae a rotar la
     * camara aunque no haya nada seleccionado, mismo criterio que Layout con Move/Rotate/Scale
     * (ver comentario de abajo).
     *
     * En Layout: si estamos con Move, Rotate o Scale activos, aplica esa transformacion al objeto
     * seleccionado en vez de rotar la camara (que es el comportamiento por defecto del gesto, ver
     * MyGLSurfaceView.onDragMove). Los 3 quedan restringidos a un eje si el arrastre empezo tocando
     * el gizmo (ver onViewportDragStart / axisLocked); si no, caen al gesto libre de siempre (Scale
     * libre = escala uniforme, ver MyGLRenderer.scaleSelectedObject). Si no hay ningun objeto
     * seleccionado, el arrastre no hace nada - a proposito no cae a rotar la camara, para que quede
     * claro que estas en una de estas herramientas sin nada para transformar. Devuelve true
     * (arrastre consumido) siempre que una de las tres este activa, se haya transformado algo o no.
     */
    private fun onViewportDragMove(dx: Float, dy: Float, x: Float, y: Float): Boolean {
        if (boxSelectDragging) {
            boxSelectCurrentX = x
            boxSelectCurrentY = y
            boxSelectOverlay.setRect(boxSelectStartX, boxSelectStartY, x, y)
            return true
        }
        if (circleSelectDragging) {
            circleSelectOverlay.setRadiusToPoint(x, y)
            val dx0 = x - circleSelectCenterX
            val dy0 = y - circleSelectCenterY
            circleSelectRadius = kotlin.math.sqrt(dx0 * dx0 + dy0 * dy0)
            return true
        }
        if (lassoSelectDragging) {
            lassoPoints.add(floatArrayOf(x, y))
            lassoSelectOverlay.addPoint(x, y)
            return true
        }
        if (currentMode == AppMode.MODELING) {
            if (currentModelingTool == LayoutTool.MOVE) {
                val axis = axisLocked
                if (axis != null) {
                    glView.renderer.moveSelectedMeshElementsOnAxis(dx, dy, axis)
                } else {
                    glView.renderer.moveSelectedMeshElements(dx, dy)
                }
                return true
            }
            if (currentModelingTool == LayoutTool.ROTATE) {
                val rotAxis = axisLocked
                if (rotAxis != null) {
                    glView.renderer.updateActiveRotateCurrentDir(x, y, rotAxis)
                    glView.renderer.rotateSelectedMeshElementsOnAxis(dx, dy, rotAxis)
                    return true
                }
                glView.renderer.rotateSelectedMeshElements(dx, dy)
                return true
            }
            if (currentModelingTool == LayoutTool.SCALE) {
                val scaleAxis = axisLocked
                if (scaleAxis != null) {
                    glView.renderer.scaleSelectedMeshElementsOnAxis(dx, dy, scaleAxis)
                    return true
                }
                glView.renderer.scaleSelectedMeshElements(dy)
                return true
            }
            return false
        }
        if (currentMode != AppMode.LAYOUT) return false
        if (currentLayoutTool == LayoutTool.PAINT) {
            // Paint: el arrastre pinta y NO rota la camara (devolver true consume el gesto, ver MyGLSurfaceView).
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

    private fun setMode(mode: AppMode) {
        currentMode = mode
        updateModeHighlight()
        leftToolColumn.visibility = if (mode == AppMode.LAYOUT) View.VISIBLE else View.GONE
        modelingToolWrapper.visibility = if (mode == AppMode.MODELING) View.VISIBLE else View.GONE
        editSelectModeRow.visibility = if (mode == AppMode.MODELING) View.VISIBLE else View.GONE
        // Unica fuente de verdad para el criterio visual de Edit Mode (wireframe negro + resaltado
        // naranja de sub-elementos vs. contorno naranja de objeto completo, ver MyGLRenderer.isEditMode
        // y onDrawFrame) - se togglea aca, en cada cambio de modo, para que quede sincronizado con
        // currentMode sin duplicar esta condicion en otro lado.
        glView.renderer.isEditMode = (mode == AppMode.MODELING)
        // Entrar a Modeling entra a Edit Mode para el objeto seleccionado (ver
        // MyGLRenderer.enterEditModeForSelected) - crea su EditableMesh la primera vez y
        // (re)construye su geometria de dibujo dinamica.
        if (mode == AppMode.MODELING) {
            val entered = glView.renderer.enterEditModeForSelected()
            if (!entered) {
                Toast.makeText(this, "Esta primitiva todavia no es editable", Toast.LENGTH_SHORT).show()
            }
        }
        // TODO: cambiar el resto de la interfaz/herramientas segun el modo (UV Editing).
    }

    private fun updateModeHighlight() {
        layoutTab.background = circleBackground(currentMode == AppMode.LAYOUT)
        modelingTab.background = circleBackground(currentMode == AppMode.MODELING)
        uvEditingTab.background = circleBackground(currentMode == AppMode.UV_EDITING)
    }

    /**
     * Popup de General/Data (ver charla con el usuario): mismo patron que showFileMenu (icono
     * propio resaltado mientras esta abierto, PopupWindow con fondo redondeado), pero el
     * contenido por ahora es solo "Proximamente" (reusa renderPendingSubmenu) - todavia no hay
     * Outliner ni tabs de Properties implementados. onBack cierra el popup directo en vez de
     * volver a una lista de categorias (no hay "adentro" al que volver todavia).
     */
    private fun showGeneralOrDataMenu(anchor: View, label: String) {
        val density = resources.displayMetrics.density
        val menuColumn = LinearLayout(this)
        menuColumn.orientation = LinearLayout.VERTICAL
        menuColumn.background = menuBackground()
        val vPad = (6 * density).toInt()
        menuColumn.setPadding(vPad, vPad, vPad, vPad)

        val targetButton = if (label == "General") generalButton else dataButton
        targetButton.background = circleBackground(true)

        val popup = PopupWindow(
            menuColumn,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.isOutsideTouchable = true
        popup.elevation = 12 * density
        popup.setOnDismissListener {
            targetButton.background = circleBackground(false)
        }

        menuColumn.addView(buildSimpleMenuRow("Próximamente") { })

        popup.showAsDropDown(anchor, 0, (8 * density).toInt())
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

    /**
     * File > New/Save (ver MyGLRenderer.newProject/saveProjectToFile - un solo slot fijo, con
     * auto-carga al abrir la app, ver charla con el usuario): Import/Export siguen cayendo al
     * fallback Toast de abajo a proposito (dependen de un parser/exportador de formato externo,
     * fuera de alcance por ahora - ver esa misma charla).
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
        // TODO: reemplazar por la logica real de New/Save/Import/Export una vez definido el formato de proyecto.
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

        // Undo/Redo: pila de snapshots completos de sceneObjects (ver MyGLRenderer.undo/redo) -
        // limpia el eje/gizmo en curso (mismo criterio que onViewportDragEnd) porque el objeto
        // que estaba siendo arrastrado puede haber cambiado o dejado de existir tras restaurar
        // un snapshot. Mismo patron de Toast que Delete/Duplicate cuando no hay nada para hacer.
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
