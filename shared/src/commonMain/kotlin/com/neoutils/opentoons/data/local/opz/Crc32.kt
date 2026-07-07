package com.neoutils.opentoons.data.local.opz

/**
 * CRC-32 (IEEE 802.3, polinômio reflexo `0xEDB88320`) em Kotlin puro (design D6, task 2.2).
 *
 * O escritor OPZ grava entradas **STORED** e o formato ZIP exige o CRC-32 de cada entrada
 * tanto no local header quanto no diretório central. Implementação própria (sem
 * `java.util.zip.CRC32`) mantém o escritor 100% `commonMain`, sem dependência nativa de
 * escrita — só o *decode* de RAR precisa de nativo.
 */
internal object Crc32 {

    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
        c
    }

    /** CRC-32 dos [bytes] (opcionalmente de [offset] por [length]). Retorna valor sem sinal em Long. */
    fun of(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Long {
        var crc = 0xFFFFFFFF.toInt()
        var i = offset
        val end = offset + length
        while (i < end) {
            crc = table[(crc xor bytes[i].toInt()) and 0xFF] xor (crc ushr 8)
            i++
        }
        return (crc.inv().toLong()) and 0xFFFFFFFFL
    }
}
