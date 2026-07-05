package org.opentoons.poc6.api

import org.junit.Test
import org.opentoons.poc6.api.tck.TckVectors
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
    fun `PushPolicy aceita editora conhecida e rejeita chave errada`() {
        val legit = TckVectors.prepared()
        val forged = TckVectors.preparedWrongKey()
        // aceita: manifesto assinado pela editora esperada
        assertTrue(PushPolicy.accepts(legit.manifestBlock, TckVectors.contentKeys.idHex))
        // rejeita: mesma obra assinada por impostor (chave errada) — mesmo que a assinatura
        // seja internamente válida, o signatário não é o publicador esperado
        assertTrue(!PushPolicy.accepts(forged.manifestBlock, TckVectors.contentKeys.idHex))
        // rejeita: manifesto malformado (nunca lança)
        assertTrue(!PushPolicy.accepts(byteArrayOf(1, 2, 3), TckVectors.contentKeys.idHex))
    }

    @Test
    fun `AnonymityConfig habilitado exige endpoint SOCKS`() {
        assertTrue(!AnonymityConfig.DISABLED.enabled)
        val tor = AnonymityConfig.tor()
        assertTrue(tor.enabled && tor.socksPort == 9050)
        var rejected = false
        try {
            AnonymityConfig(enabled = true) // sem host/porta
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected, "modo anônimo sem endpoint deveria falhar")
    }

    @Test
    fun `identidade deterministica por semente`() {
        assertEquals(NodeKeys.fromSeed("x").idHex, NodeKeys.fromSeed("x").idHex)
        val keys = NodeKeys.fromSeed("y")
        val sig = keys.sign("dados".toByteArray())
        assertTrue(NodeKeys.verify(keys.publicKey, "dados".toByteArray(), sig))
    }
}
