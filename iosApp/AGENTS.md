# OpenFlight companion app guidance

This file applies to `iosApp/` and inherits the repository-wide instructions in
`../AGENTS.md`. Do not repeat or override root guidance here. If a future
Android or shared mobile module is created outside `iosApp/`, add equivalent
scoped guidance there or move this file to the narrowest common mobile parent.

## Purpose and direction

The current app is a native SwiftUI iOS/iPadOS companion for the OpenFlight
launch monitor. It receives completed shots, displays recent history, renders
an animated driving range, changes the club used for the next shot, and uses
phone motion sensors to calibrate an IWR6843 radar mount.

Develop it incrementally toward a hybrid iOS/Android app using Kotlin
Multiplatform and Compose Multiplatform. Keep the existing iOS app releasable
throughout; do not replace it in a big-bang rewrite.

## Repository lineage and useful history

The `iosApp/` tree originated as the backend repository's `ios/` subtree on branch
`feat/iOS-ble`. Commit `b053194` is the imported feature tip and `98466df` is
the main commit merged into that branch. The former standalone
`openflight-app` repository was verified to exactly match this subtree at that
point.

Read these commits when changing the behavior they introduced:

1. `6faba27`: mobile schema V1, 20-byte BLE framing, shot notifications,
   shared JSON fixture, and the first SwiftUI app.
2. `bc223cf`: Wi-Fi/SSE sibling transport, heartbeat handling, reconnects,
   shared decoding, and server fan-out.
3. `dacc073`: BlueZ subscription callbacks that Bless 0.3.0 does not wire up
   correctly by itself.
4. `cf18702`: in-memory history, ball-flight simulation, and the RealityKit
   driving range as a separate renderer.
5. `2a3adc9`: CoreMotion calibration, bidirectional controls, server-side
   validation, persistence, and club controls.
6. `52221ad`: Pi-owned authoritative club state synchronized across Socket.IO,
   SSE, and BLE.
7. `ef5dbf6`: Raspberry Pi BLE advertising compatibility work; also consult
   `docs/ios-ble.md` in the backend repository before changing BlueZ or
   advertising behavior.
8. `5b379d3`: the current, more visible ball tracer.
9. `b053194`: iOS build, signing, simulator, and physical-device documentation.

## Contract boundaries

There are two distinct client contracts:

- The React/Vite browser uses Socket.IO and receives a larger
  `{ shot, stats }` payload. Its contract lives primarily in
  `ui/src/types/shot.ts`, `ui/src/services/socketService.ts`, and
  `ui/tests/e2e/` in the separate backend repository.
- Mobile BLE and SSE use the same smaller, flat, versioned V1 payload. Its
  server contract lives in `src/openflight/ble/protocol.py`,
  `src/openflight/ble/publisher.py`, and
  `src/openflight/shot_stream.py` in the separate backend repository.

Do not make the mobile model consume the browser payload or add browser-only
fields to it incidentally. A mobile contract change must update the server
encoder, both transports, the canonical fixture, client decoders, tests, and
compatibility documentation together.

Other authoritative backend sources are:

- `src/openflight/server.py`: REST routes, Socket.IO events, mock monitor,
  active-club ownership, and completed-shot fan-out.
- `src/openflight/phone_orientation.py`: calibration schema validation and
  persistence.

### Mobile shot schema V1

Required fields:

- `schema_version`
- `event_id`
- `timestamp`
- `club`
- `ball_speed_mph`
- `estimated_carry_yards`

Optional nullable fields consumed by the app:

- `club_speed_mph`
- `smash_factor`
- `launch_angle_vertical`
- `launch_angle_horizontal`
- `spin_rpm`
- `club_path_deg`
- `spin_axis_deg`

Reject unsupported schema versions and preserve explicit `null` values. Treat
`OpenFlightTests/Fixtures/shot_v1.json` as the canonical deterministic mobile
fixture; keep previews and transport tests schema-compatible with it.

## BLE contract

- Service UUID: `B6F633F2-E6E3-45AE-84B4-968ECCA2D9C7`
- Shot notify characteristic: `2B28F67E-9011-41D2-98ED-562B47D7A5E4`
- Control write/notify characteristic: `7E3B5D6C-7F10-4D4A-9C39-25E2B77F4A11`
- Frame version: `1`.
- Maximum frame size: 20 bytes.
- Five-byte header: version byte, big-endian unsigned 16-bit sequence,
  zero-based fragment index, fragment count.
- Each frame carries at most 15 payload bytes; the fragment count is an
  unsigned byte, so one message is at most 3,825 payload bytes.
- Duplicate and out-of-order fragments are valid. A different sequence
  replaces an incomplete message. Reject zero counts, indexes outside the
  count, inconsistent counts within a sequence, unsupported versions, and
  frames outside 5...20 bytes.
- The same framing applies to shot notifications, control writes, control
  responses, and unsolicited club-state notifications.
- Serialize control writes and use write-with-response semantics.

BLE control commands are schema-V1 envelopes with `type`, `request_id`, and a
`payload` object. Supported types are `set_club`, `get_club`, and
`iwr6843_orientation_calibration`. Responses echo `request_id`, include `ok`,
and contain either `result` or `error`. A `club_changed` notification is an
unsolicited schema-V1 event, not a response to a pending request.

The BLE publisher stores the latest completed shot and replays it when a
central subscribes. It uses bounded queues and drops the oldest unsent shot
under backpressure. Preserve delivery isolation: Bluetooth failure must never
block radar processing, recording, the browser UI, or SSE.

Do not claim BLE correctness from Simulator or CoreBluetooth mocks. Validate
transport changes with a physical iPhone and Raspberry Pi; once Android exists,
also test representative Android hardware. Preserve the BlueZ callback and
advertising workarounds unless real Pi evidence justifies changing them.

## Wi-Fi, SSE, and club state

- Default host: `raspberrypi.local:8080`.
- Shot stream: `GET /api/shots/stream` with `Accept: text/event-stream`.
- Club state: `GET /api/club`; set it with `POST /api/club` and JSON
  `{"club":"<wire-value>"}`.
- Phone calibration: `GET` or `POST /api/calibration/iwr6843/orientation`.
- SSE event names: `shot` and `club_changed`.
- Heartbeats are SSE comments (`: ping\n\n`) immediately on connection and
  every 15 seconds while idle. The iOS client retries after its 45-second idle
  timeout, with bounded exponential backoff.
- The broker allows eight subscribers by default and returns HTTP 503 when
  full. Each subscriber queue holds eight events and drops the oldest unsent
  event under backpressure.

On every subscription, the server seeds the stream with the latest completed
shot, if any. It does not keep an event log, send SSE `id:` fields, or honor
`Last-Event-ID`; reconnect replay is only the latest in-memory shot. Client
history must therefore de-duplicate by `event_id`.

Club state behaves differently from shot replay: `club_changed` is broadcast
only when the authoritative value changes and is not seeded into a new SSE
subscription. On connection or transport change, fetch `GET /api/club` rather
than assuming the locally selected club is current. A club request is not final
until the Pi replies or broadcasts the authoritative value. The same Pi-owned
state fans out through Socket.IO, SSE, and BLE.

The empty line terminating an SSE event is significant. Parser/network changes
need tests for LF and CRLF, comments, multiline data, partial byte delivery,
unknown events, malformed JSON, cancellation, idle timeout, reconnect, replay,
and duplicate suppression.

## Current app boundaries

- `OpenFlight/ContentView.swift`: dashboard composition, transport selection,
  club state, history, and navigation.
- `OpenFlight/BluetoothManager.swift`: CoreBluetooth lifecycle and controls.
- `OpenFlight/WiFiShotClient.swift`: endpoint construction, SSE streaming,
  timeout, and reconnect behavior.
- `OpenFlight/BLEFrameReassembler.swift`: transport-neutral framing.
- `OpenFlight/SSEEventParser.swift`: incremental SSE parsing.
- `OpenFlight/ShotEvent.swift`: mobile schema V1.
- `OpenFlight/PhoneControl.swift`: club and calibration control envelopes.
- `OpenFlight/PhoneOrientation.swift`: pure calibration mathematics.
- `OpenFlight/PhoneOrientationMonitor.swift`: CoreMotion sampling.
- `OpenFlight/DrivingRange/`: flight calculation and RealityKit rendering.
- `OpenFlightTests/` and `OpenFlightUITests/`: fixture, unit, and UI coverage.

Keep parsing, framing, calibration math, history, state machines, and flight
simulation independent of SwiftUI, UIKit, CoreBluetooth, CoreMotion, and 3D
frameworks. Views render typed state and send intents; they do not parse wire
payloads or own reconnection policy.

The Xcode project uses file-system-synchronized groups. New Swift files inside
the app or test directories are normally discovered automatically; do not edit
`OpenFlight.xcodeproj/project.pbxproj` merely to add a source file. Do not
commit personal signing teams or bundle identifiers.

## Mock and sample-shot workflow

Run the separate backend repository without radar hardware using:

```bash
cd /path/to/openflight
scripts/start-kiosk.sh --mock
```

Add `--ble` when BLE delivery is needed and the Pi has the optional dependency
and working Bluetooth hardware. The browser's **Simulate Shot** control sends
the Socket.IO `simulate_shot` command to `MockLaunchMonitor`. This creates a
normal completed shot and sends it through every active output, including SSE
and BLE. There is no mobile simulate-shot REST endpoint; trigger through the
browser or a Socket.IO client, then observe the mobile transport.

Use `OpenFlightTests/Fixtures/shot_v1.json` for deterministic parser, framing,
repository, and UI tests. Do not substitute random `MockLaunchMonitor` output
where stable assertions are needed.

## Kotlin Multiplatform migration

Prefer tracer-bullet vertical slices. A sensible order is: shared fixture and
wire model, parsers/framing, history/repository, one embedded Compose screen,
platform transports, then the range. Avoid parallel duplicate implementations
that drift for long periods.

Place portable behavior in `commonMain`:

- versioned wire models and serialization;
- BLE framing and SSE parsing;
- history, replay suppression, repositories, and use cases;
- calibration math and flight simulation;
- connection and range state machines;
- most Compose UI.

Keep platform behavior behind small interfaces:

- CoreBluetooth on iOS and BluetoothGatt APIs on Android;
- CoreMotion on iOS and SensorManager on Android;
- permissions, settings, lifecycle, and platform networking policy;
- 3D rendering.

Share trajectory data, scene description, camera plan, timing, and renderer
commands, but keep graphics APIs out of common code. Retain the working
RealityKit renderer on iOS initially. Build an Android renderer, likely with
Filament, and require visual, performance, lifecycle, and device parity before
retiring native iOS rendering.

When Kotlin/Android modules are introduced, document their canonical Gradle
build and test commands here in the same change. The Gradle project lives at
the repository root; from `iosApp/`, invoke its checked-in wrapper as follows:

```bash
../gradlew :shared:testAndroidHostTest
../gradlew :androidApp:testDebugUnitTest
../gradlew :androidApp:lintDebug
../gradlew :androidApp:assembleDebug
../gradlew :androidApp:installDebug
```

## Implementation and validation rules

- Preserve accessibility identifiers used by UI tests and honor Reduce Motion.
- Model permissions, malformed input, cancellation, app background/foreground
  transitions, disconnects, reconnects, and unavailable hardware explicitly.
- Do not change deployment targets, bundle identifiers, signing teams, UUIDs,
  endpoint paths, wire club values, or schema versions as incidental cleanup.
- Keep App Transport Security exceptions limited to local networking.
- Prefer explicit versioned adapters over dictionaries or scattered platform
  conditionals.
- Add focused tests for portable behavior before moving it into shared code.

Use Xcode 26.4 with the current Kotlin 2.4.10 toolchain. Discover an installed
simulator instead of assuming its name:

```bash
xcrun simctl list devices available
```

From the repository root, run unit tests with an installed simulator UUID:

```bash
xcodebuild test \
  -project iosApp/OpenFlight.xcodeproj \
  -scheme OpenFlight \
  -destination "platform=iOS Simulator,id=SIMULATOR_UUID" \
  -only-testing:OpenFlightTests \
  CODE_SIGNING_ALLOWED=NO
```

Run the full scheme, including UI tests, for UI, navigation, accessibility, or
lifecycle changes by removing `-only-testing:OpenFlightTests`.

Simulator tests do not validate BLE, motion input, local-network routing,
actual GPU performance, signing, installation, or Pi interoperability. Use a
physical iOS 17+ device and Raspberry Pi for those changes. On hardware, verify
connection/reconnection, latest-shot replay without duplicate history, a new
shot, authoritative club synchronization in both directions, background and
foreground behavior, and any changed calibration or range behavior. Report the
exact devices and checks performed. If macOS, Xcode, or hardware is unavailable,
state those unverified areas plainly rather than presenting them as tested.

Before handing off app work, summarize changed files, automated checks, both
transport paths affected, physical-device coverage, and any backend or Android
work still required.
