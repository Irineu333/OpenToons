package org.opentoons.poc7.api

/**
 * poc-07 — o seam de CLIENT portado para `commonMain` SEM mudança de contrato (design D2). É a
 * superfície que o leitor iOS (Kotlin/Native) e o publicador consomem: dial → resolve →
 * getManifest/getBlocks (+ push). A VERIFICAÇÃO não é papel do backend — vive no
 * [ChapterVerifier], fora do seam (D7). Chamadas bloqueantes; erros viram [P2pException].
 */
interface P2pBackend : AutoCloseable {

    val capabilities: Set<Capability>

    fun dial(bootstrap: BootstrapAddr)
    fun resolve(obra: ObraId): List<Provider>
    fun getManifest(provider: Provider, obra: ObraId): ByteArray
    fun getBlocks(provider: Provider, ids: List<ContentId>): List<Block>
    fun push(target: Provider, obra: ObraId, manifestBlock: ByteArray, blocks: List<Block>)

    override fun close()
}

class P2pException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
