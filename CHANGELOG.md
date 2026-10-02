# Changelog

What changed for users in each release. Generated from the commit history with
[git-cliff](https://git-cliff.org); regenerate it with `git cliff -o CHANGELOG.md`.

## 0.17.0 (2026-10-02)

### Features

- **onboarding:** Animate the mark, the waits and the theme choice (08026fc)
- **quickadd:** Understand German phrases (#3) (eca26cf)
- **a11y:** Move events without dragging, and make TalkBack read the calendar (#1) (febd3d7)

### Fixes

- **onboarding:** Keep the preview in step with the theme and apart from today (b9b9495)
- **onboarding:** Give the splash the app's own background (03a2e56)
- **onboarding:** Show each wait in full after its step has arrived (b1c8d1d)
- **maps:** Point the Nominatim User-Agent at pxldi/foscal (97f107e)

## 0.16.0 (2026-10-02)

### Features

- Translate Foscal into German (9168f05)

### Fixes

- **calendars:** Follow the phone's show and sync settings for calendars (513972a)
- **timeline:** Count columns per overlapping run, not per day (ce002ce)
- Write dates in the order the language writes them (31529fb)
- Align two German labels with the rest of the translation (79e798c)
- **settings:** Clearer German for wallpaper colours and reminder warnings (8a9654f)

## 0.15.0 (2026-09-24)

### Features

- **event:** Report refused writes and offer Undo on delete (e0b6559)
- **onboarding:** Offer app settings once calendar access is denied for good (5341e5f)
- **event:** Describe the whole repeat rule on the event detail screen (eb4d55b)

### Fixes

- **reminders:** Aim snoozed reminders at the reminder receiver (d6ed3e2)
- **quick-add:** Stop quick add crashing on an impossible time (60c4c1f)
- **recurrence:** Keep past occurrences where they were on a this-and-following split (07e66eb)
- **reminders:** Run syncNow as plain work below API 31 (d961fe4)
- **reminders:** Re-arm reminders when a calendar is hidden or shown (9faa27a)
- **reminders:** Re-arm reminders when exact alarms are granted (1ff9264)
- **reminders:** Keep reminder syncs from disarming or orphaning alarms (2b0e671)
- **ics:** Read other apps' .ics files as those apps show them (2ee810e)
- **ics:** Import .ics files once, and without exception rows on synced calendars (61e4863)
- **ics:** Export cancelled occurrences stored in the EXDATE column (199163b)
- **ics:** Export timed events with TZID and a generated VTIMEZONE (fbab460)
- **quick-add:** Read a bare quick-add time as afternoon on the 12-hour clock (2ebc4ce)
- **quick-add:** Keep quick add's Add event button above the keyboard (815bf25)
- **recurrence:** Count edited, cancelled and EXDATE occurrences when rebasing a split COUNT (99849e7)
- **editor:** Keep editor drafts through Back, process death and a moved start (f30fcf1)
- **onboarding:** Explain a final notification denial instead of opening settings (6e99325)
- **calendars:** Keep read-only calendars out of every write path (e6e090f)
- **timeline:** Repair the gutter, weekend tint, titles and the new-event date (b0f93f7)
- **reminders:** Check vendor autostart screens with the package manager (dfb755d)
- Route the launch intent only on a fresh start (9d9a847)
- **ui:** Style the system bars from the app's own theme (07696ea)
- **ics:** Stop claiming http and https .ics links (ef6f8c7)
- **maps:** Point the Nominatim User-Agent at the real repository (eb951c4)
- **reminders:** Make the reminder notification's tap intent immutable (120d8ee)
- **reminders:** Drop the QUICKBOOT actions from the exported system receiver (79f1a8d)
- **ics:** Count exported events the way the export picker counts them (815b615)
- **search:** Show "No matching events" only after a search has come back empty (bc653a4)
- **ui:** Mount the feedback snackbar on detail, settings and location screens (4b7088a)
- **event:** Keep every pending delete undoable until it is written (a4d1638)
- **onboarding:** Keep the onboarding step through rotation (4f99acc)

## 0.14.0 (2026-08-20)

### Features

- Make replies land, fold the attendee list, and make the grid felt; release 0.14.0 (#25) (cc852da)

## 0.13.0 (2026-08-19)

### Features

- Make the week grid readable, add guests, event colours and a new icon; release 0.13.0 (#24) (780df3f)

## 0.12.0 (2026-08-18)

### Features

- Let other apps open Foscal, and manage calendars; release 0.12.0 (#23) (9a60fdb)

## 0.11.0 (2026-08-18)

### Features

- Give Foscal a new mark, let Settings add calendars, and render HTML notes; release 0.11.0 (#22) (78195b3)

## 0.10.1 (2026-08-18)

### Features

- **widget:** Drop the month widget (3d04b23)
- **widget:** Rebuild the agenda widget around the date (d55410b)

### Fixes

- **agenda:** Land the agenda on today in one movement (79745e2)
- **ui:** Hold the calendar still from the destination that actually owns the transition (9c7c9bd)
- **settings:** Make Settings somewhere you navigate to, so back can come back (77c4d44)

## 0.10.0 (2026-08-18)

### Features

- **settings:** Add a real colour picker, and copy that sounds human (#19) (9bd8a3d)
- **event:** Rebuild the event detail screen around what you opened it for (2d1f3e4)
- Make the calendar one screen you zoom, not five you navigate between (936c290)
- **widget:** Add a month widget, and say where the agenda widget's events are (80a259d)

### Fixes

- **ui:** Let the app answer before it finishes animating (1fffbff)
- **month:** Swipe the month sideways only (e1a4d4b)
- **month:** Make one gesture move one month, and move it visibly (44e0005)
- **editor:** Stop the editor pushing an all-day event's end date out by a day (ae62f52)
- **agenda:** Give the agenda window edges to page against (f2c390f)
- **ui:** Hold the page still underneath the screens that slide over it (830ac96)

## 0.9.1 (2026-08-17)

### Fixes

- **build:** Keep the constructor R8 was stripping out of WorkManager's database (#18) (7210cb3)

## 0.9.0 (2026-08-17)

### Features

- **ics:** Add .ics import and export (#8) (a907786)
- **ics:** Round-trip recurrence exceptions through .ics; add Material You toggle (#10) (23c3b0b)
- **agenda:** Revamp the agenda around pinned month headers (#11) (00840c6)

### Fixes

- Repair reminder loss, timezone re-anchoring, forced-theme colors; modernize toolchain (#5) (4608dce)
- Repair recurring-reminder loss, duplicate local calendars, and week-view anchor/alignment (#6) (0d88cbf)
- Repair reminder misfires, stale alarms, and locale-frozen weekday labels (#7) (7358d7d)
- Repair reminder, recurrence, permission, and locale defects; dedupe view-model plumbing (#9) (9c10700)
- **reminders:** Make reminders arrive, and say why when they do not (#15) (b0d7cf9)

## 0.8.0 (2026-07-09)


