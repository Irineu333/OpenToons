package com.neoutils.opentoons.util

/**
 * Leitor de dimensões a partir do **header** da imagem, em `commonMain` (design D1, task 1.1).
 * Uma única implementação → resultado idêntico nas três plataformas. Lê apenas os primeiros
 * bytes de PNG (IHDR), JPEG (marcadores SOF*), WebP (`VP8 `/`VP8L`/`VP8X`) e GIF (logical
 * screen descriptor); retorna `null` para formato desconhecido ou header truncado.
 *
 * **Política de EXIF (task 1.2):** as dimensões são as do *bitstream* (largura/altura como
 * codificadas), **ignorando** a orientação EXIF do JPEG. Casa com JVM (`ImageIO`) e Android
 * (`BitmapFactory`), que também a ignoram; o iOS (`UIImage`) *aplicava* a orientação e
 * divergia — trocando largura/altura num JPEG `orientation=6`. Por isso o sniff comum
 * substitui os três decoders divergentes (task 1.4). Para geometria e pixel não divergirem,
 * o render do Coil 3 deve entregar o pixel na mesma orientação do bitstream; a confirmação em
 * runtime por plataforma é verificação manual (Open Question do design, task 1.2).
 */
object ImageHeaderReader {

    /** Dimensões do bitstream, ou `null` se o formato for desconhecido/o header truncado. */
    fun readSize(bytes: ByteArray): ImageSize? = when {
        isPng(bytes) -> png(bytes)
        isJpeg(bytes) -> jpeg(bytes)
        isWebp(bytes) -> webp(bytes)
        isGif(bytes) -> gif(bytes)
        else -> null
    }

    // ---- leitura de inteiros sem sinal ----

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun beU16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun beU32(b: ByteArray, i: Int): Int =
        (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun leU16(b: ByteArray, i: Int): Int = (u8(b, i + 1) shl 8) or u8(b, i)
    private fun leU24(b: ByteArray, i: Int): Int =
        (u8(b, i + 2) shl 16) or (u8(b, i + 1) shl 8) or u8(b, i)

    private fun tag(b: ByteArray, i: Int, s: String): Boolean {
        if (i + s.length > b.size) return false
        for (k in s.indices) if (u8(b, i + k) != s[k].code) return false
        return true
    }

    // ---- PNG ----

    private fun isPng(b: ByteArray): Boolean =
        b.size >= 24 &&
            u8(b, 0) == 0x89 && u8(b, 1) == 0x50 && u8(b, 2) == 0x4E && u8(b, 3) == 0x47 &&
            u8(b, 4) == 0x0D && u8(b, 5) == 0x0A && u8(b, 6) == 0x1A && u8(b, 7) == 0x0A

    // IHDR é o primeiro chunk: largura em [16,20), altura em [20,24), big-endian.
    private fun png(b: ByteArray): ImageSize? {
        val w = beU32(b, 16)
        val h = beU32(b, 20)
        return if (w > 0 && h > 0) ImageSize(w, h) else null
    }

    // ---- JPEG ----

    private fun isJpeg(b: ByteArray): Boolean =
        b.size >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8

    // Varre segmentos até um marcador SOF* (Start Of Frame), de onde saem altura/largura. Pula
    // os demais segmentos pelo seu comprimento. Ignora a orientação EXIF (fica no APP1, não no SOF).
    private fun jpeg(b: ByteArray): ImageSize? {
        var i = 2
        while (i + 9 <= b.size) {
            if (u8(b, i) != 0xFF) { i++; continue }
            val marker = u8(b, i + 1)
            when {
                marker == 0xFF -> { i++; continue } // padding
                marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD9 -> { i += 2; continue } // sem length
                else -> {
                    val len = beU16(b, i + 2)
                    if (len < 2) return null
                    val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                    if (isSof) {
                        val h = beU16(b, i + 5)
                        val w = beU16(b, i + 7)
                        return if (w > 0 && h > 0) ImageSize(w, h) else null
                    }
                    i += 2 + len
                }
            }
        }
        return null
    }

    // ---- WebP (RIFF) ----

    private fun isWebp(b: ByteArray): Boolean =
        b.size >= 16 && tag(b, 0, "RIFF") && tag(b, 8, "WEBP")

    private fun webp(b: ByteArray): ImageSize? = when {
        tag(b, 12, "VP8 ") -> webpLossy(b)
        tag(b, 12, "VP8L") -> webpLossless(b)
        tag(b, 12, "VP8X") -> webpExtended(b)
        else -> null
    }

    // Lossy: frame tag (3B) + start code 9D 01 2A, depois largura/altura 14-bit little-endian.
    private fun webpLossy(b: ByteArray): ImageSize? {
        if (b.size < 30) return null
        if (u8(b, 23) != 0x9D || u8(b, 24) != 0x01 || u8(b, 25) != 0x2A) return null
        val w = leU16(b, 26) and 0x3FFF
        val h = leU16(b, 28) and 0x3FFF
        return if (w > 0 && h > 0) ImageSize(w, h) else null
    }

    // Lossless: assinatura 0x2F, depois 14-bit (largura-1) e 14-bit (altura-1), empacotados LE.
    private fun webpLossless(b: ByteArray): ImageSize? {
        if (b.size < 25 || u8(b, 20) != 0x2F) return null
        val b0 = u8(b, 21); val b1 = u8(b, 22); val b2 = u8(b, 23); val b3 = u8(b, 24)
        val w = (((b1 and 0x3F) shl 8) or b0) + 1
        val h = (((b3 and 0x0F) shl 10) or (b2 shl 2) or ((b1 and 0xC0) shr 6)) + 1
        return ImageSize(w, h)
    }

    // Extended: canvas (largura-1) e (altura-1) em 24-bit little-endian.
    private fun webpExtended(b: ByteArray): ImageSize? {
        if (b.size < 30) return null
        val w = leU24(b, 24) + 1
        val h = leU24(b, 27) + 1
        return ImageSize(w, h)
    }

    // ---- GIF ----

    private fun isGif(b: ByteArray): Boolean =
        b.size >= 10 && tag(b, 0, "GIF8") && (u8(b, 4) == '7'.code || u8(b, 4) == '9'.code) &&
            u8(b, 5) == 'a'.code

    private fun gif(b: ByteArray): ImageSize? {
        val w = leU16(b, 6)
        val h = leU16(b, 8)
        return if (w > 0 && h > 0) ImageSize(w, h) else null
    }
}
