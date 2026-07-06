package app.calendarium.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.calendarium.core.ui.theme.Motion
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

    fun editorEdit(eventId: Long, instanceStartMillis: Long): String =
        "editor?eventId=$eventId&calendarId=&start=$instanceStartMillis&end="
}

@Composable
fun CalendariumNavHost(
    startOnboarding: Boolean,
    openEventId: Long = -1L,
    onEventConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()

    val startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.MAIN

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { fadeIn(tween(Motion.DurationMedium)) },
        exitTransition = { fadeOut(tween(Motion.DurationMedium)) },
        popEnterTransition = { fadeIn(tween(Motion.DurationMedium)) },
        popExitTransition = { fadeOut(tween(Motion.DurationMedium)) },
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
                    onOpenEditEvent = { id, instanceStart ->
                        navController.navigate(Routes.editorEdit(id, instanceStart))
                    },
                    openDetailEventId = openEventId,
                    onEventConsumed = onEventConsumed,
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
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Up,
                    tween(Motion.DurationMedium),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Down,
                    tween(Motion.DurationMedium),
                )
            },
        ) {
            EventEditorRoute(onBack = { navController.popBackStack() })
        }
    }
}
