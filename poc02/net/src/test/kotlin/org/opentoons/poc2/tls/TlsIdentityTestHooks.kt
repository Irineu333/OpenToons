package org.opentoons.poc2.tls

import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.opentoons.poc2.core.NodeIdentity
import java.security.cert.X509Certificate

/**
 * Simula o ataque de transplante: a extensão de identidade do certificado [source]
 * (legítima) apresentada junto com a chave de canal do certificado [target] (do atacante).
 * A verificação de binding DEVE falhar — a assinatura cobre o SPKI da chave de canal.
 */
object TlsIdentityTestHooks {

    fun extractWithTransplantedExtension(
        source: X509Certificate,
        target: X509Certificate,
    ): Ed25519PublicKeyParameters {
        val extBytes = source.getExtensionValue(TlsIdentity.IDENTITY_EXTENSION_OID.id)
        val seq = ASN1Sequence.getInstance(DEROctetString.getInstance(extBytes).octets)
        val identityPub = Ed25519PublicKeyParameters(DEROctetString.getInstance(seq.getObjectAt(0)).octets, 0)
        val signature = DEROctetString.getInstance(seq.getObjectAt(1)).octets
        val signedData = "opentoons-tls-binding:".toByteArray() + target.publicKey.encoded
        if (!NodeIdentity.verify(identityPub, signedData, signature)) {
            throw SecurityException("binding identidade↔chave de canal inválido (transplante)")
        }
        return identityPub
    }
}
