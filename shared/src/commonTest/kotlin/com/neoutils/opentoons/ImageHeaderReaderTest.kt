package com.neoutils.opentoons

import com.neoutils.opentoons.util.ImageHeaderReader
import com.neoutils.opentoons.util.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Testes de vetor do [ImageHeaderReader] por formato (task 1.3). Cobrem PNG, GIF, WebP
 * (lossy/lossless/extended) e JPEG — inclusive um JPEG com `orientation=6` no EXIF, para
 * fixar a política (task 1.2): as dimensões são as do **bitstream** (largura > altura),
 * **não** as "visuais" pós-rotação. É o vetor que o iOS decodificava trocado.
 */
class ImageHeaderReaderTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun png_leDimensoesDoIHDR() {
        // sig(8) + len(4) + "IHDR" + width=800 + height=1280 + bitdepth
        val png = bytes(
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x03, 0x20, 0x00, 0x00, 0x05, 0x00,
            0x08,
        )
        assertEquals(ImageSize(800, 1280), ImageHeaderReader.readSize(png))
    }

    @Test
    fun gif_leLogicalScreenDescriptor() {
        // "GIF89a" + width=320 (LE) + height=240 (LE)
        val gif = bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x40, 0x01, 0xF0, 0x00, 0x00)
        assertEquals(ImageSize(320, 240), ImageHeaderReader.readSize(gif))
    }

    @Test
    fun webpLossy_leSyncCode() {
        val webp = bytes(
            0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, // RIFF + size
            0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20, // WEBP + "VP8 "
            0x00, 0x00, 0x00, 0x00, // chunk size
            0x00, 0x00, 0x00, // frame tag
            0x9D, 0x01, 0x2A, // start code
            0x80, 0x02, 0xE0, 0x01, // width=640, height=480 (14-bit LE)
        )
        assertEquals(ImageSize(640, 480), ImageHeaderReader.readSize(webp))
    }

    @Test
    fun webpLossless_desempacotaLargura14Bit() {
        val webp = bytes(
            0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x4C, // WEBP + "VP8L"
            0x00, 0x00, 0x00, 0x00,
            0x2F, 0x00, 0x01, 0x20, 0x00, // sig + packing (w=257, h=129)
        )
        assertEquals(ImageSize(257, 129), ImageHeaderReader.readSize(webp))
    }

    @Test
    fun webpExtended_leCanvas24Bit() {
        val webp = bytes(
            0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x58, // WEBP + "VP8X"
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, // flags + reserved
            0xE7, 0x03, 0x00, // width-1 = 999 → 1000
            0xCF, 0x07, 0x00, // height-1 = 1999 → 2000
        )
        assertEquals(ImageSize(1000, 2000), ImageHeaderReader.readSize(webp))
    }

    @Test
    fun jpeg_orientation6_devolveBitstreamNaoRotacionado() {
        // FF D8 + APP1(EXIF, orientation=6 é irrelevante p/ o SOF) + SOF0 com 1200x800.
        val jpeg = bytes(
            0xFF, 0xD8,
            0xFF, 0xE1, 0x00, 0x08, 0x45, 0x78, 0x69, 0x66, 0x00, 0x00, // APP1 "Exif\0\0"
            0xFF, 0xC0, 0x00, 0x11, 0x08, 0x03, 0x20, 0x04, 0xB0, 0x03, // SOF0: h=800, w=1200
        )
        // Bitstream: largura 1200 > altura 800. Se aplicássemos orientation=6, viria 800x1200.
        assertEquals(ImageSize(1200, 800), ImageHeaderReader.readSize(jpeg))
    }

    @Test
    fun formatoDesconhecido_retornaNull() {
        assertNull(ImageHeaderReader.readSize(bytes(0x00, 0x01, 0x02, 0x03)))
        assertNull(ImageHeaderReader.readSize(ByteArray(0)))
    }
}
