@file:OptIn(ExperimentalForeignApi::class)

package org.opentoons.poc7.trama

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import org.opentoons.poc7.api.ChapterVerifier
import org.opentoons.poc7.api.ContentId
import org.opentoons.poc7.api.ManifestCodec
import poc07i2p.poc07_i2p_close
import poc07i2p.poc07_i2p_connect
import poc07i2p.poc07_i2p_ready
import poc07i2p.poc07_i2p_recv
import poc07i2p.poc07_i2p_send
import poc07i2p.poc07_i2p_start
import kotlin.time.TimeSource

/**
 * poc-07 célula 3 (E2E I2P no iPhone) — o TERCEIRO caminho de leitura, agora sobre **I2P**:
 * um router i2pd EMBARCADO in-process (libi2pd via cinterop C-ABI, o MESMO mecanismo da libp2p)
 * sobe dentro do app (reseed → tunnels → netDB), DESCOBRE a destination `.b32` do servidor pela
 * rede I2P (lookup de LeaseSet, garlic-routed) e baixa o capítulo por um stream I2P. O verify
 * Ed25519+sha256 continua FORA do seam (o MESMO `ChapterVerifier`), no device.
 *
 * Protocolo de aplicação sobre o stream I2P (o servidor é um i2pd server tunnel → TCP local):
 *   envia "M\n"      → [u32 BE len][manifesto]
 *   envia "B<cid>\n" → [u32 BE len][bloco]
 *   envia "Q\n"      → fim
 */
@OptIn(ExperimentalForeignApi::class)
object I2pReaderProbe {

    /** Sobe o router I2P (idempotente) e espera ficar pronto (tunnels de saída + leaseset local). */
    fun startRouter(datadir: String, readyTimeoutMs: Long): String {
        val rc = poc07_i2p_start(datadir)
        if (rc != 0) return "POC07-I2P start falhou rc=$rc"
        var waited = 0L
        while (poc07_i2p_ready() == 0 && waited < readyTimeoutMs) {
            blockingSleep(1000); waited += 1000
        }
        return if (poc07_i2p_ready() == 1) "POC07-I2P router pronto em ${waited}ms" else "POC07-I2P router NÃO ficou pronto em ${readyTimeoutMs}ms"
    }

    /** Uma leitura E2E sobre I2P: connect (descoberta do LeaseSet) → fetch → verify no device. */
    fun readOnce(b32: String, connectTimeoutMs: Int = 120_000): ReadSample {
        val clock = TimeSource.Monotonic
        val t0 = clock.markNow()
        val h = poc07_i2p_connect(b32, connectTimeoutMs)
            ?: return ReadSample(false, "connect/leaseset I2P falhou", -1, -1, -1, -1, t0.elapsedNow().inWholeMilliseconds, 0)
        val connectMs = t0.elapsedNow().inWholeMilliseconds
        return try {
            val tContent = clock.markNow()
            send(h, "M")
            val manifestBlock = readFrame(h)
                ?: return fail("sem manifesto sobre I2P", connectMs, t0)
            val ttfbMs = tContent.elapsedNow().inWholeMilliseconds

            val decoded = ManifestCodec.decode(manifestBlock)
            val tDl = clock.markNow()
            val blocks = ArrayList<ByteArray>(decoded.manifest.blockCids.size)
            for (cid in decoded.manifest.blockCids) {
                send(h, "B$cid")
                blocks.add(readFrame(h) ?: return fail("bloco $cid ausente sobre I2P", connectMs, t0))
            }
            val downloadMs = tDl.elapsedNow().inWholeMilliseconds
            send(h, "Q")

            val tV = clock.markNow()
            val result = ChapterVerifier(CampaignVectors.CONTENT_PUB_KEY_HEX).verify(manifestBlock, blocks)
            val verifyMs = tV.elapsedNow().inWholeMilliseconds
            val totalMs = t0.elapsedNow().inWholeMilliseconds

            when (result) {
                is ChapterVerifier.Result.Verified -> ReadSample(
                    true, "verified/${result.manifest.chapterId}", connectMs, ttfbMs, downloadMs, verifyMs, totalMs, result.chapter.size,
                )
                else -> ReadSample(false, "verify=$result", connectMs, ttfbMs, downloadMs, verifyMs, totalMs, 0)
            }
        } catch (e: Throwable) {
            ReadSample(false, "erro: ${e::class.simpleName}: ${e.message}", connectMs, -1, -1, -1, t0.elapsedNow().inWholeMilliseconds, 0)
        } finally {
            poc07_i2p_close(h)
        }
    }

    private fun fail(msg: String, connectMs: Long, t0: TimeSource.Monotonic.ValueTimeMark) =
        ReadSample(false, msg, connectMs, -1, -1, -1, t0.elapsedNow().inWholeMilliseconds, 0)

    private fun send(h: CPointer<*>, line: String) {
        val b = (line + "\n").encodeToByteArray()
        b.usePinned { poc07_i2p_send(h.reinterpret(), it.addressOf(0).reinterpret(), b.size.convert()) }
    }

    /** Lê um frame [u32 BE len][payload] do stream I2P (recv em laço até completar). */
    private fun readFrame(h: CPointer<*>): ByteArray? {
        val lenBytes = recvFully(h, 4) ?: return null
        val n = ((lenBytes[0].toInt() and 0xff) shl 24) or ((lenBytes[1].toInt() and 0xff) shl 16) or
            ((lenBytes[2].toInt() and 0xff) shl 8) or (lenBytes[3].toInt() and 0xff)
        if (n < 0 || n > 8 * 1024 * 1024) return null
        if (n == 0) return ByteArray(0)
        return recvFully(h, n)
    }

    private fun recvFully(h: CPointer<*>, n: Int): ByteArray? {
        val out = ByteArray(n)
        var off = 0
        var idle = 0
        while (off < n) {
            val remaining = n - off
            val chunk = ByteArray(remaining)
            val got = chunk.usePinned {
                poc07_i2p_recv(h.reinterpret(), it.addressOf(0).reinterpret(), remaining.convert(), 30)
            }.toInt()
            if (got < 0) return null
            if (got == 0) { // timeout de 30s sem bytes; tolera 2 janelas antes de desistir
                idle++; if (idle >= 2) return null else continue
            }
            idle = 0
            chunk.copyInto(out, off, 0, got)
            off += got
        }
        return out
    }
}
