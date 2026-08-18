# VirtualPC-VM native QEMU build

This directory contains the reproducible native-QEMU build infrastructure for VirtualPC-VM.

## First target

- Host: Android ARM64
- ABI: `arm64-v8a`
- NDK: `27.2.12479018`
- Minimum Android API used by the toolchain: 24
- QEMU: `11.0.3`
- QEMU system target: `x86_64-softmmu`
- Runtime binary: `qemu-system-x86_64`
- Accelerator: TCG

The QEMU source archive is downloaded from the official QEMU download server and verified with the pinned SHA-256 in `version.env`. QEMU source is intentionally not vendored into this repository.

## Build flow

```text
GitHub Actions
      |
      +-- Android SDK / NDK 27.2.12479018
      |
      +-- GLib cross-build for Android
      |
      +-- QEMU 11.0.3 source + SHA-256 verification
      |
      +-- x86_64-softmmu + TCG
      |
      `-- qemu-system-x86_64 artifact
```

The first stage is headless. VNC/display support is deliberately a later milestone so that the first native build proves the Android executable/toolchain path independently.

## Files

- `version.env` — pinned versions, checksums and target definitions.
- `scripts/build-android.sh` — native cross-build entry point.
- `patches/` — reserved for small, reviewed Android-specific patches if the upstream build exposes a real portability issue.
- `output/` — local/CI build output; generated files are not committed.
