package org.opentoons.poc7.api

/**
 * Helpers de bytes big-endian e hex — substituem `java.nio.ByteBuffer`/`ByteBuffer.putLong`
 * (inexistentes em Kotlin/Native). Mesma semântica de wire das POCs anteriores (BE), então o
 * formato do manifesto/frame não muda: um nó JVM e um nó Native falam o mesmo blob.
 */

internal fun Int.toBeBytes(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte(),
)

internal fun Long.toBeBytes(): ByteArray = ByteArray(8) { i -> (this ushr (56 - i * 8)).toByte() }

internal fun ByteArray.readBeInt(off: Int): Int =
    ((this[off].toInt() and 0xff) shl 24) or
        ((this[off + 1].toInt() and 0xff) shl 16) or
        ((this[off + 2].toInt() and 0xff) shl 8) or
        (this[off + 3].toInt() and 0xff)

internal fun ByteArray.readBeLong(off: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (this[off + i].toLong() and 0xff)
    return v
}

/** Escritor append-only (substitui `ByteBuffer.allocate/put`). */
internal class BytesWriter {
    private var buf = ByteArray(64)
    private var len = 0
    private fun ensure(extra: Int) {
        if (len + extra > buf.size) {
            var n = buf.size * 2
            while (n < len + extra) n *= 2
            buf = buf.copyOf(n)
        }
    }
    fun putInt(v: Int): BytesWriter { ensure(4); v.toBeBytes().copyInto(buf, len); len += 4; return this }
    fun putLong(v: Long): BytesWriter { ensure(8); v.toBeBytes().copyInto(buf, len); len += 8; return this }
    fun put(bytes: ByteArray): BytesWriter { ensure(bytes.size); bytes.copyInto(buf, len); len += bytes.size; return this }
    fun toByteArray(): ByteArray = buf.copyOf(len)
}

fun String.decodeHex(): ByteArray =
    ByteArray(length / 2) { ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte() }

fun ByteArray.encodeHex(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) { val v = b.toInt() and 0xff; sb.append(hex[v ushr 4]); sb.append(hex[v and 0x0f]) }
    return sb.toString()
}
