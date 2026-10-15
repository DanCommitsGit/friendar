# Decisions

Short notes on choices that can't be read from the code. Add a new entry when a decision is made or an alternative is ruled out, and delete entries that stop being true.

## No pairing, authentication or encryption layer

Any Friendar phone in range with the app open can link. The app is for two friends in the same building and carries nothing sensitive beyond a GPS position, so a pairing flow was judged not worth its complexity. Do not add one unless asked.

## Roles come from a random nonce

Both phones advertise and scan at once, and the one with the lower nonce connects. A fixed or user-chosen role would mean one person has to tap a different button than the other.

## UWB controller assignment is tried, not fixed

Some phone pairs only produce measurements when one particular phone is UWB controller. The BLE host therefore flips the assignment after each failed attempt and remembers the one that worked. Do not hardcode controller to the host.

## The azimuth sign is detected, and assumed reversed until it is

On both test phones (Samsung S22 Ultra and S23 Ultra) the raw UWB azimuth is positive when the peer is to the left of the phone and negative when it is to the right, checked with the debug readout. The androidx.core.uwb documentation gives the azimuth range (-90 to 90) but not which sign is left or right, and no public report of the same behavior on other phones was found, so it cannot be assumed for all hardware.

Flip arrow is Yes, No or Auto. Auto compares where each sign would put the friend with the GPS bearing, using the compass alone for north (`FlipDetector`). It needs good fixes on both phones, a UWB range over 8 m and a reliable compass, so it mostly decides outdoors. The verdict is saved and used from the start of the next search. Until anything is learned, Auto behaves like Yes.

## Distance units follow the locale

Units are Auto, Metric or Imperial. Auto uses feet in the US, Liberia and Myanmar and meters elsewhere. Tapping the small distance under the big one swaps the two and sets the unit explicitly.

## Background search is a setting

With Keep searching in the background on, a foreground service holds the search while another app is in front. With it off there is no service and the search ends when the app leaves the foreground.

## Technical readings sit behind the debug switch

Measurement sources, raw readings and the log appear only when Debug info is on in settings. The map caption is shown only when the position is less trustworthy than usual.

## Wi-Fi Aware is a fallback, not the main link

UWB is blocked by bodies and walls, and Wi-Fi RTT is not, but it is only good to a few meters and many phones lack it. It supplies distance when UWB has none, ahead of BLE signal strength, and it never provides direction.

## Exactly two phones

The code is built around one peer. Supporting a group needs a group identifier in the BLE advertisement, per-peer links and UWB sessions (chips allow roughly three or four), and per-friend fusion state. A single UWB controller with several controlees only gives ranges between the hub and each friend, not between friends.

## Distribution is GitHub only

Releases are APKs on the GitHub Releases page, signed with the debug key. APKs are gitignored and nothing is published to the Play Store.
