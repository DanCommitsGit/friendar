# Friendar

Android app (Kotlin, Compose) that points two nearby phones at each other using UWB, with BLE, Wi-Fi Aware RTT and GPS as fallbacks. No server. Single module, package `io.github.dancommitsgit.friendar`.

## Commands

```bash
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests "io.github.dancommitsgit.friendar.DirectionTest"
./gradlew assembleRelease
```

The release build is signed with the debug key. Tests cover only the pure-math files; Bluetooth, UWB, Wi-Fi Aware and sensor code can only be checked on two real phones.

## Docs

- `docs/architecture.md` explains how the link, UWB sessions and fusion fit together.
- `docs/decisions.md` records why things are the way they are and what was ruled out. Read it before changing the link, roles, fusion or distribution, and add an entry when a decision is made.
