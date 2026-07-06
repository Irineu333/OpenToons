package com.neoutils.opentoons

import androidx.compose.ui.window.ComposeUIViewController
import com.neoutils.opentoons.data.db.iosDatabase
import com.neoutils.opentoons.di.AppGraph
import platform.UIKit.UIViewController

/**
 * Entry point do leitor no iOS (task 1.2). O app Swift/Xcode chama [MainViewController] e
 * embute o `UIViewController` resultante. O [AppGraph] usa o database no diretório Documents;
 * o FileKit no iOS resolve `filesDir`/diálogos sem init explícito.
 */
fun MainViewController(): UIViewController {
    val graph = AppGraph(iosDatabase())
    return ComposeUIViewController { App(graph) }
}
