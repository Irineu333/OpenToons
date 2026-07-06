package com.neoutils.opentoons.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.neoutils.opentoons.App
import com.neoutils.opentoons.data.db.androidDatabase
import com.neoutils.opentoons.di.AppGraph

/**
 * Host Android do leitor (task 1.2). Monta o [AppGraph] com o database Android; a UI é 100%
 * do módulo `shared`. O FileKit Core auto-inicializa no Android via App Startup, e o picker
 * de compose (`rememberFilePickerLauncher`) obtém o contexto da própria composição.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val graph = AppGraph(androidDatabase(applicationContext))

        setContent {
            App(graph)
        }
    }
}
