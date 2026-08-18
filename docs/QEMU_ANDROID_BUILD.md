# VirtualPC-VM QEMU Android build

Host ABIs: armeabi-v7a and arm64-v8a.
Guest targets: x86_64-softmmu, i386-softmmu, aarch64-softmmu, arm-softmmu.

The build separates Android host ABI from guest architecture, following the proven Limbo model. TCG is the baseline; acceleration is optional.

QEMU must be built for Android/bionic or shipped with compatible runtime libraries. Termux binaries are not treated as drop-in APK binaries because of their linker/library layout.

First milestone: arm64-v8a host + x86_64-softmmu guest, then ARMv7 and the remaining guests.

VNC is the initial display transport; the app owns the VM configuration and VNC UI.
