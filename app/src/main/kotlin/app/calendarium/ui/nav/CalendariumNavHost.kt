package app.calendarium.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.calendarium.ui.calendars.CalendarsRoute
import app.calendarium.ui.event.EventDetailRoute
import app.calendarium.ui.home.HomeRoute
import app.calendarium.ui.onboarding.OnboardingRoute
import app.calendarium.ui.permission.PermissionGate

@Composable
fun CalendariumNavHost(startOnboarding: Boolean) {
    val navController = rememberNavController()

    val startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.MAIN

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(
                onContinue = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.MAIN) {
            PermissionGate {
                HomeRoute(
                    onOpenCalendars = { navController.navigate(Routes.CALENDARS) },
                    onOpenEvent = { id -> navController.navigate(Routes.eventDetail(id)) },
                )
            }
        }
        composable(Routes.CALENDARS) {
            CalendarsRoute(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.EVENT_DETAIL,
            arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
        ) {
            EventDetailRoute(onBack = { navController.popBackStack() })
        }
    }
}
