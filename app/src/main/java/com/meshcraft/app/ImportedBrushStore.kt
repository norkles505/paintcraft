package com.meshcraft.app

import android.content.Context
import android.util.Base64

/**
 * Guarda los pinceles importados de ibisPaint para que sigan en la lista al cerrar y abrir la app.
 * De cada uno se guardan sus datos descomprimidos (IbisBrush.raw) en base64, uno por linea, en las preferencias de la app.
 * Se vuelven a armar con IbisBrush.fromData; si alguno ya no se puede leer (datos danados) se salta sin afectar a los demas.
 */
object ImportedBrushStore {
    private const val PREFS = "imported_brushes"
    private const val KEY = "list"

    /** Agrega a la lista de pinceles (BrushType) los que estaban guardados. Seguro llamarlo varias veces: los repetidos no se duplican. */
    fun load(context: Context) {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            try {
                val data = Base64.decode(line.trim(), Base64.NO_WRAP)
                BrushType.addImported(IbisBrush.fromData(data))
            } catch (e: IllegalArgumentException) {
                // Linea danada o de un formato que ya no se lee: se salta.
            }
        }
    }

    /** Guarda los pinceles importados que hay ahora en la lista (reemplaza lo guardado antes, asi tambien se olvidan los que se sacaron). */
    fun save(context: Context) {
        val sb = StringBuilder()
        for (bt in BrushType.values()) {
            val ibis = bt.ibis ?: continue
            sb.append(Base64.encodeToString(ibis.raw, Base64.NO_WRAP)).append('\n')
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, sb.toString()).apply()
    }
}
