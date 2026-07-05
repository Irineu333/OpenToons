package org.opentoons.poc2.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManifestTest {

    private val keys = ManifestCrypto.generateKeyPair()
    private val manifest = Manifest(
        chapterId = "opentoons/serie-teste/cap-001",
        seq = 7,
        blockCids = listOf(
            "bafkreigh2akiscaildcqabsyg3dfr6chu3fgpregiymsck7e7aqa4s52zy",
            "bafkreidgvpkjawlxz6sffxzwgooowe5yt7i6wsyg236mfoks77nywkptdq",
        ),
    )

    // 5.1 — assinatura e verificação
    @Test
    fun `manifesto assinado com a chave do publicador passa na verificacao`() {
        val signed = ManifestCrypto.sign(manifest, keys.first)
        assertTrue(ManifestCrypto.verify(signed, keys.second))
    }

    @Test
    fun `verificacao com chave publica de outro publicador falha`() {
        val signed = ManifestCrypto.sign(manifest, keys.first)
        val outraChave = ManifestCrypto.generateKeyPair().second
        assertFalse(ManifestCrypto.verify(signed, outraChave))
    }

    // 5.2 — QUALQUER byte alterado invalida o manifesto
    @Test
    fun `alterar qualquer byte do manifesto invalida a assinatura`() {
        val signed = ManifestCrypto.sign(manifest, keys.first)
        val original = manifest.canonicalBytes()
        for (i in original.indices) {
            val adulterado = original.copyOf()
            adulterado[i] = (adulterado[i].toInt() xor 0x01).toByte()
            assertFalse(
                ManifestCrypto.verifyBytes(adulterado, signed.signature, keys.second),
                "byte $i adulterado deveria invalidar a assinatura",
            )
        }
    }

    // 5.3 — rollback via seq monotônico
    @Test
    fun `manifesto autentico com seq antigo e rejeitado como rollback`() {
        val verifier = ManifestVerifier(keys.second)

        val v7 = ManifestCrypto.sign(manifest, keys.first)
        assertIs<ManifestVerifier.Result.Accepted>(verifier.submit(v7))

        val v8 = ManifestCrypto.sign(manifest.copy(seq = 8), keys.first)
        assertIs<ManifestVerifier.Result.Accepted>(verifier.submit(v8))

        // Reapresentar o seq=7 (autêntico!) deve ser rejeitado como rollback
        val resultado = verifier.submit(v7)
        val rollback = assertIs<ManifestVerifier.Result.Rollback>(resultado)
        assertEquals(7, rollback.presented)
        assertEquals(8, rollback.lastKnown)
    }

    @Test
    fun `seq igual ao ultimo conhecido tambem e rejeitado`() {
        val verifier = ManifestVerifier(keys.second)
        val v7 = ManifestCrypto.sign(manifest, keys.first)
        assertIs<ManifestVerifier.Result.Accepted>(verifier.submit(v7))
        val v7bis = ManifestCrypto.sign(manifest.copy(blockCids = manifest.blockCids.reversed()), keys.first)
        assertIs<ManifestVerifier.Result.Rollback>(verifier.submit(v7bis))
    }

    @Test
    fun `manifesto com assinatura invalida nao atualiza o seq`() {
        val verifier = ManifestVerifier(keys.second)
        val chaveFalsa = ManifestCrypto.generateKeyPair().first
        val falso = ManifestCrypto.sign(manifest.copy(seq = 99), chaveFalsa)
        assertIs<ManifestVerifier.Result.BadSignature>(verifier.submit(falso))

        // O seq=99 do manifesto falso não pode ter "queimado" o contador
        val legitimo = ManifestCrypto.sign(manifest, keys.first)
        assertIs<ManifestVerifier.Result.Accepted>(verifier.submit(legitimo))
    }
}
