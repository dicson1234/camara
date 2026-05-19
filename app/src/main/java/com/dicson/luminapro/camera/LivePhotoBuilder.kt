package com.dicson.luminapro.camera

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * LivePhotoBuilder — Google Motion Photo v1 (Formato Oficial)
 *
 * Implementa la especificación OFICIAL de Android:
 * https://developer.android.com/media/platform/motion-photo-format
 *
 * Incluye AMBOS formatos para máxima compatibilidad:
 * - Camera:MotionPhoto (formato actual v1)
 * - Camera:MicroVideo (formato legacy, para apps antiguas)
 * - Container:Directory con Item elements (requerido por Google Photos)
 *
 * Compatible con: Google Photos ✓, Galería MIUI ✓, Samsung Gallery ✓
 *
 * Nombre de archivo DEBE contener "MP" antes de la extensión
 * según la especificación: ^([^\s\/\\][^\/\\]*MP)\.(JPG|jpg)
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

    fun buildMotionPhotoBytes(jpegBytes: ByteArray, mp4Bytes: ByteArray): ByteArray {
        require(jpegBytes.size >= 2) { "JPEG vacío" }
        require(mp4Bytes.isNotEmpty()) { "MP4 vacío" }
        require((jpegBytes[0].toInt() and 0xFF) == 0xFF && (jpegBytes[1].toInt() and 0xFF) == 0xD8) { "No es JPEG válido" }

        // Generar el XMP con el formato oficial de Google Motion Photo v1
        val xmp = generateOfficialXmp(mp4Bytes.size)
        val modifiedJpeg = injectXmpIntoJpeg(jpegBytes, xmp)

        // Concatenar: JPEG modificado + MP4 (tightly packed, sin padding)
        val out = ByteArrayOutputStream(modifiedJpeg.size + mp4Bytes.size)
        out.write(modifiedJpeg)
        out.write(mp4Bytes)
        return out.toByteArray()
    }

    /**
     * Genera el XMP completo según la especificación oficial de Google.
     * Incluye:
     * 1. Camera:MotionPhoto = 1 (v1 actual)
     * 2. Camera:MotionPhotoVersion = 1
     * 3. Camera:MotionPhotoPresentationTimestampUs = 1500000 (1.5s)
     * 4. Camera:MicroVideo = 1 (legacy para apps viejas)
     * 5. Camera:MicroVideoVersion = 1
     * 6. Camera:MicroVideoOffset = videoLength
     * 7. Container:Directory con Item elements (REQUERIDO por Google Photos)
     */
    private fun generateOfficialXmp(videoLength: Int): String {
        return buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
            append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            append("<rdf:Description rdf:about=\"\"")
            // Namespace Camera (formato actual v1)
            append(" xmlns:Camera=\"http://ns.google.com/photos/1.0/camera/\"")
            // Namespace Container (directorio de items)
            append(" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"")
            // Namespace Item (elementos del contenedor)
            append(" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"")
            // === Camera metadata (v1 actual) ===
            append(" Camera:MotionPhoto=\"1\"")
            append(" Camera:MotionPhotoVersion=\"1\"")
            append(" Camera:MotionPhotoPresentationTimestampUs=\"1500000\"")
            // === Camera metadata (legacy MicroVideo para compatibilidad) ===
            append(" Camera:MicroVideo=\"1\"")
            append(" Camera:MicroVideoVersion=\"1\"")
            append(" Camera:MicroVideoOffset=\"$videoLength\"")
            append(" Camera:MicroVideoPresentationTimestampUs=\"1500000\"")
            append(">")
            // === Container Directory (REQUERIDO por Google Photos) ===
            append("<Container:Directory>")
            append("<rdf:Seq>")
            // Item 1: Imagen primaria (JPEG)
            append("<rdf:li rdf:parseType=\"Resource\">")
            append("<Item:Mime>image/jpeg</Item:Mime>")
            append("<Item:Semantic>Primary</Item:Semantic>")
            append("<Item:Length>0</Item:Length>")
            append("<Item:Padding>0</Item:Padding>")
            append("</rdf:li>")
            // Item 2: Video (MP4)
            append("<rdf:li rdf:parseType=\"Resource\">")
            append("<Item:Mime>video/mp4</Item:Mime>")
            append("<Item:Semantic>MotionPhoto</Item:Semantic>")
            append("<Item:Length>$videoLength</Item:Length>")
            append("</rdf:li>")
            append("</rdf:Seq>")
            append("</Container:Directory>")
            append("</rdf:Description>")
            append("</rdf:RDF>")
            append("</x:xmpmeta>")
            append("<?xpacket end=\"w\"?>")
        }
    }

    /**
     * Inyecta el XMP como segmento APP1 inmediatamente después del SOI (0xFFD8).
     * Usa el namespace estándar de Adobe XAP para que sea reconocido universalmente.
     */
    private fun injectXmpIntoJpeg(jpegData: ByteArray, xmp: String): ByteArray {
        val xmpNamespace = "http://ns.adobe.com/xap/1.0/\u0000"
        val namespaceBytes = xmpNamespace.toByteArray(Charsets.UTF_8)
        val xmpBytes = xmp.toByteArray(Charsets.UTF_8)
        val payload = namespaceBytes + xmpBytes
        val segmentLength = payload.size + 2 // +2 por los 2 bytes de longitud del segmento

        val out = ByteArrayOutputStream(jpegData.size + segmentLength + 4)

        // 1. Escribir SOI (Start of Image)
        out.write(0xFF)
        out.write(0xD8)

        // 2. Escribir segmento APP1 con XMP
        out.write(0xFF)
        out.write(0xE1) // APP1 marker
        out.write((segmentLength shr 8) and 0xFF) // Longitud MSB
        out.write(segmentLength and 0xFF)          // Longitud LSB
        out.write(payload)

        // 3. Escribir el resto del JPEG original (sin el SOI inicial)
        out.write(jpegData, 2, jpegData.size - 2)

        return out.toByteArray()
    }
}
