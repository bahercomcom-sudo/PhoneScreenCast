# PhoneScreenCast

Streams an Android phone's screen live to any browser on the same Wi-Fi.
MediaProjection -> WebRTC (video) -> local Wi-Fi -> browser. No cloud, no accounts, no analytics.

- Java, AGP 8.7.3, Gradle 8.9, compileSdk/targetSdk 35, minSdk 30
- WebRTC: `io.github.webrtc-sdk:android:125.6422.07` (includes armeabi-v7a)
- Local server (HTTP + WebSocket signaling, one port 8080-8089): NanoHTTPD
- QR code: ZXing core (offline)

Build: push to GitHub; the `Build APK` workflow uploads `app-debug.apk` as an artifact.
Use: open the app, tap START STREAMING, allow screen capture, then open the shown URL (or scan the QR code) on another device.
