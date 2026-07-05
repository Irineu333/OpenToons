package org.opentoons.poc3.core

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * PoC poc-03 — reconstrução e verificação do capítulo **em Kotlin, do lado do app**
 * (design D7, tarefa 5.2): a fronteira FFI ([org.opentoons.poc3.ffi.Libp2pFacade.getBlocks])
 * entrega os blocos length-prefixed; este código fatia, verifica o hash de cada bloco
 * contra os CIDs do manifesto, verifica a assinatura Ed25519 do manifesto e só então
 * reconstrói o capítulo. Nada disso cruza a fronteira — é a mesma lógica de verificação
 * do poc-01/02, o que mantém as três POCs comparáveis.
 *
 * O CID de teste do poc-01 é `sha2-256` hex do bloco (sufixo curto). Aqui reusamos essa
 * convenção: um bloco casa seu CID quando o hex do seu sha-256 termina com o CID pedido —
 * suficiente para o critério de integridade/adulteração do E4 (7.3).
 */
class ChapterVerifier(private val publisherPubKey: Ed25519PublicKeyParameters) {

    sealed interface Result {
        /** Capítulo íntegro e reconstruído (blocos de conteúdo concatenados na ordem). */
        data class Verified(val manifest: Manifest, val chapter: ByteArray) : Result
        data object BadSignature : Result
        data class BlockHashMismatch(val cid: String) : Result
        data class Malformed(val reason: String) : Result
    }

    /**
     * @param manifestBlock bytes do bloco de manifesto (formato [ManifestCodec]).
     * @param lengthPrefixedContent os blocos de conteúdo como entregues pelo facade:
     *   `[len u32 BE][bloco]...`, na ordem de `manifest.blockCids` a partir do 2º CID
     *   (o 1º CID identifica o próprio manifesto).
     */
    fun verify(manifestBlock: ByteArray, lengthPrefixedContent: ByteArray): Result {
        val decoded = try {
            ManifestCodec.decode(manifestBlock)
        } catch (e: Exception) {
            return Result.Malformed("manifesto: ${e.message}")
        }
        if (!decoded.verifySignature()) return Result.BadSignature
        // trava de confiança: a chave do manifesto tem de ser a do publicador conhecido
        if (!decoded.pubKeyBytes.contentEquals(publisherPubKey.encoded)) return Result.BadSignature

        val contentCids = decoded.manifest.blockCids.drop(1) // 1º é o manifesto
        val blocks = try {
            sliceLengthPrefixed(lengthPrefixedContent)
        } catch (e: Exception) {
            return Result.Malformed("blocos: ${e.message}")
        }
        if (blocks.size != contentCids.size) {
            return Result.Malformed("esperados ${contentCids.size} blocos, vieram ${blocks.size}")
        }

        val chapter = ByteArray(blocks.sumOf { it.size })
        var off = 0
        for (i in blocks.indices) {
            val block = blocks[i]
            if (!matchesCid(block, contentCids[i])) return Result.BlockHashMismatch(contentCids[i])
            block.copyInto(chapter, off); off += block.size
        }
        return Result.Verified(decoded.manifest, chapter)
    }

    private fun matchesCid(block: ByteArray, cid: String): Boolean {
        val hex = sha256Hex(block)
        // aceita CID = hex completo ou sufixo curto do hex (convenção do poc-01)
        return hex == cid || hex.endsWith(cid)
    }

    companion object {
        fun sliceLengthPrefixed(bytes: ByteArray): List<ByteArray> {
            val buf = ByteBuffer.wrap(bytes)
            val out = ArrayList<ByteArray>()
            while (buf.remaining() >= 4) {
                val len = buf.int
                require(len >= 0 && len <= buf.remaining()) { "length-prefix inválido: $len" }
                out.add(ByteArray(len).also { buf.get(it) })
            }
            require(buf.remaining() == 0) { "bytes residuais no buffer" }
            return out
        }

        fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { "%02x".format(it) }
    }
}
