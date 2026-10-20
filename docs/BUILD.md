# Building Phoenix Link

The APK builds independently of the Windows project. Runtime streaming requires
the separate [Windows host](https://github.com/RazvanManolache/DLSS5-Everything/releases),
its Phoenix components and a compatible externally supplied model.

## Tools

- [Git](https://git-scm.com/downloads) and [PowerShell 7](https://github.com/PowerShell/PowerShell/releases).
- [JDK 17](https://adoptium.net/temurin/releases/?version=17), with `JAVA_HOME` set.
- [Android SDK command-line tools or Android Studio](https://developer.android.com/studio).
  Set `ANDROID_HOME` to the SDK directory.
- SDK platform `android-35`, build-tools `35.0.0`, NDK `27.3.13750724`,
  CMake `3.22.1`. Install using Android Studio's SDK Manager or:

  ```powershell
  sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.3.13750724" "cmake;3.22.1"
  ```

The checked-in wrapper downloads **Gradle 8.11.1** with a pinned SHA-256.
Android Gradle Plugin is **8.5.2**. First builds need Internet access for these
tools, Maven dependencies and pinned Pyrowave/Granite sources. Accept SDK licenses
when prompted. Tested build host: Windows x64; CI checks a clean Windows checkout.

## Debug APK

```powershell
git clone https://github.com/RazvanManolache/dlss5-everything-android-companion.git
cd dlss5-everything-android-companion
./scripts/Build-Android.ps1 -Abi arm64-v8a
```

Output: `local-build/PhoenixLink/PhoenixLink-preview.apk`. This uses the local
Android debug signing identity. It is for development, not the public signed release.

The script prepares pinned codec dependencies in ignored `.build-deps/` and builds
the APK and unit-test sources. It does **not** run tests, install anything or use a GPU.

## Run JVM tests and compile the release

```powershell
./scripts/Prepare-Pyrowave.ps1
cd android
./gradlew.bat testDebugUnitTest assembleRelease assembleDebugAndroidTest "-PclientAbi=arm64-v8a" --no-daemon
```

JVM tests run without a device. Instrumentation is compiled, not executed by this
command. On macOS/Linux use `./gradlew` instead of `gradlew.bat`; those hosts have
not been qualified for this release. Native staging is generated in
`local-build/native-current`; it can be regenerated after relocation.

Without signing variables, release output is
`android/app/build/outputs/apk/release/app-release-unsigned.apk`.
To sign with your own identity, set all four environment variables before Gradle:

- `PHOENIX_ANDROID_KEYSTORE`: absolute path to your private keystore.
- `PHOENIX_ANDROID_STORE_PASSWORD`: keystore password.
- `PHOENIX_ANDROID_KEY_ALIAS`: signing alias.
- `PHOENIX_ANDROID_KEY_PASSWORD`: key password.

Then run `assembleRelease`; output is `app-release.apk` in the same directory.
Keep the keystore and passwords outside the checkout and preserve them for updates.
Never commit them. The public APK uses a dedicated release identity and is not debuggable.
A development-key installation may need uninstalling before installing it; export
any needed settings first. Uninstalling removes local pairing data.

Verify a signed APK with Android SDK Build Tools:

```powershell
apksigner verify --verbose --print-certs path/to/app-release.apk
```

## Optional runtime integration tests

The Windows host fixture intentionally references a **sibling** checkout at
`../dlss5-everything`. Clone
[DLSS5 Everything](https://github.com/RazvanManolache/DLSS5-Everything) there and use
its documented .NET/Windows build prerequisites. This is needed only for integration
tests, not the APK build. Real-inference tests also need a compatible host package
and externally supplied model.

Use an already-booted, disposable Android 14+ emulator:

```powershell
./scripts/Prepare-Pyrowave.ps1
./scripts/Test-AndroidEmulator.ps1 -Serial emulator-5582
```

The script only accepts explicit `emulator-NNNN` serials, changes that emulator's
permissions, installs test APKs and cleans up test pairing state. It never selects
a physical device. `-Backend phoenix -PackageRoot <private-package>` opts into real
PC processing; `-Pyrowave` opts into GPU codec checks, which may fail on emulator
drivers. Logs/screenshots/credentials remain in ignored `local-build/`.
Face-demo tests require an externally supplied fixture; no copyrighted game image
is included here. Historical results and their limits are documented separately.
