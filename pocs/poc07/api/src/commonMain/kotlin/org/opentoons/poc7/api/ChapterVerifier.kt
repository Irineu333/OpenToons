package org.opentoons.poc7.api

/**
 * poc-07 — verificação do capítulo FORA do seam (D7 do poc-03), portada para Kotlin/Native:
 * assinatura Ed25519 do manifesto + trava de chave do publicador + sha-256 de cada bloco. O
 * backend só transporta bytes; este código é o MESMO para qualquer backend e roda NO DEVICE
 * (o verify do leitor iOS). Chave do publicador em bytes crus (32 B) para não vazar tipos.
 */
class ChapterVerifier(private val publisherPubKey: ByteArray) {

    constructor(publisherKeyHex: String) : this(publisherKeyHex.decodeHex())

    sealed interface Result {
        data class Verified(val manifest: Manifest, val chapter: ByteArray) : Result
        data object BadSignature : Result
        data class BlockHashMismatch(val cid: String) : Result
        data class Malformed(val reason: String) : Result
    }

    fun verify(manifestBlock: ByteArray, blocks: List<ByteArray>): Result {
        val decoded = try {
            ManifestCodec.decode(manifestBlock)
        } catch (e: Exception) {
            return Result.Malformed("manifesto: ${e.message}")
        }
        if (!decoded.verifySignature()) return Result.BadSignature
        // trava de confiança: a chave do manifesto tem de ser a do publicador conhecido
        if (!decoded.pubKeyBytes.contentEquals(publisherPubKey)) return Result.BadSignature

        val cids = decoded.manifest.blockCids
        if (blocks.size != cids.size) {
            return Result.Malformed("esperados ${cids.size} blocos, vieram ${blocks.size}")
        }

        val chapter = ByteArray(blocks.sumOf { it.size })
        var off = 0
        for (i in blocks.indices) {
            if (sha256Hex(blocks[i]) != cids[i]) return Result.BlockHashMismatch(cids[i])
            blocks[i].copyInto(chapter, off); off += blocks[i].size
        }
        return Result.Verified(decoded.manifest, chapter)
    }
}
