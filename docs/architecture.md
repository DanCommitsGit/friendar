# Architecture

- `FinderViewModel` owns the single `FinderState`, the link lifecycle, UWB sessions and measurement fusion.
- Both phones run `BleHost` and `BleJoiner` at once; the one with the lower advertised nonce connects. Messages are defined in `Protocol.kt`.
- The BLE host drives UWB attempts: it assigns controller and controlee, and flips the assignment after a 12 s timeout, since some pairs only range with one particular phone as controller.
- Direction is a weighted fusion of UWB angles, GPS bearing and a frame yaw learned from compass and UWB-versus-GPS (`Bearing.kt`, `Direction.kt`, `Geo.kt`). Distance comes from UWB, then Wi-Fi RTT, then BLE RSSI.
- The code assumes exactly one peer: one `BleHost.peer`, one `FinderState`, one UWB session.
