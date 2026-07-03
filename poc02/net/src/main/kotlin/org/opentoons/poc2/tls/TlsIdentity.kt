package org.opentoons.poc2.tls

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.opentoons.poc2.core.NodeIdentity
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * E1a — certificados X.509 autoassinados ligados à identidade Ed25519 do nó.
 *
 * Duas estratégias, correspondendo à questão aberta do design:
 *  1. [ed25519Cert] — a própria chave de identidade é a chave do certificado
 *     (funciona se o JSSE/provider TLS da plataforma aceitar Ed25519 no handshake);
 *  2. [boundCert] — chave de canal ECDSA P-256 efêmera + extensão customizada com
 *     (pubkey de identidade, assinatura da identidade sobre o SPKI da chave de canal) —
 *     o mesmo esquema do libp2p-tls, sem compromisso de interop de wire.
 *
 * A cadeia CA é irrelevante e ignorada: a confiança vem da extensão/chave, não de CA.
 */
object TlsIdentity {

    /** OID privado da PoC para a extensão de identidade (arco experimental; sem registro). */
    val IDENTITY_EXTENSION_OID: ASN1ObjectIdentifier = ASN1ObjectIdentifier("1.3.6.1.4.1.53594.99.1")

    /** Prefixo de contexto da assinatura de binding (papel do "libp2p-tls-handshake:"). */
    private const val BINDING_CONTEXT = "opentoons-tls-binding:"

    data class ChannelCredentials(val certificate: X509Certificate, val privateKey: PrivateKey)

    /**
     * Estratégia 1: certificado cuja chave É a identidade Ed25519.
     *
     * Achado do experimento (JVM): as chaves convertidas pelo provider BC reportam
     * algoritmo "Ed25519", mas o KeyManager do JSSE seleciona certificados pelo nome
     * "EdDSA" do provider SunEC → handshake_failure. A conversão precisa usar os
     * providers do PRÓPRIO JDK — o que já indica a resposta para Android, onde não
     * existe provider EdDSA de plataforma (confirmação no dispositivo, task 2.3).
     * Lança [java.security.NoSuchAlgorithmException] onde a plataforma não tem EdDSA.
     */
    fun ed25519Cert(identity: NodeIdentity): ChannelCredentials {
        val privInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(identity.privateKey)
        // sem provider explícito: exige EdDSA da plataforma (SunEC no JDK; ausente no Android)
        val jcaPriv = KeyFactory.getInstance("EdDSA")
            .generatePrivate(PKCS8EncodedKeySpec(privInfo.encoded))
        val spki = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(identity.publicKey)
        val holder = certBuilder(spki)
            .build(JcaContentSignerBuilder("Ed25519").build(jcaPriv))
        val cert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(holder.encoded.inputStream()) as X509Certificate
        return ChannelCredentials(cert, jcaPriv)
    }

    /**
     * Estratégia 2: chave de canal ECDSA P-256 + extensão com a identidade assinada.
     *
     * Achado do experimento (Android, API 31): NÃO pinar o provider "BC" — o Android
     * registra um provider de sistema com esse nome, castrado (sem `EC`), que sombreia o
     * bcprov embarcado. Sem provider explícito, a plataforma resolve (AndroidOpenSSL no
     * Android, SunEC na JVM) e o mesmo código roda nos dois lugares.
     */
    fun boundCert(identity: NodeIdentity): ChannelCredentials {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val channelKeys: KeyPair = gen.generateKeyPair()

        val channelSpki = channelKeys.public.encoded // SPKI DER da chave de canal
        val binding = identity.sign(BINDING_CONTEXT.toByteArray() + channelSpki)
        val extension = DERSequence(
            arrayOf(DEROctetString(identity.id), DEROctetString(binding)),
        )

        val spki = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(channelSpki)
        val holder = certBuilder(spki).addExtension(IDENTITY_EXTENSION_OID, false, extension)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(channelKeys.private))
        val cert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(holder.encoded.inputStream()) as X509Certificate
        return ChannelCredentials(cert, channelKeys.private)
    }

    private fun certBuilder(
        spki: org.bouncycastle.asn1.x509.SubjectPublicKeyInfo,
    ): X509v3CertificateBuilder {
        val name = X500Name("CN=opentoons-poc2")
        val now = System.currentTimeMillis()
        return X509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 3_600_000),
            Date(now + 10L * 365 * 24 * 3_600_000), // validade longa: a identidade manda, não o relógio
            name,
            spki,
        )
    }

    /**
     * Extrai e autentica a identidade Ed25519 de um certificado apresentado no handshake.
     * Aceita as duas estratégias; lança [SecurityException] se o binding for inválido.
     */
    fun extractIdentity(cert: X509Certificate): Ed25519PublicKeyParameters {
        val extBytes = cert.getExtensionValue(IDENTITY_EXTENSION_OID.id)
        if (extBytes == null) {
            // Estratégia 1: sem extensão, a chave do cert precisa SER Ed25519
            val key = cert.publicKey
            if (key.algorithm != "Ed25519" && key.algorithm != "EdDSA") {
                throw SecurityException("certificado sem extensão de identidade e sem chave Ed25519")
            }
            // valida a autoassinatura: prova de posse da chave de identidade
            cert.verify(key)
            val spki = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(key.encoded)
            return Ed25519PublicKeyParameters(spki.publicKeyData.bytes, 0)
        }
        // Estratégia 2: extensão SEQUENCE { identityPub, assinatura(contexto || SPKI do canal) }
        val octets = DEROctetString.getInstance(extBytes).octets
        val seq = ASN1Sequence.getInstance(octets)
        val identityPub = Ed25519PublicKeyParameters(DEROctetString.getInstance(seq.getObjectAt(0)).octets, 0)
        val signature = DEROctetString.getInstance(seq.getObjectAt(1)).octets
        val signedData = BINDING_CONTEXT.toByteArray() + cert.publicKey.encoded
        if (!NodeIdentity.verify(identityPub, signedData, signature)) {
            throw SecurityException("binding identidade↔chave de canal inválido")
        }
        return identityPub
    }
}
