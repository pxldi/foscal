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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.foscal.IntentRoute
import app.foscal.core.ui.theme.Motion
import app.foscal.ui.editor.EventEditorRoute
import app.foscal.ui.event.EventDetailScreen
import app.foscal.ui.feedback.FeedbackEffects
import app.foscal.ui.feedback.LocalSnackbarHostState
import app.foscal.ui.home.HomeRoute
import app.foscal.ui.location.LocationPickerRoute
import app.foscal.ui.location.LocationViewerRoute
import app.foscal.ui.onboarding.OnboardingRoute
import app.foscal.ui.permission.PermissionGate
import app.foscal.ui.quickadd.QuickAddRoute
import app.foscal.ui.search.SearchRoute
import app.foscal.ui.settings.SettingsScreen
import app.foscal.ui.settings.SettingsSection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.hypot

private const val OnboardingRevealDurationMillis = 1100

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val SEARCH = "search"
    const val QUICK_ADD = "quick_add"
    const val SETTINGS = "settings"

    /** One page of Settings. `section` is a [app.foscal.ui.settings.SettingsSection] name. */
    const val SETTINGS_SECTION = "settings/{section}"

    fun settingsSection(section: String): String = "settings/$section"

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
    const val EVENT_EDITOR =
        "editor?eventId={eventId}&copyFrom={copyFrom}&calendarId={calendarId}" +
            "&start={start}&end={end}" +
            "&title={title}&location={location}&description={description}&allDay={allDay}"

    fun editorNew(
        calendarId: Long? = null,
        startMillis: Long? = null,
        endMillis: Long? = null,
        title: String = "",
        location: String = "",
        description: String = "",
        allDay: Boolean = false,
    ): String {
        val cal = calendarId?.toString() ?: ""
        val start = startMillis?.toString() ?: ""
        val end = endMillis?.toString() ?: ""
        return "editor?eventId=&copyFrom=&calendarId=$cal&start=$start&end=$end" +
            "&title=${Uri.encode(title)}&location=${Uri.encode(location)}" +
            "&description=${Uri.encode(description)}&allDay=$allDay"
    }

    fun editorEdit(eventId: Long, instanceStartMillis: Long): String =
        "editor?eventId=$eventId&copyFrom=&calendarId=&start=$instanceStartMillis&end=" +
            "&title=&location=&description=&allDay=false"

    /**
     * The editor opened on a *new* event that starts out as a copy of an existing one.
     *
     * `copyFrom` rather than `eventId` because the distinction is the whole point: the editor
     * reads the source event and then forgets where it came from, so saving writes a second event
     * instead of overwriting the first.
     */
    fun editorCopy(eventId: Long, instanceStartMillis: Long): String =
        "editor?eventId=&copyFrom=$eventId&calendarId=&start=$instanceStartMillis&end=" +
            "&title=&location=&description=&allDay=false"
}

@Composable
fun FoscalNavHost(
    startOnboarding: Boolean,
    route: IntentRoute = IntentRoute.None,
    onRouteConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    var onboardingRevealTick by remember { mutableIntStateOf(0) }

    // The day another app asked for, held here rather than navigated to: the calendar is already
    // on screen, so this moves it instead of pushing anything on top of it.
    var focusDate by remember { mutableStateOf<LocalDate?>(null) }

    // An .ics file another app handed over, waiting on the user to say which calendar it goes to.
    // Saveable because the launch intent is not routed again after a rotation, so this is the only
    // thing keeping the dialog open across one.
    var importIcsUri by rememberSaveable { mutableStateOf<String?>(null) }

    val startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.MAIN

    // Nothing is consumed while onboarding is up, so a request that arrives before the user has
    // finished still lands once they have — the effect re-runs when startOnboarding flips.
    LaunchedEffect(route, startOnboarding) {
        if (startOnboarding) return@LaunchedEffect
        when (route) {
            IntentRoute.None -> return@LaunchedEffect

            IntentRoute.QuickAdd ->
                navController.navigate(Routes.QUICK_ADD) { launchSingleTop = true }

            is IntentRoute.Event ->
                navController.navigate(Routes.detail(route.id, route.instanceStartMillis)) {
                    launchSingleTop = true
                }

            is IntentRoute.EditEvent ->
                navController.navigate(Routes.editorEdit(route.id, route.instanceStartMillis)) {
                    launchSingleTop = true
                }

            is IntentRoute.NewEvent ->
                navController.navigate(
                    Routes.editorNew(
                        startMillis = route.startMillis,
                        endMillis = route.endMillis,
                        title = route.title,
                        location = route.location,
                        description = route.description,
                        allDay = route.allDay,
                    ),
                ) { launchSingleTop = true }

            is IntentRoute.ImportIcs -> {
                importIcsUri = route.uri
                navController.popBackStack(Routes.MAIN, inclusive = false)
            }

            is IntentRoute.Day -> {
                focusDate = Instant.ofEpochMilli(route.millis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                // Whatever is stacked over the calendar would hide the day we were asked for.
                navController.popBackStack(Routes.MAIN, inclusive = false)
            }
        }
        onRouteConsumed()
    }

    // Created here, above every destination, so a message outlives the screen that posted it.
    val snackbarHost = remember { SnackbarHostState() }
    FeedbackEffects(snackbarHost, hiltViewModel())

    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHost) {
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
                            focusDate = focusDate,
                            onFocusDateConsumed = { focusDate = null },
                            importIcsUri = importIcsUri,
                            onImportIcsFinished = { importIcsUri = null },
                            onOpenEditor = { calId, start, end ->
                                navController.navigate(Routes.editorNew(calId, start, end))
                            },
                            onOpenSearch = { navController.navigate(Routes.SEARCH) },
                            onOpenEventDetail = { id, instanceStart ->
                                navController.navigate(Routes.detail(id, instanceStart))
                            },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        )
                    }
                }
            }
            composable(
            route = Routes.EVENT_EDITOR,
            arguments = listOf(
                navArgument("copyFrom") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
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
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
                navArgument("location") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
                navArgument("description") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
                navArgument("allDay") {
                    type = NavType.StringType
                    defaultValue = "false"
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
                // The detail screen under an edit would re-read the event, which is still in the
                // provider until the Undo runs out, and show it as though nothing had happened.
                onDeleted = {
                    if (!navController.popBackStack(Routes.EVENT_DETAIL, inclusive = true)) {
                        navController.popBackStack()
                    }
                },
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
        // A destination rather than a flag on the home screen. As a flag it had no back stack
        // entry, so the system back gesture found nothing to pop and closed the app instead of
        // returning to the calendar.
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
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
        ) {
            SettingsScreen(
                section = null,
                onOpenSection = { navController.navigate(Routes.settingsSection(it.name)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.SETTINGS_SECTION,
            arguments = listOf(navArgument("section") { type = NavType.StringType }),
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
            SettingsScreen(
                section = SettingsSection.fromName(backStackEntry.arguments?.getString("section")),
                onOpenSection = { navController.navigate(Routes.settingsSection(it.name)) },
                onBack = { navController.popBackStack() },
            )
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
                onDuplicate = { id, instanceStart ->
                    navController.navigate(Routes.editorCopy(id, instanceStart))
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
