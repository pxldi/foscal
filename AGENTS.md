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

- **Verify the device before trusting coordinates.** The current device is
  **320x640 at density 160**, where screenshots are native size and taps map **1:1**. An
  earlier AVD was 1080x2400 and needed a 1.2x factor. Always run `adb shell wm size` first;
  a stale factor silently sends every tap to the wrong widget.
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
(this-vs-all-events, exceptions), reminders/notifications, real calendar colors,
offline local calendars, `.ics` import/export via the system document picker,
permission-first onboarding. Week view is the shared
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
  second colour. Brand palette: ink `#101C36`, card `#A8CBFF`, band `#FFC963`,
  tab `#2B4FA8`. These are the mark's own colours and are deliberately separate from
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
- **Force-stopping the app cancels its alarms *and* its jobs, and nothing runs until it is
  reopened.** This is OS behaviour, not a bug — but it means `adb shell am force-stop` invalidates
  any reminder test. Use `am kill` plus HOME to simulate a backgrounded app instead.
- **`ensureLocalCalendar` is find-or-create, deliberately.** The Calendar Provider outlives the
  app's own data, so a plain insert on every onboarding run adds a duplicate "My calendar" after
  each data clear or reinstall and strands the user's events in the first one.
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
