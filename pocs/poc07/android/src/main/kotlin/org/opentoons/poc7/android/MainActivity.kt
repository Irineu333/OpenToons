package org.opentoons.poc7.android

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import org.opentoons.poc7.trama.CampaignVectors
import org.opentoons.poc7.trama.ReaderProbe
import org.opentoons.poc7.trama.Reporter

/**
 * poc-07 baseline 4.2 — o Moto g30 (Kotlin/JVM/ART) rodando o MESMO `ReaderProbe` de commonMain
 * que o iPhone roda em Native. Prova que mover a Trama para `commonMain` não regrediu o caminho
 * JVM/Android. 5 leituras a frio contra a VPS; loga cada uma no Logcat (tag POC07) e reporta à
 * VPS. Sem branch de app: só a fábrica da Trama (idêntico ao desktop/iOS).
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).also { it.textSize = 11f; setContentView(it) }
        val host = "143.95.220.165"
        val port = 6070
        Thread {
            val idHex = CampaignVectors.vpsNodeIdHex
            Log.i("POC07", "POC07-READ-START host=$host port=$port bootstrap=$idHex")
            val sb = StringBuilder("baseline Moto g30 (JVM/ART)\n")
            for (i in 1..5) {
                val s = ReaderProbe.readOnce(host, port, idHex, 15_000)
                val tagged = "run$i " + s.line()
                Log.i("POC07", tagged)
                Reporter.report(host, 6071, "MOTO $tagged\n")
                sb.append(tagged).append('\n')
                runOnUiThread { tv.text = sb.toString() }
            }
            Log.i("POC07", "POC07-READ-DONE")
        }.start()
    }
}
