package com.mynote.app.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mynote.app.di.AppContainer
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
                onOpenNote = { id -> navController.navigate("edit/$id") },
                onNewNote = { navController.navigate("edit/new") },
                onManageCategories = { navController.navigate("categories") }
            )
        }
        composable("edit/{noteId}") {
            Text("编辑页待实现")
        }
        composable("categories") {
            Text("分类页待实现")
        }
    }
}
