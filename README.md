# Friendar

Friendar points you at a friend's phone when you are both nearby. Open the app on two phones, tap Find, and follow the arrow.

Distance and direction come from ultra-wideband (UWB) when both phones have it. When UWB has no clear line, distance falls back to Wi-Fi Aware ranging and then Bluetooth signal strength, and direction falls back to GPS and the compass. There is no server and no account.

## Requirements

Two Android 12 or newer phones with Bluetooth. UWB, Wi-Fi Aware and GPS improve the result but are optional, and a page in the app lists which of them each phone supports.

## Privacy

There is no pairing or encryption. Any phone nearby running Friendar with the app open can link to yours, and linked phones send each other their GPS position.

## Build

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

This needs JDK 17 and the Android SDK with platform 36 and build tools 35.0.0. The APK is written to `app/build/outputs/apk/debug/`.

The unit tests cover the math. The Bluetooth, UWB, Wi-Fi Aware and sensor code has only been checked on a Samsung S22 Ultra and a Samsung S23 Ultra.

## Docs

- [Architecture](docs/architecture.md) explains how the link, UWB sessions and measurement fusion fit together.
- [Decisions](docs/decisions.md) records why things are the way they are and what was ruled out.

## License

[MIT](LICENSE)
