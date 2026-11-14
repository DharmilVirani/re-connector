# RE Connector

Personal native Android navigator for the Royal Enfield Himalayan 450 Tripper display.

## Build on Arch Linux

Open this directory in Android Studio, let Gradle sync, then run the `app` configuration. For command-line builds from Fish, set the installed SDK/JDK paths first:

```fish
set -gx ANDROID_HOME "$HOME/Android/Sdk"
set -gx ANDROID_SDK_ROOT "$ANDROID_HOME"
set -gx JAVA_HOME /opt/android-studio/jbr
fish_add_path "$JAVA_HOME/bin" "$ANDROID_HOME/platform-tools"
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Current progress

- Native Kotlin/Compose Android Studio project with online destination search, Valhalla motorcycle routing, saved routes, and MapLibre offline corridor downloads.
- Tripper Wi-Fi transport, protocol session, map renderer, H.264 encoder, RTP sender, and location foreground service are integrated in the app.
- Offline map downloads are bounded below OpenFreeMap's published per-download limits and show saved/downloaded/paused states.
- The personal debug APK targets 64-bit ARM (`arm64-v8a`) for the OnePlus 13R and iQOO development phone.
- The current arm64-only debug APK is about 45 MB.
- `./gradlew testDebugUnitTest assembleDebug` passes: 73 unit tests across 14 suites, with no failures.

The debug APK is installed and launches on the connected ADB handset (device-reported model `I2405`); startup was smoke-checked with no crash. It has **not** been tested on the bike. The Tripper firmware 11.63 handshake, live TFT stream, screen-off behavior, offline tile download/rendering, and OxygenOS 16 lifecycle still need parked-device validation. Use the parked-hardware gate in [the protocol decision](docs/decisions/tripper-protocol-validation.md). Provider choices are recorded in [the routing decision](docs/decisions/route-provider.md).
