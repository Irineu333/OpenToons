package org.opentoons.poc6.trama.wire

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import org.opentoons.poc6.trama.wire.FrameConnection
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * E2 — RPC por frames com request-id sobre UMA conexão segura (design D4): sem muxer,
 * sem bitswap. Frame = [8 B request-id][1 B tipo][corpo serializado]; respostas carregam
 * o mesmo id com o bit de resposta ligado, então chegam em QUALQUER ordem.
 *
 * Formato de wire: CBOR (kotlinx.serialization). Racional (questão Q4 do design): os
 * corpos são dominados por bytes de bloco (`@ByteString` no CBOR = length-prefix cru,
 * mesmo custo do protobuf); nos corpos pequenos a diferença medida foi de poucos bytes
 * (ver RpcCodecTest); e o CBOR é autodescritivo — depurável com um dump hex, sem schema.
 */
@OptIn(ExperimentalSerializationApi::class)
object RpcCodec {
    val cbor = Cbor { ignoreUnknownKeys = true }

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        cbor.encodeToByteArray(serializer, value)

    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T =
        cbor.decodeFromByteArray(serializer, bytes)
}

object RpcTypes {
    const val GET_MANIFEST: Byte = 1
    const val GET_BLOCK: Byte = 2
    const val RESOLVE: Byte = 3
    const val PEX: Byte = 4      // amostra de membros (cliente e malha)
    const val HELLO: Byte = 5    // nó PLENO se registra na malha (cliente nunca envia)
    const val ANNOUNCE: Byte = 6 // anúncio de provider com TTL
    const val PUSH: Byte = 7     // poc-05: publicador não-discável EMPURRA obra (só saída)
    const val ERROR: Byte = 0x7f
    const val RESPONSE_BIT = 0x80.toByte()
}

@Serializable data class GetManifestRequest(val obraId: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class BlobResponse(@ByteString val bytes: ByteArray)

@Serializable data class GetBlockRequest(val cid: String)

@Serializable data class ResolveRequest(val obraId: String)

@Serializable data class ResolveResponse(val providers: List<String>) // "idHex@host:porta"

@Serializable data class PexRequest(val limit: Int = 16)

@Serializable data class PexResponse(val members: List<String>) // "idHex@host:porta"

@Serializable data class HelloRequest(val member: String) // "idHex@host:porta" do nó PLENO

@Serializable data class Ack(val ok: Boolean = true)

@Serializable data class AnnounceRequest(val obraId: String, val provider: String, val ttlMs: Long)

/**
 * poc-05 — frame de PUSH: o publicador empurra manifesto assinado + blocos crus. Note o que
 * NÃO existe aqui: nenhum campo de endereço/identidade de ORIGEM (D1). O receptor sabe a
 * obra pelo manifesto; a autenticação do canal é o handshake Noise, não um campo no wire.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class PushBlock(@ByteString val bytes: ByteArray)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class PushRequest(
    val obraId: String,
    @ByteString val manifestBlock: ByteArray,
    val blocks: List<PushBlock>,
)

@Serializable data class RpcErrorResponse(val message: String)

class RpcException(message: String) : RuntimeException(message)

/** Um lado do RPC: cliente (call) e/ou servidor (handler) sobre a mesma conexão. */
class RpcPeer(
    private val conn: FrameConnection,
    private val handler: ((type: Byte, body: ByteArray) -> Pair<Byte, ByteArray>)? = null,
    handlerThreads: Int = 4,
) : Closeable {

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<Pair<Byte, ByteArray>>>()
    private val executor: ExecutorService = Executors.newFixedThreadPool(handlerThreads)

    private val reader = thread(isDaemon = true, name = "poc6-trama-rpc-reader") {
        try {
            while (true) {
                val frame = try {
                    conn.receive() ?: break
                } catch (_: java.net.SocketException) {
                    break // conexão fechada pelo outro lado ou pelo nosso close()
                }
                val buf = ByteBuffer.wrap(frame)
                val id = buf.long
                val type = buf.get()
                val body = ByteArray(buf.remaining()).also { buf.get(it) }
                if (type.toInt() and 0x80 != 0) {
                    // resposta: correlaciona pelo id, independentemente da ordem
                    pending.remove(id)?.complete((type.toInt() and 0x7f).toByte() to body)
                } else {
                    val h = handler ?: continue
                    executor.execute {
                        val (respType, respBody) = try {
                            h(type, body)
                        } catch (e: Exception) {
                            RpcTypes.ERROR to RpcCodec.encode(
                                RpcErrorResponse.serializer(),
                                RpcErrorResponse(e.message ?: e.javaClass.simpleName),
                            )
                        }
                        send(id, (respType.toInt() or 0x80).toByte(), respBody)
                    }
                }
            }
        } finally {
            pending.values.forEach { it.completeExceptionally(RpcException("conexão fechada")) }
            executor.shutdown()
        }
    }

    private fun send(id: Long, type: Byte, body: ByteArray) {
        val frame = ByteBuffer.allocate(9 + body.size).putLong(id).put(type).put(body).array()
        conn.send(frame)
    }

    /** Envia sem aguardar — as respostas podem ser aguardadas fora de ordem. */
    fun callAsync(type: Byte, body: ByteArray): CompletableFuture<Pair<Byte, ByteArray>> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<Pair<Byte, ByteArray>>()
        pending[id] = future
        send(id, type, body)
        return future
    }

    fun call(type: Byte, body: ByteArray, timeoutSeconds: Long = 30): Pair<Byte, ByteArray> {
        val (respType, respBody) = callAsync(type, body).get(timeoutSeconds, TimeUnit.SECONDS)
        if (respType == RpcTypes.ERROR) {
            throw RpcException(RpcCodec.decode(RpcErrorResponse.serializer(), respBody).message)
        }
        return respType to respBody
    }

    /** Bloqueia até o par fechar a conexão (uso no lado servidor). */
    fun awaitClose() {
        reader.join()
    }

    override fun close() {
        conn.close()
        reader.interrupt()
    }
}
