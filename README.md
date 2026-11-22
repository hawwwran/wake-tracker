# Wake Tracker

Android app that shows when the phone's screen was on: a total for every day, the first
and the last time of the day, and summaries per day, week, workweek and weekend. The
record is read-only. Nothing in the app edits or deletes what was recorded.

## How it records, and why that is reliable

The app does not watch the screen. Android itself writes every screen-on and screen-off
into its usage log (the one behind Digital Wellbeing) and keeps it for about ten days,
whether this app runs or not. Wake Tracker copies that log into its own database:

- every three hours in a background job (`data/SyncJob.kt`),
- on every start of its process: a restart of the phone, an update of the app,
- whenever the app is opened, and once a minute while it stays open.

So there is no service to keep alive and no permanent notification, and a phone that kills
or restricts background apps loses nothing: the next read picks up where the last one
stopped. On first start the app takes over what the log already holds, so the record
begins about ten days before the install.

`data/Synchronizer.kt` is the copying, built to be repeated and interrupted safely:

- A read starts a little before the point the record reaches to, and the database skips
  events it already has (primary key: time and type).
- The events of a read and the new reach are stored in one transaction.
- A read that returns nothing at all moves nothing, because "no events" and "the log
  refused to answer" look the same from outside.
- If the app could not read for longer than the log keeps its entries, the stretch that
  cannot be recovered is stored as a gap. A gap shows as a hole in the charts and as
  "not recorded" on its days. It is never passed off as time with the screen off.

What is stored are the raw events (screen on and off, lock screen shown and hidden,
shutdown and startup), never totals. Sessions and sums are computed when a page is shown
(`model/Sessions.kt`, `model/Stats.kt`), so a change to how things are counted applies to
the whole record, and the two settings below can be switched back and forth.

The one permission that matters is "usage access", which the owner switches on in the
system settings; the app asks for it on first start. The database is part of Android's
backup, so the record moves to the owner's next phone.

## Reading the record

Pick day, week, workweek (Monday to Friday) or weekend at the top, then swipe or use the
arrows to go back in time. The title jumps back to now.

- **Day**: the total, first on, last on, how many times the screen came on, the day as a
  chart and every stretch as a line. Tapping a stretch on the chart finds its line, and
  the other way round.
- **Week, workweek, weekend**: the total, the daily average, the average first and last
  time, and a line per day with that day's chart under a shared hour axis. Tapping a day
  opens it; the back button returns.

Averages count only days recorded from start to end, so today, still running, does not
drag them down.

Two settings, on the page behind the cog:

- **A day starts at** (default 4:00). Time after midnight and before that hour belongs to
  the day before, so an evening that runs past midnight stays one day and "last on" is the
  time the phone was put down for the night.
- **Count lock-screen wakes** (default on). A wake where the screen lit up but the phone
  was not unlocked: a notification, a glance at the clock. They are drawn lower and dimmer
  in the charts. Switched off, only the times the phone was in use count, also for first
  and last on.

The same page shows where the record stands: where it starts, how far it reaches, when the
log was last read, and any gaps.

## Build and test

The Gradle daemon is pinned to JDK 21 in `gradle.properties`; the Android SDK path comes
from `local.properties` (`sdk.dir=...`, not in git).

```bash
./gradlew :app:testDebugUnitTest    # JVM unit tests, no device needed
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.hawwwran.waketracker GET_USAGE_STATS allow   # usage access, without the settings page
```

The logic that decides what the record says is plain Kotlin with no Android in it and is
covered by the unit tests: building sessions from events (`SessionBuilderTest`), days and
periods across midnight and clock changes (`PeriodsTest`), totals and averages
(`StatsTest`), reading from the database across gaps (`HistoryTest`), and the copying
itself against a fake log (`SynchronizerTest`).

## Updates

The app is in no store. It asks GitHub for the newest release of this repository, at most
twice a day and on demand from the settings page, and shows a bar across the top while a
newer version is waiting. Tapping it shows what changed; one more tap downloads the APK,
checks it against the size and SHA-256 GitHub lists, and hands it to Android's installer
(`update/Updater.kt`).

Android installs an update only if it carries the same signature as the installed app.
[The release signing key](docs/signing.md) says which key that is, where it lives and how
it is backed up. Debug builds are signed with it too, so either kind installs over the
other. An update keeps the app's data.

The first time, Android asks whether Wake Tracker may install apps, and Google Play
Protect may offer to scan an app it does not know; declining that dialog aborts the
install.

### Trying an update without releasing

A debug build reads the address of the releases API from a file, when there is one, so the
whole flow can be run against this computer:

1. Build a debug APK with a higher `versionCode` and `versionName` than the one installed.
2. Serve a "list releases" answer that points at it: one release with `tag_name`
   `v<version>` and an asset with `name`, `size`, `digest` (`sha256:<hex>`) and
   `browser_download_url` (`http://localhost:8765/...`), on
   `/repos/hawwwran/wake-tracker/releases`.
3. `adb reverse tcp:8765 tcp:8765`, then
   `adb shell "run-as com.hawwwran.waketracker sh -c 'echo http://localhost:8765 > files/update-source'"`
   and restart the app.

Remove `files/update-source` afterwards. To go back to the lower version, `adb install -r -d`.

## Releasing

Releases are made from Project Manager (`~/git/project-manager`) with the script in
`.project-manager/release`. Its one action, "Android app", raises `versionCode` and
`versionName` in `app/build.gradle.kts`, runs the tests, builds and signs the APK on this
computer, checks its certificate, pushes the bump and the tag `vX.Y.Z`, and attaches the
APK to a GitHub release. From then on the installed app offers it. A dry run does
everything except pushing.

The release notes are what the app shows when it offers the update, as plain text. The
script puts a rule under them and the install hint for readers on GitHub below it; the app
shows only the part above the rule.
