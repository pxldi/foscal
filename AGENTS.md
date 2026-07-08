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

Always run `./gradlew assembleDebug` and `./gradlew lint` after non-trivial
changes. Do not commit code that does not build or that fails lint.

## Testing on the emulator

A headless AVD named `calendarium_test` is available for UI verification:

```bash
emulator -avd calendarium_test -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect &
# wait until: adb shell getprop sys.boot_completed == 1
```

- Screen is 1080x2400; captured screenshots display at 900x2000, so **multiply
  screenshot coordinates by 1.2** before `adb shell input tap` (input uses device px).
- Reproduce first-run: `adb shell pm clear app.calendarium` then
  `adb shell pm revoke app.calendarium android.permission.READ_CALENDAR` (+WRITE_CALENDAR,
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

The visual identity ("the Calendarium voice") is derived from the Claude Design
project *Android Calendar App Design* (`Calendar.dc.html`). Keep new UI on-system:

- **Typography** — two variable fonts bundled in `core/core-ui/src/main/res/font`:
  **Bricolage Grotesque** (display/voice: date numerals, month header, screen
  titles, event-detail title) and **Hanken Grotesque** (all body/UI). Wired via
  `core-ui/.../theme/Type.kt` → `CalendariumTypography`; display+headline styles
  are Bricolage, everything else Hanken. Reach for `BricolageFamily` directly
  only for numerals/headers that need the voice.
- **Accent** — Cobalt `#1A73E8` (`CalendariumBlue`) is the default, driving today,
  selection, buttons and the FAB. Users can switch to **Violet** or **Forest** in
  Settings; the choice persists via `Preferences.accentColor` (`AccentColor` enum
  in `:core-model`) and `CalendariumTheme(accent = …)` rebuilds the color scheme.
  Per-accent tokens live in `theme/Color.kt` (`AccentTokens`); never hardcode the
  accent — read `colorScheme.primary`.
- **Weekend labels** — use `weekendLabelColor()` from the theme (theme-aware gold),
  never a hardcoded value.
- **Icon** — one unified mark for launcher (`res/drawable/ic_launcher_foreground.xml`)
  and the in-app onboarding hero (`OnboardingScreen.CalendariumMark`). Keep them
  in sync if you change one.
- Time is **24-hour by default** (`HH:mm`), but the user can switch to 12-hour in
  Settings. Never hardcode a time pattern in UI: read the ambient
  `LocalUse24HourClock` (in `ui/util/TimeFormat.kt`) and format via
  `timeFormatter(is24Hour)` / `rememberTimeFormatter()`. Non-composable label
  helpers take an `is24Hour: Boolean` threaded from the composable call site.
  `ThemeMode` (System/Light/Dark, `:core-model`) similarly drives
  `CalendariumTheme(darkTheme = …)` from `MainActivity`.

## Architecture

- Multi-module Gradle project (Kotlin DSL + version catalog at
  `gradle/libs.versions.toml`).
- `:app` is the only application module. `:core:*` are libraries.
- Single-activity Compose app. Navigation Compose is used for routing.
- Hilt for DI. The DI graph lives in `:app` plus `DataModule` in `:core:data`.
- Data access goes through `app.calendarium.core.data.CalendarRepository`,
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
- **Provider calls can throw `IllegalArgumentException`** for values it rejects;
  the repository's `safe*` helpers swallow both that and `SecurityException` so a
  bad write never crashes the app.

## Conventions

- Kotlin only (no Java sources).
- Follow Material 3 in Compose. Theme lives in `:core-ui`.
- No comments unless they explain *why* something non-obvious is done.
- License is GPLv3; do not introduce dependencies under incompatible licenses
  (no proprietary SDKs, no Google Analytics/FCM). All deps must be FOSS.

## Sync model

Calendarium does **not** implement CalDAV itself. Syncing with Nextcloud /
ownCloud is delegated to DAVx⁵ via the Android system Calendar Provider.
Onboarding should detect DAVx⁵ and otherwise point the user to install it.
