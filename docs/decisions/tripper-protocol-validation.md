# Tripper Protocol Validation

**Status:** Protocol reference reviewed; compatibility with the user's firmware still requires a parked-bike test.

## User-provided target

- Bike: Royal Enfield Himalayan 450.
- Tripper firmware: 11.63.
- SSID: `RE_LT09_260125`.
- TFT address: `192.168.1.1`.
- User-observed UDP ports: control 2000/2002 and video 5000.
- Royal Enfield Android app: 10.1.23 (45).

These are owner-reported observations, not yet validated by this app.

## Public reference

Reviewed `OpenMotoDash/better-dash` at commit `e32d885372e8f18b49c3bf73c8add8692d377b5b`. It is an unofficial Python project for desktop/Linux/Raspberry Pi, not an Android route planner. Its README describes following a GPX route on cached map tiles. The source is licensed under Apache-2.0; this app will use it as protocol evidence and will not copy its implementation.

Source: <https://github.com/OpenMotoDash/better-dash/tree/e32d885372e8f18b49c3bf73c8add8692d377b5b>

Observed in that revision:

- The client sends K1G control packets to UDP 2000 on the bike broadcast address and listens for replies/button events on local UDP 2002.
- H.264/RTP video is sent to `192.168.1.1:5000`.
- The reported handshake requests an RSA public key, creates a random 32-byte AES key, and RSA/PKCS#1 v1.5 encrypts `SSID bytes + AES key`. The repository assumes a 1024-bit dash RSA key.
- The client sends navigation-context, route-card and projection control messages; its sample projection heartbeat is 4 Hz.
- The reference renderer targets 526×300 pixels. Its RTP sender fragments large H.264 NAL units as FU-A and avoids STAP-A aggregation.

These are reverse-engineering findings from one public implementation, not a Royal Enfield specification. No evidence yet confirms these exact packets work with Tripper FOTA 11.63.

## Integration constraints

- Pair only with the owner's bike and use the Android user's consented Wi-Fi connection.
- Bind each bike UDP socket to the selected Tripper `Network`; do not bind the entire app process, so routing can use mobile data.
- Do not store the provided SSID as a protocol constant. Do not log SSID, coordinates, RSA material, session keys or full packets.
- Send projection-stop messages and close sockets on service stop or failure.
- Keep navigation output independent of bike control functions.

## Parked-bike gate

Before using the stream on a ride, verify while stationary:

1. Android joins the Tripper network and the app receives the public-key/auth response.
2. The TFT reports/accepts the projection sequence and shows a test map frame.
3. The stream stops cleanly and the normal TFT view returns.
4. Mobile-data route requests still use the internet network while only bike UDP sockets use the Tripper network.
5. After these pass with the phone visible, repeat with the phone locked and display off.

No firmware changes or moving-bike protocol experiments are part of this gate.
