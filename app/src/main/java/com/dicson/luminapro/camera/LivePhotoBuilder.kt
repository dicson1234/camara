package com.dicson.luminapro.camera

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * LivePhotoBuilder (Motion Photo / MicroVideo)
 * 
 * Esta clase se encarga de empaquetar una fotografía JPEG y un video MP4
 * dentro de un solo archivo. Inyecta los metadatos XMP de Google MicroVideo
 * para que el archivo sea reconocido como una "Foto en Movimiento" (Live Photo)
 * nativamente en:
 * - TikTok (al subir como foto, te da la opción de Live)
 * - Google Photos
 * - Xiaomi Gallery (nativa del Redmi Note 8 Pro)
 * - Instagram (formato compatible)
 */
object LivePhotoBuilder {

    private const val XMP_NAMESPACE = "http://ns.google.com/photos/1.0/camera/"

    /**
     * Combina una imagen JPEG y un video MP4 en un solo archivo Motion Photo.
     * 
     * @param jpegBytes Array de bytes de la fotografía principal (alta calidad).
     * @param mp4Bytes Array de bytes del buffer de video corto (aprox 1.5 a 3 segs).
     * @param outputFile El archivo final (usualmente con extensión .jpg).
     */
    @Throws(IOException::class)
    fun buildMotionPhoto(jpegBytes: ByteArray, mp4Bytes: ByteArray, outputFile: File) {
        val videoLength = mp4Bytes.size
        
        // 1. Generar la cadena XMP con el MicroVideoOffset.
        // El offset es literalmente el tamaño en bytes del archivo de video adjunto al final.
        val xmpMetadata = generateMicroVideoXmp(videoLength)
        
        // 2. Inyectar el XMP en los bytes del JPEG.
        // Necesitamos crear un segmento APP1 con el XMP.
        val modifiedJpeg = injectXmpIntoJpeg(jpegBytes, xmpMetadata)

        // 3. Escribir todo secuencialmente en el archivo final.
        FileOutputStream(outputFile).use { fos ->
            // Primero la imagen JPEG con el XMP incrustado
            fos.write(modifiedJpeg)
            
            // Luego, mágicamente agregamos el video MP4 al final del archivo.
            // Los visores de imágenes normales ignoran esta data, pero las apps
            // compatibles leen el XMP, saltan al final y extraen el video.
            fos.write(mp4Bytes)
            fos.flush()
        }
    }

    /**
     * Genera el payload XML (XMP) estandarizado por Google para MicroVideos.
     */
    private fun generateMicroVideoXmp(videoLength: Int): String {
        // Presentation timestamp en microsegundos (mitad del video, o momento del shutter)
        val presentationTimestamp = 1500000 

        return """
            <?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                    GCamera:MicroVideo="1"
                    GCamera:MicroVideoVersion="1"
                    GCamera:MicroVideoOffset="$videoLength"
                    GCamera:MicroVideoPresentationTimestampUs="$presentationTimestamp"/>
              </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()
    }

    /**
     * Inyecta el XMP crudo en un segmento APP1 de un archivo JPEG.
     */
    private fun injectXmpIntoJpeg(jpegData: ByteArray, xmp: String): ByteArray {
        val out = ByteArrayOutputStream()
        
        // El string XMP para JPEGs requiere este prefijo exacto nulo-terminado
        val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000"
        val xmpPayloadBytes = xmpHeader.toByteArray() + xmp.toByteArray()
        
        val payloadSize = xmpPayloadBytes.size + 2 // +2 por los bytes de longitud del segmento
        
        var i = 0
        var inserted = false

        while (i < jpegData.size - 1) {
            // Chequear marcador (0xFF)
            if ((jpegData[i].toInt() and 0xFF) == 0xFF) {
                val marker = jpegData[i + 1].toInt() and 0xFF
                
                // SOI (Start Of Image) es 0xD8
                if (marker == 0xD8) {
                    out.write(jpegData, i, 2)
                    i += 2
                    
                    // Inmediatamente después de SOI, inyectamos nuestro APP1 con XMP
                    if (!inserted) {
                        out.write(0xFF)
                        out.write(0xE1) // Marcador APP1
                        
                        // Tamaño del segmento (Big Endian)
                        out.write(payloadSize shr 8)
                        out.write(payloadSize and 0xFF)
                        
                        // Escribir los datos
                        out.write(xmpPayloadBytes)
                        inserted = true
                    }
                    continue
                }
            }
            out.write(jpegData[i].toInt())
            i++
        }
        
        // Asegurarse de escribir el último byte
        if (i < jpegData.size) {
            out.write(jpegData[i].toInt())
        }
        
        return out.toByteArray()
    }
}
