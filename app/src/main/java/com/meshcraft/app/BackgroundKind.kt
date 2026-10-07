package com.meshcraft.app

/**
 * Fondo que va debajo de las capas de un modelo (selector "Fondo" del panel de capas). Es el color con el que se mezclan
 * las capas (importa para Multiplicar, Trama, etc.) y lo que se ve donde no hay nada pintado.
 * NONE = sin fondo: en el modelo se ve el tablero claro (para saber que es transparente) y al exportar sale transparente.
 */
enum class BackgroundKind {
    WHITE, CHECKER, CHECKER_DARK, NONE;

    companion object {
        /** Lee el nombre guardado en el proyecto; un nombre desconocido o viejo cae en WHITE. */
        fun fromName(name: String): BackgroundKind = values().firstOrNull { it.name == name } ?: WHITE
    }
}
