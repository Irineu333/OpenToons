import SwiftUI
import Probe

// poc-07 portão 2.1 — casca mínima que chama o Kotlin/Native. Se esta linha executa no
// iPhone físico, o binário Kotlin/Native instalou e RODOU: risco #1 (skew Xcode 16.4 ×
// iOS 26.5) respondido empiricamente, sem simulador. A saída vai para stdout (capturada
// pelo console do devicectl) e para a tela.
@main
struct Poc07ProbeApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    @State private var result: String = "chamando Kotlin/Native…"

    var body: some View {
        VStack(spacing: 12) {
            Text("poc-07 · portão 2.1").font(.headline)
            Text(result).font(.system(.footnote, design: .monospaced))
                .multilineTextAlignment(.center).padding()
        }
        .onAppear {
            // Probe é `object Probe` no Kotlin → `Probe.shared` no Swift.
            let line = Probe.shared.hello()
            NSLog("%@", line)          // os_log
            print(line)                 // stdout — capturado pelo devicectl --console
            // Spike 2.2: mesma bateria de crypto, agora via provider CryptoKit no device.
            let crypto = CryptoSpike.shared.run()
            NSLog("%@", crypto)
            print(crypto)
            result = line + "\n" + crypto
            // Spike 2.3: dial TCP real do device ao echo da VPS (IP público) via ktor-network.
            DispatchQueue.global().async {
                let sock = SocketSpike.shared.run(host: "143.95.220.165", port: 5599)
                NSLog("%@", sock)
                print(sock)
                let ffi = Ffi.shared.run()
                NSLog("%@", ffi)
                print(ffi)
                DispatchQueue.main.async { result += "\n" + sock + "\n" + ffi }
            }
        }
    }
}
