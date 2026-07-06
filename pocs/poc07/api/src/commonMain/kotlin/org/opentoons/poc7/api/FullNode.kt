package org.opentoons.poc7.api

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * poc-07 — o seam de FULL NODE portado para `commonMain` SEM mudança de contrato (design D2).
 * `AutoCloseable` é o de `kotlin.*` (comum); a implementação de rede vive no `:trama`. O
 * blockstore em memória troca `ConcurrentHashMap` por um mapa guardado por lock do atomicfu
 * (cross-platform), preservando a segurança sob conexões concorrentes do nó pleno JVM/VPS.
 */
interface FullNode : AutoCloseable {

    val idHex: String
    val boundPort: Int

    fun selfProvider(): Provider
    fun advertise(address: String)
    fun start(listen: ListenSpec, bootstrap: List<BootstrapAddr>)
    fun serve(store: Blockstore)
    fun publish(obra: ObraId, manifestBlock: ByteArray, blocks: List<Block>)
    fun announce(obra: ObraId)
    fun acceptPushes(publisherKeyHex: String)
    fun stop()

    override fun close() = stop()
}

/**
 * poc-05 (D1) — política NEUTRA de aceitação de push, reusada IDÊNTICA pelos dois backends.
 * Um replicador só grava conteúdo empurrado se o manifesto está assinado E o signatário é um
 * publicador conhecido. Falso silencioso em qualquer erro; nunca lança.
 */
object PushPolicy {
    fun accepts(manifestBlock: ByteArray, expectedPublisherKeyHex: String): Boolean {
        val decoded = runCatching { ManifestCodec.decode(manifestBlock) }.getOrNull() ?: return false
        if (!decoded.verifySignature()) return false
        val expected = runCatching { expectedPublisherKeyHex.decodeHex() }.getOrNull() ?: return false
        return decoded.pubKeyBytes.contentEquals(expected)
    }
}

/** Fonte de conteúdo NEUTRA que um [FullNode] serve. */
interface Blockstore {
    fun manifest(obra: ObraId): ByteArray?
    fun block(id: ContentId): ByteArray?
    fun obras(): Set<ObraId>
    fun putManifest(obra: ObraId, manifestBlock: ByteArray)
    fun putBlock(block: Block)
}

/** Blockstore em memória, thread-safe via lock do atomicfu (substitui `ConcurrentHashMap`). */
class MemoryBlockstore : Blockstore {
    private val lock = SynchronizedObject()
    private val manifests = HashMap<String, ByteArray>()
    private val blocks = HashMap<String, ByteArray>()

    override fun manifest(obra: ObraId): ByteArray? = synchronized(lock) { manifests[obra.value] }
    override fun block(id: ContentId): ByteArray? = synchronized(lock) { blocks[id.hex] }
    override fun obras(): Set<ObraId> = synchronized(lock) { manifests.keys.map { ObraId(it) }.toSet() }
    override fun putManifest(obra: ObraId, manifestBlock: ByteArray) = synchronized(lock) {
        manifests[obra.value] = manifestBlock
    }
    override fun putBlock(block: Block) = synchronized(lock) {
        blocks[block.id.hex] = block.bytes
        Unit
    }
}
