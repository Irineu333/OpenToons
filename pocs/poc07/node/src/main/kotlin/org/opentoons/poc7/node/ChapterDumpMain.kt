package org.opentoons.poc7.node

import org.opentoons.poc7.trama.CampaignVectors
import java.io.File

/**
 * poc-07 célula 2 — despeja o MESMO capítulo de campanha (manifesto assinado + blocos por CID
 * da CampaignVectors) em arquivos, para o servidor libp2p (rust) servir. Assim o conteúdo é
 * idêntico ao servido pela Trama, e o leitor iOS verifica com a MESMA pubkey do publicador.
 *
 * Uso: `ChapterDumpMain <dir>` → escreve manifest.bin + block_<cid>.bin
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "chapter" }).apply { mkdirs() }
    val prep = CampaignVectors.prepared()
    File(dir, "manifest.bin").writeBytes(prep.manifestBlock)
    prep.blocks.forEach { b -> File(dir, "block_${b.id.hex}.bin").writeBytes(b.bytes) }
    println("CHAPTER-DUMP dir=${dir.absolutePath} obra=${CampaignVectors.OBRA} manifest=${prep.manifestBlock.size}B blocks=${prep.blocks.size}")
    prep.blocks.forEach { println("  cid=${it.id.hex} bytes=${it.bytes.size}") }
}
