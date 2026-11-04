package com.sharfo.myplans.core.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Wizard : Screen("wizard")
    data object Preview : Screen("preview/{projectId}") {
        fun createRoute(projectId: Long) = "preview/$projectId"
    }
    data object CadEditor : Screen("cad_editor")
}
