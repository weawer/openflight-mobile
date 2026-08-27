# OpenFlight mobile

This is the standalone Kotlin Multiplatform and Compose Multiplatform home for
the OpenFlight iOS/Android companion app. It contains the existing SwiftUI app
under `iosApp/` and the incremental shared/Android implementation.

The first tracer slice shares the mobile schema-V1 model, JSON decoder, sample
shot, tests, and a small Compose screen. `androidApp` is the Android entry
point; `iosApp` is the existing native iOS application; and `shared` produces
the reusable Android library and iOS frameworks.

## Prerequisites

- JDK 17 (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk` on Arch/CachyOS)
- Android SDK platform 37 and Build Tools 36.0.0
- `ANDROID_HOME=$HOME/Android/Sdk`
- macOS with Xcode 16+ only when building or integrating the iOS frameworks

Open this repository root in Android Studio or VS Code.

## Build and test

```bash
./gradlew :shared:testAndroidHostTest
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidApp:lintDebug
./gradlew :androidApp:assembleDebug
```

With an emulator or Android device visible in `adb devices`:

```bash
./gradlew :androidApp:installDebug
adb shell am start -n org.openflight.mobile/.MainActivity
```

The APK is written below `androidApp/build/outputs/apk/`. Do not commit
`local.properties`; Gradle uses `ANDROID_HOME` for the SDK location.

## Local mock server

Start OpenFlight from a separate backend checkout without Raspberry Pi
hardware:

```bash
cd /path/to/openflight
scripts/start-kiosk.sh --mock
```

The Android emulator connects to the host through
`http://10.0.2.2:8080/api/shots/stream`. On Android 17 it asks for local
network access before connecting. Use the web UI's **Simulate Shot** control to
send a completed mock shot through the real Socket.IO-to-SSE fan-out path.

## iOS application

On macOS, open `iosApp/OpenFlight.xcodeproj`. The SwiftUI app remains the
releasable iOS implementation while portable behavior moves incrementally into
`shared`. See `iosApp/README.md` for signing, simulator, physical-device, and
Raspberry Pi validation instructions.

## Repository boundary

The [OpenFlight backend repository](https://github.com/weawer/openflight) owns
the canonical HTTP, SSE, BLE, and JSON contract. This repository owns the mobile
clients and will carry a pinned,
versioned contract snapshot under `contracts/`. The current shared fixture
temporarily remains at `iosApp/OpenFlightTests/Fixtures/shot_v1.json` until that
backend-owned bundle is published.

## License

OpenFlight mobile is licensed under the GNU Affero General Public License v3.0.
See `LICENSE`.
