package com.meshcraft.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Pincel de ibisPaint leido de los bytes de su codigo QR (ver fromQrBytes). Solo datos: no usa Android ni OpenGL.
 *
 * Como esta armado el QR (descubierto comparando muchos pinceles, ver la hoja de calculo del proyecto):
 *  - bytes del QR = "IPBZ" + 00 02 23 44 (constante) + datos comprimidos con deflate crudo (sin cabecera zlib).
 *  - los datos descomprimidos son 380 bytes: floats de 32 bits en big-endian (los porcentajes van como fraccion: 100 % = 1.0),
 *    algunos enteros y unos pocos bits de interruptores. Si el pincel tiene nombre propio (copias o renombrados) el nombre va
 *    en el offset 196 (largo de 2 bytes + texto UTF-8) y todo lo que viene despues se corre esos bytes (ver [shift]).
 *  - los offsets de abajo son los del formato SIN nombre; los >= 196 se corren cuando hay nombre.
 *
 * Que campos estan confirmados: los sliders de todas las pestanas (probados uno por uno con valores distintos) y tres interruptores
 * (antialiasing, opacidad constante, anadir opacidad). Los demas interruptores, los menus (modo de fusion, tipo Mono/Acuarela/Doble...)
 * y las curvas de presion todavia no se saben leer: quedan en [raw].
 */
class IbisBrush private constructor(
    /** Nombre propio guardado dentro del QR, o null (los pinceles de fabrica no lo traen: el nombre sale en la imagen del QR, no en los datos). */
    val name: String?,
    /** Numero del pincel de fabrica en ibisPaint (offset 16): 1 Pluma (Fuerte), 4 Rotulador (Fuerte), 6 Lapiz digital, 7 Aerografo (Normal)... */
    val brushId: Int,
    /** Identificador de la punta (16 bytes en hexa, offset 160): dos pinceles con la misma punta dibujan la misma forma. NO trae la imagen de la punta. */
    val tipId: String,
    /** Identificador de la textura (offset 180); el mismo en todos los pinceles vistos = sin textura. */
    val textureId: String,

    // ---- Basico ----
    /** Grosor en px de ibisPaint. */
    val size: Float,
    val maxSize: Float,
    val minSize: Float,
    /** Opacidad 0..1. */
    val opacity: Float,
    /** Grosor y opacidad al inicio y al final del trazo, como fraccion (1.0 = 100 %): una pluma empieza y termina en punta con 0. */
    val startSize: Float,
    val endSize: Float,
    val startOpacity: Float,
    val endOpacity: Float,
    val startDuration: Float,
    val endDuration: Float,

    // ---- Forma ----
    /** Distancia entre toques como fraccion del grosor (0.01 = 1 %). */
    val spacing: Float,
    /** "Diseno de opacidad" 0..1: parece la dureza del borde (aerografo 0.1, lapiz y marcador 1.0). Falta calibrarlo contra capturas. */
    val opacityDesign: Float,
    /** Angulo inicial de la punta, en grados. */
    val startAngle: Float,
    val aspect: Float,
    val point: Float,

    // ---- Desvanecer ----
    val fadeStartLength: Float,
    val fadeEndLength: Float,
    val blurDegree: Float,

    // ---- Dinamico (con el dedo no hay presion; la velocidad si se podria medir) ----
    val pressureSize: Float,
    val pressureOpacity: Float,
    val pressureBlur: Float,
    val speedSize: Float,
    val speedOpacity: Float,
    val speedBlur: Float,

    // ---- Temblor ----
    val jitterPosition: Float,
    val jitterSize: Float,
    val jitterOpacity: Float,
    val jitterSpacing: Float,
    val jitterAngle: Float,
    val scatterParticleSize: Float,
    val scatterDensity: Float,
    val scatterDeviation: Float,

    // ---- Tipo / textura ----
    val textureOpacity: Float,
    val textureScale: Float,
    val textureAngle: Float,

    // ---- Ajustes ----
    val stabilizer: Float,

    // ---- Interruptores confirmados ----
    val antialiasing: Boolean,
    val constantOpacity: Boolean,
    val addOpacity: Boolean,

    /** Los datos descomprimidos completos, por si hace falta algun campo que todavia no se lee. */
    val raw: ByteArray
) {
    /** Nombre para mostrar: el que trae el QR, o el del pincel de fabrica si se conoce su numero, o uno generico con el numero. */
    val displayName: String get() = name ?: FACTORY_NAMES[brushId] ?: ("Pincel ibisPaint " + brushId)

    companion object {
        /** Nombres de los pinceles de fabrica vistos hasta ahora (su QR no trae el nombre en los datos: solo sale en la imagen). */
        private val FACTORY_NAMES = mapOf(
            1 to "Pluma (Fuerte)",
            4 to "Rotulador (Fuerte)",
            6 to "Lápiz digital",
            7 to "Aerógrafo (Normal)",
            38 to "Marcador",
            41 to "Carboncillo",
            142 to "Acuarela (Mezcla)",
            145 to "Contorno"
        )

        private const val BASE_SIZE = 380
        private const val NAME_OFFSET = 196

        /**
         * Lee un pincel de los bytes que trae un QR de ibisPaint (el contenido del codigo en modo binario, tal cual).
         * Tira IllegalArgumentException (con mensaje para mostrar) si no parece un QR de pincel de ibisPaint.
         */
        fun fromQrBytes(bytes: ByteArray): IbisBrush {
            if (bytes.size < 12 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'P'.code.toByte() ||
                bytes[2] != 'B'.code.toByte() || bytes[3] != 'Z'.code.toByte()
            ) {
                throw IllegalArgumentException("Ese codigo QR no es de un pincel de ibisPaint")
            }
            return fromData(inflate(bytes, 8))
        }

        /**
         * Lee un pincel de sus datos ya descomprimidos (los 380 bytes, o mas si trae nombre): lo que queda en [raw]. Sirve para volver a armar
         * un pincel guardado sin repetir la descompresion. Tira IllegalArgumentException si los datos no tienen el tamano esperado.
         */
        fun fromData(data: ByteArray): IbisBrush {
            if (data.size < BASE_SIZE) throw IllegalArgumentException("El pincel de ibisPaint trae menos datos de los esperados")

            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            // Con nombre propio los datos miden 2 + largo bytes mas que sin nombre (ver la descripcion de arriba).
            val extra = data.size - BASE_SIZE
            var name: String? = null
            var shift = 0
            if (extra >= 2) {
                val len = buf.getShort(NAME_OFFSET).toInt() and 0xFFFF
                if (len == extra - 2) {
                    name = String(data, NAME_OFFSET + 2, len, Charsets.UTF_8)
                    shift = extra
                }
            }

            // Los offsets >= 196 vienen despues del nombre: se corren si el QR lo trae.
            fun f(off: Int): Float = buf.getFloat(if (off >= NAME_OFFSET) off + shift else off)
            fun hex(from: Int): String {
                val start = if (from >= NAME_OFFSET) from + shift else from
                val sb = StringBuilder()
                for (i in start until start + 16) sb.append(String.format("%02x", data[i].toInt() and 0xFF))
                return sb.toString()
            }
            fun bit(byteIndex: Int, bit: Int): Boolean = (data[byteIndex].toInt() shr bit) and 1 == 1

            return IbisBrush(
                name = name,
                brushId = buf.getInt(16),
                tipId = hex(160),
                textureId = hex(180),
                size = f(20),
                maxSize = f(24),
                minSize = f(152),
                opacity = f(84),
                startSize = f(28),
                endSize = f(32),
                startOpacity = f(36),
                endOpacity = f(40),
                startDuration = f(344),
                endDuration = f(348),
                spacing = f(48),
                opacityDesign = f(52),
                startAngle = f(44),
                aspect = f(268),
                point = f(272),
                fadeStartLength = f(364),
                fadeEndLength = f(368),
                blurDegree = f(252),
                pressureSize = f(60),
                pressureOpacity = f(64),
                pressureBlur = f(132),
                speedSize = f(120),
                speedOpacity = f(124),
                speedBlur = f(128),
                jitterPosition = f(88),
                jitterSize = f(76),
                jitterOpacity = f(72),
                jitterSpacing = f(92),
                jitterAngle = f(68),
                scatterParticleSize = f(208),
                scatterDensity = f(212),
                scatterDeviation = f(216),
                textureOpacity = f(144),
                textureScale = f(136),
                textureAngle = f(140),
                stabilizer = f(352),
                antialiasing = bit(59, 0),
                constantOpacity = bit(56, 3),
                addOpacity = bit(56, 6),
                raw = data
            )
        }

        /** Descomprime el deflate crudo (sin cabecera zlib) que empieza en [from]. */
        private fun inflate(bytes: ByteArray, from: Int): ByteArray {
            val inflater = Inflater(true)
            try {
                inflater.setInput(bytes, from, bytes.size - from)
                val out = ByteArray(8192)
                var total = 0
                while (!inflater.finished()) {
                    val n = inflater.inflate(out, total, out.size - total)
                    if (n == 0) {
                        // Sin progreso: se acabaron los datos (QR cortado) o el buffer se lleno (mas de lo que mide un pincel).
                        if (inflater.needsInput() || inflater.needsDictionary() || total == out.size) break
                    }
                    total += n
                }
                if (!inflater.finished()) throw IllegalArgumentException("Los datos del pincel estan incompletos o danados")
                return out.copyOf(total)
            } catch (e: java.util.zip.DataFormatException) {
                throw IllegalArgumentException("Los datos del pincel estan danados")
            } finally {
                inflater.end()
            }
        }
    }
}
