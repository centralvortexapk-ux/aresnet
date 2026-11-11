package com.example.crypto

import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Result of a file security inspection.
 */
sealed class SecurityCheckResult {
    object Safe : SecurityCheckResult()
    data class Rejected(val reason: String, val threatLevel: ThreatLevel) : SecurityCheckResult()
}

enum class ThreatLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * FileSecurityValidator protects AresNet users from malicious files:
 * 1. Blocks executable formats (.apk, .dex, .exe, .sh, .bat, etc.).
 * 2. Detects double extension attacks (e.g. "song.mp3.apk").
 * 3. Sanitizes path traversal attempts ("../../etc/passwd").
 * 4. Inspects "Magic Bytes" on initial file chunks to prevent malware disguised as audio/video/image.
 */
object FileSecurityValidator {

    private const val TAG = "FileSecurityValidator"

    // Disallowed executable and script extensions
    val DANGEROUS_EXTENSIONS = setOf(
        "apk", "dex", "exe", "bat", "cmd", "sh", "bin", "jar", "vbs", "ps1",
        "msi", "dll", "so", "scr", "pif", "com", "js", "py", "elf", "appimage"
    )

    // Allowed media extensions for decentralized sharing
    val ALLOWED_MEDIA_EXTENSIONS = setOf(
        // Audio
        "mp3", "m4a", "flac", "wav", "ogg", "aac", "opus", "wma", "mid",
        // Video
        "mp4", "mkv", "webm", "avi", "mov", "3gp",
        // Images / Covers
        "jpg", "jpeg", "png", "webp", "gif"
    )

    /**
     * Inspects a filename for extension spoofing, double extensions, and path traversal.
     */
    fun validateFileName(fileName: String): SecurityCheckResult {
        if (fileName.isBlank()) {
            return SecurityCheckResult.Rejected("Nombre de archivo vacío", ThreatLevel.LOW)
        }

        // 1. Path traversal check
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return SecurityCheckResult.Rejected(
                "Intento de Path Traversal detectado en el nombre de archivo",
                ThreatLevel.CRITICAL
            )
        }

        // 2. Right-to-Left Override (RTLO) unicode attack check (\u202E)
        if (fileName.contains("\u202E") || fileName.contains("\u202D")) {
            return SecurityCheckResult.Rejected(
                "Caracteres Unicode RTLO sospechosos detectados (posible suplantación de extensión)",
                ThreatLevel.CRITICAL
            )
        }

        // 3. Double extension check (e.g. track.mp3.apk or song.wav.exe)
        val parts = fileName.lowercase(Locale.ROOT).split(".")
        if (parts.size > 2) {
            val lastExt = parts.last()
            val secondLastExt = parts[parts.size - 2]
            if (DANGEROUS_EXTENSIONS.contains(lastExt) || (ALLOWED_MEDIA_EXTENSIONS.contains(secondLastExt) && DANGEROUS_EXTENSIONS.contains(lastExt))) {
                return SecurityCheckResult.Rejected(
                    "Archivo ejecutable con doble extensión engañosa: .$secondLastExt.$lastExt",
                    ThreatLevel.CRITICAL
                )
            }
        }

        // 4. Disallowed executable check
        val extension = fileName.substringAfterLast(".", "").lowercase(Locale.ROOT)
        if (DANGEROUS_EXTENSIONS.contains(extension)) {
            return SecurityCheckResult.Rejected(
                "Tipo de archivo no permitido ($extension): No se permiten archivos ejecutables en AresNet",
                ThreatLevel.HIGH
            )
        }

        // 5. Allowed media whitelist check
        if (!ALLOWED_MEDIA_EXTENSIONS.contains(extension)) {
            return SecurityCheckResult.Rejected(
                "Extensión '$extension' no admitida en la biblioteca multimedia",
                ThreatLevel.MEDIUM
            )
        }

        return SecurityCheckResult.Safe
    }

    /**
     * Inspects the "Magic Bytes" (header signature) of the first chunk (Chunk 0)
     * to confirm that the binary content matches legitimate audio/video/image formats
     * and is NOT an executable (APK, DEX, ZIP, ELF, Windows PE) renamed with a fake media extension.
     */
    fun validateMagicBytes(chunkData: ByteArray, expectedExtension: String): SecurityCheckResult {
        if (chunkData.size < 4) {
            return SecurityCheckResult.Safe // Not enough bytes to evaluate
        }

        // Check for Android APK / ZIP archive magic bytes: 'PK\x03\x04'
        if (chunkData[0] == 0x50.toByte() && chunkData[1] == 0x4B.toByte() &&
            chunkData[2] == 0x03.toByte() && chunkData[3] == 0x04.toByte()
        ) {
            return SecurityCheckResult.Rejected(
                "Archivo falso: Se detectó formato ZIP/APK ejecutable camuflado como $expectedExtension",
                ThreatLevel.CRITICAL
            )
        }

        // Check for Android Dalvik Executable magic bytes: 'dex\n'
        if (chunkData[0] == 0x64.toByte() && chunkData[1] == 0x65.toByte() &&
            chunkData[2] == 0x78.toByte() && chunkData[3] == 0x0A.toByte()
        ) {
            return SecurityCheckResult.Rejected(
                "Archivo falso: Se detectó ejecutable DEX de Android camuflado como $expectedExtension",
                ThreatLevel.CRITICAL
            )
        }

        // Check for Linux ELF executable: '\x7FELF'
        if (chunkData[0] == 0x7F.toByte() && chunkData[1] == 0x45.toByte() &&
            chunkData[2] == 0x4C.toByte() && chunkData[3] == 0x46.toByte()
        ) {
            return SecurityCheckResult.Rejected(
                "Archivo falso: Se detectó binario ejecutable ELF camuflado como $expectedExtension",
                ThreatLevel.CRITICAL
            )
        }

        // Check for Windows PE executable: 'MZ'
        if (chunkData[0] == 0x4D.toByte() && chunkData[1] == 0x5A.toByte()) {
            return SecurityCheckResult.Rejected(
                "Archivo falso: Se detectó ejecutable Windows MZ/PE camuflado como $expectedExtension",
                ThreatLevel.CRITICAL
            )
        }

        // Verify genuine media headers
        val ext = expectedExtension.lowercase(Locale.ROOT)
        when (ext) {
            "mp3" -> {
                val hasId3 = chunkData.size >= 3 &&
                        chunkData[0] == 0x49.toByte() &&
                        chunkData[1] == 0x44.toByte() &&
                        chunkData[2] == 0x33.toByte() // "ID3"
                val hasMpegSync = (chunkData[0].toInt() and 0xFF) == 0xFF &&
                        ((chunkData[1].toInt() and 0xE0) == 0xE0) // MPEG frame sync
                if (!hasId3 && !hasMpegSync && chunkData.size >= 128) {
                    Log.w(TAG, "Cabecera MP3 no estándar, pero no ejecutable.")
                }
            }
            "flac" -> {
                val hasFlac = chunkData.size >= 4 &&
                        chunkData[0] == 0x66.toByte() &&
                        chunkData[1] == 0x4C.toByte() &&
                        chunkData[2] == 0x61.toByte() &&
                        chunkData[3] == 0x43.toByte() // "fLaC"
                if (!hasFlac) {
                    return SecurityCheckResult.Rejected(
                        "Formato FLAC inválido o corrupto (firma 'fLaC' ausente)",
                        ThreatLevel.MEDIUM
                    )
                }
            }
            "mp4", "m4a" -> {
                // MP4 contains 'ftyp' at offset 4
                if (chunkData.size >= 8) {
                    val hasFtyp = chunkData[4] == 0x66.toByte() &&
                            chunkData[5] == 0x74.toByte() &&
                            chunkData[6] == 0x79.toByte() &&
                            chunkData[7] == 0x70.toByte() // "ftyp"
                    if (!hasFtyp) {
                        Log.d(TAG, "Contenedor ISO Base Media sin box ftyp inicial")
                    }
                }
            }
            "ogg", "opus" -> {
                val hasOgg = chunkData.size >= 4 &&
                        chunkData[0] == 0x4F.toByte() &&
                        chunkData[1] == 0x67.toByte() &&
                        chunkData[2] == 0x67.toByte() &&
                        chunkData[3] == 0x53.toByte() // "OggS"
                if (!hasOgg) {
                    return SecurityCheckResult.Rejected(
                        "Firma OGG no coincide con 'OggS'",
                        ThreatLevel.MEDIUM
                    )
                }
            }
            "png" -> {
                val hasPng = chunkData.size >= 4 &&
                        (chunkData[0].toInt() and 0xFF) == 0x89 &&
                        chunkData[1] == 0x50.toByte() &&
                        chunkData[2] == 0x4E.toByte() &&
                        chunkData[3] == 0x47.toByte() // "\x89PNG"
                if (!hasPng) {
                    return SecurityCheckResult.Rejected(
                        "Firma de imagen PNG corrupta o inválida",
                        ThreatLevel.HIGH
                    )
                }
            }
            "jpg", "jpeg" -> {
                val hasJpg = chunkData.size >= 3 &&
                        (chunkData[0].toInt() and 0xFF) == 0xFF &&
                        (chunkData[1].toInt() and 0xFF) == 0xD8 &&
                        (chunkData[2].toInt() and 0xFF) == 0xFF // JPEG SOI marker
                if (!hasJpg) {
                    return SecurityCheckResult.Rejected(
                        "Firma de imagen JPEG corrupta o inválida",
                        ThreatLevel.HIGH
                    )
                }
            }
        }

        return SecurityCheckResult.Safe
    }

    /**
     * Sanitizes a file name for safe local disk storage, removing hazardous characters.
     */
    fun sanitizeSafeLocalFileName(rawName: String): String {
        return rawName
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .replace("..", "_")
            .trim()
            .ifEmpty { "ares_track_${System.currentTimeMillis()}.mp3" }
    }
}
