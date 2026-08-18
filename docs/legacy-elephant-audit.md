# Legacy Elephant audit

## Scope

This document records the current boundary between the legacy Vectras/Elephant code and the VirtualPC-VM runtime on `qemu-11-arm64-runtime-ui`.

## Findings

The historical `com.anbui.elephant.interaction.Interaction` class was not part of VM execution. Its implementation only contacted the Anbui `egg` service to fetch view/like counters, post view/like updates, and persist local view/like state. It depended on the old `LogPrinter` and `Retrofit2Utils` helpers. That functionality is Store/social metadata, not VM configuration, storage, QEMU process management, display, input, or lifecycle.

The historical interaction model and helper classes have therefore been removed from the branch. `RomInfo` no longer owns an `Interaction` instance; the optional view/like widgets are hidden instead of being replaced by fake values. This keeps ROM information and import behavior intact without coupling the VM app to the old social service.

`com.anbui.elephant.log.LogPrinter`, `Interaction`, `DataInteraction`, `InteractionUtils`, and the old `com.anbui.elephant.retrofit2utils.Retrofit2Utils` compatibility adapter are not present in the current production source. The remaining callers in `MainActivity` and `SetupWizard2Activity` now call the project-owned `com.vectras.vm.network.AppNetworkUtils` directly. That network layer performs real asynchronous HTTP operations; no fake responses or compatibility facade are used.

The QEMU runtime (`com.virtualpcvm`) does not depend on Elephant, Store, Updater, or the application network layer. Its ARM64 QEMU assets remain embedded under `app/src/main/assets/qemu/`.

## Historical origin

The interaction stack was explicitly removed in the following branch history:

- `a2b633a24bbe3d1426e3d269037e0b49a6f2eb17` — removed the 307-line `Interaction` telemetry/social implementation.
- `c42a9637558ba9188164bf11267fc14cf5691b1d` — removed `DataInteraction`.
- `4dfa4597cd9253343269303417d60e2a517e6782` — removed `InteractionUtils`.
- `1c27dcc9cfa1b83646a821ffbb6a99479ef2a512` — removed the empty interaction package marker.
- `65d341aae5c0b16d9a566c12fbc9d0dfe156240a` — introduced a temporary compatibility boundary for remaining legacy callers.
- `bfc7e8b5ac51b671f951c06278032a905368f27b` — removed the old Retrofit catalog entries.
- `7e16e1fae6b349bd888c00cfb5a6eba4d66a1b86` — hardened the application-owned HTTP body reader for Android compatibility.
- `1934a75812a234868f4732567dc04a56843e088c` — migrated `MainActivity` from the compatibility adapter to `AppNetworkUtils`.
- `d596c8c4c6e9c0fab3025d1a026071f36c281fe2` — migrated `SetupWizard2Activity` from the compatibility adapter to `AppNetworkUtils`.
- `78d5b58554eb4c377894ce1dd79deb828a2cfd75` — deleted the final `Retrofit2Utils` compatibility adapter.

## Architectural boundary

The new VM path is intentionally kept separate:

`VirtualPC UI -> VM controller/config/storage -> QemuRuntime/QemuProcess -> embedded ARM64 QEMU`

Store, updater, and setup networking are optional infrastructure. They must never become dependencies of QEMU runtime startup. The core VM path must also remain usable without network access.

## Signing

Normal debug builds use the Android-managed debug signing configuration. The historical Vectras/Termux:X11 keystore is opt-in through `legacySigningEnabled` and is never generated or committed by CI.
