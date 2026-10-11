package com.meshcraft.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream

/**
 * Lee el codigo QR de una imagen (por ejemplo una captura de pantalla del QR de un pincel de ibisPaint) y devuelve los bytes que trae,
 * tal cual (no como texto: el QR de ibisPaint es binario). Usa la libreria ZXing para encontrar el QR dentro de la imagen.
 */
object QrImageReader {
    /** Lado mayor al que se reduce la imagen antes de leerla (una captura de pantalla entera es mas grande de lo que hace falta). */
    private const val MAX_SIDE = 2048

    /**
     * Bytes del QR de la imagen uri. Tira IllegalArgumentException (con un mensaje para mostrar al usuario) si no se puede abrir la
     * imagen o no hay un QR legible. Puede tardar un poco: llamarlo desde un hilo aparte, no desde el de la interfaz.
     */
    fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray {
        val bitmap = loadScaled(resolver, uri) ?: throw IllegalArgumentException("No se pudo abrir la imagen")
        try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            // Primero tal cual; si no aparece, con los colores invertidos (QR blanco sobre fondo oscuro).
            return decode(source) ?: decode(source.invert()) ?: throw IllegalArgumentException("No se encontró un código QR legible en la imagen")
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(source: LuminanceSource): ByteArray? {
        val hints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)
        return try {
            val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints)
            // Los bytes originales del modo binario: ZXing los guarda aparte del texto (que para datos binarios no sirve).
            val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<*>
            if (segments != null && segments.isNotEmpty()) {
                val out = ByteArrayOutputStream()
                for (s in segments) if (s is ByteArray) out.write(s)
                out.toByteArray()
            } else {
                result.text.toByteArray(Charsets.ISO_8859_1)
            }
        } catch (e: com.google.zxing.ReaderException) {
            // NotFound, Checksum o Format: no hay un QR legible.
            null
        }
    }

    /** Abre la imagen reducida para que su lado mayor no pase de MAX_SIDE (ahorra memoria). */
    private fun loadScaled(resolver: ContentResolver, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Con inJustDecodeBounds, decodeStream no devuelve imagen (siempre null): solo llena bounds con el ancho y el alto.
        val boundsStream = resolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}
