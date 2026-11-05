package com.sharfo.myplans.core.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sharfo.myplans.feature.cad.presentation.CADEditorScreen
import com.sharfo.myplans.feature.home.HomeScreen
import com.sharfo.myplans.feature.preview.PlanPreviewScreen
import com.sharfo.myplans.feature.wizard.WizardScreen

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onCreateNew = { navController.navigate(Screen.Wizard.route) },
                onOpenProject = { projectId -> navController.navigate(Screen.Preview.createRoute(projectId)) }
            )
        }
        composable(Screen.Wizard.route) {
            WizardScreen(
                onBack = { navController.popBackStack() },
                onPlanGenerated = { projectId ->
                    navController.navigate(Screen.Preview.createRoute(projectId)) {
                        popUpTo(Screen.Home.route)
                    }
                }
            )
        }
        composable(
            route = Screen.Preview.route,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType })
        ) {
            PlanPreviewScreen(
                onBack = { navController.popBackStack() },
                onOpenCadEditor = { navController.navigate(Screen.CadEditor.route) }
            )
        }
        composable(Screen.CadEditor.route) {
            CADEditorScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
