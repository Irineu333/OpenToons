package org.opentoons.poc2.node

import org.opentoons.poc2.core.NodeIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 7.1 (mecânica, em localhost) — o MESMO ciclo do E4 sem o endereço público: bootstrap +
 * publicador formam malha por dials de saída; o cliente, conhecendo SÓ o bootstrap e o
 * obraId, descobre o publicador (nunca informado), baixa, verifica e reconstrói.
 * A variante pelo caminho público real (port forwarding + dispositivo em outra rede)
 * usa exatamente este código via Main.kt.
 */
class E2eLocalTest {

    @Test
    fun `descoberta fria + download verificado ponta a ponta em localhost`() {
        val bootId = NodeIdentity.fromSeed("poc2-boot".toByteArray())
        val pubId = NodeIdentity.fromSeed("poc2-pub".toByteArray())

        FullNode(bootId, "127.0.0.1:0", 0, meshIntervalMs = 500).use { bootstrap ->
            FullNode(
                pubId, "127.0.0.1:0", 0,
                bootstrap = listOf(bootstrap.self),
                meshIntervalMs = 500,
            ).use { publisher ->
                publisher.publishChapter(
                    TestChapter.OBRA_ID, TestChapter.CHAPTER_ID, seq = 7, pages = TestChapter.PAGES,
                )
                publisher.meshNow() // HELLO + PEX + anúncio chegam ao bootstrap

                // cliente frio: só bootstrap + obraId; identidade efêmera
                val client = ClientSession(NodeIdentity.generate())
                val discovery = client.coldDiscover(bootstrap.self, TestChapter.OBRA_ID)

                assertTrue(discovery.providers.isNotEmpty(), "descoberta fria não achou providers")
                assertEquals(publisher.self.idHex, discovery.providers.first().idHex, "provider ≠ publicador")
                assertTrue(discovery.rtts <= 3, "lookup frio custou ${discovery.rtts} RTTs (> 3, limiar D5)")

                val chapter = client.fetchVerified(
                    discovery.providers.first(), TestChapter.CHAPTER_ID, pubId.publicKey,
                )
                assertEquals(3, chapter.size)
                chapter.zip(TestChapter.PAGES).forEach { (got, expected) ->
                    assertContentEquals(expected, got)
                }
            }
        }
    }
}
