package org.opentoons.poc6.api

/**
 * PoC poc-05 — o seam de CLIENT (design D1: o do poc-04 ESTENDIDO), a superfície que o app
 * mobile e o publicador anônimo consomem. Um backend = uma implementação; o app escolhe por
 * build variant (D1 do poc-04), nunca por branch. O publicador anônimo é apenas um client
 * construído com [AnonymityConfig] habilitada (D2) que, além de ler, também faz [push].
 *
 * Contrato herdado (poc-04):
 *  - [dial] conecta ao nó de bootstrap — o único endereço que o app conhece a priori.
 *  - [resolve] descobre providers de uma obra a partir da malha/DHT alcançável pelo
 *    bootstrap. COMO (gossip PEX+RESOLVE × walk de Kademlia) é interno ao backend.
 *  - [getManifest]/[getBlocks] baixam o manifesto assinado e os blocos de um provider.
 *    A VERIFICAÇÃO (Ed25519 + hash) NÃO é papel do backend: vive em [ChapterVerifier],
 *    fora do seam (D7 do poc-03) — o backend só transporta bytes.
 *
 * Contrato novo (poc-05):
 *  - [push] entrega uma obra (manifesto assinado + blocos) a um nó pleno alcançável, que a
 *    GRAVA no [Blockstore] dele. É o espelho de [getBlocks] (que lê): o publicador
 *    não-discável — atrás do NAT/Tor, sem porta de escuta — não pode ser PUXADO, então
 *    EMPURRA (invertendo o sentido da replicação, que no poc-02/04 era pull). Requisito
 *    estrutural (D1): o frame de push NÃO carrega endereço de origem do publicador —
 *    o [target] é o destino, o conteúdo é o manifesto+blocos, nada mais. A aceitação
 *    (autenticação Noise + assinatura do manifesto validada ANTES de gravar) é do
 *    receptor — ver [FullNode.acceptPushes].
 *
 *  - Chamadas são bloqueantes; erros de rede/descoberta/rejeição viram [P2pException].
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

    /**
     * Empurra uma obra para [target] (um nó pleno/replicador) gravar no [Blockstore] dele.
     * Espelho de escrita do [getBlocks]. O [target] carrega a identidade (pubkey) esperada
     * do receptor — para o publicador autenticar QUEM recebe pelo handshake (defesa contra
     * exit malicioso quando o dial é tunelado). Lança [P2pException] se o receptor rejeitar
     * (não-autenticado, manifesto de chave errada) ANTES de gravar, ou em falha de rede.
     * O frame de push NÃO contém endereço de origem do publicador (D1). [obra] é a unidade
     * de conteúdo sob a qual o receptor grava — identidade pública por design, jamais origem.
     */
    fun push(target: Provider, obra: ObraId, manifestBlock: ByteArray, blocks: List<Block>)

    override fun close()
}

/** Falha de rede/descoberta/transporte reportada pelo backend (mensagem livre). */
class P2pException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
