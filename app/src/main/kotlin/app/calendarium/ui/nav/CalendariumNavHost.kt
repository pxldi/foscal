package app.calendarium.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.calendarium.ui.editor.EventEditorRoute
import app.calendarium.ui.home.HomeRoute
import app.calendarium.ui.onboarding.OnboardingRoute
import app.calendarium.ui.permission.PermissionGate

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"

    /**
     * Editor supports both new and edit. `eventId`, `calendarId`, `start`, `end` are all
     * optional; if `eventId` is set it's edit mode, otherwise new with the given pre-fills.
     */
    const val EVENT_EDITOR = "editor?eventId={eventId}&calendarId={calendarId}&start={start}&end={end}"

    fun editorNew(
        calendarId: Long? = null,
        startMillis: Long? = null,
        endMillis: Long? = null,
    ): String {
        val cal = calendarId?.toString() ?: ""
        val start = startMillis?.toString() ?: ""
        val end = endMillis?.toString() ?: ""
        return "editor?eventId=&calendarId=$cal&start=$start&end=$end"
    }

    fun editorEdit(eventId: Long): String = "editor?eventId=$eventId&calendarId=&start=&end="
}

@Composable
fun CalendariumNavHost(startOnboarding: Boolean, openEventId: Long = -1L) {
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
                    onOpenEditor = { calId, start, end ->
                        navController.navigate(Routes.editorNew(calId, start, end))
                    },
                    onOpenEditEvent = { id -> navController.navigate(Routes.editorEdit(id)) },
                    initialDetailEventId = openEventId,
                )
            }
        }
        composable(
            route = Routes.EVENT_EDITOR,
            arguments = listOf(
                navArgument("eventId") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
                navArgument("calendarId") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
                navArgument("start") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
                navArgument("end") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
            ),
        ) {
            EventEditorRoute(onBack = { navController.popBackStack() })
        }
    }
}
