import UIKit
import SwiftUI
import Shared

// Embute o Compose Multiplatform do módulo `shared` (framework `Shared`). O leitor é
// imersivo e desenha a própria chrome, então ignoramos as safe areas.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }
}
