# Architecture

Sources are grouped by package under `io.github.dancommitsgit.friendar`.

- `finder` holds `FinderViewModel`, which owns the single `FinderState`, the link lifecycle and the timers, and `UwbSession`, which runs UWB attempts, roles and ranging.
- `link` holds `BleHost` and `BleJoiner` (`Ble.kt`), Wi-Fi Aware ranging and messaging (`Aware.kt`) and the wire messages (`Protocol.kt`).
- `sensors` wraps the phone attitude and location updates.
- `fusion` holds the pure Kotlin with no Android dependencies: filters, bearings, geometry, `FriendTracker` and `bestRange`. These are the files the unit tests cover.
- `ui` holds the Compose screens, theme and navigation.
- `MainActivity` and `LocatorService` stay in the root package because the manifest names them.

## How it fits together

- Both phones run `BleHost` and `BleJoiner` at once; the one with the lower advertised nonce connects.
- The BLE host drives UWB attempts: it assigns controller and controlee, and flips the assignment after a 12 s timeout, since some pairs only range with one particular phone as controller.
- `FriendTracker` fuses UWB angles, the GPS bearing and a frame yaw learned from compass and UWB-versus-GPS (`Bearing.kt`, `Direction.kt`, `Geo.kt`) into the arrow direction and the map position.
- Distance comes from UWB, then Wi-Fi RTT, then BLE RSSI, in that order (`bestRange`).
- The code assumes exactly one peer: one `BleHost.peer`, one `FinderState`, one UWB session.
