# Friendar

[![CI](https://github.com/DanCommitsGit/friendar/actions/workflows/ci.yml/badge.svg)](https://github.com/DanCommitsGit/friendar/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/DanCommitsGit/friendar)](https://github.com/DanCommitsGit/friendar/releases/latest)

Friend radar for Android. Follow the arrow to the friend you're looking for.

Friendar points you at a friend's phone when you are both nearby, using ultra-wideband (UWB) with Bluetooth, Wi-Fi Aware and GPS as fallbacks. Open the app on two phones, tap Start, and follow the arrow. It works with no mobile signal or Wi-Fi network.

## Install

Download and install `friendar-<version>.apk` from the [latest release](https://github.com/DanCommitsGit/friendar/releases/latest) on Android 12 or newer.

## Privacy

There is no pairing or encryption. Any phone nearby running Friendar with the app open can link to yours, and linked phones send each other their GPS position.

## Build

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

You need JDK 21 and the Android SDK with platform 36 and build tools 35.0.0. The APK is written to `app/build/outputs/apk/debug/`.

## Docs

- [Architecture](docs/architecture.md) explains how the link, UWB sessions and measurement fusion fit together.
- [Decisions](docs/decisions.md) records why things are the way they are and what was ruled out.

## License

[MIT](LICENSE)
