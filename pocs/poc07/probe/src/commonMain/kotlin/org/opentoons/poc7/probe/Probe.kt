package org.opentoons.poc7.probe

/** Nome/versão da plataforma — `expect/actual`, prova que a travessia commonMain→Native fecha. */
expect fun platformName(): String

/**
 * Portão 2.1 — computação REAL, determinística e verificável que roda dentro do binário
 * Kotlin/Native no device. FNV-1a de 64 bits: se o iPhone devolver o MESMO valor que o host
 * JVM, é prova de que o código Kotlin executou de fato (não um constante linkada) e que a
 * aritmética de 64 bits do runtime Native está correta no ARM real.
 */
object Probe {
    fun fnv1a64(data: ByteArray): ULong {
        var h = 0xcbf29ce484222325UL
        for (b in data) {
            h = h xor b.toUByte().toULong()
            h *= 0x100000001b3UL
        }
        return h
    }

    /** Emite uma linha canônica em stdout (capturada pelo console do devicectl) e a retorna. */
    fun hello(): String {
        val vector = "opentoons-poc07".encodeToByteArray()
        val hex = fnv1a64(vector).toString(16)
        val line = "POC07-PROBE platform=${platformName()} fnv1a64(opentoons-poc07)=0x$hex"
        println(line)
        return line
    }
}
