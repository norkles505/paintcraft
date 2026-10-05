import re, shutil, pathlib

root = pathlib.Path(__file__).parent
kt = root / "app/src/main/java/com/meshcraft/app"
drw = root / "app/src/main/res/drawable"
backup = root / "reserva" / "etapa3"
(backup / "drawable").mkdir(parents=True, exist_ok=True)
log = []

p = kt / "MainActivity.kt"
shutil.copy2(p, backup / "MainActivity.kt")
src = p.read_bytes().decode("utf-8").replace("\r\n", "\n").split("\n")

cls = next(i for i, l in enumerate(src) if l.startswith("class MainActivity"))
end = max(i for i, l in enumerate(src) if l == "}")
pat = re.compile(r"^    (?:@Volatile |private |internal |override |lateinit |const |inline )*(fun|val|var|data class|class)\s+(?:<[^>]+>\s*)?(?:\w+\.)?(\w+)")
starts = []
for i in range(cls + 1, end):
    m = pat.match(src[i])
    if m:
        j = i
        while j - 1 > cls and re.match(r"^(    /\*\*|     \*|    \*/|     \*/|    //)", src[j - 1]):
            j -= 1
        starts.append((j, m.group(2)))
members = []
for k, (s, name) in enumerate(starts):
    e = starts[k + 1][0] if k + 1 < len(starts) else end
    members.append((s, e, name))

W = "LinearLayout.LayoutParams.WRAP_CONTENT"
UPDATE = f"        if (popup.isShowing) {{\n            popup.update({W}, {W})\n        }}\n"

replacements = {
    "objectMenuItems": (
        "    /** Contenido de Layout > Object: solo las acciones que tienen logica real (ver onObjectMenuAction). */\n"
        "    private val objectMenuItems = listOf(\"Duplicate Objects\", \"Show/Hide\", \"Clear\", \"Delete\")\n"
    ),
    "fillModeMenuWithCategoryContent": (
        "    /** Dispatcher: decide que render function usar segun la categoria elegida. */\n"
        "    private fun fillModeMenuWithCategoryContent(menuColumn: LinearLayout, mode: AppMode, category: String, popup: PopupWindow) {\n"
        "        when (category) {\n"
        "            \"View\" -> renderViewMenu(menuColumn, mode, popup)\n"
        "            \"Select\" -> renderLayoutSelectMenu(menuColumn, popup)\n"
        "            \"Add\" -> renderLayoutAddMenu(menuColumn, popup)\n"
        "            \"Object\" -> renderLayoutObjectMenu(menuColumn, popup)\n"
        "        }\n"
        "    }\n"
    ),
    "renderLayoutSelectMenu": (
        "    /** Layout > Select: solo \"None\" (deselecciona todo). Tocar espacio vacio en el viewport hace lo mismo. */\n"
        "    private fun renderLayoutSelectMenu(menuColumn: LinearLayout, popup: PopupWindow) {\n"
        "        menuColumn.removeAllViews()\n"
        "        menuColumn.addView(buildSimpleMenuRow(\"← Volver\") {\n"
        "            fillModeMenuWithCategories(menuColumn, AppMode.LAYOUT, popup)\n"
        "        })\n"
        "        menuColumn.addView(buildSimpleMenuRow(\"None\") {\n"
        "            popup.dismiss()\n"
        "            glView.renderer.deselectAll()\n"
        "            glView.requestRender()\n"
        "        })\n" + UPDATE +
        "    }\n"
    ),
    "renderViewMenu": (
        "    /** Layout > View: los 6 puntos de vista (Top, Bottom, Front, Back, Right, Left) - reusan animateCameraTo, igual que el gizmo de ejes (ver viewpointOptions). */\n"
        "    private fun renderViewMenu(menuColumn: LinearLayout, mode: AppMode, popup: PopupWindow) {\n"
        "        menuColumn.removeAllViews()\n"
        "        menuColumn.addView(buildSimpleMenuRow(\"← Volver\") {\n"
        "            fillModeMenuWithCategories(menuColumn, mode, popup)\n"
        "        })\n"
        "        for (option in viewpointOptions) {\n"
        "            menuColumn.addView(buildSimpleMenuRow(option.label) {\n"
        "                popup.dismiss()\n"
        "                animateCameraTo(option.angleX, option.angleY, option.planeAxis)\n"
        "            })\n"
        "        }\n" + UPDATE +
        "    }\n"
    ),
    "renderLayoutAddMenu": (
        "    /** Layout > Add: directo a las primitivas de malla; todas crean geometria real via MyGLRenderer.addXxx(). */\n"
        "    private fun renderLayoutAddMenu(menuColumn: LinearLayout, popup: PopupWindow) {\n"
        "        menuColumn.removeAllViews()\n"
        "        menuColumn.addView(buildSimpleMenuRow(\"← Volver\") {\n"
        "            fillModeMenuWithCategories(menuColumn, AppMode.LAYOUT, popup)\n"
        "        })\n"
        "        for (entry in meshPrimitiveEntries) {\n"
        "            menuColumn.addView(buildAddMenuItem(entry.iconRes, entry.label) {\n"
        "                popup.dismiss()\n"
        "                val r = glView.renderer\n"
        "                when (entry.label) {\n"
        "                    \"Plane\" -> r.addPlane()\n"
        "                    \"Cube\" -> r.addCube()\n"
        "                    \"Circle\" -> r.addCircle()\n"
        "                    \"UV Sphere\" -> r.addUvSphere()\n"
        "                    \"Ico Sphere\" -> r.addIcoSphere()\n"
        "                    \"Cylinder\" -> r.addCylinder()\n"
        "                    \"Cone\" -> r.addCone()\n"
        "                    \"Torus\" -> r.addTorus()\n"
        "                    \"Grid\" -> r.addGrid()\n"
        "                    \"Monkey\" -> r.addMonkey()\n"
        "                }\n"
        "                glView.requestRender()\n"
        "            })\n"
        "        }\n" + UPDATE +
        "    }\n"
    ),
}
delete = {"selectModeSubmenuItems", "selectMoreLessSubmenuItems", "selectAllByTypeEntries", "viewSimpleActionItems",
          "viewTrailingActionItems", "viewNavigationSubmenuItems", "addMenuEntries", "curvePrimitiveEntries",
          "surfacePrimitiveEntries", "metaballPrimitiveEntries", "greasePencilPrimitiveEntries", "emptyPrimitiveEntries",
          "imagePrimitiveEntries", "renderSelectSubmenu", "renderSelectAllByTypeSubmenu", "addSelectActionRow",
          "onSelectMenuAction", "renderViewpointSubmenu", "renderViewSubmenu", "addViewActionRow", "onViewMenuAction",
          "renderPrimitivesSubmenu", "onAddMenuAction", "renderMeshPrimitivesSubmenu"}
names = [m[2] for m in members]
missing = [n for n in list(replacements) + list(delete) if n not in names]
assert not missing, missing

out = src[:members[0][0]]
for s, e, name in members:
    if name in delete:
        continue
    if name in replacements:
        out.extend(replacements[name].rstrip("\n").split("\n"))
        out.append("")
    else:
        out.extend(src[s:e])
out.extend(src[end:])
text = "\n".join(out)

code = re.sub(r"//.*", "", text)
code = re.sub(r"/\*.*?\*/", "", code, flags=re.S)
code = re.sub(r'"(\\.|[^"\\])*"', '""', code)
balanced = code.count("{") == code.count("}")
log.append(f"MainActivity: lineas {len(src)} -> {len(out)}, llaves balanceadas={balanced}")
if not balanced:
    (root / "_menus_report.txt").write_text("\n".join(log) + "\nNO SE ESCRIBIO\n")
    raise SystemExit
p.write_bytes(text.replace("\n", "\r\n").encode("utf-8"))

# ---- iconos que quedaron sin uso ----
allkt = "".join(f.read_bytes().decode("utf-8") for f in kt.glob("*.kt"))
allxml = "".join(f.read_bytes().decode("utf-8") for f in (root / "app/src/main").rglob("*.xml") if f.parent != drw)
pat_ic = re.compile(r"^(ic_add_.*|ic_curve_.*|ic_surface_.*|ic_metaball_.*|ic_grease_pencil_.*|ic_empty_.*|ic_image_.*)\.xml$")
for f in sorted(drw.glob("*.xml")):
    if not pat_ic.match(f.name):
        continue
    if re.search(r"R\.drawable\." + f.stem + r"\b", allkt) or ("@drawable/" + f.stem) in allxml:
        log.append("NO borrado (en uso): " + f.name)
        continue
    shutil.copy2(f, backup / "drawable" / f.name)
    f.unlink()
    log.append("borrado icono " + f.name)
(root / "_menus_report.txt").write_text("\n".join(log) + "\n")
