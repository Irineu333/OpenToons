@file:OptIn(ExperimentalForeignApi::class)

package org.opentoons.poc7.trama

import cnames.structs.Poc07Store
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import org.opentoons.poc7.api.ChapterPublisher
import org.opentoons.poc7.api.ChapterVerifier
import org.opentoons.poc7.api.ManifestCodec
import poc07lp.poc07_lp_free_bytes
import poc07lp.poc07_lp_free_node
import poc07lp.poc07_lp_free_server
import poc07lp.poc07_lp_free_store
import poc07lp.poc07_lp_free_str
import poc07lp.poc07_lp_get_blocks
import poc07lp.poc07_lp_get_manifest
import poc07lp.poc07_lp_new
import poc07lp.poc07_lp_resolve
import poc07lp.poc07_lp_server_bootstrap
import poc07lp.poc07_lp_server_listen_port
import poc07lp.poc07_lp_server_new
import poc07lp.poc07_lp_server_peer_id
import poc07lp.poc07_lp_server_start_providing
import poc07lp.poc07_lp_store_new
import poc07lp.poc07_lp_store_put_block
import poc07lp.poc07_lp_store_set_manifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * poc-07 task 5.3 — TCK de conformidade da **libp2p** montado COMPLETO no alvo iOS (simulador,
 * D-rules), fechando a ressalva honesta da célula 2 (a topologia TCK não havia sido montada;
 * só rodava o E2E remoto + rejeição de adulteração no device).
 *
 * Sobe TRÊS full nodes libp2p REAIS in-process, em loopback, via cinterop C-ABI ao rust-libp2p
 * 0.54 (o MESMO `.a` do backend do device, cross-compilado p/ o simulador):
 *   - bootstrap  (ServerNode, Kademlia server, store vazio)
 *   - publicador (ServerNode, serve o capítulo de 768 KiB do store em memória; disca o bootstrap
 *                 e `start_providing`)
 *   - cliente    (Node, disca o bootstrap, resolve por Kademlia, baixa por request-response)
 *
 * Roda os cenários de correção do poc-04 SOBRE libp2p de verdade (não sobre a Trama):
 *   1. resolve descobre o provider → download → **verify Ed25519+sha256 íntegro** (Verified)
 *   2. bloco adulterado no publicador → rejeitado no cliente (BlockHashMismatch)
 *   3. manifesto de chave errada → rejeitado no cliente (BadSignature)
 *
 * O verify é o MESMO `ChapterVerifier` neutro (fora do seam, D7) que já está TCK-verde com a
 * Trama — aqui exercido sobre bytes trazidos pela libp2p, no alvo Native. Sem número de campanha:
 * só correção por implementação (portão da célula 2). (push/expiry são cenários específicos da
 * Trama; o leitor libp2p desta POC é read-only — `push` não é seu papel.)
 */
class Libp2pTckTest {

    // 32 bytes hex (identidade Ed25519 determinística) por papel — peer ids estáveis e distintos.
    private val bootSeed = "11".repeat(32)
    private val pubSeed = "22".repeat(32)
    private val obra = TckVectors.OBRA.value

    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        closers.asReversed().forEach { runCatching { it() } }
        closers.clear()
    }

    private data class Topo(val bootPeer: String, val bootPort: Int)

    /** Store em memória do publicador, alimentado a partir do `Prepared` (manifesto + blocos). */
    private fun newStore(
        prepared: ChapterPublisher.Prepared,
        tamperFirstBlock: Boolean = false,
    ): CPointer<Poc07Store> {
        val store = poc07_lp_store_new() ?: fail("store_new falhou")
        closers += { poc07_lp_free_store(store) }
        val mb = prepared.manifestBlock
        mb.usePinned { p ->
            poc07_lp_store_set_manifest(store, obra, p.addressOf(0).reinterpret(), mb.size.convert())
        }
        prepared.blocks.forEachIndexed { i, b ->
            val bytes = if (tamperFirstBlock && i == 0) {
                b.bytes.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
            } else {
                b.bytes
            }
            bytes.usePinned { p ->
                poc07_lp_store_put_block(store, b.id.hex, p.addressOf(0).reinterpret(), bytes.size.convert())
            }
        }
        return store
    }

    /** bootstrap (vazio) + publicador (serve `store`, disca o bootstrap, anuncia a obra). */
    private fun standardTopology(store: CPointer<Poc07Store>): Topo {
        val bootStore = poc07_lp_store_new() ?: fail("store_new (bootstrap) falhou")
        closers += { poc07_lp_free_store(bootStore) }
        println("TCK: subindo bootstrap…")
        val boot = poc07_lp_server_new(bootStore, bootSeed, 0u) ?: fail("bootstrap server_new falhou")
        closers += { poc07_lp_free_server(boot) }
        val bootPeer = readAndFreeStr(poc07_lp_server_peer_id(boot)) ?: fail("bootstrap peer_id nulo")
        val bootPort = poc07_lp_server_listen_port(boot).toInt()
        assertTrue(bootPort > 0, "bootstrap não subiu listener TCP")
        println("TCK: bootstrap up peer=$bootPeer port=$bootPort")

        val pub = poc07_lp_server_new(store, pubSeed, 0u) ?: fail("publicador server_new falhou")
        closers += { poc07_lp_free_server(pub) }
        val bootMulti = "/ip4/127.0.0.1/tcp/$bootPort/p2p/$bootPeer"
        println("TCK: publicador up, discando bootstrap…")
        assertEquals(0, poc07_lp_server_bootstrap(pub, bootMulti), "publicador não discou o bootstrap")
        assertEquals(0, poc07_lp_server_start_providing(pub, obra), "start_providing falhou")
        println("TCK: publicador anunciou a obra")
        return Topo(bootPeer, bootPort)
    }

    /** Cliente libp2p: disca o bootstrap, resolve por Kademlia, baixa manifesto + blocos. */
    private fun clientRead(topo: Topo, resolveTimeoutMs: Long = 20_000): Pair<ByteArray, List<ByteArray>>? {
        val bootMulti = "/ip4/127.0.0.1/tcp/${topo.bootPort}/p2p/${topo.bootPeer}"
        println("TCK: cliente discando bootstrap…")
        val node = poc07_lp_new(bootMulti) ?: return null
        try {
            println("TCK: cliente up, resolvendo obra por Kademlia…")
            var provider = ""
            var waited = 0L
            while (waited < resolveTimeoutMs) {
                provider = readAndFreeStr(poc07_lp_resolve(node, obra)) ?: ""
                if (provider.isNotEmpty()) break
                blockingSleep(300); waited += 300
            }
            println("TCK: resolve → provider='$provider' (após ${waited}ms)")
            if (provider.isEmpty()) return null

            val manifest = readBytesAndFree { len -> poc07_lp_get_manifest(node, provider, obra, len) } ?: return null
            val decoded = ManifestCodec.decode(manifest)
            val cids = decoded.manifest.blockCids.joinToString("\n")
            val raw = readBytesAndFree { len -> poc07_lp_get_blocks(node, provider, cids, len) } ?: return null
            return manifest to parseLengthPrefixed(raw)
        } finally {
            poc07_lp_free_node(node)
        }
    }

    @Test
    fun resolveDescobreProviderEDownloadVerificaIntegro() {
        val topo = standardTopology(newStore(TckVectors.prepared()))
        val (manifest, blocks) = clientRead(topo) ?: fail("resolve/download libp2p falhou")
        val result = ChapterVerifier(TckVectors.contentKeys.idHex).verify(manifest, blocks)
        val verified = result as? ChapterVerifier.Result.Verified ?: fail("verificação falhou: $result")
        assertEquals(TckVectors.CHAPTER_ID, verified.manifest.chapterId)
        assertEquals(TckVectors.SEQ, verified.manifest.seq)
        assertTrue(verified.chapter.contentEquals(TckVectors.chapterBytes), "capítulo divergente")
    }

    @Test
    fun blocoAdulteradoERejeitado() {
        val topo = standardTopology(newStore(TckVectors.prepared(), tamperFirstBlock = true))
        val (manifest, blocks) = clientRead(topo) ?: fail("resolve/download libp2p falhou")
        val result = ChapterVerifier(TckVectors.contentKeys.idHex).verify(manifest, blocks)
        assertTrue(result is ChapterVerifier.Result.BlockHashMismatch, "esperado BlockHashMismatch, veio $result")
    }

    @Test
    fun manifestoDeChaveErradaERejeitado() {
        val topo = standardTopology(newStore(TckVectors.preparedWrongKey()))
        val (manifest, blocks) = clientRead(topo) ?: fail("resolve/download libp2p falhou")
        val result = ChapterVerifier(TckVectors.contentKeys.idHex).verify(manifest, blocks)
        assertTrue(result is ChapterVerifier.Result.BadSignature, "esperado BadSignature, veio $result")
    }

    private inline fun readBytesAndFree(call: (CPointer<ULongVar>) -> CPointer<*>?): ByteArray? = memScoped {
        val len = alloc<ULongVar>()
        val ptr = call(len.ptr) ?: return@memScoped null
        val bytes = ptr.reinterpret<ByteVar>().readBytes(len.value.toInt())
        poc07_lp_free_bytes(ptr.reinterpret(), len.value)
        bytes
    }

    private fun readAndFreeStr(p: CPointer<ByteVar>?): String? {
        if (p == null) return null
        val s = p.toKString()
        poc07_lp_free_str(p)
        return s
    }

    private fun parseLengthPrefixed(raw: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var off = 0
        while (off + 4 <= raw.size) {
            val len = ((raw[off].toInt() and 0xff) shl 24) or ((raw[off + 1].toInt() and 0xff) shl 16) or
                ((raw[off + 2].toInt() and 0xff) shl 8) or (raw[off + 3].toInt() and 0xff)
            off += 4
            out.add(raw.copyOfRange(off, off + len)); off += len
        }
        return out
    }
}
