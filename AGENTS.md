# Friendar

Android app (Kotlin, Compose) that points two nearby phones at each other using UWB, with BLE, Wi-Fi Aware RTT and GPS as fallbacks. No server. Single module, package `io.github.dancommitsgit.friendar`.

## Commands

```bash
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests "io.github.dancommitsgit.friendar.fusion.DirectionTest"
./gradlew assembleRelease
```

Local release builds are signed with the debug key. Tests cover only the pure-math files; Bluetooth, UWB, Wi-Fi Aware and sensor code can only be checked on two real phones.

## Releasing

Run the Release workflow from the Actions tab on `main`. semantic-release reads the conventional commits since the last tag, picks the next version (`fix` is a patch, `feat` a minor, `!` or a `BREAKING CHANGE` footer a major), then the workflow tests, builds, signs with the key in the repository secrets and publishes the APK with generated notes to GitHub Releases. Tick `dry_run` to see the next version and notes without releasing. Commit types other than `feat`, `fix` and `perf` do not trigger a release.

## Commits

When a commit fixes a GitHub issue, add a `Fixes #<number>` footer so merging it closes the issue.

## Docs

- `docs/architecture.md` explains how the link, UWB sessions and fusion fit together.
- `docs/decisions.md` records why things are the way they are and what was ruled out. Read it before changing the link, roles, fusion or distribution, and add an entry when a decision is made.
