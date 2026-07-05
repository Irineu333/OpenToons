package org.opentoons.poc3.core

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Valida a verificação Kotlin-side do poc-03 (design D7): a fronteira FFI entrega bytes,
 * este código verifica. Cobre o caminho feliz (5.2) e a rejeição de adulterado (7.3) —
 * exatamente o que o E4 precisa provar, mas sem depender do binding nativo (que ainda não
 * cross-compila neste ambiente; ver docs/poc03-report.md).
 */
class ChapterVerifierTest {

    private val rnd = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }

    private fun keypair(): Pair<Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters> {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(rnd))
        val kp = gen.generateKeyPair()
        return (kp.private as Ed25519PrivateKeyParameters) to (kp.public as Ed25519PublicKeyParameters)
    }

    private fun sign(bytes: ByteArray, priv: Ed25519PrivateKeyParameters): ByteArray {
        val s = Ed25519Signer(); s.init(true, priv); s.update(bytes, 0, bytes.size)
        return s.generateSignature()
    }

    private fun encodeManifest(m: Manifest, sig: ByteArray, pk: ByteArray): ByteArray {
        val canonical = m.canonicalBytes()
        val buf = ByteBuffer.allocate(4 + pk.size + 4 + sig.size + canonical.size)
        buf.putInt(pk.size).put(pk); buf.putInt(sig.size).put(sig); buf.put(canonical)
        return buf.array()
    }

    private fun lengthPrefix(blocks: List<ByteArray>): ByteArray {
        val out = ByteArray(blocks.sumOf { it.size + 4 })
        val buf = ByteBuffer.wrap(out)
        blocks.forEach { buf.putInt(it.size).put(it) }
        return out
    }

    /** Capítulo de 3 blocos como no poc-01: 1 manifesto + 3 blocos de conteúdo. */
    private fun fixtureChapter(priv: Ed25519PrivateKeyParameters, pub: Ed25519PublicKeyParameters):
        Triple<ByteArray, ByteArray, ByteArray> {
        val contents = listOf(
            "pagina-1".repeat(100).toByteArray(),
            "pagina-2".repeat(100).toByteArray(),
            "pagina-3".repeat(100).toByteArray(),
        )
        val contentCids = contents.map { ChapterVerifier.sha256Hex(it) }
        val manifest = Manifest("cap-teste", seq = 1, blockCids = listOf("manifesto-cid") + contentCids)
        val sig = sign(manifest.canonicalBytes(), priv)
        val manifestBlock = encodeManifest(manifest, sig, pub.encoded)
        val expectedChapter = ByteArray(contents.sumOf { it.size }).also { out ->
            var off = 0; contents.forEach { it.copyInto(out, off); off += it.size }
        }
        return Triple(manifestBlock, lengthPrefix(contents), expectedChapter)
    }

    @Test
    fun `capitulo integro e verificado e reconstruido`() {
        val (priv, pub) = keypair()
        val (manifestBlock, content, expected) = fixtureChapter(priv, pub)
        val res = ChapterVerifier(pub).verify(manifestBlock, content)
        assertTrue(res is ChapterVerifier.Result.Verified, "esperava Verified, veio $res")
        assertTrue(res.chapter.contentEquals(expected))
        assertEquals("cap-teste", res.manifest.chapterId)
    }

    @Test
    fun `bloco adulterado e rejeitado por hash`() {
        val (priv, pub) = keypair()
        val (manifestBlock, content, _) = fixtureChapter(priv, pub)
        content[8] = (content[8] + 1).toByte() // vira 1 byte no 1º bloco de conteúdo
        val res = ChapterVerifier(pub).verify(manifestBlock, content)
        assertTrue(res is ChapterVerifier.Result.BlockHashMismatch, "esperava mismatch, veio $res")
    }

    @Test
    fun `assinatura invalida e rejeitada`() {
        val (priv, pub) = keypair()
        val (manifestBlock, content, _) = fixtureChapter(priv, pub)
        manifestBlock[manifestBlock.size - 1] = (manifestBlock[manifestBlock.size - 1] + 1).toByte()
        val res = ChapterVerifier(pub).verify(manifestBlock, content)
        assertTrue(res is ChapterVerifier.Result.BadSignature, "esperava BadSignature, veio $res")
    }

    @Test
    fun `manifesto assinado por outra chave e rejeitado`() {
        val (priv, pub) = keypair()
        val (_, attacker) = keypair()
        val (manifestBlock, content, _) = fixtureChapter(priv, pub)
        // app confia em `attacker`, mas o manifesto foi assinado por `pub`
        val res = ChapterVerifier(attacker).verify(manifestBlock, content)
        assertTrue(res is ChapterVerifier.Result.BadSignature, "esperava BadSignature, veio $res")
    }
}
