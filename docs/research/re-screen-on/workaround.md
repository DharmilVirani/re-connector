# Candidate: use the existing RE app with the physical panel off

Status: source-supported experiment; not validated with the motorcycle.

Scope: existing Royal Enfield 10.1.23(45), full-map projection, no replacement navigation app, no Arena or agents. Connected phone reports I2405 / Android 16. OnePlus 13R/OxygenOS 16 remains separately untested.

## Mechanism

Turn off the physical display through SurfaceControl display power mode while the framework and foreground RE activity stay awake. This differs from sending the power key or locking Android. It may avoid the NavigationFragment onPause/onStop frame cutoff identified in findings.md. Actual map snapshot freshness with the physical panel off is unproven until tested; the physical display might affect rendering or be reactivated by OEM/RE behavior.

## First experiment: scrcpy over the existing USB connection

scrcpy is not installed in the current shell PATH. No installation or screen-state change was performed.

While parked, user starts a real route in the RE app and confirms full map on TFT. With a current scrcpy installed, the proposed command is:

```sh
scrcpy --turn-screen-off --no-video --no-audio --no-clipboard-autosync
```

The control channel stays active, with no extra video/audio stream to the computer. Keep the session running. Check that the panel is physically unlit, RE activity remains resumed, and TFT receives fresh frames (zoom the map using the bike control, not just stare at an unchanged parked map). Check for spontaneous screen wake. Stop scrcpy to restore display power; official docs also say the physical power button turns the panel back on. Normal Android locking may stop projection and is not the test condition. Do not simulate progress by transmitting live user coordinates to new services.

## Practical phone-only candidate

Shizuku + Display Toggle (MIT app, F-Droid/developer release). Start Shizuku using wireless debugging on Android 11+, authorize Display Toggle, return to active RE full-map navigation, then use Display Toggle's Quick Settings OFF tile. Prefer the tile because launching another full-screen app can pause RE. Shizuku startup normally needs repeating after reboot; compatibility and persistence when Wi-Fi changes to the TFT hotspot require testing. The device remains logically awake/unlocked. This is a real panel power API, not a black overlay or minimum brightness.

Code inspection: DisplayToggle util/Display.kt enumerates physical displays on Android 14+ and invokes SurfaceControl.setDisplayPowerMode(token, OFF=0). ShizukuUserService performs the privileged call. scrcpy's Device.setDisplayPower uses the same SurfaceControl route on current master; its alternate Android 15 DisplayManager path is explicitly disabled because it does not work as expected. Neither source proves RE/TFT compatibility.

## Success and limits

Success requires fresh full-map frames throughout panel-off operation and normal recovery. It does not establish reduced total heat/battery drain without measurements: map rendering, GNSS, video encoding, and Wi-Fi remain active. No APK patch or root is needed by the proposed Shizuku startup path. No app was installed, launched, or configured during this research.

## Primary sources

- https://github.com/Genymobile/scrcpy/blob/master/doc/device.md
- https://github.com/Genymobile/scrcpy/blob/master/doc/control.md
- https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/device/Device.java
- https://github.com/UlyssesZh/DisplayToggle
- https://github.com/UlyssesZh/DisplayToggle/blob/master/app/src/main/java/io/github/ulysseszh/displaytoggle/util/Display.kt
- https://f-droid.org/en/packages/io.github.ulysseszh.displaytoggle/
- https://shizuku.rikka.app/guide/setup/

News and user reports about newer RE screen-lock updates were not used as evidence of firmware 11.63 support. A screen that is locked but still illuminated does not meet this task's requirement.

## Native RE settings trial requested by user

Read-only check of connected iQOO I2405 / Android 16: RE remains 10.1.23(45), versionCode 193. Fine/coarse location and notifications granted; background location, Bluetooth connect, and Bluetooth scan denied. SYSTEM_ALERT_WINDOW app-op has no recorded operation/default mode; no explicit overlay grant confirmed. No RE entry in Android deviceidle whitelist (this does not establish the OEM Battery Unrestricted UI setting). App declares location/connected-device foreground-service permissions and ACCESS_BACKGROUND_LOCATION. Those declarations do not prove full-map casting is implemented in a background service. No system permissions or battery policies changed through ADB. Opened RE app-details Settings for the user to configure the requested battery/location/overlay permissions and Bluetooth. Native screen-off full-map result remains untested, awaiting parked bike connection and user-started navigation.
