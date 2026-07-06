# Calendarium

A free, open-source Android calendar app with a modern, Material 3 / Material You
design. Built for people who want a beautiful, fast, privacy-respecting calendar
that works both **fully offline** (local calendars) and **online** with any
CalDAV-compatible server (Nextcloud, ownCloud, Radicale, Baïkal, …) via
[DAVx⁵](https://www.davx5.com).

> Status: **beta**. The core calendar is functional and stable — month/week/day/agenda
> views, event create/edit/delete, recurring events, reminders, and offline local
> calendars all work. Rough edges remain (see [Roadmap](#roadmap)).

## Design goals

- **Beautiful and modern.** Jetpack Compose + Material 3 with dynamic color.
- **Works offline.** Create and use local calendars without any account or
  network.
- **Open sync.** Uses the Android system Calendar Provider, so any installed
  sync adapter (DAVx⁵ being the recommended one for CalDAV) keeps everything
  in sync automatically.
- **Free & open source** under the GNU General Public License v3.

## Current status

**Working today**

- Four calendar views: **Month** (titled event chips; a vertical pager of
  whole-month grids that fades out-of-month days to grey), **Week** and **Day**
  (positioned time-grid blocks with overlap handling), and **Agenda**.
  Multi-day and spanning all-day events render on every day they cover.
- **Event editor** — create, edit, and delete events with title, calendar,
  all-day toggle, start/end date-time pickers, location, notes, and reminder.
- **Recurring events** — daily / weekly / monthly / yearly. Editing or deleting
  a repeating event prompts for **this occurrence** vs. **the whole series**;
  single-occurrence changes are stored as proper exceptions. Recurrence rules
  synced from CalDAV (BYDAY/INTERVAL/UNTIL) are preserved on unrelated edits.
- **Reminders / notifications** — exact-alarm reminders via `AlarmManager`,
  re-scheduled on boot and whenever the calendar changes, delivered on a
  dedicated notification channel that deep-links back to the event.
- **Real calendar colors** everywhere, with automatic light/dark contrast text.
- **Search** across events by title, location, or notes; recurring series
  collapse to their next occurrence so they don't flood results.
- **Offline local calendars** and hand-off to DAVx⁵ for CalDAV sync.
- **Permission-first onboarding** — calendar access is requested up front and
  the UI reacts the instant it is granted; no provider access happens before.
- Material 3 dynamic color (Material You), edge-to-edge, light & dark themes.

## Roadmap

**Next up**

- Richer recurrence editing: custom interval, end date / occurrence count,
  and by-weekday selection (currently frequency-only in the editor).
- "This and following events" scope option for recurring edits/deletes.

**Later / future goals**

- Home-screen widgets and a quick-add entry point.
- Week/day view: drag-to-create and drag-to-move events.
- Time-zone-aware editing UI and a world-clock style secondary zone.
- Import/export of `.ics` files.
- Local unit/UI test coverage for the repository and view models.
- Play Store / F-Droid release: signing config, screenshots, store listing.

## Tech stack

- Kotlin 2.x, Jetpack Compose, Material 3
- Single-activity, multi-module Gradle project
- Hilt for dependency injection, Room for local caching, WorkManager for
  reminder refresh
- Reads/writes `android.provider.CalendarContract`
- minSdk 26, targetSdk 35

## Project layout

```
app/                       # :app — MainActivity, navigation, DI glue
core/
  core-model/              # domain models (Kotlin/JVM)
  core-ui/                 # Material 3 theme + shared composables
  core-data/               # CalendarContract repository, Room, DataStore
```

## Build

Requires JDK 17 and the Android SDK (platform 35, build-tools 35.0.0).

```bash
./gradlew :app:assembleDebug
./gradlew lint
```

Install on a connected device:

```bash
./gradlew :app:installDebug
```

## Syncing with Nextcloud / ownCloud

Calendarium itself is a calendar *client* and intentionally does not ship its
own CalDAV engine. To sync with a CalDAV server, install
[DAVx⁵](https://www.davx5.com) (free, FOSS, available on
[F-Droid](https://f-droid.org/packages/at.bitfire.davdroid)) and add your
account there. DAVx⁵ will push/pull events into the Android Calendar Provider
and Calendarium will pick them up automatically. The onboarding flow inside
Calendarium will hand off to DAVx⁵ if it is installed, or guide you to install
it otherwise.

## License

Copyright (C) the Calendarium authors.

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE) for the full text.
