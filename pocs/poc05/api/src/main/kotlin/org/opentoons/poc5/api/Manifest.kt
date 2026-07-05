package org.opentoons.poc5.api

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * PoC poc-04 — manifesto assinado Ed25519, reusado das POCs anteriores SEM alteração de
 * formato (design D8): serialização canônica length-prefixed + codec de transporte
 * `[pkLen][pk][sigLen][sig][canonical]`. Vive no `:api` FORA do seam — é o mesmo código
 * (e o mesmo blob) para qualquer backend; a rede só transporta bytes.
 */
data class Manifest(
    val chapterId: String,
    val seq: Long,
    val blockCids: List<String>,
) {
    /** Serialização canônica length-prefixed: qualquer byte alterado invalida a assinatura. */
    fun canonicalBytes(): ByteArray {
        val fields = buildList {
            add(chapterId.toByteArray(StandardCharsets.UTF_8))
            add(ByteBuffer.allocate(8).putLong(seq).array())
            blockCids.forEach { add(it.toByteArray(StandardCharsets.UTF_8)) }
        }
        val size = fields.sumOf { 4 + it.size } + 4
        val buf = ByteBuffer.allocate(size)
        buf.putInt(fields.size)
        fields.forEach { buf.putInt(it.size); buf.put(it) }
        return buf.array()
    }
}

/** Codec de transporte do manifesto: `[pkLen][pk][sigLen][sig][canonical]` (poc-01 E4). */
object ManifestCodec {

    data class Decoded(val manifest: Manifest, val signature: ByteArray, val pubKeyBytes: ByteArray) {
        fun verifySignature(): Boolean = NodeKeys.verify(
            Ed25519PublicKeyParameters(pubKeyBytes, 0), manifest.canonicalBytes(), signature,
        )
    }

    fun encode(keys: NodeKeys, manifest: Manifest): ByteArray {
        val canonical = manifest.canonicalBytes()
        val signature = keys.sign(canonical)
        val pk = keys.id
        val buf = ByteBuffer.allocate(4 + pk.size + 4 + signature.size + canonical.size)
        buf.putInt(pk.size); buf.put(pk)
        buf.putInt(signature.size); buf.put(signature)
        buf.put(canonical)
        return buf.array()
    }

    fun decode(bytes: ByteArray): Decoded {
        val buf = ByteBuffer.wrap(bytes)
        val pk = ByteArray(buf.int).also { buf.get(it) }
        val sig = ByteArray(buf.int).also { buf.get(it) }
        val canonical = ByteArray(buf.remaining()).also { buf.get(it) }
        return Decoded(parseCanonical(canonical), sig, pk)
    }

    fun parseCanonical(canonical: ByteArray): Manifest {
        val buf = ByteBuffer.wrap(canonical)
        val count = buf.int
        val fields = (0 until count).map { ByteArray(buf.int).also { f -> buf.get(f) } }
        require(fields.size >= 2) { "manifesto malformado" }
        return Manifest(
            chapterId = String(fields[0], StandardCharsets.UTF_8),
            seq = ByteBuffer.wrap(fields[1]).long,
            blockCids = fields.drop(2).map { String(it, StandardCharsets.UTF_8) },
        )
    }
}

/** Prepara uma obra para publicação: blocos endereçados por sha-256 + manifesto assinado. */
object ChapterPublisher {

    data class Prepared(val manifest: Manifest, val manifestBlock: ByteArray, val blocks: List<Block>)

    fun prepare(contentKeys: NodeKeys, chapterId: String, seq: Long, pages: List<ByteArray>): Prepared {
        val blocks = pages.map { Block.of(it) }
        val manifest = Manifest(chapterId, seq, blocks.map { it.id.hex })
        return Prepared(manifest, ManifestCodec.encode(contentKeys, manifest), blocks)
    }
}
