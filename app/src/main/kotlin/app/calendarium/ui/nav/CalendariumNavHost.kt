package app.calendarium.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.calendarium.core.ui.theme.Motion
import app.calendarium.ui.editor.EventEditorRoute
import app.calendarium.ui.event.EventDetailScreen
import app.calendarium.ui.home.HomeRoute
import app.calendarium.ui.onboarding.OnboardingRoute
import app.calendarium.ui.permission.PermissionGate
import app.calendarium.ui.quickadd.QuickAddRoute
import app.calendarium.ui.search.SearchRoute
import app.calendarium.ui.settings.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val QUICK_ADD = "quick_add"

    /** Full-screen event detail. `start` selects the tapped occurrence of a recurring event. */
    const val EVENT_DETAIL = "detail?eventId={eventId}&start={start}"

    fun detail(eventId: Long, instanceStartMillis: Long = 0L): String =
        "detail?eventId=$eventId&start=$instanceStartMillis"

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
    openQuickAdd: Boolean = false,
    onEventConsumed: () -> Unit = {},
    onQuickAddConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()

    val startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.MAIN

    LaunchedEffect(openQuickAdd, startOnboarding) {
        if (openQuickAdd && !startOnboarding) {
            navController.navigate(Routes.QUICK_ADD) { launchSingleTop = true }
            onQuickAddConsumed()
        }
    }

    // A notification tap carries only the event id; open its detail screen.
    LaunchedEffect(openEventId, startOnboarding) {
        if (openEventId > 0L && !startOnboarding) {
            navController.navigate(Routes.detail(openEventId)) { launchSingleTop = true }
            onEventConsumed()
        }
    }

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
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenEventDetail = { id, instanceStart ->
                        navController.navigate(Routes.detail(id, instanceStart))
                    },
                )
            }
        }
        composable(
            route = Routes.SETTINGS,
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    tween(Motion.DurationMedium),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(Motion.DurationMedium),
                )
            },
        ) {
            SettingsScreen(onBack = { navController.popBackStack() })
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
        composable(Routes.SEARCH) {
            SearchRoute(
                onBack = { navController.popBackStack() },
                onOpenEventDetail = { id, instanceStart ->
                    navController.navigate(Routes.detail(id, instanceStart))
                },
            )
        }
        composable(
            route = Routes.EVENT_DETAIL,
            arguments = listOf(
                navArgument("eventId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("start") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    tween(Motion.DurationMedium),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(Motion.DurationMedium),
                )
            },
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getLong("eventId") ?: -1L
            val start = backStackEntry.arguments?.getLong("start") ?: 0L
            EventDetailScreen(
                eventId = eventId,
                instanceStartMillis = start,
                onBack = { navController.popBackStack() },
                onEdit = { id, instanceStart ->
                    navController.navigate(Routes.editorEdit(id, instanceStart))
                },
            )
        }
        composable(
            route = Routes.QUICK_ADD,
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
            QuickAddRoute(onBack = { navController.popBackStack() })
        }
    }
}
