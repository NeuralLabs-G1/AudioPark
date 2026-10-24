# AudioPark

AudioPark is an Android audio player prototype focused on understanding what the current phone can do with its connected audio outputs.

## Current prototype features

- Scans the device music library and shows song title, artist, and duration.
- Plays songs from the library with previous, play/pause, next, seek, and time controls.
- Supports shuffle and repeat modes.
- Lists connected Android audio outputs and paired Bluetooth devices.
- Lets you select one output for normal playback.
- Includes a best-effort dual mode that creates two synchronized app-owned players when Android exposes two routable outputs.
- Refreshes the output list when devices connect or disconnect.

## Important limitation

Dual mode is best effort. It starts one player per selected output, but Android or the phone's audio hardware may reject one route, reroute both players, or produce audible timing drift. The app cannot force simultaneous wired-plus-Bluetooth playback when the OS does not expose both routes. The UI reports which mode was requested; test the actual outputs on the target phone.

The first useful product milestone is to install this on the target phone, connect the wired headphones and TWS, and record which outputs Android exposes. If the phone/OS exposes a supported simultaneous route, AudioPark can add a capability-specific implementation later. If it exposes only one route, app code alone cannot make the original combination reliable.

## Build

Open this folder in Android Studio with Android SDK 35 installed, or build with Gradle 8.9 and JDK 17. The project uses Kotlin and the Android platform APIs only.

## Project structure

- `app/src/main/java/com/neurallabs/audiopark/MainActivity.kt` — player, output discovery, and single-route selection.
- `app/src/main/AndroidManifest.xml` — app declaration.
