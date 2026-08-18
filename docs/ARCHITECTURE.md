# VirtualPC-VM architecture

## Goal

VirtualPC-VM is an Android front-end and runtime for QEMU. The Android application is independent from Vectras VM; QEMU is the virtualization engine, not the GUI framework.

## What we use as references

- **QEMU upstream:** emulator/device model and TCG execution. We follow upstream command-line semantics and avoid copying the Vectras application layer.
- **Limbo:** Android/NDK organization and the important distinction between the Android host ABI and the guest architecture. A 64-bit ARM phone can, for example, run an x86_64 guest through QEMU TCG.
- **Vectras VM:** reference for Android UX ideas and real-world QEMU packaging, but not as the application architecture.

## Current implementation

`android/app` is the new application boundary and uses the `com.virtualpcvm` namespace. It contains the VM model, QEMU process manager, installer, VNC screen and UI.

The legacy root `app` remains outside the new Gradle project for now. It is intentionally not included by `android/settings.gradle`; this lets us migrate safely before deleting legacy Vectras code.

## Host ABIs

The APK is built for:

- `armeabi-v7a`
- `arm64-v8a`

QEMU guest binaries are selected independently from the host ABI. The installer maps the Android host ABI to the matching Termux package repository instead of assuming every phone is ARM64.

## QEMU packaging strategy

Phase 1 uses an in-app downloader so the APK itself does not contain a huge QEMU payload. Phase 2 will replace this with reproducible QEMU bundles produced by GitHub Actions and verified before installation. We do not silently copy Vectras binaries into this repository.

## Milestones

1. Standalone Android Gradle project — done on `qemu-rebuild`.
2. Remove Vectras application identity/dependencies from the new module — done.
3. ABI-aware QEMU installation/runtime — implemented as the first iteration.
4. CI APK builds for both ARM Android ABIs — workflow added.
5. Reproducible QEMU native bundles — next.
6. Native display/input path and lower-overhead rendering — next.
7. VM persistence, snapshots, networking and polished GUI — next.
8. Delete the legacy Vectras tree after the new application is proven buildable and boot-tested.
