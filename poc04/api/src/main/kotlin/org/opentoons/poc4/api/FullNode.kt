package org.opentoons.poc4.api

import java.util.concurrent.ConcurrentHashMap

/**
 * PoC poc-04 — o seam de FULL NODE (design D2): a superfície do servidor (desktop/VPS).
 * É AQUI que gossip e Kademlia mais divergem (announce/republish/serve) — a questão Q1.
 *
 * Contrato:
 *  - [start] escuta conforme o [ListenSpec] e entra na malha/DHT pelos bootstraps.
 *  - [serve] pluga a fonte de conteúdo: TODA leitura de manifesto/bloco servida ao vivo
 *    passa pelo [Blockstore] — é o que permite o full node dual-stack do E5 (dois
 *    FullNode sobre o MESMO blockstore) e os testes de adulteração neutros do TCK.
 *  - [publish] grava a obra no blockstore plugado e a registra para anúncio.
 *  - [announce] passa a anunciar a obra na malha/DHT. Expiry e republish são INTERNOS
 *    ao backend (epidemia com TTL × provider records K-closest) — o app declara O QUE
 *    anuncia via [AnnounceTuning], nunca COMO o re-anúncio se propaga.
 *  - [stop] sai da malha e libera portas. `close()` == `stop()`.
 */
interface FullNode : AutoCloseable {

    /** Identidade neutra do nó: chave pública Ed25519 em hex. */
    val idHex: String

    /** Porta TCP efetivamente escutada (após [start]; ListenSpec.port=0 → efêmera). */
    val boundPort: Int

    fun start(listen: ListenSpec, bootstrap: List<BootstrapAddr>)

    fun serve(store: Blockstore)

    fun publish(obra: ObraId, manifestBlock: ByteArray, blocks: List<Block>)

    fun announce(obra: ObraId)

    fun stop()

    override fun close() = stop()
}

/**
 * Fonte de conteúdo NEUTRA que um [FullNode] serve. Vive fora dos backends de propósito:
 * o dual-stack do E5 compartilha UMA instância entre os dois FullNode, e o TCK injeta
 * wrappers de adulteração sem tocar em nenhum backend.
 */
interface Blockstore {
    fun manifest(obra: ObraId): ByteArray?
    fun block(id: ContentId): ByteArray?
    fun obras(): Set<ObraId>
    fun putManifest(obra: ObraId, manifestBlock: ByteArray)
    fun putBlock(block: Block)
}

/** Blockstore em memória — suficiente para a PoC (mesmo rigor do poc-02/03). */
class MemoryBlockstore : Blockstore {
    private val manifests = ConcurrentHashMap<String, ByteArray>()
    private val blocks = ConcurrentHashMap<String, ByteArray>()

    override fun manifest(obra: ObraId): ByteArray? = manifests[obra.value]
    override fun block(id: ContentId): ByteArray? = blocks[id.hex]
    override fun obras(): Set<ObraId> = manifests.keys.map { ObraId(it) }.toSet()
    override fun putManifest(obra: ObraId, manifestBlock: ByteArray) {
        manifests[obra.value] = manifestBlock
    }
    override fun putBlock(block: Block) {
        blocks[block.id.hex] = block.bytes
    }
}
