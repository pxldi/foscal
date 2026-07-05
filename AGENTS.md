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
