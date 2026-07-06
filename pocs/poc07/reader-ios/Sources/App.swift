import SwiftUI
import OpenToonsKit

// poc-07 CÉLULA 3 — leitor E2E sobre I2P no iPhone físico. Um router i2pd EMBARCADO in-process
// (libi2pd via cinterop C-ABI) sobe dentro do app: reseed → tunnels → netDB. O leitor DESCOBRE
// a destination `.b32` do servidor pela rede I2P (lookup de LeaseSet, garlic-routed) e baixa o
// capítulo de 768 KiB por um stream I2P, **verificando Ed25519 + sha-256 no device**. Saída para
// stdout (capturada por devicectl --console) e para a tela.
@main
struct Poc07ReaderApp: App {
    var body: some Scene { WindowGroup { ContentView() } }
}

struct ContentView: View {
    @State private var lines: [String] = ["poc-07 célula 3 · I2P · iniciando…"]

    // destination .b32 do servidor de capítulo (i2pd server tunnel → chapter server local).
    private let b32 = "amw6j4ctypuz4pgfkrqvdmeko5cokfhwehtxfgh27l7imo2svyea.b32.i2p"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                Text("poc-07 · célula 3 · leitor I2P (Native)").font(.headline)
                ForEach(lines, id: \.self) { l in
                    Text(l).font(.system(.caption2, design: .monospaced))
                }
            }.padding()
        }.onAppear {
            DispatchQueue.global().async { runI2pReads() }
        }
    }

    private func log(_ s: String) {
        NSLog("%@", s)
        print(s)
        DispatchQueue.main.async { lines.append(s) }
    }

    private func runI2pReads() {
        // 1) datadir gravável + certificados de reseed copiados do bundle
        let fm = FileManager.default
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let datadir = docs.appendingPathComponent("i2pd")
        try? fm.createDirectory(at: datadir, withIntermediateDirectories: true)
        if let bundleCerts = Bundle.main.resourceURL?.appendingPathComponent("certificates") {
            let dest = datadir.appendingPathComponent("certificates")
            if !fm.fileExists(atPath: dest.path) {
                try? fm.copyItem(at: bundleCerts, to: dest)
            }
        }
        // pré-seed do netDb (routerInfos conhecidos-bons) → o router tem peers conectáveis já no boot.
        // Refresh a cada boot (datadir persiste entre instalações): remove o antigo e recopia.
        if let bundleNetDb = Bundle.main.resourceURL?.appendingPathComponent("netDb") {
            let dest = datadir.appendingPathComponent("netDb")
            try? fm.removeItem(at: dest)
            try? fm.copyItem(at: bundleNetDb, to: dest)
        }
        let certCount = (try? fm.contentsOfDirectory(atPath: datadir.appendingPathComponent("certificates/reseed").path).count) ?? 0
        let riCount = (try? fm.subpathsOfDirectory(atPath: datadir.appendingPathComponent("netDb").path).filter { $0.hasSuffix(".dat") }.count) ?? 0
        log("POC07-I2P datadir=\(datadir.path) reseedCerts=\(certCount) preseedNetDb=\(riCount)")

        // 2) sobe o router I2P embarcado e espera ficar pronto (reseed + tunnels; leva minutos)
        log("POC07-I2P subindo router embarcado (reseed + tunnels)…")
        let readyMsg = I2pReaderProbe.shared.startRouter(datadir: datadir.path, readyTimeoutMs: 300000)
        log(readyMsg)

        // 3) leituras E2E sobre I2P: descobre a destination .b32 e baixa+verifica no device
        log("POC07-I2P alvo b32=\(b32)")
        for i in 1...3 {
            let s = I2pReaderProbe.shared.readOnce(b32: b32, connectTimeoutMs: 150000)
            let tagged = "i2p\(i) " + s.line()
            log(tagged)
        }
        log("POC07-I2P-DONE")
    }
}
