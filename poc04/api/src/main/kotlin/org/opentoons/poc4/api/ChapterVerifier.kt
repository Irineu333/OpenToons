package org.opentoons.poc4.api

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters

/**
 * PoC poc-04 — verificação do capítulo FORA do seam (D7 do poc-03): assinatura Ed25519 do
 * manifesto + trava de chave do publicador + hash sha-256 de cada bloco. O backend só
 * transporta bytes; este código é o MESMO para qualquer backend — é o que mantém as
 * células da matriz E2E comparáveis entre si e com as POCs anteriores.
 */
class ChapterVerifier(private val publisherPubKey: Ed25519PublicKeyParameters) {

    constructor(publisherKeyHex: String) : this(NodeKeys.publicKeyFromHex(publisherKeyHex))

    sealed interface Result {
        /** Capítulo íntegro e reconstruído (blocos de conteúdo concatenados na ordem). */
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
        if (!decoded.pubKeyBytes.contentEquals(publisherPubKey.encoded)) return Result.BadSignature

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
