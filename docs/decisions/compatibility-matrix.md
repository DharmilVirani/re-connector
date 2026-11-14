# Compatibility Matrix

| Component | Intended target | Evidence/status |
| --- | --- | --- |
| Motorcycle display | Himalayan 450 Tripper, FOTA 11.63 | User-reported. Not tested by this app. |
| Development phone | OnePlus 13R, OxygenOS 16 | User-reported target. Screen-off foreground-service and Wi-Fi behavior still needs device testing. |
| Secondary phone | iQOO Neo10 | Model user-reported; OS version and current connection status not confirmed for acceptance testing. |
| Royal Enfield app | Android 10.1.23 (45) | User-reported baseline; its private implementation is not copied. |
| Android build | AGP 9.5.1, Gradle 9.6.0, Compose compiler plugin 2.2.10, compileSdk 37 | Debug APK and unit tests build locally with Android Studio's JBR and installed SDK. |
| Map rendering | MapLibre Native Android 13.7.1 | Integrated through `MapView`, `Presentation`, private `VirtualDisplay`, and MediaCodec surface; not yet exercised on a physical phone. |
| Map data | OpenFreeMap Liberty style | Integrated MapLibre offline corridor download with 2,800 completed-tile / 32 MB stop thresholds, 4 MB per-resource cap, four-request shared HTTP limit, and attribution. Offline rendering remains device-test-required. |
| Route service | Valhalla motorcycle costing at FOSSGIS public endpoint | Selected for personal low-volume V1; no service guarantee. See [routing provider decision](route-provider.md). |
| TFT protocol | OpenMotoDash/better-dash reference | Source defaults match reported addresses/ports, but exact 11.63 handshake remains unverified. |

## Evidence

- Android foreground location requirements: <https://developer.android.com/develop/sensors-and-location/location/permissions> and <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- MapLibre offline API: <https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/index.html>
- OpenFreeMap terms and limits: <https://openfreemap.org/tos/> and <https://openfreemap.org/quick_start/>
- AGP 9.5 compatibility: <https://developer.android.com/build/releases/agp-9-5-0-release-notes>
