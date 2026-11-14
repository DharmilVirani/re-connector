# Project definition: Himalayan 450 Tripper navigator

## Confirmed needs
- Android-first custom app for OnePlus 13R, OxygenOS 16; Royal Enfield app 10.1.23 (45); Himalayan 450 Tripper firmware 11.63.
- Connect directly to the existing Tripper TFT, using its RE Wi-Fi (SSID `RE_LT09_260125`, IP `192.168.1.1`) and observed protocol ports UDP 2000/2002 control and UDP 5000 video.
- Display a full moving map, blue route path, and turn guidance on the TFT while phone display is off; keep the phone in the pocket or mount. Voice prompts are not required in v1. Reduce display-related battery use and heat.
- Map/routing may use a non-Google provider if it offers sufficiently useful navigation. Use hybrid navigation: plan/search and calculate motorcycle routes online; let the rider save the route, turn instructions, and permitted map data for offline guidance. Offline maps and turns are required for saved routes. Offline route recalculation is deferred for v1. For the personal, low-volume prototype, use Valhalla motorcycle costing through the FOSSGIS public demo with a replaceable endpoint; its fair-use endpoint has no availability guarantee. Use submitted-search geocoding under the current Nominatim policy, without autocomplete. Online routing is acceptable otherwise. iOS is a possible later phase, not an initial requirement.
- No Raspberry Pi. User wants a custom app; public OpenDash source can inform protocol research. First release is a personal APK for the OnePlus 13R; Play Store distribution and iOS are deferred.

## Current implementation direction
- Android-native Kotlin app. Compose handles setup, destination search, route turns, saved trips, and offline downloads. An Android location foreground service owns Wi-Fi connection, route progress, MapLibre rendering, H.264 encoding, and RTP/UDP transport. The current off-screen renderer is a MapLibre `MapView` inside a `Presentation` on a private `VirtualDisplay`, rendered to the MediaCodec surface. This path is implemented but still needs physical-device validation.
- OpenFreeMap's Liberty style provides the basemap; MapLibre offline regions cache a 500 m corridor around a saved route from zoom 8 through 15. The app pauses a download at 2,800 tiles or 32 MB, caps each OpenFreeMap resource at 4 MB, limits shared HTTP concurrency to four, and identifies the app with its User-Agent. This is a conservative implementation guard below the provider's published cap; actual offline downloads and rendering still need device validation. MapLibre is the renderer, not the map-data or routing provider.
- Valhalla motorcycle costing through the FOSSGIS public demo is selected for personal low-volume V1; Nominatim handles submitted searches without autocomplete. Keep both endpoints replaceable, and keep bike UDP sockets bound individually to RE Wi-Fi while provider requests use the default internet network.
- V1 route entry recommendation: in-app destination search/planning and save/download. Defer GPX import for v1 (user-approved).
- Product behavior recommendation: online search/routing/rerouting when connected; explicit save-for-offline action downloads route geometry, maneuver instructions, and map data only from a source licensed for offline use; offline mode follows the saved route with live GPS and turn guidance. No traffic or rerouting while offline; clearly state when off-route and offline.
- Build on the open-source Tripper protocol evidence in OpenDash rather than assume Royal Enfield implementation details. Treat protocol compatibility as a testable, version-specific hypothesis.

## Outcomes and acceptance
- While the TFT is connected and a route is active, phone panel can be physically off and the phone remains in a documented operating state; TFT map position follows fresh GPS, turn guidance advances, and route deviation is handled as specified.
- Reconnect after interruption and present clear stale-GPS/network/TFT failure states; never animate simulated movement as live navigation.
- Measure battery, temperature/thermal warnings, disconnects, and navigation correctness against the RE app under comparable rides. No improvement percentage promised before measurement.

## Exclusions for initial release
- iOS, Raspberry Pi, accounts/cloud sync, social/ride analytics, multimedia/call integration, live traffic unless selected provider supports it and user prioritizes it.
- No bike firmware changes or use of private RE credentials. Offline recalculation is deferred; if the rider goes off route without data, warn and continue providing safe route context until online recalculation is available.

## Risks and unknowns
- Screen-off TFT projection is generated from app-owned rendering, not Android screen capture; the service should start while the app activity is visible and keep running under normal secure lock, subject to OnePlus/OxygenOS testing.
- OpenFreeMap Liberty is selected for the offline-capable basemap, subject to its current usage terms; Valhalla/FOSSGIS is selected for low-volume personal V1 motorcycle routing, with no uptime guarantee; Nominatim handles submitted-search geocoding without autocomplete. Do not use Google Maps Tile API tiles for offline packs.
- Offline map caching needs a map-data/tile source whose license and service terms allow downloads; the public OpenStreetMap tile server prohibits bulk/offline prefetching. MapLibre is a renderer, not a map-data license or routing service. Google Maps Platform Map Tiles API also disallows offline use and most prefetch/storage; do not plan to cache Google basemap tiles. Google Maps app itself documents offline driving only inside downloaded area and without traffic or alternate routes.
- Exact RF/connection, pairing, authentication, packet framing and TFT compatibility must be confirmed against source/code and the actual bike; public protocol is unofficial.
- The app should generate its own map frames rather than mirror the handset display. Navigation is intended to continue with the display off and device locked through an active foreground location service; test OnePlus/OxygenOS lock, battery saver, Wi-Fi streaming, and recovery behavior on-device. Do not promise this before hardware validation.
- Android/OxygenOS background limits, location permissions and Wi-Fi routing may interrupt the service.
- Public routing and map services are best-effort and can change their policies or availability. V1 has no live traffic; commercial or self-hosted routing remains a later option if public-service reliability is inadequate.
- User priorities for live traffic, rerouting, spoken prompts, data usage, and distribution channel are not yet confirmed. Offline requirement is confirmed for saved routes only.

## Traceability
- R1 TFT live full map with panel off -> Kotlin foreground navigation service, renderer/encoder, TFT transport -> parked and ride acceptance tests.
- R2 reduce battery/heat -> eliminate phone display rendering, minimize encoder/frame/GPS/network work -> paired baseline measurements.
- R3 dependable route/turn guidance -> selected map/routing provider and fresh-GPS handling; pre-download map tiles and route geometry for saved routes -> route, offline saved-route, deviation, GPS-loss and reconnect tests.
- R4 Android first -> Kotlin Android app, defer iOS -> Android device matrix led by OnePlus 13R/OxygenOS 16.
- `REQ-OFFLINE-01` saved-route geometry, maneuvers, and permitted map data work offline without promising offline rerouting -> `DEC-OFFLINE-01` -> `IMP-OFFLINE-01` / `IMP-CORRIDOR-01` -> `TST-OFFLINE-01`; physical tile-cache validation remains `TST-DEVICE-OFFLINE-01`.
