# The release signing key

Android replaces an installed app only by an APK that carries the same certificate. Every
build handed to a phone is therefore signed with one key, and losing that key means the
installed app can never be updated again: the phone would have to uninstall it and install
a build signed with a new key. Uninstalling deletes the app's database, and with it every
day of the record older than the ten or so days Android keeps in its own log.

## Identity

| Field | Value |
|---|---|
| Keystore | `keystore.jks`, PKCS12 |
| Alias | `wake-tracker` |
| Owner | `CN=Wake Tracker, O=hawwwran` |
| Algorithm | RSA 4096, SHA384withRSA |
| Created | 2026-11-22 |
| Valid until | 2054-04-09 |
| Certificate SHA-256 | `C7:FB:C7:B6:38:DA:E1:80:FE:B7:59:A0:FC:47:CF:7E:14:CB:54:EF:ED:19:66:D7:C0:80:40:10:52:AC:12:A3` |
| File SHA-256 | `fc9ad77d95cfb815139d84cff40eb71b2f956e4e7d93aac068f8b4ffee69bf65` |

A PKCS12 keystore has one password for the store and the key, so `WT_KEYSTORE_PASSWORD` and
`WT_KEY_PASSWORD` hold the same value.

## Where it lives

| What | Where |
|---|---|
| `keystore.jks` | The root of this checkout. Git ignores it (`*.jks`). |
| The password | `~/.gradle/gradle.properties` (mode 600): `WT_KEYSTORE_PASSWORD`, `WT_KEY_ALIAS`, `WT_KEY_PASSWORD`. |
| The backup of both | The owner's password manager. |

Neither is in the repository or on GitHub. Release APKs are built on this computer only.

`app/build.gradle.kts` signs both build types with the key when `keystore.jks` is there, so
a debug build from Android Studio installs over a released one and the other way round.
Without the keystore a debug build still works, signed with the machine's own debug key,
and a phone with the real app refuses it.

## Checking a build

```bash
apksigner=$(ls ~/Android/Sdk/build-tools/*/apksigner | sort -V | tail -n 1)
"$apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

It must print the certificate SHA-256 from the table, in lower case and without colons:

```
Signer #1 certificate DN: CN=Wake Tracker, O=hawwwran
Signer #1 certificate SHA-256 digest: c7fbc7b638dae180feb759a0fc47cf7e14cb54efed1966d7c080401052ac12a3
```

The release script (`.project-manager/release`) makes this check before anything is
pushed.

## A new computer

1. Save `keystore.jks` from the backup into the root of the checkout, `chmod 600` it, and
   compare `sha256sum keystore.jks` with the table.
2. Add the three `WT_` lines to `~/.gradle/gradle.properties` and `chmod 600` that file.
3. `.project-manager/release check app` says whether the key, the JDK and the Android SDK
   are in place.

## If the key leaks or is lost

A leaked key lets its holder build an APK the phone accepts as an update, but only if they
can also publish it as a release of this repository, or talk the owner into installing it
by hand. Either way the answer is a new key: generate one as below, put the new
certificate's SHA-256 into `CERT_SHA256` in `.project-manager/release` and into this page,
and release. The phone then has to uninstall the app and install the new build, which
starts the record again from what Android's log still holds.

```bash
password=$(openssl rand -hex 24)
keytool -genkeypair -keystore keystore.jks -storetype PKCS12 -alias wake-tracker \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=Wake Tracker, O=hawwwran" -storepass "$password"
```
