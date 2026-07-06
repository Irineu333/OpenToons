package org.opentoons.poc7.trama

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import org.opentoons.poc7.api.Block
import org.opentoons.poc7.api.BootstrapAddr
import org.opentoons.poc7.api.Capability
import org.opentoons.poc7.api.ChapterVerifier
import org.opentoons.poc7.api.ContentId
import org.opentoons.poc7.api.ManifestCodec
import org.opentoons.poc7.api.ObraId
import org.opentoons.poc7.api.P2pBackend
import org.opentoons.poc7.api.P2pException
import org.opentoons.poc7.api.Provider
import poc07lp.poc07_lp_dial
import poc07lp.poc07_lp_free_bytes
import poc07lp.poc07_lp_free_node
import poc07lp.poc07_lp_free_str
import poc07lp.poc07_lp_get_blocks
import poc07lp.poc07_lp_get_manifest
import poc07lp.poc07_lp_new
import poc07lp.poc07_lp_peer_id_from_ed25519
import poc07lp.poc07_lp_resolve
import kotlin.time.TimeSource

/**
 * poc-07 célula 2 (E2E real) — o SEGUNDO backend satisfazendo a MESMA `P2pBackend`, agora sobre
 * **libp2p** (rust-libp2p 0.54 via cinterop C-ABI ao `.a` do device). Kotlin/Native (iPhone)
 * chama o Rust: dial → resolve (Kademlia) → get_manifest/get_blocks (request-response). A
 * VERIFICAÇÃO continua fora do seam (o mesmo `ChapterVerifier`). Coexiste com a Trama no binário.
 */
@OptIn(ExperimentalForeignApi::class)
class Libp2pBackend : P2pBackend {

    private var node: COpaquePointer? = null
    override val capabilities: Set<Capability> = emptySet()

    override fun dial(bootstrap: BootstrapAddr) {
        // publicKeyHex carrega o PeerId libp2p do bootstrap (formato nativo do backend — o seam
        // trata endereços como strings opacas). Node::new já disca o bootstrap.
        val multi = "/ip4/${bootstrap.host}/tcp/${bootstrap.port}/p2p/${bootstrap.publicKeyHex}"
        node = poc07_lp_new(multi)
            ?: throw P2pException("libp2p: new/dial do bootstrap falhou ($multi)")
    }

    override fun resolve(obra: ObraId): List<Provider> {
        val n = node ?: throw P2pException("dial(bootstrap) antes de resolve()")
        val p = poc07_lp_resolve(n.reinterpret(), obra.value) ?: throw P2pException("libp2p: resolve erro")
        val s = p.toKString()
        poc07_lp_free_str(p)
        if (s.isEmpty()) return emptyList()
        val peer = s.substringAfterLast("/p2p/")
        return listOf(Provider(peer, listOf(s)))
    }

    override fun getManifest(provider: Provider, obra: ObraId): ByteArray = memScoped {
        val n = node ?: throw P2pException("dial antes de getManifest")
        val len = alloc<ULongVar>()
        val ptr = poc07_lp_get_manifest(n.reinterpret(), provider.addresses[0], obra.value, len.ptr)
            ?: throw P2pException("libp2p: get_manifest falhou")
        val bytes = ptr.reinterpret<ByteVar>().readBytes(len.value.toInt())
        poc07_lp_free_bytes(ptr, len.value)
        bytes
    }

    override fun getBlocks(provider: Provider, ids: List<ContentId>): List<Block> = memScoped {
        val n = node ?: throw P2pException("dial antes de getBlocks")
        val cids = ids.joinToString("\n") { it.hex }
        val len = alloc<ULongVar>()
        val ptr = poc07_lp_get_blocks(n.reinterpret(), provider.addresses[0], cids, len.ptr)
            ?: throw P2pException("libp2p: get_blocks falhou")
        val raw = ptr.reinterpret<ByteVar>().readBytes(len.value.toInt())
        poc07_lp_free_bytes(ptr, len.value)
        parseLengthPrefixed(raw).mapIndexed { i, b -> Block(ids[i], b) }
    }

    override fun push(target: Provider, obra: ObraId, manifestBlock: ByteArray, blocks: List<Block>) {
        throw P2pException("push não é papel do leitor libp2p desta POC")
    }

    override fun close() {
        node?.let { poc07_lp_free_node(it.reinterpret()); node = null }
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

/** Leitor E2E a frio sobre libp2p — mesmo fluxo/instrumento do [ReaderProbe] da Trama. */
@OptIn(ExperimentalForeignApi::class)
object Libp2pReaderProbe {
    fun readOnce(host: String, port: Int, bootstrapIdHex: String): ReadSample {
        val backend = Libp2pBackend()
        val clock = TimeSource.Monotonic
        return try {
            val t0 = clock.markNow()
            backend.dial(BootstrapAddr(host, port, bootstrapIdHex))
            val connectMs = t0.elapsedNow().inWholeMilliseconds

            var providers = backend.resolve(CampaignVectors.OBRA)
            var waited = 0L
            while (providers.isEmpty() && waited < 15_000) { blockingSleep(500); waited += 500; providers = backend.resolve(CampaignVectors.OBRA) }
            if (providers.isEmpty()) return ReadSample(false, "resolve libp2p sem provider", connectMs, -1, -1, -1, -1, 0)
            val provider = providers.first()

            val tContent = clock.markNow()
            val manifestBlock = backend.getManifest(provider, CampaignVectors.OBRA)
            val ttfbMs = tContent.elapsedNow().inWholeMilliseconds

            val decoded = ManifestCodec.decode(manifestBlock)
            val tDl = clock.markNow()
            val blocks = backend.getBlocks(provider, decoded.manifest.blockCids.map { ContentId(it) })
            val downloadMs = tDl.elapsedNow().inWholeMilliseconds

            val tV = clock.markNow()
            val result = ChapterVerifier(CampaignVectors.CONTENT_PUB_KEY_HEX).verify(manifestBlock, blocks.map { it.bytes })
            val verifyMs = tV.elapsedNow().inWholeMilliseconds
            val totalMs = t0.elapsedNow().inWholeMilliseconds

            when (result) {
                is ChapterVerifier.Result.Verified -> ReadSample(true, "verified/${result.manifest.chapterId}", connectMs, ttfbMs, downloadMs, verifyMs, totalMs, result.chapter.size)
                else -> ReadSample(false, "verify=$result", connectMs, ttfbMs, downloadMs, verifyMs, totalMs, 0)
            }
        } catch (e: Throwable) {
            ReadSample(false, "erro: ${e::class.simpleName}: ${e.message}", -1, -1, -1, -1, -1, 0)
        } finally {
            runCatching { backend.close() }
        }
    }
}
