package org.opentoons.poc7.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Aferição do SPI portado (roda em jvmTest E nos alvos Native): identidade determinística,
 * assinatura/verify Ed25519 no alvo, e o `ChapterVerifier` aceitando o íntegro e REJEITANDO
 * bloco adulterado / chave errada — o verify do device, fora do seam.
 */
class ApiSeamTest {

    private val content = NodeKeys.fromSeed("poc7-content")
    private val wrong = NodeKeys.fromSeed("poc7-imposter")

    @Test
    fun deterministicIdentity() {
        // a mesma semente → a mesma pubkey em qualquer plataforma
        assertEquals(content.idHex, NodeKeys.fromSeed("poc7-content").idHex)
        assertEquals(64, content.idHex.length) // 32 B em hex
    }

    @Test
    fun signAndVerify() {
        val msg = "capitulo".encodeToByteArray()
        val sig = content.sign(msg)
        assertTrue(NodeKeys.verify(content.id, msg, sig))
        assertTrue(!NodeKeys.verify(content.id, "adulterado".encodeToByteArray(), sig))
    }

    private fun prepared(keys: NodeKeys) = ChapterPublisher.prepare(
        keys, "serie/cap-1", 7L,
        listOf(ByteArray(1024) { (it % 251).toByte() }, ByteArray(512) { (it % 241).toByte() }),
    )

    @Test
    fun verifierAcceptsIntact() {
        val p = prepared(content)
        val r = ChapterVerifier(content.idHex).verify(p.manifestBlock, p.blocks.map { it.bytes })
        assertTrue(r is ChapterVerifier.Result.Verified, "esperado Verified, veio $r")
    }

    @Test
    fun verifierRejectsTamperedBlock() {
        val p = prepared(content)
        val tampered = p.blocks.map { it.bytes.copyOf() }
        tampered[0][0] = (tampered[0][0].toInt() xor 1).toByte()
        val r = ChapterVerifier(content.idHex).verify(p.manifestBlock, tampered)
        assertTrue(r is ChapterVerifier.Result.BlockHashMismatch, "esperado mismatch, veio $r")
    }

    @Test
    fun verifierRejectsWrongKey() {
        val p = prepared(wrong) // assinado pela chave errada; leitor confia em `content`
        val r = ChapterVerifier(content.idHex).verify(p.manifestBlock, p.blocks.map { it.bytes })
        assertTrue(r is ChapterVerifier.Result.BadSignature, "esperado BadSignature, veio $r")
    }
}
