package org.opentoons.poc4.api

import org.junit.Test
import org.opentoons.poc4.api.tck.TckVectors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PoC poc-04 — testes do `:api` isolado: o verify fora do seam (aceitação + rejeições) e o
 * roundtrip do manifesto. O comportamento em rede é papel do TCK, não daqui.
 */
class ApiSeamTest {

    @Test
    fun `manifesto roundtrip e assinatura verificam`() {
        val prep = TckVectors.prepared()
        val decoded = ManifestCodec.decode(prep.manifestBlock)
        assertEquals(TckVectors.CHAPTER_ID, decoded.manifest.chapterId)
        assertEquals(TckVectors.SEQ, decoded.manifest.seq)
        assertEquals(prep.blocks.map { it.id.hex }, decoded.manifest.blockCids)
        assertTrue(decoded.verifySignature())
        assertContentEquals(TckVectors.contentKeys.id, decoded.pubKeyBytes)
    }

    @Test
    fun `verify aceita capitulo integro`() {
        val prep = TckVectors.prepared()
        val result = ChapterVerifier(TckVectors.contentKeys.idHex)
            .verify(prep.manifestBlock, prep.blocks.map { it.bytes })
        val verified = result as ChapterVerifier.Result.Verified
        assertContentEquals(TckVectors.chapterBytes, verified.chapter)
    }

    @Test
    fun `verify rejeita bloco corrompido`() {
        val prep = TckVectors.prepared()
        val tampered = prep.blocks.map { it.bytes.copyOf() }
        tampered[0][0] = (tampered[0][0].toInt() xor 1).toByte()
        val result = ChapterVerifier(TckVectors.contentKeys.idHex)
            .verify(prep.manifestBlock, tampered)
        assertTrue(result is ChapterVerifier.Result.BlockHashMismatch)
    }

    @Test
    fun `verify rejeita manifesto de chave errada`() {
        val prep = TckVectors.preparedWrongKey()
        val result = ChapterVerifier(TckVectors.contentKeys.idHex)
            .verify(prep.manifestBlock, prep.blocks.map { it.bytes })
        assertTrue(result is ChapterVerifier.Result.BadSignature)
    }

    @Test
    fun `identidade deterministica por semente`() {
        assertEquals(NodeKeys.fromSeed("x").idHex, NodeKeys.fromSeed("x").idHex)
        val keys = NodeKeys.fromSeed("y")
        val sig = keys.sign("dados".toByteArray())
        assertTrue(NodeKeys.verify(keys.publicKey, "dados".toByteArray(), sig))
    }
}
