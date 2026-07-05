package org.opentoons.poc6.api

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
 *  - [acceptPushes] (poc-05) declara a política MÍNIMA de aceitação de push: a chave do
 *    publicador cujo conteúdo este nó (replicador) topa gravar quando EMPURRADO. Sem chamar
 *    isto, o nó rejeita todo push (o default é não replicar nada de terceiros). COMO o push
 *    chega (RPC Noise na Trama × request-response no libp2p) é interno ao backend; a decisão
 *    "aceita conhecido, rejeita chave errada" é a mesma nos dois (ver [PushPolicy]).
 *  - [stop] sai da malha e libera portas. `close()` == `stop()`.
 */
interface FullNode : AutoCloseable {

    /** Identidade neutra do nó: chave pública Ed25519 em hex. */
    val idHex: String

    /** Porta TCP efetivamente escutada (após [start]; ListenSpec.port=0 → efêmera). */
    val boundPort: Int

    /**
     * poc-05 — este nó como [Provider] DISCÁVEL, no formato NATIVO do backend (Trama:
     * `host:port`; libp2p: multiaddr com `/p2p/`). O publicador que conhece o replicador
     * (C1) empurra para cá sem interpretar o endereço (opaco por contrato — [Provider]).
     * Requer [start] (precisa da porta/identidade efetiva). Num rig anônimo o endereço
     * onion externo substitui este (infra fora do nó).
     */
    fun selfProvider(): Provider

    /**
     * poc-05 (C2, dual-homed) — declara um endereço ADICIONAL pelo qual este nó é alcançável,
     * ANUNCIADO junto com o endereço próprio (ex.: o `.onion` para o publicador anônimo, além
     * do IP público para os leitores clearnet). String OPACA no formato nativo do backend — o
     * seam não interpreta "IP × onion" (a recomendação do Marco 4). O consumidor escolhe o
     * endereço alcançável por ele (o anônimo disca o onion; o clearnet, o IP). Chamar ANTES de
     * [announce]. Sem esta chamada, o nó anuncia só o endereço próprio (C1).
     */
    fun advertise(address: String)

    fun start(listen: ListenSpec, bootstrap: List<BootstrapAddr>)

    fun serve(store: Blockstore)

    fun publish(obra: ObraId, manifestBlock: ByteArray, blocks: List<Block>)

    fun announce(obra: ObraId)

    /**
     * poc-05 (D1) — habilita a recepção de push: este nó passa a aceitar `push` de obras
     * cujo manifesto seja assinado por [publisherKeyHex] (a "editora"), gravando no
     * [Blockstore] plugado por [serve]. Push autenticado por Noise + assinatura válida da
     * chave esperada é gravado; qualquer outro (chave errada, manifesto malformado) é
     * REJEITADO antes de gravar. Idempotente; pode ser chamado para múltiplos publicadores.
     * Política fina (allowlist por obra) é non-goal da POC (Marco 4).
     */
    fun acceptPushes(publisherKeyHex: String)

    fun stop()

    override fun close() = stop()
}

/**
 * poc-05 (D1) — política NEUTRA de aceitação de push, reusada IDÊNTICA pelos dois backends
 * (como [ChapterVerifier] é reusado pelos dois leitores). Um replicador só grava conteúdo
 * empurrado se o manifesto está assinado E o signatário é um publicador conhecido. Vive no
 * `:api` porque é a mesma decisão para Trama e libp2p — o "verify na recepção" que a
 * assimetria do push (o publicador não é discável, não dá pra puxar-e-verificar depois)
 * exige. Não é o verify do LEITOR (esse é do [ChapterVerifier], fora do seam): é a política
 * do REPLICADOR sobre o que aceita hospedar.
 */
object PushPolicy {
    /**
     * @return true se [manifestBlock] está bem-formado, com assinatura Ed25519 válida, e
     * assinado por exatamente [expectedPublisherKeyHex]. Falso silencioso em qualquer erro
     * (malformado, assinatura inválida, chave divergente) — nunca lança.
     */
    fun accepts(manifestBlock: ByteArray, expectedPublisherKeyHex: String): Boolean {
        val decoded = runCatching { ManifestCodec.decode(manifestBlock) }.getOrNull() ?: return false
        if (!decoded.verifySignature()) return false
        return decoded.pubKeyBytes.contentEquals(runCatching { expectedPublisherKeyHex.decodeHex() }.getOrNull())
    }
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
