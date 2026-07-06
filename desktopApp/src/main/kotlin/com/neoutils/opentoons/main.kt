package com.neoutils.opentoons

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.neoutils.opentoons.data.db.desktopDatabase
import com.neoutils.opentoons.di.AppGraph
import io.github.vinceglb.filekit.FileKit

fun main() {
    // FileKit: base do storage próprio (filesDir) e dos diálogos no desktop (D3/D4).
    FileKit.init(appId = "OpenToons")
    val graph = AppGraph(desktopDatabase())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "OpenToons",
        ) {
            App(graph)
        }
    }
}
