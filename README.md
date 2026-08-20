<p align="center">
  <img src="docs/foscal-icon.png" width="128" height="128" alt="Foscal app icon" />
</p>

<h1 align="center">Foscal</h1>

A free, open-source Android calendar app with a modern Material 3 design. Built for people who want a beautiful, fast, privacy-respecting calendar
that works both **fully offline** (local calendars) and **online** with any
CalDAV-compatible server (Nextcloud, ownCloud, Radicale, Baïkal, …) via
[DAVx⁵](https://www.davx5.com).

> Status: **beta**. The core calendar is functional and stable — month/week/agenda
> views, event create/edit/delete, recurring events, reminders, and offline local
> calendars all work. Rough edges remain (see [Roadmap](#roadmap)).

## Design goals

- **Beautiful and modern.** Jetpack Compose + Material 3 with a custom, user-selectable
  accent palette.
- **Works offline.** Create and use local calendars without any account or
  network.
- **Open sync.** Uses the Android system Calendar Provider, so any installed
  sync adapter (DAVx⁵ being the recommended one for CalDAV) keeps everything
  in sync automatically.
- **Free & open source** under the GNU General Public License v3.

## Current status

**Working today**

- Three calendar views in the bottom nav: **Month** (centered date numerals with
  per-calendar color dots; whole-month grids that slide between months and fade
  out-of-month days to grey, tap the month/year title to jump to a specific
  month, with an inline day preview under the grid), **Week** (an hourly
  schedule with a weekday strip, long-press drag-to-create, and long-press
  drag-to-move timed events), and **Agenda** (a grouped schedule with pinned
  month-and-year headers, a highlighted today, a "Today" jump button, and
  endless loading of older and newer events as you scroll). Multi-day and
  spanning all-day events render on every day they cover.
- **Custom design system** — the app's own visual voice: the *Bricolage
  Grotesque* display face on dates and titles, *Hanken Grotesque* for UI, a
  Cobalt accent, soft color-stripe event cards, a unified app/onboarding icon,
  and 24-hour time by default. Full light & dark support.
- **Settings** is a fourth bottom-nav tab (it slides in alongside Month / Week /
  Agenda) with appearance, date & time, calendar visibility, and reminder options.
- **Configurable accent** — pick **Cobalt / Violet / Forest** or a custom color
  in Settings; the whole UI re-tints instantly and the choice is persisted. On
  Android 12+ you can instead switch on **wallpaper colors** (Material You) and
  let the system palette drive the app.
- **Theme & clock** — force **System / Light / Dark** and toggle **12- / 24-hour
  time**; both apply live across every screen and persist.
- **Event editor** — create, edit, and delete events with title, calendar,
  all-day toggle, start/end date-time pickers, location, attendees, notes, and any
  number of reminders. Events keep the time zone they were authored in, so editing one that
  came from CalDAV does not shift it for other clients.
- **Recurring events** — daily / weekly / monthly / yearly, with a custom
  editor for interval, end date / occurrence count, and by-weekday
  selection. Editing or deleting a repeating event prompts for **this
  occurrence**, **this and following events**, or **the whole series**;
  single-occurrence changes are stored as proper exceptions and "this and
  following" splits the series at the chosen point. Recurrence rules synced
  from CalDAV (BYDAY/INTERVAL/UNTIL) are preserved on unrelated edits.
- **Reminders / notifications** — exact-alarm reminders via `AlarmManager`,
  re-scheduled on boot and whenever the calendar changes, delivered on a
  dedicated notification channel that deep-links back to the event.
- **Attendees** — invite people by email in the editor and see the whole list on
  the event, organizer first, folded behind a line that counts the answers (going /
  not going / maybe / no reply) and opening to name everybody. Attendees synced down
  from CalDAV are shown and preserved across unrelated edits; tapping one opens your
  mail app. Answering an invitation yourself is one tap under **RSVP**. Foscal writes
  to the calendar and lets the sync adapter deliver both the invitations and your
  reply — it sends no mail of its own, and cannot stop the account that does. On an
  event somebody else organized the list is shown read-only and left strictly
  untouched on save, so editing a meeting you were invited to never sends a
  scheduling message on your behalf.
- **Join video call** — a Meet / Zoom / Teams / Webex / Jitsi / Whereby / Nextcloud
  Talk link in an event's location or notes becomes a one-tap Join action on the
  event, labelled with the provider it points at.
- **Real calendar colors** everywhere, with automatic light/dark contrast text.
- **Search** across events by title, location, or notes; results expand older
  and newer as you scroll, and recurring series collapse to a single occurrence
  so they don't flood results.
- **Home-screen widget** showing your upcoming events with real calendar
  colors, tap-to-open, and a "+" quick-add button.
- **Quick add** — type a natural phrase ("Dentist friday 9:30am", "Lunch
  tomorrow noon", "PTO all-day") and it parses the title, date, and time.
- **Import & export `.ics`** — export every event on your visible calendars to a
  standard iCalendar file — choosing which calendars go into it, with each one's
  event count next to it — or import one into a calendar you pick, or into a new
  one made without leaving the dialog. Recurrence
  rules, all-day spans, time zones, reminders and multi-line notes are carried
  across, and so are attendees (`ORGANIZER` / `ATTENDEE` with their `PARTSTAT`), and so
  are per-occurrence changes to a repeating event: a moved
  occurrence exports as a `RECURRENCE-ID` override and a deleted one as an
  `EXDATE`, both of which import back onto the series. Files are chosen through
  the system document picker, so Foscal needs no storage permission and only
  ever touches the one file you select.
- **Offline local calendars** and hand-off to DAVx⁵ for CalDAV sync.
- **Permission-first onboarding** — calendar access is requested up front and
  the UI reacts the instant it is granted; no provider access happens before.
  The final step lets users choose theme, accent color, reminders, and opt-in
  map picking before entering the main calendar.
- Material 3, edge-to-edge, light & dark themes. The color scheme defaults to
  Foscal's own accent system (Cobalt / Violet / Forest / custom); wallpaper-derived
  dynamic color is available as an opt-in toggle on Android 12+.

## Roadmap

**Next up**

- Time-zone-aware editing UI and a world-clock style secondary zone.
- Map preview on the event detail screen (attendees and join-video-call are done).

**Later / future goals**

- More unit/UI test coverage (repository, more view models, widget factory).
- Play Store / F-Droid release: signing config, screenshots, store listing.

## Tech stack

- Kotlin 2.x, Jetpack Compose, Material 3
- Single-activity, multi-module Gradle project
- Hilt for dependency injection and DataStore for preferences
- Reads/writes `android.provider.CalendarContract`
- minSdk 26, targetSdk 36, compileSdk 37

## Project layout

```
app/                       # :app — MainActivity, navigation, DI glue
core/
  core-model/              # domain models (Kotlin/JVM)
  core-ui/                 # Material 3 theme + shared composables
  core-data/               # CalendarContract repository + DataStore preferences
```

## Build

Requires JDK 17 and the Android SDK (platform 37.1, build-tools 37.0.0).

```bash
./gradlew :app:assembleDebug
./gradlew lint
```

Install on a connected device:

```bash
./gradlew :app:installDebug
```

### Baseline profile

Release builds ship a baseline profile: a list of the classes and methods the app runs on the way
to a usable calendar, which ART compiles ahead of time at install. Without it every one of those
methods is interpreted and JIT-compiled during someone's first few sessions, which is why a fresh
install can feel janky on a phone that is otherwise fine.

The profile is generated on a device and **committed**, so ordinary release builds — CI included —
never need one. Regenerate it when the startup path or the paging code changes meaningfully.

It needs a device where ART will hand back its profile, which means `ro.debuggable=1`: an emulator
running an **AOSP or `google_apis`** image, *not* a `google_apis_playstore` one, or a rooted phone.

```bash
sdkmanager "system-images;android-34;aosp_atd;x86_64"
avdmanager create avd -n foscal-baseline -k "system-images;android-34;aosp_atd;x86_64"
emulator -avd foscal-baseline -no-window -no-audio &
adb wait-for-device && adb shell getprop ro.debuggable   # must print 1

./gradlew :app:generateReleaseBaselineProfile
```

The run installs an unminified release build, walks it through onboarding and a few page turns, and
writes the result to `app/src/release/generated/baselineProfiles/`. Commit what lands there.

Give the device a phone-shaped screen before running. Some test images default to something far
smaller — an `aosp_atd` image here reported 320x640 — and a profile recorded on a screen nothing
like a phone is a profile of the wrong app:

```bash
adb shell wm size 1080x2400 && adb shell wm density 420
```

Afterwards, check the profile actually covers the calendar rather than just the way in:

```bash
grep -o 'app/foscal/ui/[a-z]*' app/src/release/generated/baselineProfiles/baseline-prof.txt \
  | sort | uniq -c | sort -rn
```

If `onboarding` dominates and `month` / `week` / `agenda` have only a handful of rules each, the
journey never reached the calendar and the profile is not worth committing.

Expect it to take a while — it builds the app twice and runs the journey several times over. The
journey itself lives in `benchmark/`, which is a test-only module and is never part of the APK.

## Syncing with Nextcloud / ownCloud

Foscal itself is a calendar *client* and intentionally does not ship its
own CalDAV engine. To sync with a CalDAV server, install
[DAVx⁵](https://www.davx5.com) (free, FOSS, available on
[F-Droid](https://f-droid.org/packages/at.bitfire.davdroid)) and add your
account there. DAVx⁵ will push/pull events into the Android Calendar Provider
and Foscal will pick them up automatically. The onboarding flow inside
Foscal will hand off to DAVx⁵ if it is installed, or guide you to install
it otherwise.

## License

Copyright (C) the Foscal authors.

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE) for the full text.
