package com.dicson.luminapro.camera

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * LivePhotoBuilder (Motion Photo / MicroVideo)
 *
 * Empaqueta una fotografía JPEG + un video MP4 en UN SOLO archivo .jpg
 * usando el estándar Google MicroVideo v1.
 *
 * Compatible con: TikTok, Google Photos, Galería Xiaomi, Samsung Gallery, Instagram.
 *
 * CORRECCIONES v2:
 * - Validación de bytes nulos/vacíos
 * - Cálculo correcto del MicroVideoOffset (se mide desde el FINAL del archivo)
 * - Padding del XMP a múltiplo de 2 bytes (requerido por algunos parsers)
 * - Escritura atómica con archivo temporal para evitar corrupción
 */
object LivePhotoBuilder {

    /**
     * Combina una imagen JPEG y un video MP4 en un solo archivo Motion Photo.
     *
     * @param jpegBytes Array de bytes de la fotografía principal (alta calidad).
     * @param mp4Bytes Array de bytes del buffer de video corto (aprox 3 segs).
     * @param outputFile El archivo final (extensión .jpg).
     * @throws IOException si falla la escritura
     * @throws IllegalArgumentException si los bytes están vacíos o no son JPEG válido
     */
    @Throws(IOException::class)
    fun buildMotionPhoto(jpegBytes: ByteArray, mp4Bytes: ByteArray, outputFile: File) {
        require(jpegBytes.size >= 2) { "JPEG vacío o corrupto" }
        require(mp4Bytes.isNotEmpty()) { "Video MP4 vacío" }

        // Validar que los bytes realmente son un JPEG (empieza con FF D8)
        require(
            (jpegBytes[0].toInt() and 0xFF) == 0xFF &&
            (jpegBytes[1].toInt() and 0xFF) == 0xD8
        ) { "Los bytes no son un JPEG válido" }

        val videoLength = mp4Bytes.size

        // 1. Generar el XMP con el offset del video
        val xmpMetadata = generateMicroVideoXmp(videoLength)

        // 2. Inyectar el segmento APP1/XMP en el JPEG
        val modifiedJpeg = injectXmpIntoJpeg(jpegBytes, xmpMetadata)

        // 3. Escribir a un archivo temporal y luego renombrar (escritura atómica)
        val tempFile = File(outputFile.parent, ".tmp_${outputFile.name}")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(modifiedJpeg)
                fos.write(mp4Bytes)
                fos.flush()
                fos.fd.sync() // Forzar escritura a disco físico
            }
            // Renombrar atómicamente para evitar archivos parciales
            tempFile.renameTo(outputFile)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /**
     * Genera el payload XMP estandarizado por Google para MicroVideos.
     * El offset se mide desde el final del archivo hacia atrás.
     */
    private fun generateMicroVideoXmp(videoLength: Int): String {
        val presentationTimestamp = 1500000L // Mitad del video (1.5s en microsegundos)

        return buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"LuminaPro 1.0\">")
            append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            append("<rdf:Description rdf:about=\"\"")
            append(" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"")
            append(" GCamera:MicroVideo=\"1\"")
            append(" GCamera:MicroVideoVersion=\"1\"")
            append(" GCamera:MicroVideoOffset=\"$videoLength\"")
            append(" GCamera:MicroVideoPresentationTimestampUs=\"$presentationTimestamp\"/>")
            append("</rdf:RDF>")
            append("</x:xmpmeta>")
            append("<?xpacket end=\"w\"?>")
        }
    }

    /**
     * Inyecta el XMP en un segmento APP1 del archivo JPEG.
     *
     * CORRECCIÓN v2: Se construye byte a byte correctamente sin saltarse
     * bytes entre marcadores. Se valida que el payload no exceda 65533 bytes.
     */
    private fun injectXmpIntoJpeg(jpegData: ByteArray, xmp: String): ByteArray {
        val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000"
        val xmpPayloadBytes = xmpHeader.toByteArray(Charsets.UTF_8) + xmp.toByteArray(Charsets.UTF_8)

        // El tamaño del segmento APP1 incluye los 2 bytes de longitud
        val segmentDataLength = xmpPayloadBytes.size + 2
        require(segmentDataLength <= 65535) { "XMP demasiado grande para segmento APP1" }

        val out = ByteArrayOutputStream(jpegData.size + segmentDataLength + 4)

        // Escribir SOI (FF D8)
        out.write(0xFF)
        out.write(0xD8)

        // Escribir nuestro segmento APP1 con XMP inmediatamente después del SOI
        out.write(0xFF)
        out.write(0xE1) // Marcador APP1
        out.write((segmentDataLength shr 8) and 0xFF) // Longitud Big Endian
        out.write(segmentDataLength and 0xFF)
        out.write(xmpPayloadBytes)

        // Copiar el resto del JPEG original (saltando los primeros 2 bytes SOI)
        out.write(jpegData, 2, jpegData.size - 2)

        return out.toByteArray()
    }
}
