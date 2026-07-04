package org.opentoons.poc4.api

/**
 * PoC poc-04 — o seam de CLIENT (design D2): a superfície que o app mobile consome.
 * Um backend = uma implementação; o app escolhe por build variant (D1), nunca por branch.
 *
 * Contrato:
 *  - [dial] conecta ao nó de bootstrap — o único endereço que o app conhece a priori.
 *  - [resolve] descobre providers de uma obra a partir da malha/DHT alcançável pelo
 *    bootstrap. COMO (gossip PEX+RESOLVE × walk de Kademlia) é interno ao backend.
 *  - [getManifest]/[getBlocks] baixam o manifesto assinado e os blocos de um provider.
 *    A VERIFICAÇÃO (Ed25519 + hash) NÃO é papel do backend: vive em [ChapterVerifier],
 *    fora do seam (D7 do poc-03) — o backend só transporta bytes.
 *  - Chamadas são bloqueantes; erros de rede/descoberta viram [P2pException].
 */
interface P2pBackend : AutoCloseable {

    /** Capacidades divergentes consultáveis — ver [Capability] (decisão 2.2/D4). */
    val capabilities: Set<Capability>

    /** Conecta ao bootstrap. Idempotente; pode ser chamado com outros nós para redundância. */
    fun dial(bootstrap: BootstrapAddr)

    /** Descobre providers da obra. Lista vazia = ninguém anuncia (ou anúncios expiraram). */
    fun resolve(obra: ObraId): List<Provider>

    /** Baixa o bloco de manifesto assinado (formato [ManifestCodec]) do provider. */
    fun getManifest(provider: Provider, obra: ObraId): ByteArray

    /** Baixa blocos por [ContentId], na ordem pedida. */
    fun getBlocks(provider: Provider, ids: List<ContentId>): List<Block>

    override fun close()
}

/** Falha de rede/descoberta/transporte reportada pelo backend (mensagem livre). */
class P2pException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
