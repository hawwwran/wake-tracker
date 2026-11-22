# Wake Tracker

Android app that shows when the phone's screen was on: a total for every day, the first
and the last time of the day, and summaries per day, week, workweek and weekend. The
record is read-only. Nothing in the app edits or deletes what was recorded.

A phone can also share its record with other phones, end-to-end encrypted, through a small
relay (`server/`). A parent's phone then shows a child's record in a tab of its own.

The repository has two parts, released apart: the Android app (everything outside
`server/`) and the relay (`server/`). [`version.json`](version.json) says which version of
each the sources are at.

## How it records, and why that is reliable

The app does not watch the screen. Android itself writes every screen-on and screen-off
into its usage log (the one behind Digital Wellbeing) and keeps it for about ten days,
whether this app runs or not. Wake Tracker copies that log into its own database:

- every hour in a background job (`data/SyncJob.kt`),
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

On a phone where the app was installed from a downloaded APK, Android greys that switch
out as a "restricted setting" (since Android 13; for usage access since Android 15). The
owner lifts it on the app's page in the system settings: the menu at the top right, "Allow
restricted settings", which appears once the greyed-out switch was tapped. The app's first
screen says so and has a button to that page. An install over `adb` is not restricted.

### Working by itself

Android puts apps that are rarely opened to sleep, and phones of some makers (vivo among
them) are strict about it: the hourly job then runs a few times a day, or not until the
app is opened. The record does not suffer, for the reason above. A phone that shares its
record does: the paired phones see it as it was at the last upload.

The settings page therefore shows when the app last ran in the background and whether
battery saving may hold it back, with a button that asks Android to leave the app alone
(`data/Background.kt`). On a vivo phone the owner also allows "Autostart" for the app in
the phone's settings; no app can do that for itself.

What nothing can work around: an app the owner force-stops stays stopped until it is
opened again. Within ten days nothing is lost even then.

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

## Sharing a record

Off until a relay's address is entered under "Sharing" on the settings page. From then on
the phone uploads its record there after every read that brought something new
(`share/Uploader.kt`). With no connection the days wait: the next read tries again, and so
does a job that Android holds until there is a network (`share/UploadJob.kt`).

Reading another phone's record takes its **pairing code**:

1. On the phone that shares: settings, "Show pairing code".
2. On the phone that reads: settings, "Pair another phone", scan the code, give the phone
   a name.

The reading phone then has a tab per paired phone on its first page, with the same pages
as for its own record. Under the tabs stand the two times that say how fresh it is: when
that phone last sent to the relay, and when this phone last fetched. A phone can read
several others, and several phones can read one. The name is the reader's own and can be
changed later; the copy of the other record is fetched while its tab is open and kept on
the phone, so it also shows without a connection.

What keeps it private:

- The record is encrypted on the phone before it leaves (AES-256-GCM), with a key derived
  from a secret that exists only on that phone and in its pairing code. The code goes from
  screen to camera; the relay never gets it and cannot read what it stores
  ([`server/README.md`](server/README.md) lists what it does see).
- Each file is sealed to its device and its day, and padded to whole blocks, so the relay
  cannot swap days around and learns little from sizes.
- Nothing is hidden on the phone that shares. Its settings page says that it uploads and
  where to, and has "Stop sharing" (the relay deletes its copy) and "New pairing code"
  (everyone holding the old code is cut off at once).

`share/ShareCrypto.kt` and `share/Blobs.kt` are the file format, `share/Relay.kt` the
requests, `share/Puller.kt` the reading side.

## Build and test

The Gradle daemon is pinned to JDK 21 in `gradle.properties`; the Android SDK path comes
from `local.properties` (`sdk.dir=...`, not in git).

```bash
./gradlew :app:testDebugUnitTest    # JVM unit tests, no device needed
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.hawwwran.waketracker GET_USAGE_STATS allow   # usage access, without the settings page
server/test.sh                      # the relay, on PHP's built-in server
```

The logic that decides what the record says is plain Kotlin with no Android in it and is
covered by the unit tests: building sessions from events (`SessionBuilderTest`), days and
periods across midnight and clock changes (`PeriodsTest`), totals and averages
(`StatsTest`), reading from the database across gaps (`HistoryTest`), and the copying
itself against a fake log (`SynchronizerTest`). Sharing is tested the same way: the
encryption, the file formats and the pairing code (`FormatsTest`), and one phone uploading
while another reads, through a relay in memory that drops connections, loses devices and
tampers with files (`SharingTest`).

`LiveRelayCheck` runs the app's real relay client against a deployed relay. It is skipped
unless given an address:

```bash
WAKE_TRACKER_RELAY=https://relay.example ./gradlew :app:testDebugUnitTest --tests '*LiveRelayCheck*' --rerun-tasks
```

## Updates

The app is in no store. It asks GitHub for the newest `android/v*` release of this
repository, at most twice a day and on demand from the settings page, and shows a bar
across the top while a newer version is waiting. Tapping it shows what changed; one more
tap downloads the APK, checks it against the size and SHA-256 GitHub lists, and hands it
to Android's installer (`update/Updater.kt`).

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
   `android/v<version>` and an asset with `name`, `size`, `digest` (`sha256:<hex>`) and
   `browser_download_url` (`http://localhost:8765/...`), on
   `/repos/hawwwran/wake-tracker/releases`.
3. `adb reverse tcp:8765 tcp:8765`, then
   `adb shell "run-as com.hawwwran.waketracker sh -c 'echo http://localhost:8765 > files/update-source'"`
   and restart the app.

Remove `files/update-source` afterwards. To go back to the lower version, `adb install -r -d`.

## Releasing

Releases are made from Project Manager (`~/git/project-manager`) with the script in
`.project-manager/release`. It has three actions:

| Action | Tag | What it does |
|---|---|---|
| Android app | `android/vX.Y.Z` | Raises `versionCode` and `versionName` in `app/build.gradle.kts`, runs the tests, builds and signs the APK on this computer, checks its certificate, pushes the bump and the tag, and attaches the APK to a GitHub release. From then on the installed apps offer it. |
| Relay server | `server/vX.Y.Z` | Sets the version in `server/VERSION.md`, runs `server/test.sh`, packs `server/` into a tarball, pushes the bump and the tag, and attaches the tarball to a GitHub release. |
| Deploy the relay | | Takes the tarball of a server release from GitHub, uploads it over FTP to the host named in `deploy.conf`, and runs `server/test.sh` against the relay there. `data/` on the host, where the phones' uploads live, is never touched. |

All three support a dry run, which does everything except pushing and uploading.

`deploy.conf` is not in git: where the owner's relay runs is not this repository's
business. [`deploy.conf.example`](deploy.conf.example) shows its four lines. The FTP
password is not in it either; lftp takes it from `~/.netrc`.

The Android release notes are what the app shows when it offers the update, as plain text.
The script puts a rule under them and the install hint for readers on GitHub below it; the
app shows only the part above the rule.
