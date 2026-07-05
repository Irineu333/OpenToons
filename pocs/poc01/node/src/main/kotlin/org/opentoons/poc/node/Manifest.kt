package org.opentoons.poc.node

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * PoC E3 — manifesto assinado com proteção contra rollback (ADR-0003).
 *
 * Valida o MECANISMO (Ed25519 + `seq` monotônico), não o schema definitivo.
 * Ed25519 via BouncyCastle (já no classpath pelo nabu; funciona também no Android,
 * onde o provider Ed25519 do JDK não existe).
 */
data class Manifest(
    val chapterId: String,
    val seq: Long,
    val blockCids: List<String>,
) {
    /**
     * Serialização canônica e determinística: length-prefix em cada campo.
     * Qualquer byte alterado invalida a assinatura.
     */
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

data class SignedManifest(val manifest: Manifest, val signature: ByteArray)

object ManifestCrypto {

    fun generateKeyPair(): Pair<Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters> {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        return (kp.private as Ed25519PrivateKeyParameters) to (kp.public as Ed25519PublicKeyParameters)
    }

    fun sign(manifest: Manifest, privKey: Ed25519PrivateKeyParameters): SignedManifest {
        val signer = Ed25519Signer()
        signer.init(true, privKey)
        val bytes = manifest.canonicalBytes()
        signer.update(bytes, 0, bytes.size)
        return SignedManifest(manifest, signer.generateSignature())
    }

    /** Verifica a assinatura sobre bytes arbitrários (permite testar adulteração byte a byte). */
    fun verifyBytes(bytes: ByteArray, signature: ByteArray, pubKey: Ed25519PublicKeyParameters): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, pubKey)
        verifier.update(bytes, 0, bytes.size)
        return verifier.verifySignature(signature)
    }

    fun verify(signed: SignedManifest, pubKey: Ed25519PublicKeyParameters): Boolean =
        verifyBytes(signed.manifest.canonicalBytes(), signed.signature, pubKey)
}

/**
 * Codec de transporte do manifesto assinado (E4): `[pkLen][pk][sigLen][sig][canonical]`.
 * O bloco resultante vai no blockstore e é baixado/verificado pelo app.
 */
object ManifestCodec {

    data class Decoded(val manifest: Manifest, val signature: ByteArray, val pubKeyBytes: ByteArray) {
        fun verify(): Boolean = ManifestCrypto.verifyBytes(
            manifest.canonicalBytes(), signature, Ed25519PublicKeyParameters(pubKeyBytes, 0),
        )
    }

    fun encode(signed: SignedManifest, pubKey: Ed25519PublicKeyParameters): ByteArray {
        val pk = pubKey.encoded
        val canonical = signed.manifest.canonicalBytes()
        val buf = ByteBuffer.allocate(4 + pk.size + 4 + signed.signature.size + canonical.size)
        buf.putInt(pk.size).put(pk)
        buf.putInt(signed.signature.size).put(signed.signature)
        buf.put(canonical)
        return buf.array()
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

/**
 * Detecção de rollback: aceita apenas manifestos autênticos com `seq` estritamente
 * maior que o último `seq` conhecido para o mesmo capítulo.
 */
class ManifestVerifier(private val pubKey: Ed25519PublicKeyParameters) {

    private val lastSeq = mutableMapOf<String, Long>()

    sealed interface Result {
        data object Accepted : Result
        data object BadSignature : Result
        data class Rollback(val presented: Long, val lastKnown: Long) : Result
    }

    fun submit(signed: SignedManifest): Result {
        if (!ManifestCrypto.verify(signed, pubKey)) return Result.BadSignature
        val id = signed.manifest.chapterId
        val known = lastSeq[id]
        if (known != null && signed.manifest.seq <= known) {
            return Result.Rollback(signed.manifest.seq, known)
        }
        lastSeq[id] = signed.manifest.seq
        return Result.Accepted
    }
}
