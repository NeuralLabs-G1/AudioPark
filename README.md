# AudioPark

AudioPark is an Android audio player prototype focused on understanding what the current phone can do with its connected audio outputs.

## What this first version does

- Plays an audio file selected from the device.
- Lists audio output devices currently connected to Android.
- Lets you pick one output for the player when Android accepts that route.
- Refreshes the device list when outputs connect or disconnect.

## Important limitation

This prototype does **not** promise simultaneous playback through wired headphones and Bluetooth TWS. The standard Android player routing interface selects a preferred device for a player; it does not provide a general app API for cloning that player's stream to arbitrary devices. Whether a phone can play to multiple outputs depends on its Android build and audio hardware. This app makes that limitation visible instead of pretending that a successful route selection means both outputs are active.

The first useful product milestone is to install this on the target phone, connect the wired headphones and TWS, and record which outputs Android exposes. If the phone/OS exposes a supported simultaneous route, AudioPark can add a capability-specific implementation later. If it exposes only one route, app code alone cannot make the original combination reliable.

## Build

Open this folder in Android Studio with Android SDK 35 installed, or build with Gradle 8.9 and JDK 17. The project uses Kotlin and the Android platform APIs only.

## Project structure

- `app/src/main/java/com/neurallabs/audiopark/MainActivity.kt` — player, output discovery, and single-route selection.
- `app/src/main/AndroidManifest.xml` — app declaration.
