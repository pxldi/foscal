# Calendarium

A free, open-source Android calendar app with a modern, Material 3 / Material You
design. Built for people who want a beautiful, fast, privacy-respecting calendar
that works both **fully offline** (local calendars) and **online** with any
CalDAV-compatible server (Nextcloud, ownCloud, Radicale, Baïkal, …) via
[DAVx⁵](https://www.davx5.com).

> Status: **early development**. Not usable yet.

## Design goals

- **Beautiful and modern.** Jetpack Compose + Material 3 with dynamic color.
- **Works offline.** Create and use local calendars without any account or
  network.
- **Open sync.** Uses the Android system Calendar Provider, so any installed
  sync adapter (DAVx⁵ being the recommended one for CalDAV) keeps everything
  in sync automatically.
- **Free & open source** under the GNU General Public License v3.

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
