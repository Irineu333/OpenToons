package org.opentoons.poc7.trama.wire

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor

/**
 * poc-07 — RPC por frames com request-id sobre UMA conexão segura, PORTADO para coroutines.
 * Frame = `[8 B id BE][1 B tipo][corpo CBOR]`; respostas carregam o mesmo id com o bit 0x80,
 * chegando em qualquer ordem. Threads/`ExecutorService`/`CompletableFuture`/`ConcurrentHashMap`
 * do poc-06 viraram um escopo de coroutines + [CompletableDeferred] + mapa sob lock do atomicfu.
 */
@OptIn(ExperimentalSerializationApi::class)
object RpcCodec {
    val cbor = Cbor { ignoreUnknownKeys = true }
    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray = cbor.encodeToByteArray(serializer, value)
    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T = cbor.decodeFromByteArray(serializer, bytes)
}

object RpcTypes {
    const val GET_MANIFEST: Byte = 1
    const val GET_BLOCK: Byte = 2
    const val RESOLVE: Byte = 3
    const val PEX: Byte = 4
    const val HELLO: Byte = 5
    const val ANNOUNCE: Byte = 6
    const val PUSH: Byte = 7
    const val ERROR: Byte = 0x7f
}

@Serializable data class GetManifestRequest(val obraId: String)
@OptIn(ExperimentalSerializationApi::class) @Serializable data class BlobResponse(@ByteString val bytes: ByteArray)
@Serializable data class GetBlockRequest(val cid: String)
@Serializable data class ResolveRequest(val obraId: String)
@Serializable data class ResolveResponse(val providers: List<String>)
@Serializable data class PexRequest(val limit: Int = 16)
@Serializable data class PexResponse(val members: List<String>)
@Serializable data class HelloRequest(val member: String)
@Serializable data class Ack(val ok: Boolean = true)
@Serializable data class AnnounceRequest(val obraId: String, val provider: String, val ttlMs: Long)
@OptIn(ExperimentalSerializationApi::class) @Serializable data class PushBlock(@ByteString val bytes: ByteArray)
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class PushRequest(
    val obraId: String,
    @ByteString val manifestBlock: ByteArray,
    val blocks: List<PushBlock>,
)
@Serializable data class RpcErrorResponse(val message: String)

class RpcException(message: String) : RuntimeException(message)

private fun Long.be8(): ByteArray = ByteArray(8) { i -> (this ushr (56 - i * 8)).toByte() }
private fun ByteArray.be8At(off: Int): Long {
    var v = 0L; for (i in 0 until 8) v = (v shl 8) or (this[off + i].toLong() and 0xff); return v
}

/** Um lado do RPC: cliente (call) e/ou servidor (handler) sobre a mesma conexão segura. */
class RpcPeer(
    private val conn: FrameConnection,
    private val handler: (suspend (type: Byte, body: ByteArray) -> Pair<Byte, ByteArray>)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextId = atomic(1L)
    private val lock = SynchronizedObject()
    private val pending = HashMap<Long, CompletableDeferred<Pair<Byte, ByteArray>>>()
    private val readerJob: Job = scope.launch { readLoop() }

    private suspend fun readLoop() {
        try {
            while (true) {
                val frame = conn.receive() ?: break
                val id = frame.be8At(0)
                val type = frame[8]
                val body = frame.copyOfRange(9, frame.size)
                if (type.toInt() and 0x80 != 0) {
                    val d = synchronized(lock) { pending.remove(id) }
                    d?.complete((type.toInt() and 0x7f).toByte() to body)
                } else {
                    val h = handler ?: continue
                    scope.launch {
                        val (respType, respBody) = try {
                            h(type, body)
                        } catch (e: Exception) {
                            RpcTypes.ERROR to RpcCodec.encode(
                                RpcErrorResponse.serializer(),
                                RpcErrorResponse(e.message ?: "erro"),
                            )
                        }
                        sendFrame(id, (respType.toInt() or 0x80).toByte(), respBody)
                    }
                }
            }
        } finally {
            synchronized(lock) {
                pending.values.forEach { it.completeExceptionally(RpcException("conexão fechada")) }
                pending.clear()
            }
        }
    }

    private suspend fun sendFrame(id: Long, type: Byte, body: ByteArray) {
        conn.send(id.be8() + byteArrayOf(type) + body)
    }

    suspend fun callAsync(type: Byte, body: ByteArray): CompletableDeferred<Pair<Byte, ByteArray>> {
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<Pair<Byte, ByteArray>>()
        synchronized(lock) { pending[id] = d }
        sendFrame(id, type, body)
        return d
    }

    suspend fun call(type: Byte, body: ByteArray, timeoutMs: Long = 30_000): Pair<Byte, ByteArray> {
        val d = callAsync(type, body)
        val (respType, respBody) = withTimeout(timeoutMs) { d.await() }
        if (respType == RpcTypes.ERROR) {
            throw RpcException(RpcCodec.decode(RpcErrorResponse.serializer(), respBody).message)
        }
        return respType to respBody
    }

    /** Suspende até o par fechar a conexão (uso no lado servidor). */
    suspend fun awaitClose() {
        readerJob.join()
    }

    fun close() {
        conn.close()
        scope.cancel()
    }
}
