package org.opentoons.poc7.node

import org.opentoons.poc7.api.AnnounceTuning
import org.opentoons.poc7.api.ListenSpec
import org.opentoons.poc7.api.MemoryBlockstore
import org.opentoons.poc7.trama.CampaignVectors
import org.opentoons.poc7.trama.TramaBackend
import org.opentoons.poc7.trama.wire.TcpTransport
import java.util.concurrent.CountDownLatch

/**
 * poc-07 — servidor de campanha da VPS (célula 1, clearnet). Levanta um full node Trama que
 * escuta na porta pública, publica o capítulo de 768 KiB ([CampaignVectors]) e se anuncia com
 * o **endereço público** (ADR-0006, endereço manual) para o leitor do iPhone discar por dados
 * móveis. Identidade determinística (`poc7-vps`) para o leitor saber a chave esperada do Noise.
 *
 * Uso: `VpsServerMain <listenPort> <publicHost> [publicPort]`
 */
fun main(args: Array<String>) {
    val listenPort = args.getOrNull(0)?.toIntOrNull() ?: 6070
    val publicHost = args.getOrNull(1) ?: "0.0.0.0"
    val publicPort = args.getOrNull(2)?.toIntOrNull() ?: listenPort
    val advertised = "$publicHost:$publicPort"

    // TTL folgado: campanha real, sem pressa de expiry.
    val tuning = AnnounceTuning(ttlMillis = 10 * 60_000, republishMillis = 15_000)
    val node = TramaBackend.fullNode(
        CampaignVectors.VPS_NODE_SEED, tuning, TcpTransport(listenPort, advertised),
    )
    node.serve(MemoryBlockstore())
    node.start(ListenSpec(listenPort), emptyList())

    val prep = CampaignVectors.prepared()
    node.publish(CampaignVectors.OBRA, prep.manifestBlock, prep.blocks)
    node.announce(CampaignVectors.OBRA)

    val totalBytes = prep.blocks.sumOf { it.bytes.size }
    println("VPS-NODE-UP idHex=${node.idHex} listen=0.0.0.0:$listenPort advertise=$advertised")
    println("VPS-NODE-OBRA obra=${CampaignVectors.OBRA} chapter=${CampaignVectors.CHAPTER_ID} bytes=$totalBytes contentKey=${CampaignVectors.contentKeys.idHex}")
    System.out.flush()

    // fica no ar até morrer (kill).
    CountDownLatch(1).await()
}
