# AGENTS.md — guidance for AI assistants working on this repo

## Toolchain

This project uses a self-contained toolchain installed at
`/home/pxldi/.local/android-dev/`:

- JDK 17 (Temurin): `/home/pxldi/.local/android-dev/jdk`
- Android SDK: `/home/pxldi/.local/android-dev/android-sdk`

Before running any Gradle command, set up the environment:

```bash
export JAVA_HOME=/home/pxldi/.local/android-dev/jdk
export ANDROID_HOME=/home/pxldi/.local/android-dev/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

`local.properties` already points to the SDK dir.

## Commands

- **Build debug APK:** `./gradlew :app:assembleDebug`
- **Run all checks (lint + tests):** `./gradlew check`
- **Lint only:** `./gradlew lint`
- **Unit tests:** `./gradlew test`
- **Install on device:** `./gradlew :app:installDebug`
- **Format / verify Kotlin style:** the project uses `kotlin.code.style=official`,
  so formatting follows the official Kotlin conventions; no ktlint/detekt is
  wired up yet.

## Build configuration

AGP 9 with **built-in Kotlin support**. Consequences worth knowing before editing a
`build.gradle.kts`:

- **Do not apply `org.jetbrains.kotlin.android`** in `:app`, `:core:core-ui`, or
  `:core:core-data`. AGP supplies Kotlin itself and the plugin is incompatible with the
  new DSL; applying it fails the build outright. `:core:core-model` is a plain JVM module
  and still uses `kotlin-jvm`. The Compose compiler plugin *is* still applied separately.
- **`jvmTarget` lives in `android { kotlin { compilerOptions { … } } }`.** The old
  top-level `kotlinOptions { }` block is gone.
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

A headless AVD named `calendarium_test` (predates the rename; `emulator -list-avds` is the
authority if it is missing) is available for UI verification:

```bash
emulator -avd calendarium_test -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect &
# wait until: adb shell getprop sys.boot_completed == 1
```

- Screen is 1080x2400; captured screenshots display at 900x2000, so **multiply
  screenshot coordinates by 1.2** before `adb shell input tap` (input uses device px).
- Reproduce first-run: `adb shell pm clear app.foscal` then
  `adb shell pm revoke app.foscal android.permission.READ_CALENDAR` (+WRITE_CALENDAR,
  POST_NOTIFICATIONS). Grant back with `pm grant`.
- Inspect provider state directly with `adb shell content query/insert/update/delete --uri
  content://com.android.calendar/{events,instances/when/<from>/<to>,reminders,calendars,exception/<id>}`.

## Current status

Beta. Working: Month / Week / Agenda / Settings tabs (bottom nav — Settings is a
tab in `HomeScreen`'s `AnimatedContent`, not a separate nav destination; the
selected tab is `rememberSaveable` so returning from detail/editor preserves the
current tab), event create/edit/delete, recurring events
(this-vs-all-events, exceptions), reminders/notifications, real calendar colors,
offline local calendars, permission-first onboarding. Week view is the shared
hourly `TimelineLayout` with long-press drag-to-create and long-press
drag-to-move for timed events; recurring timed moves are stored as single
occurrence exceptions. There is no separate Day view — it was dropped as
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
  Never hardcode the accent — read `colorScheme.primary`.
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
- **Icon** — one unified mark for launcher (`res/drawable/ic_launcher_foreground.xml`)
  and the in-app onboarding hero (`OnboardingScreen.FoscalMark`). Keep them
  in sync if you change one.
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
  SharedPreferences and cancels exactly those next time.
- **`ensureLocalCalendar` is find-or-create, deliberately.** The Calendar Provider outlives the
  app's own data, so a plain insert on every onboarding run adds a duplicate "My calendar" after
  each data clear or reinstall and strands the user's events in the first one.
- **Timeline headers must use `TimelineGutterWidth` / `TimelineEndInset`.** Any weekday strip drawn
  above a `TimelineLayout` shares those two values or its columns drift out of alignment with the
  grid columns below; the error accumulates across the week and shows up on the last day.
- **Reminders are all-or-nothing.** The provider has no partial-update path for
  `Reminders`, so `updateEvent` deletes every row for the event and reinserts from
  `EventInput.reminderMinutes`. That list must therefore always be the *complete* set —
  any caller that passes a single value (or `minOrNull()`) silently destroys the other
  alarms, including ones DAVx⁵ synced down. The editor renders a chip per preset plus one
  per already-present value so nothing it can't display gets dropped on save.
- **Never re-anchor an event's time zone.** `EventInput.timezone` must carry the edited
  event's original `EVENT_TIMEZONE`; the editor keeps it in
  `EditorUiState.originalTimezone`. Rewriting it to the device zone preserves the chosen
  instant locally but re-anchors recurrence expansion and shifts the event for every other
  client on the same CalDAV calendar. Only new events (and all-day events, which are UTC by
  contract) may use the device zone. Unparseable stored zones fall back to it.
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
