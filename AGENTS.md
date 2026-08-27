# OpenFlight mobile repository guidance

This file applies to the standalone OpenFlight mobile repository. More specific
iOS guidance lives in `iosApp/AGENTS.md` and inherits these instructions.

## Purpose

This repository is the Kotlin Multiplatform and Compose Multiplatform companion app
for OpenFlight. The Android app must receive launch-monitor shots over local
Wi-Fi and Bluetooth, maintain shot history, synchronize Pi-owned state, and
eventually provide calibration controls and a rendered driving range.

Bluetooth is a first-class transport. The app must remain useful at a range
without a router or internet connection. Wi-Fi/SSE remains equally supported
for development, home installations, diagnostics, and installations where BLE
range or Raspberry Pi Bluetooth support is unsuitable.

Use incremental vertical slices. Do not replace working behavior with a
big-bang rewrite, and do not couple UI directly to Android networking,
Bluetooth, sensors, or wire-format parsing.

## Current project structure

```text
openflight-mobile/
├── androidApp/       Android application, lifecycle, permissions, and adapters
├── shared/           KMP contracts, parsers, state, repositories, and UI
├── iosApp/           Existing SwiftUI app and native iOS adapters
├── contracts/        Pinned backend contract snapshots (planned)
├── gradle/           wrapper and dependency version catalog
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

Current implementation status:

- `shared` contains the mobile schema-V1 shot model and JSON decoder, SSE
  parsing, an in-memory de-duplicating shot store, a sample shot, tests, and the
  initial Compose UI.
- `androidApp` contains the Android activity, ViewModel, local-network
  permission handling, and an `HttpURLConnection` SSE adapter.
- Android Wi-Fi/SSE has been validated against the backend mock server.
- Android BLE, persisted settings, authoritative club bootstrap, calibration,
  durable history, and native 3D rendering are not implemented yet.
- KMP declares Android, iOS ARM device, and iOS ARM simulator targets. The
  existing SwiftUI app is present but is not yet linked to the shared framework.

The present two-module layout is appropriate. Do not split `shared` into many
modules until measured build times, dependency isolation, or ownership needs
justify it.

## Toolchain and canonical commands

Use the checked-in Gradle wrapper from the repository root; do not require a global
Gradle or Kotlin installation.

Required development baseline:

- JDK 17
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0
- An Android emulator or physical device for app validation

Canonical checks from this directory:

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

Do not commit `local.properties`, Gradle caches, build outputs, IDE metadata, or
machine-specific SDK paths. Keep dependency and plugin versions centralized in
`gradle/libs.versions.toml`. Update the wrapper checksum when intentionally
changing Gradle.

## Backend and client contract boundaries

The companion API and the browser UI are separate contracts:

- Mobile BLE and SSE consume a small, flat, versioned event schema.
- The React browser uses Socket.IO and a larger `{ shot, stats }` payload.

Never make the Android model consume the browser payload. Do not add
browser-only fields to the mobile model as incidental cleanup.

The authoritative sources live in the separate OpenFlight backend repository:

- `src/openflight/api/contracts.py`: transport-neutral event models and
  encoders.
- `src/openflight/api/routes.py`: versioned HTTP routes and compatibility
  aliases.
- `src/openflight/api/dependencies.py`: operations injected into HTTP
  routes.
- `src/openflight/shot_stream.py`: SSE buffering, replay, heartbeat, and
  backpressure.
- `src/openflight/ble/protocol.py`: BLE UUIDs and binary framing.
- `src/openflight/ble/publisher.py`: Raspberry Pi GATT publishing and
  control dispatch.
- `src/openflight/server.py`: hardware-aware operations, authoritative club
  state, mock monitor, and completed-shot fan-out.
- `src/openflight/phone_orientation.py`: calibration validation and
  persistence.

The backend repository owns the canonical JSON Schemas and fixtures. Vendor a version-labelled
snapshot under `contracts/` and validate it in CI. Do not copy Python source or
depend on a sibling checkout at runtime.

## Mobile shot event schema V1

Required fields:

- `schema_version`
- `event_id`
- `timestamp`
- `club`
- `ball_speed_mph`
- `estimated_carry_yards`

Optional nullable fields currently consumed by the app:

- `club_speed_mph`
- `smash_factor`
- `launch_angle_vertical`
- `launch_angle_horizontal`
- `spin_rpm`
- `club_path_deg`
- `spin_axis_deg`

Reject unsupported schema versions and invalid required fields. Preserve
explicit JSON `null` values for unavailable measurements. Ignore unknown
additive fields so compatible backend additions do not break released apps.

The current deterministic fixture is
`iosApp/OpenFlightTests/Fixtures/shot_v1.json`. This is a temporary location.
Move canonical ownership to the backend contract bundle; this repository
should ultimately test a pinned snapshot rather than reaching into an iOS
source directory.

A contract change must update the backend encoder, BLE and SSE adapters,
canonical schemas and fixtures, Kotlin decoder, Android tests, and compatibility
documentation together.

## Versioned HTTP API

Prefer the general versioned resources for new client work:

- `GET /api/v1/capabilities`
- `GET /api/v1/state`
- `GET /api/v1/events`
- `GET /api/v1/club`
- `PUT /api/v1/club` with `{"club":"<wire-value>"}`
- `GET /api/v1/calibrations/iwr6843/orientation`
- `PUT /api/v1/calibrations/iwr6843/orientation`

The current Android SSE tracer still uses the compatibility endpoint
`GET /api/shots/stream`. Migrate it through a tested configuration change; do
not change the hard-coded URL without updating mock-server and transport tests.

Legacy routes remain supported during migration:

- `GET /api/shots/stream`
- `GET` or `POST /api/club`
- `GET` or `POST /api/calibration/iwr6843/orientation`

There is no production simulate-shot REST endpoint. Do not add one merely for
mobile development.

## Wi-Fi and SSE rules

- Default Raspberry Pi host: `raspberrypi.local:8080`.
- Android Emulator reaches the development host as `10.0.2.2:8080`.
- Send `Accept: text/event-stream` and disable response caching.
- SSE event names are `shot` and `club_changed`.
- The server sends an immediate heartbeat comment (`: ping\n\n`) and another
  every 15 seconds while idle.
- Use a 45-second idle timeout and bounded exponential reconnect delay.
- Treat HTTP 503 as exhausted stream capacity, not malformed shot data.
- The broker supports eight subscribers by default. Each queue holds eight
  events and drops the oldest pending event under backpressure.

On subscription, the server replays only the latest completed shot. It does
not retain an event log, emit SSE `id:` fields, or honor `Last-Event-ID`.
De-duplicate history by case-insensitive `event_id` across reconnects and
transport switches.

Club state differs from shot replay. A `club_changed` event is emitted only
when the authoritative value changes and is not seeded into a new legacy SSE
subscription. Bootstrap with `GET /api/v1/state`, or with `GET /api/club` while
using compatibility routes. A local club request is pending until the Pi
confirms or broadcasts the authoritative value.

Incremental SSE parsing must preserve the blank line that dispatches an event.
Maintain tests for LF and CRLF, comments, multiline data, partial byte
delivery, unknown events, malformed UTF-8/JSON, cancellation, idle timeout,
HTTP errors, reconnect, replay, and duplicate suppression.

## BLE contract

Published identifiers:

- Service UUID: `B6F633F2-E6E3-45AE-84B4-968ECCA2D9C7`
- Shot notify characteristic: `2B28F67E-9011-41D2-98ED-562B47D7A5E4`
- Control write/notify characteristic: `7E3B5D6C-7F10-4D4A-9C39-25E2B77F4A11`

Framing rules:

- Frame version is `1`.
- A frame is between 5 and 20 bytes.
- The five-byte header is: version byte, big-endian unsigned 16-bit sequence,
  zero-based fragment index, and fragment count.
- A frame carries at most 15 payload bytes.
- Fragment count is an unsigned byte, limiting one message to 3,825 payload
  bytes.
- Duplicate and out-of-order fragments are valid.
- A new sequence replaces an incomplete older message.
- Reject a zero fragment count, index outside the count, inconsistent counts
  within a sequence, unsupported versions, and invalid frame sizes.
- The same framing applies to shot notifications, control writes, control
  responses, and unsolicited club-state notifications.

Control commands are schema-V1 envelopes with `type`, `request_id`, and a
`payload` object. Supported types are:

- `set_club`
- `get_club`
- `iwr6843_orientation_calibration`

Responses echo `request_id`, include `ok`, and contain either `result` or
`error`. `club_changed` is an unsolicited state event, not a response to a
pending request. Serialize control writes and use write-with-response.

The server stores and replays the latest completed shot when a central
subscribes. BLE delivery is isolated from radar processing, recording, browser
Socket.IO, and SSE; preserve that isolation.

## KMP boundaries

Place portable behavior in `shared/src/commonMain`:

- versioned wire models and JSON serialization;
- BLE fragmentation/reassembly and SSE parsing;
- common transport events and errors;
- retry, connection, and transport-selection state machines;
- replay suppression, history, repositories, and use cases;
- club and calibration control envelopes;
- calibration mathematics and ball-flight simulation;
- screen state, user intents, and most Compose UI.

Keep platform behavior behind small interfaces:

- Android `BluetoothGatt`, scanning, and callback adaptation;
- Android HTTP socket/stream access;
- `SensorManager` sampling;
- permissions and local-network policy;
- lifecycle and background behavior;
- encrypted/persistent settings where platform facilities are required;
- native 3D renderer integration.

The UI renders typed state and sends intents. It must not parse JSON, assemble
BLE fragments, create raw GATT requests, or own reconnection timing.

Use one transport-neutral repository for BLE and Wi-Fi so history, club state,
errors, and connection status cannot drift. Transport switching must not
duplicate a replayed shot or overwrite newer authoritative state.

## Android implementation rules

- Use a lifecycle-aware Android state owner. Do not let an Activity or
  Composable own long-lived network or Bluetooth work.
- Make cancellation close active HTTP connections and GATT operations promptly.
- Model connecting, connected, permission-required, unavailable, recoverable
  error, and disconnected states explicitly.
- Persist the selected Pi host and transport; never permanently hard-code the
  emulator endpoint as the only choice.
- Keep cleartext HTTP disabled by default. Limit development/LAN exceptions to
  intended local hosts and document any expansion.
- Do not log raw calibration records, device identifiers, or other sensitive
  values unnecessarily.
- Preserve accessibility semantics, scalable text, dark theme behavior, and
  Reduce Motion equivalents.
- Treat background/foreground, rotation, process recreation, Bluetooth being
  toggled, and permission revocation as normal state transitions.

Android permissions vary by OS version. Keep manifest declarations and runtime
requests version-aware:

- `INTERNET` for HTTP/SSE.
- `ACCESS_LOCAL_NETWORK` where required by the targeted Android version.
- Android 12+ `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` for BLE central behavior.
- Only request legacy location permission on Android versions where BLE
  scanning actually requires it.

Do not claim BLE correctness from unit tests, an emulator, or fake GATT
callbacks. Android BLE changes require representative physical Android
hardware and a Raspberry Pi running the OpenFlight peripheral.

## Mock and deterministic development workflow

Run the backend without radar hardware from a separate OpenFlight backend
checkout:

```bash
cd /path/to/openflight
scripts/start-kiosk.sh --mock
```

The browser's **Simulate Shot** control sends the Socket.IO `simulate_shot`
command to the mock launch monitor. The resulting completed shot follows the
normal fan-out path to active SSE and BLE outputs. Use this flow for integration
testing rather than inventing a mobile-only shot path.

Use the canonical contract fixture for deterministic parser, framing,
repository, and UI tests. Random mock shots are appropriate for demonstrations
but not stable assertions.

BLE integration additionally requires backend BLE support, Raspberry Pi
Bluetooth hardware, and the server's BLE option. Preserve the BlueZ subscription
callback and advertising compatibility workarounds unless physical Pi evidence
supports changing them.

## Migration order

Prioritize offline range operation:

1. Freeze and vendor the backend-owned schema V1 fixture.
2. Define a common raw transport interface and move retry/state policy out of
   `AndroidSseShotSource` into common code.
3. Port BLE framing and control envelopes to `commonMain` with exhaustive tests.
4. Implement the Android BLE scanner/GATT adapter and physical-device flow.
5. Add authoritative bootstrap state and configurable Wi-Fi endpoints.
6. Feed BLE and SSE through one repository and test transport switching.
7. Migrate dashboard, history, settings, and club controls into shared Compose
   UI.
8. Port calibration math and add the Android sensor adapter.
9. Port flight simulation and add an Android native renderer, likely Filament,
   behind a renderer interface.
10. Add release signing, store builds, diagnostics, persistence migrations, and
    full CI gates.

Each step must leave the Android app buildable and demoable. Avoid maintaining
two independent implementations of portable behavior for longer than needed.

## Testing expectations

Write focused common tests before moving portable behavior. At minimum, cover:

- valid, nullable, malformed, and unsupported-version payloads;
- unknown additive JSON fields;
- SSE line endings, fragmentation, comments, multiline events, and reset;
- BLE frame boundaries, maximum size, duplicates, ordering, sequence rollover,
  stale partial replacement, and malformed headers;
- serialized control requests, response correlation, unsolicited state, and
  timeout/cancellation;
- replay de-duplication across reconnect and transport switch;
- bounded history and persistence migration;
- permission denial/revocation and unavailable hardware;
- network and Bluetooth interruption during active delivery;
- rotation, recreation, background/foreground, and teardown;
- accessibility and renderer lifecycle for UI changes.

Use local fake HTTP/GATT boundaries for deterministic unit tests, but keep
platform adapters thin enough that physical validation exercises the real
behavior rather than duplicating it in mocks.

## Validation and handoff

Before handing off Android or shared mobile work:

1. Run shared tests, Android unit tests, lint, and debug assembly.
2. Install and launch the APK on an emulator or physical device.
3. For Wi-Fi changes, verify initial connection, heartbeat survival, latest-shot
   replay, duplicate suppression, a newly simulated shot, disconnect, and
   reconnect against a backend started with `scripts/start-kiosk.sh --mock`.
4. For BLE changes, verify discovery, connection, subscription, replay, a new
   shot, club synchronization in both directions, disconnect/reconnect,
   transport switching, and background/foreground on physical Android and Pi
   hardware.
5. For permissions or lifecycle changes, test denial, later approval,
   revocation, rotation, process recreation, and Bluetooth/network toggles.
6. Run `git diff --check`.

Report changed files, commands run, test counts, emulator/device models and OS
versions, backend revision, transports exercised, and anything not physically
validated. Never present emulator or unit-test coverage as proof of BLE, sensor,
GPU, signing, or Raspberry Pi interoperability.
