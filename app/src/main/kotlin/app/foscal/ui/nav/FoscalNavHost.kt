package app.foscal.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.foscal.core.ui.theme.Motion
import app.foscal.ui.editor.EventEditorRoute
import app.foscal.ui.event.EventDetailScreen
import app.foscal.ui.home.HomeRoute
import app.foscal.ui.location.LocationPickerRoute
import app.foscal.ui.location.LocationViewerRoute
import app.foscal.ui.onboarding.OnboardingRoute
import app.foscal.ui.permission.PermissionGate
import app.foscal.ui.quickadd.QuickAddRoute
import app.foscal.ui.search.SearchRoute
import kotlin.math.hypot

private const val OnboardingRevealDurationMillis = 1100

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val SEARCH = "search"
    const val QUICK_ADD = "quick_add"

    /** On-demand OpenStreetMap picker. `query` pre-centers the map on any existing location text. */
    const val LOCATION_PICKER = "location_picker?query={query}"

    /** Read-only OpenStreetMap view of an event's location, shown from the detail screen. */
    const val LOCATION_VIEWER = "location_viewer?location={location}"

    fun locationViewer(location: String): String =
        "location_viewer?location=${Uri.encode(location)}"

    /** Back-stack key the picker uses to hand the chosen location back to the editor. */
    const val PICKED_LOCATION_KEY = "picked_location"

    fun locationPicker(query: String): String =
        "location_picker?query=${Uri.encode(query)}"

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
fun FoscalNavHost(
    startOnboarding: Boolean,
    openEventId: Long = -1L,
    openInstanceStartMillis: Long = 0L,
    openQuickAdd: Boolean = false,
    onEventConsumed: () -> Unit = {},
    onQuickAddConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    var onboardingRevealTick by remember { mutableIntStateOf(0) }

    val startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.MAIN

    LaunchedEffect(openQuickAdd, startOnboarding) {
        if (openQuickAdd && !startOnboarding) {
            navController.navigate(Routes.QUICK_ADD) { launchSingleTop = true }
            onQuickAddConsumed()
        }
    }

    // A notification tap carries only the event id; open its detail screen.
    LaunchedEffect(openEventId, openInstanceStartMillis, startOnboarding) {
        if (openEventId > 0L && !startOnboarding) {
            navController.navigate(Routes.detail(openEventId, openInstanceStartMillis)) {
                launchSingleTop = true
            }
            onEventConsumed()
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            enterTransition = { fadeIn(tween(Motion.DurationMedium)) },
            exitTransition = { fadeOut(tween(Motion.DurationMedium)) },
            popEnterTransition = { fadeIn(tween(Motion.DurationMedium)) },
            popExitTransition = { fadeOut(tween(Motion.DurationMedium)) },
        ) {
            composable(
                route = Routes.ONBOARDING,
                exitTransition = {
                    if (targetState.destination.route == Routes.MAIN) {
                        fadeOut(
                            animationSpec = tween(
                                durationMillis = 80,
                                delayMillis = OnboardingRevealDurationMillis - 80,
                            ),
                        )
                    } else {
                        fadeOut(tween(Motion.DurationMedium))
                    }
                },
            ) {
                OnboardingRoute(
                    onContinue = {
                        onboardingRevealTick += 1
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.MAIN,
                enterTransition = {
                    if (initialState.destination.route == Routes.ONBOARDING) {
                        EnterTransition.None
                    } else {
                        fadeIn(tween(Motion.DurationMedium))
                    }
                },
                // The calendar never animates itself away. Everything reachable from here is an
                // overlay that slides over it, and Navigation reads the outgoing screen's
                // transition from *this* destination — so a fade here is the screen underneath
                // blanking out while the overlay is still sliding in, which is the doubled-up
                // motion the editor used to show on save.
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
            ) {
                OnboardingMainReveal(
                    trigger = onboardingRevealTick,
                    onRevealFinished = { onboardingRevealTick = 0 },
                ) {
                    PermissionGate {
                        HomeRoute(
                            onOpenEditor = { calId, start, end ->
                                navController.navigate(Routes.editorNew(calId, start, end))
                            },
                            onOpenSearch = { navController.navigate(Routes.SEARCH) },
                            onOpenEventDetail = { id, instanceStart ->
                                navController.navigate(Routes.detail(id, instanceStart))
                            },
                        )
                    }
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
            // The screen underneath holds still. It used to fade out as this one slid over it and
            // fade back in as it slid away, so opening an editor blanked the page behind it and
            // saving made that page reappear out of nothing — the glitch was two transitions
            // playing at once, not one bad one.
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
        ) { backStackEntry ->
            val pickedLocation by backStackEntry.savedStateHandle
                .getStateFlow<String?>(Routes.PICKED_LOCATION_KEY, null)
                .collectAsStateWithLifecycle()
            EventEditorRoute(
                onBack = { navController.popBackStack() },
                onPickLocation = { query -> navController.navigate(Routes.locationPicker(query)) },
                pickedLocation = pickedLocation,
                onPickedLocationConsumed = {
                    backStackEntry.savedStateHandle[Routes.PICKED_LOCATION_KEY] = null
                },
            )
        }
        composable(
            route = Routes.LOCATION_PICKER,
            arguments = listOf(
                navArgument("query") {
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
            // The screen underneath holds still. It used to fade out as this one slid over it and
            // fade back in as it slid away, so opening an editor blanked the page behind it and
            // saving made that page reappear out of nothing — the glitch was two transitions
            // playing at once, not one bad one.
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
        ) {
            LocationPickerRoute(
                onCancel = { navController.popBackStack() },
                onConfirm = { location ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle?.set(Routes.PICKED_LOCATION_KEY, location)
                    navController.popBackStack()
                },
            )
        }
        composable(
            route = Routes.LOCATION_VIEWER,
            arguments = listOf(
                navArgument("location") {
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
            // The screen underneath holds still. It used to fade out as this one slid over it and
            // fade back in as it slid away, so opening an editor blanked the page behind it and
            // saving made that page reappear out of nothing — the glitch was two transitions
            // playing at once, not one bad one.
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
        ) {
            LocationViewerRoute(onBack = { navController.popBackStack() })
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
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
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
                onOpenLocationMap = { location ->
                    navController.navigate(Routes.locationViewer(location))
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
            // The screen underneath holds still. It used to fade out as this one slid over it and
            // fade back in as it slid away, so opening an editor blanked the page behind it and
            // saving made that page reappear out of nothing — the glitch was two transitions
            // playing at once, not one bad one.
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
        ) {
            QuickAddRoute(onBack = { navController.popBackStack() })
        }
        }
    }
}

@Composable
private fun OnboardingMainReveal(
    trigger: Int,
    onRevealFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (trigger == 0) {
        content()
        return
    }

    val progress = remember(trigger) { Animatable(0f) }
    var revealing by remember(trigger) { mutableStateOf(true) }

    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        revealing = true
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = OnboardingRevealDurationMillis,
                easing = FastOutSlowInEasing,
            ),
        )
        revealing = false
        onRevealFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (revealing) {
                    Modifier.circularReveal(progress.value)
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

private fun Modifier.circularReveal(progress: Float): Modifier =
    drawWithContent {
        val radius = hypot(size.width, size.height) * progress
        val path = Path().apply {
            addOval(
                androidx.compose.ui.geometry.Rect(
                    center = Offset(size.width / 2f, size.height * 0.9f),
                    radius = radius,
                ),
            )
        }
        clipPath(path) {
            this@drawWithContent.drawContent()
        }
    }
