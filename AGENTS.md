# AGENTS.md — guidance for AI assistants working on this repo

## Toolchain

**Do not set `JAVA_HOME` or `ANDROID_HOME` yourself — the environment already provides
them, and overriding them is how you break the build.** Run `./gradlew` directly.

Earlier revisions of this file hardcoded a toolchain under `/home/pxldi/.local/android-dev/`.
That path does not exist in the current dev container (JDK 17 is at `/usr/local/jdk-17`, the
SDK at `/usr/local/android-sdk`), and exporting the old paths fails *deceptively*: Gradle
rejects the bogus `JAVA_HOME` outright, while a bare `java -version` still appears to work
because it falls back to the system `java` on `PATH`. If you must confirm the toolchain,
echo the variables rather than assuming a location:

```bash
echo "$JAVA_HOME $ANDROID_HOME"   # /usr/local/jdk-17 /usr/local/android-sdk
```

Two consequences of how the SDK is mounted:

- **It is read-only.** Gradle cannot install SDK components on demand, which is why
  `buildToolsVersion` is pinned in every Android module (see Build configuration).
- **`sdkmanager` is absent** from `cmdline-tools/latest/bin`, so missing components have to
  be added to the image rather than fetched from a build.

`local.properties` is absent and not needed; `ANDROID_HOME` covers it.

## Commands

- **Build debug APK:** `./gradlew :app:assembleDebug`
- **Run all checks (lint + tests):** `./gradlew check`
- **Lint only:** `./gradlew lint`
- **Unit tests:** `./gradlew test`
- **Install on device:** `./gradlew :app:installDebug`
- **Format / verify Kotlin style:** the project uses `kotlin.code.style=official`,
  so formatting follows the official Kotlin conventions; no ktlint/detekt is
  wired up yet.
- **What CI actually runs on a pull request:**
  `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest :core:core-model:test`.
  Run exactly that before pushing — it is one Gradle invocation on purpose, and it is the whole
  gate. The release variant is *not* built on pull requests; it runs on `main`, on tags, and via
  `workflow_dispatch`. The runner is a single memory-constrained machine shared with everything else
  on it, so keep the PR job to one invocation, leave `--max-workers=2` alone, and do not add steps
  that build a second variant. `concurrency.cancel-in-progress` means a force-push supersedes the
  older run rather than queueing behind it.
- **Never put `[skip ci]` in a commit that a release tag will point at.** GitHub evaluates skip
  directives per *commit*, not per ref, so the tag push inherits the skip and `release.yml` never
  runs — no APK, no GitHub Release, and no failed run to notice. Tempting on a version bump, whose
  tree CI has already passed; it costs the release instead. Recover by dispatching `release.yml`
  with the tag as input rather than by moving the tag.
- **Nothing ever launches the release APK.** CI assembles it and publishes it; no job installs it or
  starts it. Debug builds do not minify, so an R8 mistake is invisible right up until a user opens
  the shipped app. v0.9.0 crashed on every launch for exactly this reason. When a change adds a
  library that resolves anything by name — reflection, a Room database, a service loader — assume
  R8 will strip it and check `app/build/outputs/apk/release/*.apk` with `dexdump` before tagging,
  or install the APK on a device.

## R8 and `proguard-rules.pro`

R8 **full mode** is on (it is the default from AGP 8; nothing in `gradle.properties` turns it off).
The trap is that under full mode a bare `-keep class Foo` keeps the class but **not its members** —
compat mode used to retain the default constructor implicitly, full mode does not. Library consumer
rules written before the switch are therefore quietly wrong, and Room 2.6.1 ships exactly such a
rule, which is what broke v0.9.0. When adding a keep rule, write the member spec you actually need
(`{ <init>(); }`, `{ *; }`) rather than relying on the class-level keep to imply it.

## Build configuration

AGP 9 with **built-in Kotlin support**. Consequences worth knowing before editing a
`build.gradle.kts`:

- **Do not apply `org.jetbrains.kotlin.android`** in `:app`, `:core:core-ui`, or
  `:core:core-data`. AGP supplies Kotlin itself and the plugin is incompatible with the
  new DSL; applying it fails the build outright. `:core:core-model` is a plain JVM module
  and still uses `kotlin-jvm`. The Compose compiler plugin *is* still applied separately.
- **`jvmTarget` lives in `android { kotlin { compilerOptions { … } } }`.** The old
  top-level `kotlinOptions { }` block is gone.
- **`buildToolsVersion` is pinned to `37.0.0`** in `:app`, `:core:core-ui` and
  `:core:core-data`. AGP's default build-tools version trails `compileSdk`, so with the
  pin removed it tries to auto-install `build-tools;36.0.0` and the build dies with
  "The SDK directory is not writable" on any read-only or offline SDK. Keep the pin in
  step with what the image actually ships (`ls $ANDROID_HOME/build-tools`).
- **`compileSdk` is 37, `targetSdk` is 36.** They are deliberately different: the newest
  androidx libraries require compiling against 37, while 36 is what the app has actually
  been tested against for runtime behavior. Bumping `targetSdk` opts into Android 17
  behavior changes and must be a deliberate, separately verified change.
- **`lint` is part of the definition of done and currently passes with zero errors.**
  Newer AGP lint checks are strict; in particular `NonObservableLocale` will reject any
  `Locale.getDefault()` read inside a composable (see the locale note below).

Always run `./gradlew assembleDebug` and `./gradlew lint` after non-trivial
changes. Do not commit code that does not build or that fails lint.

## Testing on the emulator

The emulator runs **outside** the dev container and is reached over adb — there is no
`emulator` binary or system image inside the image, so you cannot start or create an AVD
from here. `adb devices` is the authority on whether one is attached.

- **Verify the device before trusting coordinates.** `adb shell wm size` reports both a physical
  size and an *override*, and the override is what taps and dumps are in — the current AVD is
  320x640 physical but 1080x1920 override at density 420. Reading the physical line sends every
  tap to a third of the screen. Always run it first; a stale factor silently sends every tap to
  the wrong widget.
- **`screencap` returns a black frame on this AVD, but `uiautomator dump` works.** There is no
  point diagnosing the black PNG: use the hierarchy instead. It carries every label and its
  bounds, which is enough to drive the UI *and* to measure it — a title that wrapped is twice as
  tall as one that did not, and a text scale change shows up as a wider node. Note that the `text`
  it reports for an ellipsised label is the *laid out* text, not the original string, which is
  itself a usable signal.
- **`input swipe` cannot produce a long-press drag** — it moves past touch slop before the press
  becomes long. Use `input motionevent DOWN x y`, then separate `MOVE` calls (each round trip is
  well over the long-press timeout), then `UP`.
- **No KVM, so it is slow.** `am start` returns immediately while the app is still starting;
  the first frame took ~20s. Poll `dumpsys window | grep mCurrentFocus` until it names the
  activity instead of screenshotting straight away, or you will capture the launcher and
  read it as a crash. `adb shell pidof app.foscal` distinguishes "still rendering" from
  "died".
- **`sys.boot_completed` reads empty on this image even when boot is done.** Use
  `dev.bootcomplete`, or just check that `pm list packages` answers.
- Reproduce first-run: `adb shell pm clear app.foscal` then
  `adb shell pm revoke app.foscal android.permission.READ_CALENDAR` (+WRITE_CALENDAR,
  POST_NOTIFICATIONS). Grant back with `pm grant`.
- Inspect provider state directly with `adb shell content query/insert/update/delete --uri
  content://com.android.calendar/{events,instances/when/<from>/<to>,reminders,calendars,exception/<id>}`.
- **Reminder scheduling is only verifiable at runtime**, since the alarm path is
  `AlarmManager` + receivers rather than anything a JVM test can reach:

  ```bash
  adb shell dumpsys alarm | grep -A3 foscal      # what is actually armed, and when
  adb shell cmd deviceidle force-idle            # force doze; `unforce` to release
  adb shell am get-standby-bucket app.foscal     # 10=active … 45=restricted
  adb shell am set-standby-bucket app.foscal restricted
  adb shell am broadcast -a android.intent.action.MY_PACKAGE_REPLACED -p app.foscal
  ```

  An empty `dumpsys alarm` grep means nothing is armed — treat it as a failure signal, not
  as "probably fine".

## Current status

Beta. Working: Month / Week / Agenda / Settings tabs (bottom nav — Settings is a
tab in `HomeScreen`'s `AnimatedContent`, not a separate nav destination; the
selected tab is `rememberSaveable` so returning from detail/editor preserves the
current tab), event create/edit/delete, recurring events
(this-vs-all-events, exceptions), reminders/notifications, attendees with a
join-video-call action, real calendar colors,
offline local calendars, `.ics` import/export via the system document picker,
permission-first onboarding. Week view is the shared
hourly `TimelineLayout` with long-press drag-to-create (snapped to ten minutes, with a
pill above the block naming the range), tap-to-park-then-tap-to-open for a
default-length event, and long-press drag-to-move for timed events; recurring timed
moves are stored as single occurrence exceptions. How events are *drawn* — colour
strength, title size, whether titles wrap — is the "Calendar style" settings page.
Export asks which calendars to write (checkboxes, with each one's event count) and
import can make the calendar it is about to import into without leaving the dialog.
The event detail screen carries no app bar: back floats top-left over the header
gradient, and edit plus an overflow (Duplicate, Delete) float top-right. Duplicate
goes through `Routes.editorCopy`, whose `copyFrom` argument makes the editor read an
existing event and then forget where it came from — everything carries across, the
RRULE verbatim included, except the attendee list, because saving attendees is a
scheduling message rather than a copy. There is no separate Day view — it was dropped as
redundant (Week's schedule + Agenda cover it). See the README "Current status"
and "Roadmap" sections for the full picture and what's next.

## Design system

The visual identity ("the Foscal voice") is derived from the Claude Design
project *Android Calendar App Design* (`Calendar.dc.html`). Keep new UI on-system:

- **Typography** — two variable fonts bundled in `core/core-ui/src/main/res/font`:
  **Bricolage Grotesque** (display/voice: date numerals, month header, screen
  titles, event-detail title) and **Hanken Grotesque** (all body/UI). Wired via
  `core-ui/.../theme/Type.kt` → `FoscalTypography`; display+headline styles
  are Bricolage, everything else Hanken. Reach for `BricolageFamily` directly
  only for numerals/headers that need the voice.
- **Accent** — Cobalt `#1A73E8` (`FoscalBlue`) is the default, driving today,
  selection, buttons and the FAB. Users can switch to **Violet**, **Forest**, or a
  custom ARGB color in onboarding or Settings; the choice persists via
  `Preferences.accentColor`, with `Preferences.accentCustomColor` storing the
  custom seed. Fixed per-accent tokens live in `theme/Color.kt` (`AccentTokens`);
  custom colors are expanded by `customAccentTokens(seed)` in `Theme.kt`.
  Never hardcode the accent — read `colorScheme.primary`. `Preferences.dynamicColor`
  (off by default) swaps the whole scheme for a wallpaper-derived Material You one;
  Settings shows the toggle and the accent picker mutually exclusively, and renders the
  toggle only on API 31+ — the platform has no dynamic scheme to read below it.
- **Weekend labels** — use `weekendLabelColor()` from the theme (theme-aware gold),
  never a hardcoded value.
- **Locale** — in composables read `currentLocale()` (`ui/util/Locales.kt`) or
  `rememberDateFormatter(pattern)`, never `Locale.getDefault()`: the latter is a
  process-global read, so the UI keeps stale month/weekday names after a language change,
  and AGP lint fails the build on it (`NonObservableLocale`). Non-composable label helpers
  take a `Locale` parameter threaded from the call site — `Dates.weekStartLabels(locale)` is the
  pattern. `Dates` no longer holds any top-level `DateTimeFormatter` vals: those were frozen at
  class-init and lint cannot see through them, so build formatters with `rememberDateFormatter`
  at the composable that needs one instead of adding a shared val back.
- **Light/dark branching** — read `LocalIsDarkTheme.current` (provided by `FoscalTheme`),
  never `isSystemInDarkTheme()`. The latter reports only the OS setting, so it disagrees
  with the rest of the UI whenever the user has forced Light or Dark in Settings. The only
  legitimate callers of `isSystemInDarkTheme()` are `MainActivity`, where `ThemeMode.SYSTEM`
  is resolved into the `darkTheme` argument, and that parameter's own default.
- **Event colour** — `Instances.DISPLAY_COLOR` already resolves to the event's own colour when it
  has one and the calendar's otherwise, so reading needs nothing special; only the write is the
  app's problem. `EventInput.color` of null must write `putNull(EVENT_COLOR)`, never omit the
  column, or an event put back on its calendar's colour silently keeps the old one.
- **Event blocks** — one helper, `ui/EventColors.kt`, decides the fill and the text for both the
  timed blocks and the all-day bars, so the two cannot drift apart the way they had (a solid slab
  up top, a 10% wash below). The fill is the calendar's colour; the text is black or white by
  whichever actually measures better, and then the *fill* is nudged 1–4% away from that text until
  the pair clears 4.5:1. Three of the eight presets sat at 4.2–4.3:1 without it, and a colour from
  a CalDAV server can be anything at all. `EventColorsTest` covers the presets and the greys.
- **Which calendars a view draws** — `visibleCalendarIds(repository, prefs)` in
  `ui/util/VisibleCalendars.kt` is the one source, intersecting the provider's `Calendars.VISIBLE`
  with the user's own toggle. Month uses `monthCalendarIds`, which layers a second, month-only
  exclusion on top. Layered, never folded in: the month setting can only ever remove, so a
  calendar switched off everywhere cannot be brought back by it. Add a new view's filtering here
  rather than in the view model — the four copies this replaced had already begun to drift.
- **Editing and deleting a calendar** — only ones on the app's own local account, guarded both in
  the UI (`CalendarRowCard` shows both buttons only when `calendar.isLocal`) and again in
  `CalendarRepository.updateLocalCalendar` / `deleteLocalCalendar`. A rename writes `NAME` as well
  as `CALENDAR_DISPLAY_NAME`: they are two different things to the provider, and a local calendar
  has no sync adapter that would object to the identity moving with the label. A calendar that syncs belongs to the account it came
  from: removing it here would either be undone by the next sync or pushed to the server as the
  user deleting it everywhere. The delete URI needs `CALLER_IS_SYNCADAPTER` — without it the
  provider only tombstones the row and waits for an adapter that is never coming — and the
  account name on the URI has to be the row's own, not this app's, or the provider refuses.
- **Opening from other apps** — every intent the app answers is parsed in one place,
  `IntentRoute.kt`, into an `IntentRoute` that `FoscalNavHost` acts on. `routeFor(RouteRequest)`
  holds the rules over plain data so they can be unit-tested without an `Intent`; the
  `routeFor(Intent)` overload is only the unpacking. Add a case there rather than reading
  `intent.extras` at a call site, and add the matching `<intent-filter>` — a filter with nothing
  behind it puts Foscal in "Open with" for something it then ignores.
- **Icon** — one unified mark for launcher (`res/drawable/ic_launcher_foreground.xml`)
  and the in-app onboarding hero (`OnboardingScreen.FoscalMark`, which draws
  `ic_foscal_badge.xml`). Keep them in sync if you change one, and redraw
  `ic_launcher_monochrome.xml` alongside — the themed-icon layer is a silhouette, so
  the grid has to be punched out with `fillType="evenOdd"` rather than drawn in a
  second colour. `ic_notification_calendar.xml` is the same grid again at 24dp,
  minus the bands the colour version has room for. Its cells are real superellipses rather
  than rounded rectangles: a generous corner radius reads as a circle at that size and the
  round today-cell stops being distinguishable, which is the one thing the glyph has to say. Brand palette: ground `#4355F4`, card `#FFFFFF`, band `#FFC94D`,
  tab `#8691F7`. The grid dots are drawn in the ground colour so they read as punched
  through the card rather than printed on it. The first two are also the Cobalt accent's
  primary and secondary in `:core-ui`, so the app matches the icon that opened it; the
  amber marks today in both: `todayDiscColor()` fills the disc and `onTodayDiscColor()` is
  the ink on it. Amber is a *fill* — at 1.5:1 on white it can never be a line or a label, so
  anywhere it has to be text (weekend labels, today's weekday in the agenda) uses
  `amberTextColor()`, the darkened form that clears 4.5:1. These are the mark's own colours and are deliberately separate from
  the Material scheme in `:core-ui` and from the per-calendar colours in
  `ui/CalendarColors.kt`.
- Time is **24-hour by default** (`HH:mm`), but the user can switch to 12-hour in
  Settings. Never hardcode a time pattern in UI: read the ambient
  `LocalUse24HourClock` (in `ui/util/TimeFormat.kt`) and format via
  `timeFormatter(is24Hour)` / `rememberTimeFormatter()`. Non-composable label
  helpers take an `is24Hour: Boolean` threaded from the composable call site.
  `ThemeMode` (System/Light/Dark, `:core-model`) similarly drives
  `FoscalTheme(darkTheme = …)` from `MainActivity`.

## Architecture

- Multi-module Gradle project (Kotlin DSL + version catalog at
  `gradle/libs.versions.toml`).
- `:app` is the only application module. `:core:*` are libraries.
- Single-activity Compose app. Navigation Compose is used for routing.
- Hilt for DI. The DI graph lives in `:app` plus `DataModule` in `:core:data`.
- Data access goes through `app.foscal.core.data.CalendarRepository`,
  which is backed by `CalendarContract`. Do not query `CalendarContract`
  directly from UI/feature code — go through the repository so it can be
  faked in tests.

### Non-obvious gotchas (don't regress these)

- **Permission gating.** The Calendar Provider throws `SecurityException` the
  moment it is touched without `READ_CALENDAR` — *including*
  `registerContentObserver`, which is not a query. All provider access is gated
  behind `CalendarPermissionState.granted`; nothing may hit the provider before
  permission is granted. Refresh it from the activity's `onResume` and after any
  permission result.
- **Recurring exceptions need a `_sync_id`.** AOSP links a recurrence exception
  to its master through the master's `_sync_id`. Local-calendar events have no
  sync adapter to assign one, so `createEvent` mints one for events on LOCAL
  calendars. Without it, inserting an exception silently wipes the rest of the
  series. Never remove that step. CalDAV events already have sync ids (DAVx⁵
  owns them) — do not touch those.
- **All-day events are stored at UTC midnight.** Read them back in UTC
  (`Event.startLocalDate`), not the device zone, or they shift a day west of UTC.
- **Month view swaps whole-month grids via `AnimatedContent`, not a continuous
  week scroll.** The visible month lives in the ViewModel; `nextMonth()` /
  `previousMonth()` / `goToMonth()` change it and `AnimatedContent` slides the
  new grid in (direction inferred from which month is greater). The month/year
  title opens a compact month/year jump dialog that calls `goToMonth()`. Each
  grid is built from `visibleMonthCells(month)` and animates per-cell in-month /
  out-of-month (black↔grey) coloring. The ViewModel fetches a ±2-month window so
  adjacent months are already populated. Tapping a day updates an inline preview
  panel under the grid (no modal) — there is no day-events bottom sheet.
  Vertical swipes on the month grid are aliases for month navigation (up =
  next month, down = previous month) and use dominant-axis drag detection so
  diagonal gestures do not trigger both horizontal and vertical navigation.
- **The agenda's month headers are the only thing on screen naming the date.** An agenda skips
  empty days, so scrolling a few screens leaves no clue what month — let alone year — is being
  looked at. `AgendaUiState.items` interleaves an `AgendaItem.MonthHeader` wherever the month
  changes and the screen renders those with `stickyHeader`. The list is *flat*, not months nested
  with their days, because a `LazyColumn` index has to be an index into it: both paging triggers
  and `todayIndex` work in list indices, and nesting puts them permanently out of step with the
  interleaved headers. Header keys are `YearMonth`, never `Month` — the latter folds July 2027 into
  July 2026.
- **Scrolling a row to index 0 hides it under the sticky header**, which is drawn *over* the list
  rather than in its flow. Both the initial scroll-to-today and the Today pill pass a negative
  `scrollOffset` of the header's height, measured with `onSizeChanged` rather than hardcoded as a
  dp so it stays correct at any font scale.
- **Alarm keys must include the occurrence start.** Every instance of a recurring series shares
  one `Events._ID`, so a PendingIntent request code (or notification id) keyed on
  `(eventId, minutes)` alone makes each occurrence's `FLAG_UPDATE_CURRENT` alarm overwrite the
  previous one and only the last occurrence in the horizon ever fires. Use
  `AlarmReminderScheduler.alarmKey(eventId, startMillis, minutesBefore)` for both.
- **Never schedule reminders with `setAlarmClock`.** It looks like the right API — highest priority,
  doze-exempt — but it is for the device's *user-facing alarm clock*. It publishes every reminder as
  the system "next alarm" (status bar, lock screen, Quick Settings), replacing the user's real alarm
  with a calendar entry up to 30 days out, and its `AlarmClockInfo` carries a `showIntent` the system
  launches when the user taps that chip. Passing the firing broadcast as that `showIntent` made a tap
  post the reminder immediately, so an event still weeks away arrived as "In 10m".
  `setExactAndAllowWhileIdle` gives the same delivery guarantees with none of that surface.
- **A reminder notification must state its lead time from the moment it is posted**
  (`leadLabel` in `ReminderText.kt`), never from the reminder's configured offset. The offset is a
  claim about when the alarm was *meant* to fire; printing it directly makes the notification repeat
  that claim no matter when it actually arrived, which hides both doze delays and misfires.
- **Only `METHOD_DEFAULT` / `METHOD_ALERT` / `METHOD_ALARM` reminder rows become local alarms.**
  A CalDAV server can attach EMAIL and SMS alarms and DAVx⁵ syncs them down verbatim; notifying for
  those duplicates a message the server already sends. `readReminderMinutes(notifiableOnly = true)`
  is the scheduling path; the editor still reads *every* row, since it must round-trip what it did
  not display (see the all-or-nothing rule below).
- **Alarm cancellation is driven by a persisted registry, not by the new reminder list.**
  `reschedule` receives only the reminders that still exist, so deriving what to cancel from it
  strands alarms for deleted events, removed reminders, moved occurrences, and any offset outside
  a hardcoded preset list. `AlarmReminderScheduler` records the request codes it scheduled in
  SharedPreferences and cancels exactly those next time. The registry write is in a `finally`: it is
  the only record of what is armed, so losing it strands every alarm set in that pass.
- **An all-day reminder anchors to *local* midnight, never to the stored start.** The provider keeps
  all-day events at UTC midnight, so subtracting the offset from `Instances.BEGIN` puts "15 minutes
  before" at 01:45 local in UTC+2 and at 18:45 the *previous day* in UTC-5. Compute triggers with
  `ReminderTrigger.triggerAtMillis(start, allDay, minutes, zone)` and keep `startMillis` raw — it is
  the alarm key and the Instances lookup value, not a display value.
- **The scheduling horizon is `ARM_AHEAD_DAYS + largest reminder offset`, not the larger of the two.**
  A reminder fires at `eventStart - offset`, so arming 7 days ahead of *firing* requires querying
  events up to 7 days + the offset out. `max(7d, 14d)` silently loses a 2-week reminder on an event
  20 days away — the event never enters the window, so the alarm is never set. `ReminderTrigger.horizonEnd`
  also rounds up to the next local midnight; truncating down puts the end before `now + days`.
- **An unreadable provider must not look like an empty calendar.** `getUpcomingReminders` returns
  **null** when the query fails and an empty list only when there genuinely are no reminders. An
  empty list instructs `reschedule` to cancel everything, so flattening the two silently disarmed a
  user's whole calendar on any transient failure. The worker returns `Result.retry()` on null.
- **`ReminderSyncWorker` must re-arm the content trigger *last*.** The trigger is one-shot unique
  work, so the re-arm replaces the name the running job itself holds — and WorkManager cancels a
  running instance to make room. Re-arming first therefore cancelled the worker before it armed
  anything: the trigger looped forever while no alarm was ever scheduled. `ensureScheduled` uses
  `KEEP` for the same reason, so opening the app cannot kill an in-flight sync.
- **`syncNow()` is expedited only from API 31.** Below 31 WorkManager runs expedited work as a
  foreground service and calls `getForegroundInfo`, which `CoroutineWorker` does not implement: the
  work fails with "Not implemented" before `doWork` runs, so boot, app update, clock changes and
  "Resync now" re-armed nothing on Android 8 to 11. Plain work starts at once anyway, because every
  caller runs in a live process. Do not drop the SDK gate without implementing `getForegroundInfo`.
- **Force-stopping the app cancels its alarms *and* its jobs, and nothing runs until it is
  reopened.** This is OS behaviour, not a bug — but it means `adb shell am force-stop` invalidates
  any reminder test. Use `am kill` plus HOME to simulate a backgrounded app instead.
- **`ensureLocalCalendar` is find-or-create, deliberately.** The Calendar Provider outlives the
  app's own data, so a plain insert on every onboarding run adds a duplicate "My calendar" after
  each data clear or reinstall and strands the user's events in the first one.
- **The grid's tap and its long-press drag are two pointer inputs on one node, and they both
  see every gesture.** `detectTapGestures` fires on the release of a *long* press too — it is only
  a very slow tap as far as it is concerned — so without `longPressActive` a drag that created an
  event also parked a block on top of it. The flag is set in `onDragStart` and cleared in the tap
  detector's `onPress`, which runs on the down of every gesture and therefore always before the
  long press it might belong to. Do not "fix" this by passing `onLongPress` to `detectTapGestures`
  instead: that path calls `consumeUntilUp()`, which eats the very move events the drag detector
  needs and kills the drag outright.
- **A drag rounds to the nearest snap step and a tap floors to it, deliberately.** Rounding a drag
  keeps both edges under the finger; flooring a tap keeps the block from starting above where the
  finger landed, which reads as a missed tap rather than as a snap.
- **`eventColors` is the only thing that knows about `EventColorStrength`.** The wash is applied
  before the ink is chosen, never after, so every strength gets ink picked against the fill it
  actually has and the 4.5:1 nudge still applies. A settings swatch renders its own strength by
  providing `LocalEventColorStrength` over the ambient one rather than by reimplementing the maths.
- **Timeline headers must use `TimelineGutterWidth` / `TimelineEndInset`.** Any weekday strip drawn
  above a `TimelineLayout` shares those two values or its columns drift out of alignment with the
  grid columns below; the error accumulates across the week and shows up on the last day.
- **A null `Preferences.defaultReminderMinutes` means "None", not "unset".** DataStore cannot hold
  a null Int, so the stored sentinel is `-1`; the flow resolves an *absent* key to the built-in
  15-minute default itself. A caller writing `?: 15` therefore re-adds the exact alarm the user
  turned off in Settings. Use `listOfNotNull(...)` when building `EventInput.reminderMinutes`.
- **A per-calendar reminder default has three states, and `perCalendar[id] ?: global` collapses two
  of them.** An absent key means "follow the global default"; a key mapped to null means the user
  chose "None" *for that calendar*, which must beat a non-null global. Always go through
  `CalendarReminderDefaults.resolve`, which checks `containsKey` before the lookup.
- **`ReminderHealthProbe.probe()` runs on `Dispatchers.IO`, and must keep doing so.** It reads like a
  handful of getters, but it is a DataStore read from disk plus a dozen-odd binder round trips
  (notification, alarm, power, activity and package managers). `viewModelScope` is the main
  dispatcher, so calling it without the switch ANRs the settings screen on a slow device — which is
  how this was found. `VendorSettings.autostartIntent` caches its result for the same reason: it
  costs one `resolveActivity` per candidate ROM and the answer cannot change at runtime.
- **Offer the battery-optimization *list*, never `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.**
  The one-tap dialog needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which store policy
  treats as restricted and grants only to a narrow set of app categories.
  `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` needs no permission at all.
- **Anything resolved across a package boundary needs a `<queries>` entry.** From Android 11,
  `getPackageInfo` throws `NameNotFoundException` and `resolveActivity` returns null for undeclared
  packages — indistinguishable from the app genuinely not being installed. This covers DAVx⁵ and
  every vendor autostart screen in `VendorSettings.CANDIDATES`.
- **A "this and following" split edits the rule, never rebuilds it.** `RecurrenceRules.truncateBefore`
  swaps COUNT/UNTIL for a new UNTIL and `rebaseFollowing` changes only COUNT; every other part
  (ordinal BYDAY, BYMONTHDAY, BYSETPOS, WKST, HOURLY) stays as written, because the old series keeps
  its past occurrences only if its rule still generates them. The new series is created *before*
  the old one is truncated and removed again if the truncate is refused. Exceptions from the split
  point on are deleted, and "first occurrence" is `instanceStartMillis <= DTSTART`, never an
  Instances count of zero, which an edited first occurrence also produces.
- **The provider rebuilds Instances only when an update carries DTSTART, and judges recurrence from
  the update alone.** Writing just a new RRULE updates `lastDate` but leaves every old occurrence
  expanded; writing DTSTART without the RRULE re-expands a series as a single event. Any write that
  changes a series' shape sends DTSTART, RRULE, DURATION, EVENT_TIMEZONE and ALL_DAY together, as
  `truncateSeries` does.
- **A recurrence exception starts life with the master's reminders.** The provider seeds the new
  exception row by copying the master's children, so `updateEventInstance` must clear reminders on
  the new id before writing the editor's set — otherwise editing one occurrence leaves it holding
  both copies and the alarm fires twice.
- **Reminders are all-or-nothing.** The provider has no partial-update path for
  `Reminders`, so `updateEvent` deletes every row for the event and reinserts from
  `EventInput.reminderMinutes`. That list must therefore always be the *complete* set —
  any caller that passes a single value (or `minOrNull()`) silently destroys the other
  alarms, including ones DAVx⁵ synced down. The editor renders a chip per preset plus one
  per already-present value so nothing it can't display gets dropped on save.
- **`EventInput.attendees` is nullable and null means "leave them alone".** Guests are
  all-or-nothing exactly like reminders — the provider has no partial update for `Attendees`, so
  `writeAttendees` clears and reinserts. Unlike reminders, most callers have no guest list at all
  (quick add, a week-grid drag-to-move, an `.ics` file with no ATTENDEE lines), and an empty list
  from those would wipe every guest DAVx⁵ synced down. Only a caller that actually *read* the
  guests may pass a list; the editor does, which is why it can write an empty one when the user
  removes the last guest. A recurrence exception inherits the master's guests through the same
  null.
- **The organizer lives in the `Attendees` table too.** The provider tells it from a guest only by
  `ATTENDEE_RELATIONSHIP`; RFC 5545 gives it its own ORGANIZER property and allows exactly one, so
  `Event.toIcsEvent` splits it out. Most exporters *also* list the organizer as an ATTENDEE, so the
  reader drops the duplicate row rather than showing the same person twice. The editor will not let
  the organizer be removed: dropping that row un-invites nobody, it only loses which address the
  invitation came from.
- **Foscal sends no invitations, but it can answer one.** It writes attendees to the provider and
  the calendar's sync adapter delivers them; the app never mails anybody itself. Replying is the
  same shape in reverse — `setSelfAttendeeStatus` writes `ATTENDEE_STATUS` on the user's *own* row
  and the adapter carries it back — which is why the detail screen offers Yes / Maybe / No under
  **RSVP** when the user is an attendee. There is no equivalent for anybody else on the list, so
  tapping another row opens a `mailto:` intent, the only thing the app can honestly do about them.
  Two consequences that look like bugs and are not: the reply lands in the provider **instantly**
  but the mail an Exchange/Outlook account sends on the back of it goes out on that adapter's next
  sync, which can be an hour later; and there is no way to answer *without* it, because
  `CalendarContract` carries only the status and every decision after that belongs to the adapter.
- **A drag-to-move must be told how much of a series it means, and must carry the event's own
  colour.** `moveEvent` builds a fresh `EventInput`, and every field it leaves out is a field it
  *clears*: `color` defaults to null and null means "put this event back on its calendar's colour",
  so a drag used to strip it. Read it with `getEventColor` — never pass `Event.color`, which is the
  resolved `DISPLAY_COLOR` and already falls back to the calendar's. Scope is the other half: a
  drop on an occurrence is offered the same three choices as an edit or a delete, and "all events"
  shifts the *master's* DTSTART by the delta rather than setting it to the dropped time, which for
  any occurrence past the first would jump the series forward by however many repeats have run.
- **The grid holds a move preview past the drop.** The write goes to the provider and returns
  through a flow; releasing the preview when the finger lifts put the block back at its old time
  for those frames, so a successful move read as a jump backwards and then forwards. `EventDrag`
  now carries a `committed` flag and is released by new data arriving, by `revertMoveSignal` (the
  scope dialog dismissed), or by a timeout if the write is refused and neither happens.
- **`launch()` on an `ActivityResultLauncher` can throw.** `CREATE_DOCUMENT` and `OPEN_DOCUMENT`
  need a documents provider, and stripped ROMs, some work profiles and the ATD emulator images do
  not ship one — the `ActivityNotFoundException` comes out of a click handler and takes the app
  down. Both transfer pickers go through `launchSafely`. The same rule is why `openLink`/`openMail`
  wrap `startActivity`.
- **Haptics answer for the part of the screen the hand is covering, and nothing else.** The rule is
  not "confirm every tap" — a tap on a visible control confirms itself, and a buzz on top is noise
  that trains people to ignore the ones that matter. Feedback is added exactly where the result is
  hidden or ambiguous: `LongPress` the instant `detectDragGesturesAfterLongPress` arms (nothing on
  screen says the gesture changed meaning until the finger moves), `SegmentFrequentTick` once per
  *snap step crossed* during a drag — never per pixel, or it buzzes continuously and says nothing —
  `Confirm` when a drag lands somewhere new or a tap parks a block on an hour above where the
  finger was, `ToggleOn`/`ToggleOff` on switches and the calendar checkboxes because Compose's
  `Switch` ships none, and `Confirm` on a delete that has already closed the screen. Deliberately
  *not* on the RSVP chips: the write is checked before the chip moves, so a tick at tap time would
  be the one place touch claims success before the app knows. `SegmentedControlTick` does not exist
  in this Compose version — the names are `SegmentTick` and `SegmentFrequentTick`.
- **Addresses are compared through `Attendee.normalizeAddress`, never with `=`.** The screen decides
  whether to offer a reply and the repository decides which row to write, and the two used to
  disagree: the screen compared case-insensitively while the write handed
  `attendeeEmail = ?` to SQLite, whose `=` is case-sensitive. An Exchange calendar routinely stores
  its `OWNER_ACCOUNT` in one case and the same person's attendee row in another, so the reply
  buttons appeared and then did nothing at all, silently — `safeUpdate` returning 0 is
  indistinguishable from "no such row". `setSelfAttendeeStatus` now finds the row itself and
  updates it **by id**. The rule also strips a `mailto:` prefix, because iCalendar addresses an
  ATTENDEE as a URI while the provider stores a bare address.
- **Never write `Events.SELF_ATTENDEE_STATUS`.** The provider rejects it outright for anyone but a
  sync adapter — *"Updating selfAttendeeStatus in Events table is not allowed"*, an
  `IllegalArgumentException` that `safeUpdate` swallows whole, so the call looks like it worked and
  never did. It is also unnecessary: writing the attendee row makes the provider recompute the
  column itself. It only manages that when the calendar's owner address matches the attendee row
  *exactly*, case included — which is the one thing this app cannot arrange — so nothing here reads
  the column back, and the user's own answer is always read from the `Attendees` table.
- **The editor only offers the guest field for events the user organized**
  (`EditorUiState.canEditGuests`). Rewriting the `ATTENDEE` rows of somebody else's event is not an
  edit but a scheduling message, and CalDAV servers vary in what they do with one — up to mailing
  every guest a spurious update. Editable means: a new event, a local calendar, an event with no
  ORGANIZER at all (what a plain CalDAV event from a non-scheduling client looks like — there is
  nobody whose event it is instead), or an organizer matching the calendar's `OWNER_ACCOUNT`.
  Otherwise the guests still render, read-only, and **the save passes `attendees = null`** — writing
  the list back even unchanged re-sends it to the server. Both mutators re-check the gate, so a
  disabled control is not the only thing enforcing it.
- **A location that is only a call link gets no Location card.** `MeetingLinks.find` scans location
  then description; when the whole location *is* the matched URL, the detail screen suppresses the
  location card, because it would repeat the Join card and its `geo:` intent would search a map for
  a URL. A location that merely contains a link still names a real place and keeps its card.
- **Never re-anchor an event's time zone.** `EventInput.timezone` must carry the edited
  event's original `EVENT_TIMEZONE`; the editor keeps it in
  `EditorUiState.originalTimezone`. Rewriting it to the device zone preserves the chosen
  instant locally but re-anchors recurrence expansion and shifts the event for every other
  client on the same CalDAV calendar. Only new events (and all-day events, which are UTC by
  contract) may use the device zone. Unparseable stored zones fall back to it.
- **`.ics` export reads the `Events` table, never `Instances`.** Every other read path in the app
  goes through `Instances`, which expands a recurring series into one row per occurrence — each
  carrying the master's RRULE. Exporting that writes one VEVENT per occurrence, all claiming to
  repeat forever. `getEventsForExport` reads master rows instead and derives the end from
  `DURATION`, which the provider stores *instead of* DTEND for recurring events.
- **A recurrence exception is not a top-level event.** `getEventsForExport` returns
  `ExportEvent(master, overrides, cancelledOccurrences)`: the exception rows are attached to the
  series they belong to, because on their own they look like ordinary one-off events and exporting
  them flat duplicates an occurrence the series already covers. Which rows those are cannot be
  decided on `ORIGINAL_ID` alone — a sync adapter links its exceptions by `ORIGINAL_SYNC_ID` — so
  both columns are read and the sync id is resolved back to a local row id in Kotlin.
- **Every VEVENT of one series shares the master's UID.** UID + `RECURRENCE-ID` is the only thing
  tying an override back to its series. `Ics.write` falls back to a *synthetic* uid derived from
  title and start, which an override is free to have changed, so `ExportEvent.toIcsEvents` computes
  the master's uid once (`Ics.syntheticUid`) and copies it onto each override. It also strips any
  RRULE from an override (AOSP leaves the column null, but a sync adapter may not) — leaving one in
  turns a single replaced occurrence into a second full series overlapping the first.
- **Cancelled occurrences export as `EXDATE` on the master, not as `STATUS:CANCELLED` overrides.**
  Every RFC 5545 reader understands EXDATE; a cancelled override is routinely imported as a real
  (if cancelled) event.
- **Import writes masters before overrides.** An override can only be applied once the series it
  replaces has a row id, so `writeImported` creates every non-override first, keeps a uid→id map,
  then applies EXDATEs via `deleteEventInstance` and RECURRENCE-ID VEVENTs via
  `updateEventInstance`. An override whose master is not in the file is created as a standalone
  event rather than dropped. That function is deliberately separate from `IcsTransfer.import` so
  the ordering is testable without a `Context` or a document URI.
- **Timed events export as UTC, deliberately.** A `TZID` parameter is only usable by the reader if
  the file also carries that zone's full VTIMEZONE with its DST rules; emitting a one-block
  VTIMEZONE with today's offset is worse than none, because it silently shifts occurrences on the
  other side of a DST boundary. Import still honours `TZID` and preserves it, so Foscal→Foscal
  round-trips keep the authored zone. A trailing `Z` is reported as the zone `UTC`, not as "no
  zone": falling back to the device zone there would re-anchor a recurring series' wall time.
- **An all-day `DTEND` is exclusive and must be strictly later than `DTSTART`.** The provider
  really does hold all-day rows written with `DTEND == DTSTART`, and exporting those verbatim
  produces a VEVENT covering zero days that strict parsers reject, so `Ics.write` clamps them to a
  one-day span. This matches the provider's own convention, so no translation happens at either
  boundary — `IcsEvent.end` is the provider's end.
- **Provider calls can throw `IllegalArgumentException`** for values it rejects;
  the repository's `safe*` helpers swallow both that and `SecurityException` so a
  bad write never crashes the app.
- **The app is offline by default; the ONLY networked feature is the opt-in map
  picker.** `INTERNET` exists in the manifest solely for the OpenStreetMap
  location picker, which is gated behind the `osmMapsEnabled` preference (off by
  default, toggled in onboarding or Settings). Do not add network calls anywhere
  else. Location entry is otherwise plain free text: the editor autocompletes
  only from the user's own past locations (`getRecentLocations`), and the detail
  screen's location card hands the text to the device maps app via a `geo:`
  intent. When maps are enabled, "Pick on map" opens `LocationPickerScreen`
  (osmdroid + OSM tiles); `NominatimGeocoder` forward-geocodes the query to
  center the map and reverse-geocodes the confirmed pin to an address string —
  the chosen text returns to the editor via the nav back-stack
  (`PICKED_LOCATION_KEY`). Still no stored coordinates; location stays a String.

## Conventions

- Kotlin only (no Java sources).
- Follow Material 3 in Compose. Theme lives in `:core-ui`.
- No comments unless they explain *why* something non-obvious is done.
- License is GPLv3; do not introduce dependencies under incompatible licenses
  (no proprietary SDKs, no Google Analytics/FCM). All deps must be FOSS.

## Sync model

Foscal does **not** implement CalDAV itself. Syncing with Nextcloud /
ownCloud is delegated to DAVx⁵ via the Android system Calendar Provider.
Onboarding should detect DAVx⁵ and otherwise point the user to install it.
