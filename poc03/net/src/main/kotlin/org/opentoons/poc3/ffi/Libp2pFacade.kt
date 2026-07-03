package org.opentoons.poc3.ffi

/**
 * PoC poc-03 — o contrato FFI mínimo que **as duas variantes** do E1 expõem ao app
 * (design D2/D4): E2/E3/E4 rodam sobre qualquer uma sem mudar o código do app.
 *
 * As implementações concretas são geradas pelo binding, não escritas à mão:
 *  - E1a (go):   classe Java-ish do `gomobile bind` (.aar) — adaptada por um `actual`.
 *  - E1b (rust): classe Kotlin idiomática do UniFFI (.so) — casa direto no `expect/actual`.
 *
 * São **3 chamadas que cruzam a fronteira** — [dial], [resolve], [getBlocks]. A quarta
 * chamada do contrato conceitual, `verify`, **não cruza a fronteira** (design D7): a
 * verificação Ed25519 do manifesto + hash de cada bloco é feita em Kotlin por
 * [org.opentoons.poc3.core.ChapterVerifier]. A fronteira entrega bytes; o Kotlin verifica.
 * Manter a verificação fora do FFI reduz a superfície de bug (threading/memória/lifecycle,
 * E2/5.3) e mantém a verificação comparável entre as três POCs.
 */
interface Libp2pFacade : AutoCloseable {

    /** id do nó (base58) — para logs e relatório. */
    val peerId: String

    /** dial (FFI 1) — abre conexão a um multiaddr completo (`.../p2p/<id>`). */
    fun dial(multiaddrWithPeer: String)

    /**
     * resolve (FFI 2) — descoberta fria via Kademlia real (E3): a partir só do bootstrap
     * + [obraId], devolve o multiaddr discável do provider. Nunca recebe o endereço do
     * publicador de fora (E4).
     */
    fun resolve(obraId: String): String

    /**
     * getBlocks (FFI 3) — baixa manifesto + blocos por Request-Response (D3). [cids]:
     * chaves separadas por '\n' (a 1ª é o manifesto). Retorna os blocos concatenados com
     * length-prefix uint32 big-endian, na ordem pedida — mesmo wire nas duas variantes.
     * O corte + verificação ficam em [ChapterVerifier] (Kotlin, fora do FFI).
     */
    fun getBlocks(peerMultiaddr: String, cids: String): ByteArray

    override fun close()

    companion object {
        /** Protocolo Request-Response dos blocos — idêntico no go-facade e no rust-facade. */
        const val BLOCK_PROTOCOL = "/opentoons/blocks/1.0.0"
    }
}
