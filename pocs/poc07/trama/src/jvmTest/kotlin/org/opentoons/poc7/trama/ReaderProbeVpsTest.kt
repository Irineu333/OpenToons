package org.opentoons.poc7.trama

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Aferição (D6) do instrumento de leitura CONTRA A VPS REAL (IP público, datacenter) a partir
 * do host — antes de rodar o MESMO `ReaderProbe` no iPhone. Prova que o full node serve o
 * capítulo e que o E2E (dial→resolve→download→verify) fecha ponta a ponta sobre a internet.
 * Requer o nó da VPS no ar (systemd `poc07node`).
 */
class ReaderProbeVpsTest {
    @Test
    fun readsChapterFromVps() {
        val host = "143.95.220.165"
        val port = 6070
        val sample = ReaderProbe.readOnce(host, port, CampaignVectors.vpsNodeIdHex)
        println(sample.line())
        assertTrue(sample.ok, "leitura E2E contra a VPS falhou: ${sample.detail}")
        assertTrue(sample.chapterBytes == 786432, "tamanho do capítulo inesperado: ${sample.chapterBytes}")
    }
}
