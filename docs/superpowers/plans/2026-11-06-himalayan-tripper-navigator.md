# Himalayan 450 Tripper Navigator Implementation Plan

> **For agentic workers:** Follow each task in order. Keep the project in a buildable state and use the verification commands listed in each task.

**Goal:** Build a personal Android app that sends a live full-map route and turn guidance to the Himalayan 450 Tripper TFT while the phone screen is off, including saved-route guidance without mobile data.

**Architecture:** Native Kotlin app with Compose UI and a foreground navigation service. A protocol/transport layer connects to the Tripper. A MapLibre `MapView` hosted in a `Presentation` on a private virtual display renders into a MediaCodec input surface, then bounded RTP/UDP sends video to the TFT. This off-screen rendering path is a proof-of-concept gate, not yet device-validated. Routing stays on the regular internet network while only dash sockets bind to the bike Wi-Fi network.

**Tech Stack:** Kotlin, Android SDK/Gradle, Jetpack Compose, Android foreground location service, ConnectivityManager/WifiNetworkSpecifier, MapLibre Native Android, OpenFreeMap, JTS for robust route-corridor buffering, Android MediaCodec, `AtomicFile` route persistence, Valhalla motorcycle costing through the FOSSGIS public demo for personal low-volume V1, and submitted-search Nominatim geocoding. Room is not needed for this single-user local route store.

## Current implementation status (2026-11-06)

The Kotlin app, destination search/routing, saved-route store, bounded MapLibre offline-region downloads, Tripper transport/protocol, MapLibre virtual-display renderer, H.264/RTP path, and location foreground service are implemented. `./gradlew testDebugUnitTest assembleDebug` passes. The APK has not been installed: no Android device is attached to ADB. Bike protocol compatibility, full TFT rendering, phone screen-off behavior, actual OpenFreeMap pack size/coverage, offline tile rendering, and heat/battery comparison remain device-validation gates. See [the offline map decision](../../decisions/offline-map-policy.md).

**Spec:** [2026-11-06-himalayan-tripper-navigator-design.md](../specs/2026-11-06-himalayan-tripper-navigator-design.md)

## Global Constraints

- Android-first; target OnePlus 13R/OxygenOS 16, Tripper firmware 11.63; iOS and Play Store are deferred.
- Personal APK only in v1; no Raspberry Pi.
- TFT output is 526×300 H.264 over RTP/UDP 5000; control ports 2000 and 2002 are user-observed and must be validated.
- Full moving map, blue route path and turn information required; no voice prompts or live traffic in v1.
- Start navigation while app activity is visible; continue from a location foreground service after screen-off and secure lock.
- Search, route calculation and rerouting are online. Saved routes include route geometry, maneuvers and permitted offline map data. Offline rerouting and GPX import are deferred.
- Never simulate rider position. Stale GPS, missing offline data, Wi-Fi loss, and unavailable rerouting must be visible.
- Never globally bind the process to the dash Wi-Fi; bind only dash sockets so internet requests can still use mobile data.
- No Google Maps tiles in offline packs. Recheck provider terms, attribution and limits before release.
- Keep coordinates, route geometry, SSID credentials and protocol secrets out of logs by default.
- Verify physical screen-off and keyguard behavior on OnePlus before claiming compatibility. Measure heat/battery against comparable RE-app rides; make no quantified improvement promise before measurements.

## Review Focus

- Dash Wi-Fi has no internet while cellular data is active: route API succeeds and dash UDP sockets use the selected dash network.
- Screen turns off and keyguard locks: fresh location and stream continue without a stale frozen frame.
- GPS becomes stale or unavailable: rider marker stops updating, a clear GPS-lost state appears, and no simulated movement occurs.
- Connectivity is lost during a saved route: cached map/geometry/maneuvers remain usable; off-route shows rerouting unavailable.
- Dash reports incompatible authentication/firmware or disconnects mid-stream: bounded reconnect and clear error; no false connected state.

---

### Task 1: Validate providers and protocol feasibility

**Files:**
- Create: `docs/decisions/route-provider.md`
- Create: `docs/decisions/tripper-protocol-validation.md`
- Create: `docs/decisions/compatibility-matrix.md`

**Interfaces:**
- Consumes: approved product spec and user's Himalayan 450 / firmware 11.63 / OnePlus 13R details.
- Produces: chosen route/geocoding provider with terms/cost/India coverage; versioned packet/session notes with evidence; explicit go/no-go criteria for real-bike testing.

- [x] Compare current routing/geocoding options for India coverage, route geometry/maneuvers, rerouting, personal-use terms and cost. Use Valhalla/FOSSGIS for the owner's low-volume personal V1; keep its endpoint replaceable and do not claim service availability.
- [x] Verify OpenFreeMap offline limits and MapLibre offline-region behavior; bound saved-route downloads to provider terms and add attribution.
- [x] Review OpenMotoDash source at a pinned commit for handshake/control/keepalive/RTP behavior. Separate source observations from unverified compatibility claims; do not copy source wholesale.
- [x] Define a parked-hardware protocol probe using the owner's own dash and RE app pairing. No ride test until the parked stream is stable; do not change bike firmware.
- [x] Record unresolved vendor behavior and exact compatibility assertions in the decision documents.

### Task 2: Create Android app shell and build baseline

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/reconnector/app/MainActivity.kt`
- Create: `app/src/test/java/com/reconnector/app/BuildSmokeTest.kt`

**Interfaces:**
- Produces: `MainActivity` launches Compose navigation shell; Gradle debug APK builds and installs on connected Android device.

- [ ] Create a failing build smoke test/verification target that asserts app id, minimum SDK and launch activity.
- [ ] Run `./gradlew testDebugUnitTest assembleDebug` and verify it fails before the app scaffold exists.
- [ ] Add Kotlin Android application module, Compose dependencies and version catalog; keep the module free of map/protocol code.
- [ ] Implement one Compose home screen with actions for Connect, Search destination, and Saved routes.
- [ ] Run `./gradlew testDebugUnitTest assembleDebug`; install with `adb install -r app/build/outputs/apk/debug/app-debug.apk` and launch on the connected device.

### Task 3: Model routes, maneuvers and offline state

**Files:**
- Create: `app/src/main/java/com/reconnector/navigation/model/RoutePlan.kt`
- Create: `app/src/main/java/com/reconnector/navigation/model/Maneuver.kt`
- Create: `app/src/main/java/com/reconnector/navigation/model/NavigationState.kt`
- Create: `app/src/test/java/com/reconnector/navigation/model/RouteProgressTest.kt`

**Interfaces:**
- `RoutePlan(id: String, destinationName: String, points: List<GeoPoint>, maneuvers: List<Maneuver>, downloadedRegionId: Long?)`
- `Maneuver(pointIndex: Int, instruction: String, distanceMeters: Int, kind: ManeuverKind)`
- `NavigationState` variants: `Idle`, `Preparing`, `Navigating`, `GpsLost`, `DashDisconnected`, `OfflineRerouteUnavailable`, `Stopped`.
- `RouteProgress.update(location: LocationFix, route: RoutePlan): ProgressResult` returns current segment, next maneuver and remaining distance; it never advances based on elapsed time alone.

- [ ] Write tests for route progress on-route, skipped GPS samples, stale fix, and off-route position.
- [ ] Run `./gradlew testDebugUnitTest` and confirm those tests fail before implementation.
- [ ] Implement immutable route/maneuver models and distance-to-segment route progress calculation.
- [ ] Treat location older than configured freshness window as stale; return GPS-lost and retain last valid progress without animation.
- [ ] Run route progress tests.

### Task 4: Online destination and routing flow

**Files:**
- Create: `app/src/main/java/com/reconnector/navigation/provider/RouteProvider.kt`
- Create: `app/src/main/java/com/reconnector/navigation/provider/SelectedRouteProvider.kt`
- Create: `app/src/main/java/com/reconnector/navigation/data/DestinationRepository.kt`
- Create: `app/src/main/java/com/reconnector/navigation/ui/DestinationSearchScreen.kt`
- Create: `app/src/test/java/com/reconnector/navigation/provider/RouteProviderTest.kt`

**Interfaces:**
- `RouteProvider.search(query: String): Result<List<Destination>>`
- `RouteProvider.route(origin: GeoPoint, destination: Destination): Result<RoutePlan>`
- `RouteProvider.reroute(origin: GeoPoint, destination: Destination): Result<RoutePlan>`

- [ ] Implement fake-provider contract tests for success, no results, HTTP failure, malformed geometry, and timeout.
- [ ] Implement selected provider client from Task 1's decision; configure credentials only via local ignored config/environment, never source control.
- [ ] Add destination search and route preview; show online-only status when no saved data exists.
- [ ] Verify online reroute replaces route only after a valid response; on failure preserve current route and show recoverable error.
- [ ] Run provider tests and a manual request on a test destination without exposing API keys in logs.

### Task 5: Save routes and offline map data

**Files:**
- Implemented: `app/src/main/java/com/dharmil/reconnector/navigation/store/SavedRouteStore.kt`
- Implemented: `app/src/main/java/com/dharmil/reconnector/navigation/store/OfflineMapRepository.kt`
- Implemented: `app/src/main/java/com/dharmil/reconnector/navigation/store/RouteCorridorGeometry.kt`
- Implemented: `app/src/test/java/com/dharmil/reconnector/navigation/store/OfflineDownloadBudgetTest.kt`

**Interfaces:**
- `SavedRouteRepository.save(route: RoutePlan): SavedRouteResult`
- `OfflineMapRepository.download(route: RoutePlan, detourBufferMeters: Int): Flow<DownloadProgress>`
- `OfflineMapRepository.isReady(routeId: String): Boolean`

- [x] Test route persistence round-trip, corridor geometry including a tight switchback, offline download budget, and response-size cap.
- [x] Persist route data locally with `AtomicFile`; skip Room overhead for the small personal route store.
- [x] Integrate MapLibre offline regions with OpenFreeMap Liberty, bounded download settings, User-Agent, and attribution.
- [x] Mark map ready only when MapLibre reports the pack complete; preserve partial downloads as resumable.
- [ ] Disable cellular and verify saved route rendering from cache after process restart on the phone and TFT.

### Task 6: Tripper Wi-Fi and protocol transport

**Files:**
- Create: `app/src/main/java/com/reconnector/dash/TripperNetworkManager.kt`
- Create: `app/src/main/java/com/reconnector/dash/TripperProtocol.kt`
- Create: `app/src/main/java/com/reconnector/dash/TripperTransport.kt`
- Create: `app/src/test/java/com/reconnector/dash/TripperProtocolTest.kt`

**Interfaces:**
- `TripperNetworkManager.requestConnection(ssid: String): Flow<DashNetworkState>`
- `TripperProtocol.authenticate(network: Network, identity: DashIdentity): Result<DashSession>`
- `TripperTransport.sendControl(session: DashSession, packet: ControlPacket)`
- `TripperTransport.sendVideo(session: DashSession, rtpPacket: ByteArray)`

- [ ] Add packet serialization/parsing fixtures from protocol evidence; test invalid/truncated packet and wrong-session responses.
- [x] Request RE Wi-Fi using Android platform API; never hardcode supplied SSID/password into app constants.
- [x] Bind only control/video sockets with `Network.bindSocket`; provider calls retain the default network path by design.
- [x] Implement authentication, keepalive, projection start/stop, and bounded socket lifecycle from the documented OpenMotoDash reference.
- [ ] Add integration test harness using a local fake UDP dash; then perform parked connection test against firmware 11.63.

### Task 7: Virtual-display renderer and H.264/RTP pipeline

**Files:**
- Create: `app/src/main/java/com/reconnector/dash/render/NavigationFrame.kt`
- Create: `app/src/main/java/com/reconnector/dash/render/MapPresentationRenderer.kt`
- Create: `app/src/main/java/com/reconnector/dash/render/NavigationOverlayView.kt`
- Create: `app/src/main/java/com/reconnector/dash/video/H264Encoder.kt`
- Create: `app/src/main/java/com/reconnector/dash/video/RtpPacketizer.kt`
- Create: `app/src/test/java/com/reconnector/dash/video/RtpPacketizerTest.kt`

**Interfaces:**
- `NavigationFrame(width: Int = 526, height: Int = 300, route: RoutePlan, progress: ProgressResult, gpsState: GpsState, connectivity: ConnectivityState)`
- `MapPresentationRenderer.start(surface: Surface, size: Size, route: RoutePlan)` / `update(progress: RouteProgress)` / `stop()`
- `H264Encoder.start(onEncodedFrame: (EncodedFrame) -> Unit)` / `render(frame: NavigationFrame)` / `stop()`
- `RtpPacketizer.packetize(frame: EncodedFrame): List<ByteArray>`

- [ ] Prove color-bar View → private `VirtualDisplay` → MediaCodec input `Surface` and decode a 526×300 frame while the physical screen is off.
- [ ] Host MapLibre `MapView` plus route/turn overlay in a lifecycle-owned `Presentation`; test updates and release while the Activity is paused. Keep `MapSnapshotter` + EGL upload as fallback.
- [ ] Verify 526×300 route line, rider position, next turn, GPS-lost/off-route states, missing offline tiles and attribution in encoded output.
- [ ] Test RTP sequence/timestamp/fragmentation with deterministic H.264 sample frames and packet size constraints from protocol validation.
- [x] Implement the hardware AVC MediaCodec surface path, encoder lifecycle/backpressure, and explicit failure if no compatible encoder is available.
- [ ] Decode captured local output and verify resolution/frame cadence; test parked dash display.

### Task 8: Foreground navigation service and lifecycle

**Files:**
- Create: `app/src/main/java/com/reconnector/navigation/service/NavigationService.kt`
- Create: `app/src/main/java/com/reconnector/navigation/service/NavigationNotification.kt`
- Create: `app/src/main/java/com/reconnector/navigation/service/NavigationCoordinator.kt`
- Create: `app/src/test/java/com/reconnector/navigation/service/NavigationCoordinatorTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- `NavigationCoordinator.start(route: RoutePlan, network: Network): Result<Unit>`
- `NavigationCoordinator.stop(reason: StopReason)`
- Service publishes `StateFlow<NavigationState>`; notification actions are `StopNavigation` and `ReconnectDash`.

- [ ] Test start/stop, permission denial, stale GPS, Wi-Fi loss/recovery, encoder failure, notification stop action, and resource release.
- [x] Declare Android foreground-service location/connected-device types and their specific permissions.
- [x] Start the foreground service from a visible user action; no partial CPU lock is used without a measured need.
- [x] Integrate GPS updates, saved route progress, renderer/encoder, and dash transport with bounded cleanup.
- [ ] Start navigation, lock screen, enable Battery Saver and verify service updates on iQOO, then OnePlus 13R/OxygenOS 16; record deviations.

### Task 9: End-to-end integration and personal APK

**Files:**
- Create: `app/src/androidTest/java/com/reconnector/NavigationScreenOffInstrumentedTest.kt`
- Create: `docs/testing/oneplus-13r-acceptance.md`
- Modify: `README.md`

**Interfaces:**
- Personal APK artifact: `app/build/outputs/apk/release/app-release.apk`.
- Acceptance record: versioned device/firmware test matrix and comparable thermal/battery observations.

- [x] Run unit tests and assemble the debug APK: `./gradlew testDebugUnitTest assembleDebug`.
- [ ] Execute parked acceptance: route online, connect TFT, validate full moving map, lock phone, verify stream and actual GPS updates, stop and restore dashboard.
- [ ] Execute offline saved-route acceptance with cellular disabled and route deviation; verify warning and no offline reroute claim.
- [ ] Run controlled ride only after parked acceptance; do not interact with phone in motion. Compare RE baseline and app using similar duration/weather/charging/placement; record temperature warnings, battery delta, disconnects and navigation defects.
- [ ] Build personal release APK. Keep signing key outside repository; document owner-only backup and sideload install steps.
- [ ] Review license notices, location disclosure, logs, debug flags, and failure copy before marking personal beta ready.

## Execution gates

- Task 1 provider and protocol decisions are reviewed before Tasks 4–7 use external services or real-bike communication.
- A physical TFT is required for protocol integration; without it, stop at fake-dash integration and label hardware validation outstanding.
- OnePlus 13R/OxygenOS 16 is required for screen-off compatibility acceptance.
- No live ride until parked protocol and lock-screen acceptance pass.
- Each task ends in a buildable, tested state and should be committed separately; never include API keys, SSID passwords, signing keys, or precise route diagnostics.
