package com.mynote.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mynote.app.di.AppContainer
import com.mynote.app.ui.categories.CategoriesScreen
import com.mynote.app.ui.notes.NoteEditScreen
import com.mynote.app.ui.notes.NotesScreen
import com.mynote.app.ui.notes.NotesViewModel

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "notes") {
        composable("notes") {
            val vm: NotesViewModel = viewModel(factory = NotesViewModel.factory(container.noteRepository))
            NotesScreen(
                viewModel = vm,
                backupManager = container.backupManager,
                onOpenNote = { id -> navController.navigate("edit/$id") },
                onNewNote = { navController.navigate("edit/new") },
                onManageCategories = { navController.navigate("categories") }
            )
        }
        composable("edit/{noteId}") { backStack ->
            val idArg = backStack.arguments?.getString("noteId")
            val id = idArg?.takeIf { it != "new" }?.toLongOrNull()
            NoteEditScreen(
                noteId = id,
                repository = container.noteRepository,
                imageStore = container.imageStore,
                backupManager = container.backupManager,
                onBack = { navController.popBackStack() }
            )
        }
        composable("categories") {
            CategoriesScreen(
                repository = container.noteRepository,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
