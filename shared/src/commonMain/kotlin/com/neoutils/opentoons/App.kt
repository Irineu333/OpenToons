package com.neoutils.opentoons

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import com.neoutils.opentoons.data.image.ArchiveImageFetcher
import com.neoutils.opentoons.data.image.ArchiveImageKeyer
import com.neoutils.opentoons.di.AppGraph
import com.neoutils.opentoons.ui.detail.DetailScreen
import com.neoutils.opentoons.ui.detail.DetailViewModel
import com.neoutils.opentoons.ui.library.LibraryScreen
import com.neoutils.opentoons.ui.library.LibraryViewModel
import com.neoutils.opentoons.ui.reader.ReaderScreen
import com.neoutils.opentoons.ui.reader.ReaderViewModel
import com.neoutils.opentoons.ui.theme.AppTheme

/**
 * Raiz do leitor OpenToons: tema Material 3 + Coil (fetcher próprio a partir do `.cbz`) +
 * navegação Biblioteca → Detalhe → Leitor (task 7.4). Recebe o [AppGraph] montado por cada
 * plataforma (DB + repositório + fontes + importer).
 */
@Composable
fun App(graph: AppGraph) {
    // Coil lê páginas sob demanda do arquivo próprio (offline, sem rede) — task 6.5.
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(ArchiveImageFetcher.Factory())
                add(ArchiveImageKeyer())
            }
            .build()
    }

    AppTheme {
        val navController = rememberNavController()

        NavHost(navController = navController, startDestination = "library") {
            composable("library") {
                LibraryScreen(
                    viewModel = viewModel { LibraryViewModel(graph) },
                    onOpenWork = { uuid -> navController.navigate("detail/$uuid") },
                )
            }
            composable(
                route = "detail/{uuid}",
                arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
            ) { entry ->
                val uuid = entry.arguments?.read { getStringOrNull("uuid") }.orEmpty()
                DetailScreen(
                    viewModel = viewModel(key = "detail:$uuid") { DetailViewModel(graph, uuid) },
                    onBack = { navController.popBackStack() },
                    onOpenChapter = { chapterId -> navController.navigate("reader/$chapterId") },
                )
            }
            composable(
                route = "reader/{chapterId}",
                arguments = listOf(navArgument("chapterId") { type = NavType.StringType }),
            ) { entry ->
                val chapterId = entry.arguments?.read { getStringOrNull("chapterId") }.orEmpty()
                ReaderScreen(
                    viewModel = viewModel(key = "reader:$chapterId") { ReaderViewModel(graph, chapterId) },
                    onBack = { navController.popBackStack() },
                    onNavigateChapter = { target ->
                        navController.navigate("reader/$target") {
                            popUpTo("reader/$chapterId") { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}
