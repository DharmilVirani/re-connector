# Why Royal Enfield full-map projection keeps the phone display on

Scope: diagnose Royal Enfield Android 10.1.23(45), without app changes, Arena, or agents. User reports dim-but-on navigation while connected to Himalayan 450 TFT firmware 11.63. Desired outcome: identify the mechanism and distinguish static evidence from runtime proof.

## Finding

The full-map frame producer is explicitly coupled to NavigationFragment's foreground lifecycle. It repeatedly obtains map bitmaps, scales them, and supplies an H.264 encoder. The app stops requesting/supplying these frames when the navigation fragment pauses (unless picture-in-picture is active) or stops. Keeping the navigation window awake avoids this lifecycle interruption. A separate dimming overlay reduces brightness while preserving that active window.

This is a supported software mechanism, not proof of a TFT hardware requirement or of the original developers' rationale. No live screen-off comparison against the motorcycle was performed.

## Direct APK evidence

- **LOCAL-01 — frame pipeline:** NavigationFragment.m.run checks U2 and INFOTAINMENT mode, requests a Bitmap through k18.u0, and reschedules at 250/500 ms. Bitmap callback r -> J8 scales the image to configured width and height 300. g0.run checks U2 and passes the bitmap through k9 -> w9f.c -> gub.g. D7 configures video/avc; gub uses MediaCodec and draws the bitmap onto its encoder input Surface. Encoded buffers are queued by gub.f. See the retained excerpts.
- **LOCAL-02 — lifecycle cutoff:** onPause calls g9 and sets U2=false if picture-in-picture state is false. g9 removes the snapshot Runnable from its Handler. onStop unconditionally calls g9 and sets U2=false. Thus the frame producer is deliberately stopped even without assuming Android kills the process or Wi-Fi disconnects. onResume sets U2=true and initiates projection recovery; after SCREEN_OFF it stops/recreates the encoder path.
- **LOCAL-03 — keep awake:** onCreate sets window flags 6815872 (0x680080): KEEP_SCREEN_ON, TURN_SCREEN_ON, SHOW_WHEN_LOCKED, DISMISS_KEYGUARD. E8 reasserts these flags. It creates wake lock 805306394 (0x3000001a), corresponding to FULL_WAKE_LOCK plus ACQUIRE_CAUSES_WAKEUP and ON_AFTER_RELEASE. SCREEN_OFF conditionally acquires it for 10 seconds. Actual OS handling of those legacy requests was not observed.
- **LOCAL-04 — deliberate dimming:** F9 shows a protective overlay and schedules G9 after 3 seconds; G9 -> H9 -> e6(10). e6 sets window.screenBrightness=10/255 (about 3.9%). This is an application brightness request, not display power-off or a measured panel luminance.

## Corroboration and limits

Royal Enfield's official Tripper Dash FAQ says turning off the phone screen interrupts full-map mirroring while turn-by-turn can continue. This supports the distinction between map-frame projection and route guidance. The FAQ also lists older firmware 11.59, so it is not proof of exact firmware 11.63 behavior.

Android documents KEEP_SCREEN_ON as maintaining an illuminated display while the window is visible. These flags request behavior; static code cannot prove the exact power-button callback sequence on OxygenOS 16/iQOO.

Remaining runtime question: during a parked, connected TFT navigation session, correlate screen-off with NavigationFragment lifecycle and loss of fresh map frames while checking whether turn instructions continue. No firmware changes, APK modification, app launch, or route request was performed in this investigation.

## Sources

- [APK identity and analysis limits](artifact.json)
- [NavigationFragment excerpts](NavigationFragment.java.txt)
- [Map Bitmap interface excerpts](k18.java.txt)
- [Encoder adapter excerpts](w9f.java.txt)
- [MediaCodec encoder excerpts](gub.java.txt)
- https://www.royalenfield.com/in/en/tripper-dash/ (FAQ: phone screen off; soft touch)
- https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON
- https://developer.android.com/reference/android/os/PowerManager

REA Android provider was unavailable; evidence above is from direct JADX inspection, not fabricated REA Evidence references. No active native REA session was opened; reset_investigation is not advertised by this server.
