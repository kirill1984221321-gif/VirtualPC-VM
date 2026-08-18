# Legacy Elephant audit

## Scope

This document records the current boundary between the legacy Vectras/Elephant code and the VirtualPC-VM runtime on `qemu-11-arm64-runtime-ui`.

## Findings

The historical `com.anbui.elephant.interaction.Interaction` class was not part of VM execution. Its implementation only contacted the Anbui `egg` service to fetch view/like counters, post view/like updates, and persist local view/like state. It depended on the old `LogPrinter` and `Retrofit2Utils` helpers. That functionality is Store/social metadata, not VM configuration, storage, QEMU process management, display, input, or lifecycle.

The historical interaction model and helper classes have therefore been removed from the branch. `RomInfo` no longer owns an `Interaction` instance; the optional view/like widgets are hidden instead of being replaced by fake values. This keeps ROM information and import behavior intact without coupling the VM app to the old social service.

`com.anbui.elephant.log.LogPrinter` is not present in the current source tree. No replacement logging dependency is required for VM runtime.

The old network helper was also separated from the Elephant package. `com.vectras.vm.legacy.network.LegacyNetworkUtils` is a small project-owned adapter used only by legacy Store/Updater/setup code. It performs real HTTP GET/POST/download operations and does not return fake responses. The current `com.anbui.elephant.retrofit2utils.Retrofit2Utils` class is only a compatibility facade delegating to that project-owned implementation; it is not the original Elephant library and contains no fake implementation.

The QEMU runtime (`com.virtualpcvm`) does not depend on Elephant, Store, Updater, or the compatibility facade. Its ARM64 QEMU assets remain embedded under `app/src/main/assets/qemu/`.

## Historical origin

The interaction stack was explicitly removed in the following branch history:

- `a2b633a24bbe3d1426e3d269037e0b49a6f2eb17` — removed the 307-line `Interaction` telemetry/social implementation.
- `c42a9637558ba9188164bf11267fc14cf5691b1d` — removed `DataInteraction`.
- `4dfa4597cd9253343269303417d60e2a517e6782` — removed `InteractionUtils`.
- `1c27dcc9cfa1b83646a821ffbb6a99479ef2a512` — removed the empty interaction package marker.
- `65d341aae5c0b16d9a566c12fbc9d0dfe156240a` — added the explicit compatibility facade for the remaining legacy imports.

`UpdaterActivity`, `RomStoreFragment`, `SoftwareStoreFragment`, and `ToolsManager` already use `LegacyNetworkUtils` directly. The remaining facade is retained only as a real compatibility boundary for legacy callers that have not yet been migrated; it does not restore the Elephant implementation.

## Architectural rule

The new VM path is intentionally kept separate:

`VirtualPC UI -> VM controller/config/storage -> QemuRuntime/QemuProcess -> embedded ARM64 QEMU`

Legacy Store/Updater/setup networking is optional infrastructure and must not become a dependency of QEMU runtime startup.

## Signing

Normal debug builds use the Android-managed debug signing configuration. The historical Vectras/Termux:X11 keystore is opt-in through `legacySigningEnabled` and is never generated or committed by CI.
