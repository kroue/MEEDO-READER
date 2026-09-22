# Building and handing out the reader app

The app is not on Google Play. It is built here and installed on each
reader's phone from the APK file, so there are two things to get right:
the build must be signed, and it must be signed with the *same* key every
time.

## Build it

```
gradlew.bat clean testDebugUnitTest assembleRelease
```

The signed APK lands at `app/build/outputs/apk/release/app-release.apk`
(about 3 MB — it is minified and shrunk, unlike the debug build).

Two things the build needs on the machine:

- **A JDK 17.** `gradle.properties` pins `org.gradle.java.home` to one,
  because Android Studio's bundled runtime updated itself to a version
  this project's Kotlin toolchain cannot parse. Gradle itself also needs
  `JAVA_HOME` set to that same JDK to start at all.
- **`app/google-services.json`.** Not in the repository on purpose: it
  names which backend project the build talks to, and committing one
  guarantees somebody eventually ships a build wired to the wrong
  database. The file in use points at the live project.

## Signing

`app/build.gradle.kts` reads `keystore.properties` from the project root:

```
storeFile=C:/Users/User/.meedo-signing/meedo-release.jks
storePassword=…
keyAlias=meedo-release
keyPassword=…
```

Neither that file nor the `.jks` is in the repository, and neither should
ever be. They are kept outside the project folder so that deleting or
re-cloning the working copy cannot destroy them.

**Back both up somewhere the office controls** — an encrypted drive, or
the same place the district keeps other credentials. Android will only
install an update over an existing app if it is signed with the same key.
If the key is lost, every reader has to uninstall the app (losing any
readings that had not uploaded) before the next version will install.

If no keystore is configured, the build still succeeds but produces
`app-release-unsigned.apk`, which no phone will install. That is the
symptom to look for.

### Replacing the key

While the app is only on office phones and can be uninstalled freely, the
key can still be swapped for one the office generates itself:

```
keytool -genkeypair -alias meedo-release -keyalg RSA -keysize 4096 \
  -validity 10950 -keystore meedo-release.jks
```

Point `keystore.properties` at the new file, rebuild, and have every
reader uninstall before installing the new APK. After the app is in real
use across the district this stops being cheap, so do it early or not at
all.

## Put it on a phone

Over USB, with developer options and USB debugging on:

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` keeps the phone's existing data — the local readings database, the
signed-in reader — which is what you want for an update. Otherwise copy
the APK to the phone and open it; Android will ask permission to install
from that source the first time.

## Looking at the screens without a phone

```
gradlew.bat testDebugUnitTest -Psnapshots
```

draws every screen, with made-up households, to PNGs in `app/build/design/`
— light and dark, a phone-sized view and a full-length one. It runs on this
PC (Robolectric), so it needs no phone, no emulator and no signed-in account.
The first run downloads Robolectric's Android image and takes a few minutes.

Ordinary `testDebugUnitTest` runs leave these out.

## Start-up speed

A sideloaded APK never gets the compiled-code profiles Google Play hands out,
so on a fresh install Android interprets the app and compiles it piecemeal
while readers are using it. `app/src/main/baseline-prof.txt` asks Android to
compile the app's own code at install instead; the libraries ship their own
profiles, and ProfileInstaller (pulled in by Compose) applies the lot.

Nothing to do per release — the build picks it up. If start-up ever regresses
badly, a profile recorded from real use on a device (the Baseline Profile
Gradle plugin with a Macrobenchmark module) is the next step.

## Version numbers

`versionCode` and `versionName` in `app/build.gradle.kts`. Raise
`versionCode` by one for every build handed out, even a fix: Android
refuses to install an APK whose `versionCode` is lower than the installed
one, and with equal codes there is no way to tell two builds apart on a
phone.
