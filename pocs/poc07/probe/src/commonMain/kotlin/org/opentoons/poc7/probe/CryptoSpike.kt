package org.opentoons.poc7.probe

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH

/**
 * Spike 2.2 (D4) — mede empiricamente, no MESMO alvo, se um provider da cryptography-kotlin
 * fecha as 4 primitivas que a Trama exige para cruzar para Native: **SHA-256**, **HMAC-SHA256**
 * (base do HKDF do Noise), **X25519** (DH do Noise XX), **ChaCha20-Poly1305 com nonce
 * explícito** (AEAD do Noise) e **Ed25519** (verify do manifesto, fora do seam).
 *
 * Cada primitiva é aferida (D6) contra resposta conhecida: SHA-256 contra o KAT `"abc"`;
 * X25519 pela propriedade de acordo (dois lados → mesmo segredo); ChaCha pela ida-e-volta;
 * Ed25519 aceitando a boa e **rejeitando** a adulterada. Cada bloco é isolado em try/catch:
 * se o provider não implementar a primitiva no alvo, sai `FAIL <motivo>` em vez de derrubar
 * tudo — assim uma única execução no device dá a matriz de suporte completa.
 */
@OptIn(DelicateCryptographyApi::class)
object CryptoSpike {
    private val provider get() = CryptographyProvider.Default

    private fun ByteArray.hex() = joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    fun run(): String {
        val out = StringBuilder()
        val pname = try { provider.name } catch (e: Throwable) { "NENHUM (${e.message})" }
        out.append("POC07-CRYPTO provider=$pname\n")
        out.append(sha256())
        out.append(hmacSha256())
        out.append(x25519())
        out.append(chacha())
        out.append(ed25519())
        return out.toString()
    }

    private inline fun probe(name: String, block: () -> String): String =
        try { "  $name: ${block()}\n" } catch (e: Throwable) { "  $name: FAIL ${e::class.simpleName}: ${e.message}\n" }

    // KAT: SHA-256("abc") = ba7816bf...15ad
    private fun sha256() = probe("sha256") {
        val h = provider.get(SHA256).hasher().hashBlocking("abc".encodeToByteArray()).hex()
        val ok = h == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        if (ok) "PASS (KAT abc ok)" else "FAIL KAT mismatch: $h"
    }

    private fun hmacSha256() = probe("hmac-sha256") {
        val hmac = provider.get(HMAC)
        val key = hmac.keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, ByteArray(32) { 0x0b })
        val tag = key.signatureGenerator().generateSignatureBlocking("Hi There".encodeToByteArray())
        val verified = key.signatureVerifier().tryVerifySignatureBlocking("Hi There".encodeToByteArray(), tag)
        if (verified && tag.size == 32) "PASS (tag 32B, verify ok)" else "FAIL verify=$verified size=${tag.size}"
    }

    // Propriedade de acordo: dois pares → mesmo segredo compartilhado.
    private fun x25519() = probe("x25519") {
        val xdh = provider.get(XDH)
        val a = xdh.keyPairGenerator(XDH.Curve.X25519).generateKeyBlocking()
        val b = xdh.keyPairGenerator(XDH.Curve.X25519).generateKeyBlocking()
        val sa = a.privateKey.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(b.publicKey)
        val sb = b.privateKey.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(a.publicKey)
        if (sa.contentEquals(sb) && sa.size == 32) "PASS (acordo ok, 32B)" else "FAIL sa!=sb ou tamanho"
    }

    // Nonce explícito (o que o Noise exige) + AAD + ida-e-volta.
    private fun chacha() = probe("chacha20poly1305") {
        val algo = provider.get(ChaCha20Poly1305)
        val key = algo.keyDecoder().decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, ByteArray(32) { it.toByte() })
        val cipher = key.cipher()
        val iv = ByteArray(12) { (0xA0 + it).toByte() }
        val aad = "noise-ad".encodeToByteArray()
        val pt = "capitulo-bloco".encodeToByteArray()
        val ct = cipher.encryptWithIvBlocking(iv, pt, aad)
        val back = cipher.decryptWithIvBlocking(iv, ct, aad)
        val tagged = ct.size == pt.size + 16
        if (back.contentEquals(pt) && tagged) "PASS (roundtrip, tag16, nonce explícito)" else "FAIL roundtrip"
    }

    // Aceita boa, rejeita adulterada.
    private fun ed25519() = probe("ed25519") {
        val eddsa = provider.get(EdDSA)
        val kp = eddsa.keyPairGenerator(EdDSA.Curve.Ed25519).generateKeyBlocking()
        val msg = "manifesto".encodeToByteArray()
        val sig = kp.privateKey.signatureGenerator().generateSignatureBlocking(msg)
        val good = kp.publicKey.signatureVerifier().tryVerifySignatureBlocking(msg, sig)
        val bad = kp.publicKey.signatureVerifier().tryVerifySignatureBlocking("adulterado".encodeToByteArray(), sig)
        if (good && !bad) "PASS (aceita boa, rejeita adulterada, sig ${sig.size}B)" else "FAIL good=$good bad=$bad"
    }
}
