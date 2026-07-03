package org.opentoons.poc2.rpc

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.opentoons.poc2.core.Manifest
import org.opentoons.poc2.core.ManifestCodec
import org.opentoons.poc2.core.ManifestCrypto
import org.opentoons.poc2.core.NodeIdentity
import org.opentoons.poc2.core.sha256
import org.opentoons.poc2.core.toHex

/**
 * E2 — publicação e download de capítulo sobre o RPC: "me dá o manifesto/bloco H / toma"
 * substitui o bitswap (design D4). CID da PoC = hex(sha256(bloco)) — formato próprio,
 * sem multiformats. Verificação idêntica à do poc-01: assinatura Ed25519 do manifesto
 * (formato validado, D8) + hash de cada bloco contra o manifesto.
 */
object ChapterService {

    fun cidOf(block: ByteArray): String = sha256(block).toHex()

    /** Lado do nó pleno: blockstore + manifesto assinado publicados. */
    class Publisher(private val identity: NodeIdentity) {
        private val blocks = HashMap<String, ByteArray>()
        private val manifests = HashMap<String, ByteArray>() // chapterId → bloco do manifesto

        fun publishChapter(chapterId: String, seq: Long, pages: List<ByteArray>): Manifest {
            val cids = pages.map { page ->
                cidOf(page).also { blocks[it] = page }
            }
            val manifest = Manifest(chapterId, seq, cids)
            val signed = ManifestCrypto.sign(manifest, identity.privateKey)
            manifests[chapterId] = ManifestCodec.encode(signed, identity.publicKey)
            return manifest
        }

        /** Handler para o RpcPeer do servidor. */
        fun handle(type: Byte, body: ByteArray): Pair<Byte, ByteArray> = when (type) {
            RpcTypes.GET_MANIFEST -> {
                val req = RpcCodec.decode(GetManifestRequest.serializer(), body)
                val block = manifests[req.chapterId] ?: throw RpcException("capítulo desconhecido: ${req.chapterId}")
                RpcTypes.GET_MANIFEST to RpcCodec.encode(BlobResponse.serializer(), BlobResponse(block))
            }
            RpcTypes.GET_BLOCK -> {
                val req = RpcCodec.decode(GetBlockRequest.serializer(), body)
                val block = blocks[req.cid] ?: throw RpcException("bloco desconhecido: ${req.cid}")
                RpcTypes.GET_BLOCK to RpcCodec.encode(BlobResponse.serializer(), BlobResponse(block))
            }
            else -> throw RpcException("operação desconhecida: $type")
        }
    }

    class VerificationException(message: String) : SecurityException(message)

    /**
     * Lado do cliente: baixa manifesto + blocos (blocos em PARALELO na mesma conexão —
     * é isto que o request-id compra), verifica e reconstrói o capítulo.
     */
    class Fetcher(private val rpc: RpcPeer, private val publisherKey: Ed25519PublicKeyParameters) {

        fun fetchChapter(chapterId: String): List<ByteArray> {
            val manifestBlock = getBlob(RpcTypes.GET_MANIFEST, RpcCodec.encode(GetManifestRequest.serializer(), GetManifestRequest(chapterId)))
            val decoded = ManifestCodec.decode(manifestBlock)
            if (!decoded.pubKeyBytes.contentEquals(publisherKey.encoded) || !decoded.verify()) {
                throw VerificationException("assinatura do manifesto inválida ou publicador inesperado")
            }
            require(decoded.manifest.chapterId == chapterId) { "manifesto de outro capítulo" }

            // todas as requisições de bloco disparadas SEM aguardar (concorrência na conexão única)
            val futures = decoded.manifest.blockCids.map { cid ->
                cid to rpc.callAsync(RpcTypes.GET_BLOCK, RpcCodec.encode(GetBlockRequest.serializer(), GetBlockRequest(cid)))
            }
            return futures.map { (cid, future) ->
                val (type, body) = future.get(30, java.util.concurrent.TimeUnit.SECONDS)
                if (type == (RpcTypes.ERROR.toInt() and 0x7f).toByte()) {
                    throw RpcException(RpcCodec.decode(RpcErrorResponse.serializer(), body).message)
                }
                val block = RpcCodec.decode(BlobResponse.serializer(), body).bytes
                if (cidOf(block) != cid) {
                    throw VerificationException("bloco adulterado: hash não corresponde ao manifesto ($cid)")
                }
                block
            }
        }

        private fun getBlob(type: Byte, body: ByteArray): ByteArray {
            val (_, respBody) = rpc.call(type, body)
            return RpcCodec.decode(BlobResponse.serializer(), respBody).bytes
        }
    }
}
