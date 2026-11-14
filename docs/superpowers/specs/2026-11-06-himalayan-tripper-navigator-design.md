# Himalayan 450 Tripper Navigator — v1 Design

**Status:** Approved V1 scope; implementation and physical validation in progress  
**Date:** 2026-11-06  
**Scope:** Android personal APK; implementation baseline and pending device-validation gates.

## 1. Problem and outcome

The Royal Enfield app keeps a dim phone display active while projecting Google Maps to the Himalayan 450 Tripper TFT. The user reports overheating with the phone both in a pocket and on a sun-exposed mount. The new app should project its own live full-map navigation to the TFT while the handset display is physically off and locked, reducing display-related power and heat. Heat reduction is an objective to measure, not a guaranteed result.

### Confirmed target setup

- Motorcycle: Royal Enfield Himalayan 450 with Tripper TFT.
- Tripper firmware: 11.63.
- Phone: OnePlus 13R, OxygenOS 16; an iQOO phone is available for development/ADB testing.
- RE app currently installed: 10.1.23 (45), used as a compatibility reference/fallback.
- Bike Wi-Fi SSID: `RE_LT09_260125`; dash IP: `192.168.1.1`.
- Observed ports: UDP 2000 and 2002 control; UDP 5000 video. These are user-provided observations pending independent protocol validation.
- Android first. iOS, Play Store, Raspberry Pi, live traffic, spoken prompts, and GPX import are out of v1.
- Distribution: personal APK for the user's own phone.

## 2. V1 user workflow

1. Open the app and search for a destination while internet is available.
2. Preview the route and its turns on the phone.
3. Optionally save/download it for offline use. The app shows estimated download size/progress and verifies required route/map data is present before marking it ready.
4. Connect to the Tripper using Android's Wi-Fi network request/selection flow; confirm the selected dash and complete protocol authentication.
5. Start navigation while the app is visible. A persistent foreground-service notification indicates active navigation and offers stop/reconnect actions.
6. Turn the phone display off and lock the device. The TFT continues to show a full moving map, blue route path, rider position, next turn and distance, remaining distance/ETA, and GPS/connectivity state.
7. When online, recalculate route after deviation. When offline on a saved route, continue along its cached route and turns. If the rider leaves the route with no data, clearly say rerouting is unavailable; do not invent a route or simulate motion.
8. On stop/disconnect, stop location/encoder work, send the best-effort projection-off control sequence, release Wi-Fi and wake resources, and return to the app's idle state.

No phone interaction is expected while riding. Configure and validate while parked; retain the RE app as the fallback during trials.

## 3. Product requirements and acceptance

### Functional

- Connect/authenticate with the specified Tripper firmware and stream video on UDP 5000 while sending appropriate controls/keepalives on control ports.
- Render a custom 526×300 full-map navigation frame with blue route path and legible turn information, then encode H.264 through Android MediaCodec and packetize for the TFT.
- Continue streaming and obtaining real location with the phone display off and device locked, after navigation is started in the foreground.
- Search destinations and calculate routes online; recalculate online after deviations.
- Save a route with route geometry, maneuver list, and licensed offline map resources for its corridor. Permit following it offline using fresh GPS. Offline recalculation is excluded.
- Clearly distinguish current GPS, stale GPS, unavailable location permission, unavailable network, dash disconnected, missing offline coverage, and rerouting unavailable states. Never show simulated progress as live navigation.
- Personal APK build/install path; signing key remains local and is backed up securely by the owner.

### Quality and measurable acceptance

- TFT map, route line, position, and turn state update correctly in a stationary/parked integration setup and on a short controlled ride.
- Screen-off acceptance means physical panel off and keyguard active; the TFT map continues changing as real location changes. A black overlay or frozen video frame fails.
- Online acceptance: destination search, initial route, turn progression, and online reroute work.
- Offline acceptance: after a route is saved and its download verified, disable cellular data and verify the route map/geometry/turn prompts follow actual location. Leaving route offline must show rerouting unavailable.
- Recovery acceptance: restore a stopped app session, reconnect after brief Wi-Fi loss, and show an explicit failure if the dash cannot resume.
- Heat/power: compare the RE app baseline and candidate over similar duration, ambient conditions, charging state, mounting/pocket placement, and route. Record battery delta, available temperature/thermal warnings, disconnects, and correctness. Claim improvement only from repeated comparable observations; no fixed target is set yet.
- Verify on OnePlus 13R/OxygenOS 16 before claiming target compatibility. iQOO is a development device only unless user confirms exact model/OS and asks to include it in the supported matrix.

## 4. Proposed architecture

```text
Compose UI (setup, destination search, preview, saved trips)
              │ user starts navigation
              ▼
Navigation foreground service (lifecycle, notification, GPS, route progress)
       ├── Routing/geocoding client (default internet/cellular network)
       ├── Tripper Wi-Fi manager (ConnectivityManager, explicit network handle)
       ├── Route state (online route + offline saved geometry/maneuvers)
       ├── Off-screen 526×300 renderer (map, blue route, position, turn HUD)
       ├── MediaCodec H.264 encoder
       └── Tripper protocol/transport (UDP control + RTP/video, bound per socket
                                      to the Tripper Wi-Fi Network)

Local persistent storage: saved route, maneuver data, downloaded map region,
settings, and protocol diagnostics with privacy-conscious retention.
No backend in v1.
```

The process must not bind globally to the bike Wi-Fi, which has no internet. Bind only Tripper sockets to the selected Android `Network`; leave routing, search, and tile requests on the normal internet network. This dual-network behavior is a critical hardware test.

### Screen-off execution

Start an Android location foreground service from a visible activity after explicit user action, before the display turns off. The implemented renderer hosts a MapLibre `MapView` inside a `Presentation` on an app-owned `VirtualDisplay`, with the virtual display output connected to the configured MediaCodec input `Surface`. The navigation activity does not own that map view or its lifecycle. This avoids phone screen capture and bitmap readback on the primary path. The MapLibre/virtual-display combination is not yet proven on OxygenOS 16.

This exact MapLibre/virtual-display/MediaCodec combination is not proven yet. First build a small color-bar surface pipeline, then replace the test view with MapLibre and verify continuous frames while locked and screen-off on OxygenOS 16. Keep `MapSnapshotter` plus EGL upload as a fallback if MapView does not render reliably on the virtual display. Use only the minimum wake/resource locks proven necessary and release the service, presentation, map, display and codec on stop/error. A persistent notification and user stop control are required.

## 5. Technology decisions

### Recommended v1 stack

- Language/platform: Kotlin, Android native.
- UI: Jetpack Compose for setup and route workflows; service and connection logic independent of UI lifecycle.
- Map renderer/data: MapLibre Native Android 13.7.1 with the OpenFreeMap Liberty style. The app implements offline regions around a 500 m route corridor for zooms 8–15, with conservative stop thresholds at 2,800 tiles or 32 MB, a 4 MB per-resource body cap, no more than four shared HTTP requests, and an identifying User-Agent. The app attributes OpenStreetMap/OpenMapTiles/OpenFreeMap. Offline retrieval/rendering and the off-screen MapLibre lifecycle remain unverified on the target phone. OpenFreeMap's public service is free and as-is with no availability warranty; recheck current terms before release.
- Routing/geocoding: use Valhalla's motorcycle costing through the FOSSGIS public demo for this personal, low-volume V1. Its community endpoint is fair-use and has no service guarantee, so keep the base URL replaceable. Use submitted-search geocoding with current Nominatim policy limits and no autocomplete. Keep Kurviger via GraphHopper as the motorcycle-focused upgrade candidate if paid access and route-storage terms are confirmed. Do not use the public OSRM car demo.
- TFT transport: Android `ConnectivityManager`/`Network`, `WifiNetworkSpecifier` as compatible with current OS, Java/Kotlin UDP sockets bound individually to the Tripper network, and protocol module informed by public OpenDash source.
- Video: Android `MediaCodec` hardware AVC/H.264 input-surface encoding, Android `VirtualDisplay` rendering, bounded RTP packetization and UDP delivery.
- Persistence: `AtomicFile` for local route geometry and maneuvers; MapLibre offline-region storage for map resources and download state. No account/cloud sync. Room is not needed for the small single-user route store.
- Build: Gradle Kotlin DSL, Android Studio, personal debug/release APK. Keep signing secrets out of repository.

MapLibre is only a renderer; the map provider and routing service are distinct dependencies. Google Maps Tile API content cannot be used for offline packs, so the offline basemap will not be Google tiles. OSM's public raster tile server also disallows bulk/offline prefetching; OpenFreeMap is the evaluated alternative, subject to its own download limits/terms.

## 6. Protocol research and safety boundary

OpenDash is an independent project whose README and code describe an unofficial reverse-engineered Tripper protocol: Wi-Fi discovery/pairing, a control/authentication plane, and H.264/RTP video. Use source as a research lead and document provenance/licensing; do not assume its protocol is correct or stable. Before implementation claims compatibility, trace exact handshake, session, keepalive, projection on/off, packet framing, acknowledgements, and reconnect behavior against source and safely captured traffic from the user's own bike/app. Do not bypass authentication, modify bike firmware, or use private credentials. No bike interaction or ride testing until a parked TFT connection test succeeds.

Failure behavior is fail-visible: stale GPS freezes position and displays GPS lost; it must not advance the rider along a route based on elapsed time. Failed network/video/auth transitions to a clear disconnected state and bounded retry, not silent stale-map success.

## 7. Privacy and security

- Location and saved routes remain on-device in v1, except requests sent to the selected routing/geocoding/map provider. Explain this before route search/download.
- Request only location, nearby Wi-Fi/device, and notification permissions required by selected Android versions and flows; do not request contacts/call logs.
- Store no RE account password or access tokens. Pairing material, if required by protocol, should be minimized and stored with Android platform secure storage.
- Do not log precise coordinates, SSID credentials, packet secrets, or route geometry by default. Offer user-controlled redacted diagnostics for troubleshooting.
- No crash analytics/cloud upload in v1 unless separately decided.

## 8. Verification plan

1. Protocol unit fixtures: parse/serialize exact observed control packets and RTP packet boundaries; no real target required.
2. Renderer/encoder harness: first render color bars through a private virtual display into MediaCodec and decode the output; then host MapLibre in a Presentation, render deterministic route/GPS fixtures, and verify 526×300 dimensions, route visibility, and HUD text while the physical screen is off.
3. Offline provider test: download within published limits, terminate/restart app, disable internet, render tiles and follow saved route; verify missing tile/route coverage is explicit.
4. Android lifecycle test: start service visibly, lock screen, Battery Saver on/off, screen-off interval, notification stop, app process death/recovery. Physical OnePlus required for OxygenOS policy behavior.
5. Bench/parked bike test: connect to SSID, authenticate, show moving test fixture only while parked, verify screen-off stream, connection recovery, projection-off cleanup. Do not use simulated movement on a public road.
6. Controlled ride acceptance: actual GPS only, safe familiar route, no phone interaction while moving, verify turn progression/off-route/reconnect. Compare heat and battery with baseline across repeated comparable rides.
7. Independent security and usability review before distributing APK beyond the owner.

## 9. Deferred/rejected for v1

- iOS and App Store delivery: defer; separate platform lifecycle/encoder/network work later.
- Play Store: defer; personal APK is confirmed.
- GPX import: defer by user choice.
- Voice prompts, live traffic, social/ride history, accounts/cloud sync: defer.
- Offline rerouting: defer; communicate limitation and retain saved route guidance.
- Google basemap tiles for offline use: reject due to provider restrictions; use an appropriately licensed offline-capable map source.
- Raspberry Pi and Android Auto: outside requested phone-to-existing-TFT solution.

## 10. Remaining validation decisions

1. Check the public Valhalla endpoint on representative Indian routes and confirm current fair-use conditions before relying on it for a ride. No live traffic is required.
2. Verify offline tile download/reopen behavior and route-corridor coverage with mobile data disabled.
3. Validate Tripper firmware 11.63 handshake, video stream, map rendering, and screen-off behavior while parked.
4. Compare heat and battery with the RE app under similar duration, ambient conditions, charging state, and placement before claiming improvement.

## 11. Requirement traceability

| Requirement | Design decision | Acceptance evidence |
|---|---|---|
| Full map/blue path/turns on existing TFT | Off-screen renderer → H.264 → Tripper UDP stream | Parked TFT and controlled-ride visual/turn checks |
| Screen off and locked | Visible-start foreground navigation service; no screen mirroring | OnePlus screen-off/keyguard lifecycle test |
| Less display load/heat | Phone panel not used for map frames; measure remaining GPS/Wi-Fi/encode cost | Comparable RE baseline/candidate rides |
| Online navigation | Search/route/reroute provider | Connected route and deviation test |
| Saved offline route | Save geometry/maneuvers and permitted offline map region | Cellular-off route following test |
| Safe loss handling | Fresh GPS requirement; explicit stale/offline state; no simulation | GPS/network fault injection and ride test |
| Android-first personal app | Kotlin/Compose, Gradle APK; iOS/store deferred | Install/run on OnePlus 13R |

## Evidence reviewed

- User-provided target details and needs, confirmed in conversation (accessed 2026-11-06).
- OpenDash public source inspected at commit `a33d830`; reference only, not copied into this project: https://github.com/cotton1234/tripper
- Android foreground service and location documentation: https://developer.android.com/develop/background-work/services/foreground-services ; https://developer.android.com/develop/sensors-and-location/location/permissions
- MapLibre Native Android offline region API: https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/index.html
- OpenFreeMap current offline limits, attribution, and availability statement: https://openfreemap.org/
- Google Map Tiles API offline restrictions: https://developers.google.com/maps/documentation/tile/policies
- OSM public tile server policy against bulk/offline prefetch: https://operations.osmfoundation.org/policies/tiles/

## Assumptions and unresolved risks

- The port numbers and SSID/IP are user observations; they still need validation against the bike and exact RE firmware.
- OpenFreeMap's public service and terms may change; offline limits and attribution must be rechecked at implementation/release.
- Android foreground location support does not prove that OxygenOS will keep Wi-Fi, MediaCodec, and sockets alive for a whole ride. Device validation is mandatory.
- This design has not measured baseline heat/battery and makes no quantified improvement promise.
- Routing/geocoding provider is not selected; implementation planning must wait for that decision.
