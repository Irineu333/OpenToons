package org.opentoons.poc2.node

import org.opentoons.poc2.core.NodeIdentity
import org.opentoons.poc2.core.hexToBytes
import org.opentoons.poc2.core.toHex
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.opentoons.poc2.rpc.ChapterService
import org.opentoons.poc2.tls.TlsChannel
import org.opentoons.poc2.tls.TlsIdentity
import kotlin.system.measureNanoTime

/**
 * E4 — mains da PoC (mesma infra do poc-01: endereço público manual via port forwarding).
 *
 * Nó publicador (na máquina com porta pública):
 *   ./gradlew :poc02:net:run --args="node --seed=pub --listen=4101 --public=SEU_IP:4101 --publish"
 * Nó bootstrap (pode ser outro processo/porta na mesma máquina):
 *   ./gradlew :poc02:net:run --args="node --seed=boot --listen=4100 --public=SEU_IP:4100 --peer=<id-do-pub>@SEU_IP:4101"
 * Cliente de teste JVM (de qualquer rede):
 *   ./gradlew :poc02:net:run --args="fetch --bootstrap=<id-do-boot>@SEU_IP:4100 --obra=opentoons/serie-teste --publisher=<id-do-pub>"
 * Servidor de eco TLS para o E1a no dispositivo (task 2.3/2.5):
 *   ./gradlew :poc02:net:run --args="tls-echo --seed=tls --listen=4102"
 *
 * Identidades determinísticas por --seed (técnica do E5 do poc-01): o idHex é derivável
 * e imprimível antes de subir o resto da malha.
 */
object TestChapter {
    const val OBRA_ID = "opentoons/serie-teste"
    const val CHAPTER_ID = "opentoons/serie-teste/cap-001"
    val PAGES = listOf(
        ByteArray(256 * 1024) { (it % 251).toByte() },
        ByteArray(256 * 1024) { (it % 241).toByte() },
        ByteArray(256 * 1024) { (it % 239).toByte() },
    )
}

private fun args2map(args: Array<String>): Map<String, String> =
    args.filter { it.startsWith("--") }.associate {
        val kv = it.removePrefix("--").split("=", limit = 2)
        kv[0] to kv.getOrElse(1) { "true" }
    }

fun main(args: Array<String>) {
    val opts = args2map(args)
    when (args.firstOrNull()) {
        "node" -> runNode(opts)
        "fetch" -> runFetch(opts)
        "tls-echo" -> runTlsEcho(opts)
        "id" -> println(NodeIdentity.fromSeed("poc2-${opts.getValue("seed")}".toByteArray()).idHex)
        else -> {
            System.err.println("uso: node|fetch|tls-echo|id (ver comentário em Main.kt)")
        }
    }
}

private fun identityFor(opts: Map<String, String>): NodeIdentity =
    NodeIdentity.fromSeed("poc2-${opts.getValue("seed")}".toByteArray())

private fun runNode(opts: Map<String, String>) {
    val identity = identityFor(opts)
    val peers = opts["peer"]?.split(",")?.map { FullNode.NodeAddress.parse(it) }.orEmpty()
    val node = FullNode(
        identity = identity,
        publicAddress = opts.getValue("public"),
        listenPort = opts.getValue("listen").toInt(),
        bootstrap = peers,
    )
    if (opts.containsKey("publish")) {
        node.publishChapter(TestChapter.OBRA_ID, TestChapter.CHAPTER_ID, seq = 7, pages = TestChapter.PAGES)
        println("publicado ${TestChapter.CHAPTER_ID} (obra ${TestChapter.OBRA_ID})")
    }
    println("nó pleno no ar: ${node.self}")
    Thread.currentThread().join() // roda até ser morto
}

private fun runFetch(opts: Map<String, String>) {
    val client = ClientSession(NodeIdentity.generate()) // cliente: identidade efêmera
    val bootstrap = FullNode.NodeAddress.parse(opts.getValue("bootstrap"))
    val obraId = opts["obra"] ?: TestChapter.OBRA_ID
    val publisherKey = Ed25519PublicKeyParameters(opts.getValue("publisher").hexToBytes(), 0)

    val discovery: ClientSession.Discovery
    val discoverNanos = measureNanoTime { discovery = client.coldDiscover(bootstrap, obraId) }
    check(discovery.providers.isNotEmpty()) { "descoberta fria não retornou providers" }
    println("DESCOBERTA OK: ${discovery.providers} em ${discovery.rtts} RTTs (${discoverNanos / 1_000_000} ms)")

    val chapter: List<ByteArray>
    val fetchNanos = measureNanoTime {
        chapter = client.fetchVerified(discovery.providers.first(), TestChapter.CHAPTER_ID, publisherKey)
    }
    check(chapter.size == TestChapter.PAGES.size)
    chapter.zip(TestChapter.PAGES).forEach { (got, expected) -> check(got.contentEquals(expected)) }
    println("ASSINATURA OK → CAPÍTULO RECONSTRUÍDO (${chapter.size} páginas) em ${fetchNanos / 1_000_000} ms")

    // rejeição local (mecanismo idêntico ao do dispositivo)
    val tampered = chapter.first().copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    check(ChapterService.cidOf(tampered) != ChapterService.cidOf(chapter.first()))
    println("REJEIÇÃO OK (bloco adulterado) → E4 OK")
}

/** Eco TLS para medir handshake/resumption do E1a no dispositivo físico. */
private fun runTlsEcho(opts: Map<String, String>) {
    val identity = identityFor(opts)
    println("identidade do eco TLS: ${identity.idHex}")
    val server = TlsChannel.Server(
        opts.getValue("listen").toInt(),
        TlsIdentity.boundCert(identity),
    ) { conn, peer ->
        println("cliente autenticado: ${peer.encoded.toHex()}")
        while (true) {
            val frame = conn.receive() ?: break
            conn.send(frame)
        }
    }
    println("eco TLS na porta ${server.port}")
    Thread.currentThread().join()
}
