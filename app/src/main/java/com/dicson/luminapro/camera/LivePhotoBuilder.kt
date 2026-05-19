package com.dicson.luminapro.camera

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * LivePhotoBuilder — Estándar Google MicroVideo v1
 *
 * Compatible con: Google Photos, Galería Xiaomi/MIUI, Samsung Gallery,
 * TikTok (reconoce Motion Photos al subir), Instagram.
 *
 * Nota: WhatsApp comprime las imágenes y elimina los metadatos extra,
 * por lo que la Live Photo se verá como foto estática al enviarse por WhatsApp
 * (esto pasa con TODAS las apps de Live Photo, incluyendo las de Apple).
 * Para compartir la Live Photo con movimiento por WhatsApp, hay que enviarla
 * como DOCUMENTO (no como foto).
 */
object LivePhotoBuilder {

    @Throws(IOException::class)
    fun buildMotionPhoto(jpegBytes: ByteArray, mp4Bytes: ByteArray, outputFile: File) {
        val result = buildMotionPhotoBytes(jpegBytes, mp4Bytes)
        val tempFile = File(outputFile.parent, ".tmp_${outputFile.name}")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(result)
                fos.flush()
                fos.fd.sync()
            }
            tempFile.renameTo(outputFile)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /**
     * Devuelve los bytes del archivo Motion Photo completo.
     * Útil para guardar directamente vía MediaStore sin crear un archivo temporal.
     */
    fun buildMotionPhotoBytes(jpegBytes: ByteArray, mp4Bytes: ByteArray): ByteArray {
        require(jpegBytes.size >= 2) { "JPEG vacío" }
        require(mp4Bytes.isNotEmpty()) { "MP4 vacío" }
        require((jpegBytes[0].toInt() and 0xFF) == 0xFF && (jpegBytes[1].toInt() and 0xFF) == 0xD8) { "No es JPEG" }

        val xmp = generateXmp(mp4Bytes.size)
        val modifiedJpeg = injectXmp(jpegBytes, xmp)

        val out = ByteArrayOutputStream(modifiedJpeg.size + mp4Bytes.size)
        out.write(modifiedJpeg)
        out.write(mp4Bytes)
        return out.toByteArray()
    }

    private fun generateXmp(videoLength: Int): String {
        return buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"LuminaPro\">")
            append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            append("<rdf:Description rdf:about=\"\"")
            append(" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"")
            append(" GCamera:MicroVideo=\"1\"")
            append(" GCamera:MicroVideoVersion=\"1\"")
            append(" GCamera:MicroVideoOffset=\"$videoLength\"")
            append(" GCamera:MicroVideoPresentationTimestampUs=\"1500000\"/>")
            append("</rdf:RDF>")
            append("</x:xmpmeta>")
            append("<?xpacket end=\"w\"?>")
        }
    }

    private fun injectXmp(jpegData: ByteArray, xmp: String): ByteArray {
        val header = "http://ns.adobe.com/xap/1.0/\u0000"
        val payload = header.toByteArray(Charsets.UTF_8) + xmp.toByteArray(Charsets.UTF_8)
        val segLen = payload.size + 2

        val out = ByteArrayOutputStream(jpegData.size + segLen + 4)
        out.write(0xFF); out.write(0xD8) // SOI
        out.write(0xFF); out.write(0xE1) // APP1
        out.write((segLen shr 8) and 0xFF)
        out.write(segLen and 0xFF)
        out.write(payload)
        out.write(jpegData, 2, jpegData.size - 2) // Resto del JPEG sin SOI
        return out.toByteArray()
    }
}
