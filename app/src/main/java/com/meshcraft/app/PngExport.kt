package com.meshcraft.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Que se exporta a PNG (ver MainActivity.startExport). */
enum class ExportKind {
    /** Textura completa (capas visibles) sobre el fondo elegido, tal como se ve en el modelo (con "sin fondo" sale transparente). */
    TEXTURE_WITH_BASE,
    /** Textura completa (capas visibles) con transparencia donde no hay nada pintado. */
    TEXTURE_TRANSPARENT,
    /** Solo una capa, con su transparencia. */
    LAYER
}

/**
 * Escritor de PNG propio (RGBA de 8 bits, sin entrelazado). Se usa en vez de Bitmap.compress porque el Bitmap de Android guarda
 * los colores premultiplicados por el alfa, y al exportar los pixeles semitransparentes cambiarian de color.
 */
object PngExport {

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * Escribe en out un PNG cuadrado de size x size pixeles. rgba: size * size * 4 bytes (R, G, B, A), alfa recto, sin premultiplicar. No cierra out.
     * flipVertical: las UVs del OBJ tienen v = 0 ABAJO, y la textura guarda v = 0 en la fila 0; en un PNG la fila 0 es ARRIBA. Con
     * true (por defecto) se escribe volteado en vertical, para que en Blender la textura caiga en su lugar.
     */
    fun write(out: OutputStream, size: Int, rgba: ByteArray, flipVertical: Boolean = true) {
        require(rgba.size == size * size * 4) { "Tamano de pixeles incorrecto" }
        out.write(SIGNATURE)

        // IHDR: ancho, alto, 8 bits por canal, tipo de color 6 (RGBA), sin compresion extra, sin filtro especial, sin entrelazado.
        val header = ByteArrayOutputStream(13)
        val headerData = DataOutputStream(header)
        headerData.writeInt(size)
        headerData.writeInt(size)
        headerData.writeByte(8)
        headerData.writeByte(6)
        headerData.writeByte(0)
        headerData.writeByte(0)
        headerData.writeByte(0)
        writeChunk(out, "IHDR", header.toByteArray())

        // IDAT: cada fila lleva delante un byte de filtro (0 = sin filtro) y todo va comprimido con zlib.
        val compressed = ByteArrayOutputStream(1 shl 20)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        val zip = DeflaterOutputStream(compressed, deflater, 64 * 1024)
        val rowBytes = size * 4
        val noFilter = byteArrayOf(0)
        for (y in 0 until size) {
            val srcRow = if (flipVertical) size - 1 - y else y
            zip.write(noFilter)
            zip.write(rgba, srcRow * rowBytes, rowBytes)
        }
        zip.finish()
        deflater.end()
        writeChunk(out, "IDAT", compressed.toByteArray())

        writeChunk(out, "IEND", ByteArray(0))
        out.flush()
    }

    /** Un bloque del PNG: largo, tipo, datos y CRC32 del tipo + datos. */
    private fun writeChunk(out: OutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val dos = DataOutputStream(out)
        dos.writeInt(data.size)
        dos.write(typeBytes)
        dos.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        dos.writeInt(crc.value.toInt())
        dos.flush()
    }
}
