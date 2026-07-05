package org.opentoons.poc2.tls

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.opentoons.poc2.core.NodeIdentity
import org.opentoons.poc2.transport.FrameConnection
import org.opentoons.poc2.transport.SocketFrameConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.concurrent.thread

/**
 * E1a — canal seguro TLS 1.3 da plataforma (JSSE) com autenticação mútua ligada à
 * identidade Ed25519. Nada de cripto artesanal: só geração de certificado (bcpkix)
 * e um TrustManager que troca "confiança em CA" por "prova de identidade".
 */
object TlsChannel {

    /**
     * TrustManager da PoC: ignora completamente cadeia/CA e valida que o certificado
     * apresentado prova posse de uma identidade Ed25519 — opcionalmente uma identidade
     * esperada ([expectedPeer], caso do cliente que sabe quem está discando).
     * Lançar exceção aqui aborta o handshake ANTES de qualquer dado de aplicação.
     */
    class IdentityTrustManager(private val expectedPeer: Ed25519PublicKeyParameters?) :
        X509ExtendedTrustManager() {

        private fun validate(chain: Array<X509Certificate>) {
            val identity = TlsIdentity.extractIdentity(chain[0]) // lança se binding inválido
            if (expectedPeer != null && !identity.encoded.contentEquals(expectedPeer.encoded)) {
                throw SecurityException(
                    "impostor: canal válido mas identidade não é a esperada",
                )
            }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = validate(chain)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = validate(chain)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = validate(chain)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = validate(chain)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = validate(chain)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = validate(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun sslContext(
        credentials: TlsIdentity.ChannelCredentials,
        expectedPeer: Ed25519PublicKeyParameters?,
    ): SSLContext {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry("node", credentials.privateKey, CharArray(0), arrayOf(credentials.certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, CharArray(0))
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf(IdentityTrustManager(expectedPeer)), null)
        return ctx
    }

    /** Identidade autenticada do par, extraída da sessão após o handshake. */
    fun peerIdentity(socket: SSLSocket): Ed25519PublicKeyParameters =
        TlsIdentity.extractIdentity(socket.session.peerCertificates[0] as X509Certificate)

    class Server(
        port: Int,
        credentials: TlsIdentity.ChannelCredentials,
        /** null = aceita qualquer cliente com identidade provada (autorização é camada acima). */
        expectedClient: Ed25519PublicKeyParameters? = null,
        private val handler: (FrameConnection, Ed25519PublicKeyParameters) -> Unit,
    ) : AutoCloseable {
        private val serverSocket = sslContext(credentials, expectedClient).serverSocketFactory
            .createServerSocket() as SSLServerSocket
        private val closed = AtomicBoolean(false)

        val port: Int get() = serverSocket.localPort

        init {
            serverSocket.reuseAddress = true
            serverSocket.bind(InetSocketAddress(port))
            serverSocket.enabledProtocols = arrayOf("TLSv1.3")
            serverSocket.needClientAuth = true // autenticação MÚTUA: cliente também prova identidade
            thread(isDaemon = true, name = "poc2-tls-accept") {
                while (!closed.get()) {
                    val socket = try {
                        serverSocket.accept() as SSLSocket
                    } catch (_: Exception) {
                        if (closed.get()) return@thread else continue
                    }
                    thread(isDaemon = true) {
                        socket.use { s ->
                            runCatching {
                                s.startHandshake()
                                handler(SocketFrameConnection(s), peerIdentity(s))
                            }
                        }
                    }
                }
            }
        }

        override fun close() {
            closed.set(true)
            serverSocket.close()
        }
    }

    /**
     * Discador com SSLContext persistente: reconexões ao mesmo nó reutilizam o cache de
     * sessões do contexto → session resumption do TLS 1.3 (a métrica de reconexão do D5).
     */
    class Dialer(
        credentials: TlsIdentity.ChannelCredentials,
        private val expectedServer: Ed25519PublicKeyParameters,
    ) {
        private val ctx = sslContext(credentials, expectedServer)

        fun dial(host: String, port: Int, timeoutMs: Int = 5_000): Pair<FrameConnection, SSLSocket> {
            val socket = ctx.socketFactory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.startHandshake()
            return SocketFrameConnection(socket) to socket
        }
    }

    /** Disca e completa o handshake; falha se o servidor não provar [expectedServer]. */
    fun dial(
        host: String,
        port: Int,
        credentials: TlsIdentity.ChannelCredentials,
        expectedServer: Ed25519PublicKeyParameters,
        timeoutMs: Int = 5_000,
    ): Pair<FrameConnection, SSLSocket> =
        Dialer(credentials, expectedServer).dial(host, port, timeoutMs)
}
