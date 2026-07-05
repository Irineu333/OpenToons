package org.opentoons.poc3.core

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * PoC poc-03 — manifesto assinado Ed25519, **reusado do poc-01 sem alteração** (design
 * D7). O formato é independente da stack de rede: os blocos trafegam pelo Request-Response
 * do libp2p (via facade nativo), mas a verificação de assinatura/hash é este código Kotlin,
 * do lado do app — a fronteira FFI só entrega bytes. Assim a verificação fica **comparável**
 * entre nabu (poc-01), stack própria (poc-02) e libp2p de referência (poc-03).
 *
 * Cópia do `Manifest.kt`/`ManifestCodec` do poc-01 (parte de verificação); a parte de
 * geração de chave/assinatura fica no publicador, fora do app.
 */
data class Manifest(
    val chapterId: String,
    val seq: Long,
    val blockCids: List<String>,
) {
    /** Serialização canônica length-prefixed: qualquer byte alterado invalida a assinatura. */
    fun canonicalBytes(): ByteArray {
        val fields = buildList {
            add(chapterId.toByteArray(StandardCharsets.UTF_8))
            add(ByteBuffer.allocate(8).putLong(seq).array())
            blockCids.forEach { add(it.toByteArray(StandardCharsets.UTF_8)) }
        }
        val size = fields.sumOf { 4 + it.size } + 4
        val buf = ByteBuffer.allocate(size)
        buf.putInt(fields.size)
        fields.forEach { buf.putInt(it.size); buf.put(it) }
        return buf.array()
    }
}

/** Verificação Ed25519 sobre bytes arbitrários — idêntica ao poc-01 (D7). */
object ManifestCrypto {
    fun verifyBytes(bytes: ByteArray, signature: ByteArray, pubKey: Ed25519PublicKeyParameters): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, pubKey)
        verifier.update(bytes, 0, bytes.size)
        return verifier.verifySignature(signature)
    }
}

/**
 * Codec de transporte do manifesto: `[pkLen][pk][sigLen][sig][canonical]` — o mesmo bloco
 * que vai no blockstore e é baixado/verificado pelo app (poc-01 E4).
 */
object ManifestCodec {

    data class Decoded(val manifest: Manifest, val signature: ByteArray, val pubKeyBytes: ByteArray) {
        fun verifySignature(): Boolean = ManifestCrypto.verifyBytes(
            manifest.canonicalBytes(), signature, Ed25519PublicKeyParameters(pubKeyBytes, 0),
        )
    }

    fun decode(bytes: ByteArray): Decoded {
        val buf = ByteBuffer.wrap(bytes)
        val pk = ByteArray(buf.int).also { buf.get(it) }
        val sig = ByteArray(buf.int).also { buf.get(it) }
        val canonical = ByteArray(buf.remaining()).also { buf.get(it) }
        return Decoded(parseCanonical(canonical), sig, pk)
    }

    fun parseCanonical(canonical: ByteArray): Manifest {
        val buf = ByteBuffer.wrap(canonical)
        val count = buf.int
        val fields = (0 until count).map { ByteArray(buf.int).also { f -> buf.get(f) } }
        require(fields.size >= 2) { "manifesto malformado" }
        return Manifest(
            chapterId = String(fields[0], StandardCharsets.UTF_8),
            seq = ByteBuffer.wrap(fields[1]).long,
            blockCids = fields.drop(2).map { String(it, StandardCharsets.UTF_8) },
        )
    }
}
