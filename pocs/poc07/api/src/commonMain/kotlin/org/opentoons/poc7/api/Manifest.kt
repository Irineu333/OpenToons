package org.opentoons.poc7.api

/**
 * poc-07 — manifesto assinado Ed25519 portado para `commonMain` SEM alterar o formato
 * (design D8): serialização canônica length-prefixed BE + codec de transporte
 * `[pkLen][pk][sigLen][sig][canonical]`. `ByteBuffer` virou [BytesWriter]/leituras BE;
 * o blob no fio é idêntico ao das POCs JVM.
 */
data class Manifest(
    val chapterId: String,
    val seq: Long,
    val blockCids: List<String>,
) {
    fun canonicalBytes(): ByteArray {
        val fields = buildList {
            add(chapterId.encodeToByteArray())
            add(seq.toBeBytes())
            blockCids.forEach { add(it.encodeToByteArray()) }
        }
        val w = BytesWriter()
        w.putInt(fields.size)
        fields.forEach { w.putInt(it.size).put(it) }
        return w.toByteArray()
    }
}

object ManifestCodec {

    data class Decoded(val manifest: Manifest, val signature: ByteArray, val pubKeyBytes: ByteArray) {
        fun verifySignature(): Boolean =
            NodeKeys.verify(pubKeyBytes, manifest.canonicalBytes(), signature)
    }

    fun encode(keys: NodeKeys, manifest: Manifest): ByteArray {
        val canonical = manifest.canonicalBytes()
        val signature = keys.sign(canonical)
        val pk = keys.id
        return BytesWriter()
            .putInt(pk.size).put(pk)
            .putInt(signature.size).put(signature)
            .put(canonical)
            .toByteArray()
    }

    fun decode(bytes: ByteArray): Decoded {
        var off = 0
        val pkLen = bytes.readBeInt(off); off += 4
        val pk = bytes.copyOfRange(off, off + pkLen); off += pkLen
        val sigLen = bytes.readBeInt(off); off += 4
        val sig = bytes.copyOfRange(off, off + sigLen); off += sigLen
        val canonical = bytes.copyOfRange(off, bytes.size)
        return Decoded(parseCanonical(canonical), sig, pk)
    }

    fun parseCanonical(canonical: ByteArray): Manifest {
        var off = 0
        val count = canonical.readBeInt(off); off += 4
        val fields = ArrayList<ByteArray>(count)
        repeat(count) {
            val len = canonical.readBeInt(off); off += 4
            fields.add(canonical.copyOfRange(off, off + len)); off += len
        }
        require(fields.size >= 2) { "manifesto malformado" }
        return Manifest(
            chapterId = fields[0].decodeToString(),
            seq = fields[1].readBeLong(0),
            blockCids = fields.drop(2).map { it.decodeToString() },
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
