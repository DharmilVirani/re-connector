# Offline Map Download Decision

**Status:** Selected for personal V1  
**Date:** 2026-11-06  
**Owner:** Dharmil Virani

## Decision

Use MapLibre Native Android 13.7.1 offline regions with OpenFreeMap's Liberty style. Saving a route stores its geometry and maneuvers locally; downloading the map is a separate explicit action. A download covers a 500 m buffer around the route at zoom levels 8–15. JTS BufferOp creates a valid buffered polygon and merges close switchbacks instead of emitting a self-intersecting ring.

OpenFreeMap's current terms permit each offline download to contain at most 3,000 tiles or 50 MB, with at most four parallel requests and an identifying User-Agent. The service is free as-is and does not guarantee availability. The app uses conservative stop thresholds of 2,800 completed tiles or 32 MB, limits each OpenFreeMap response body to 4 MB, and caps shared HTTP concurrency at four. The margin allows for up to four in-flight responses of at most 4 MB each. These limits are implementation guardrails; actual MapLibre request scheduling and downloaded pack sizes still need device verification. [OpenFreeMap terms](https://openfreemap.org/tos/)

MapLibre reports completed tile/resource counts and completed bytes, but it does not report the final required byte total. Required-resource count may be only a lower bound while the download is in progress. The UI therefore reports counts and downloaded size rather than inventing a percentage, allows a partial download to resume, and treats a pack as ready only when MapLibre reports it complete. [MapLibre offline-region API](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-region-status/index.html)

The OpenFreeMap style URL and attribution are shared between the offline-region definition and the TFT renderer. The renderer uses the same MapLibre offline database, so complete corridor resources are available offline without a separate tile cache. Offline route recalculation remains out of scope.

## Traceability

- `REQ-OFFLINE-01` (`req:offline-saved-route`): saved route geometry, maneuvers, and permitted map resources remain usable offline; no offline rerouting.
- `DEC-OFFLINE-01` (`dec:offline-map-corridor-downloads`): OpenFreeMap corridor pack and conservative per-download limits.
- `IMP-OFFLINE-01` (`imp:offline-map-repository`): `app/src/main/java/com/dharmil/reconnector/navigation/store/OfflineMapRepository.kt`, `OfflineDownloadBudget.kt`, and `app/src/main/java/com/dharmil/reconnector/network/AppHttpClient.kt`.
- `IMP-CORRIDOR-01` (`imp:route-corridor-geometry`): JTS BufferOp, pinned to 1.20.0; license notices are included under `app/src/main/assets/licenses/JTS/`.
- `TST-OFFLINE-01` (`tst:offline-map-unit-coverage`): unit tests cover the download budget, straight/bent corridors, a tight switchback, zero-length geometry, and response-size enforcement.
- `TST-DEVICE-OFFLINE-01` (`tst:offline-device-acceptance`): pending; download a route, restart the app, disable mobile data, and confirm corridor tiles render on the TFT while following the saved geometry.

## Limits

- A completed pack does not prove coverage outside the 500 m corridor or above zoom 15.
- Partial or provider-error packs are not marked ready. If the route exceeds the cap, reduce corridor width/zoom or use a separately reviewed map provider.
- Public map and routing services may change availability or terms; recheck before distributing the APK beyond personal use.
- Offline rendering and provider-limit behavior have not yet been tested on the OnePlus 13R or the Tripper.
