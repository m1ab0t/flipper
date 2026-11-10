# Flipper Remote

A tiny Android remote for Flipper Zero over Bluetooth. It mirrors the Flipper's screen live and has a d-pad, OK and Back. No dependencies.

## Use

1. On the Flipper: Settings › Bluetooth › ON. Close the official Flipper app on your phone, because the Flipper accepts only one connection.
2. Install the APK (Android 5.0+) → **Connect** → enter the PIN the Flipper shows the first time.
3. Tap a button for a short press. Hold it for a long press, which repeats while held.

## Build

No Gradle. Needs JDK 17 and the Android SDK (build-tools 34, platform 34):

```sh
ANDROID_HOME=~/android-sdk ./build.sh   # -> flipper-remote.apk
```

It talks to the Flipper's BLE serial service using protobuf RPC (`gui_start_screen_stream_request`, `gui_send_input_event_request`). The protobuf is hand-encoded in `MainActivity.java`.
