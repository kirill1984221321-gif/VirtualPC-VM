package com.virtualpcvm

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class Architecture(val label: String, val binary: String) {
    X86_64("x86_64 (64-bit PC)", "qemu-system-x86_64"),
    I386("i386 (32-bit PC)",     "qemu-system-i386"),
    ARM64("aarch64 (ARM64)",     "qemu-system-aarch64"),
    ARM("arm (32-bit ARM)",       "qemu-system-arm"),
    RISCV64("riscv64",            "qemu-system-riscv64"),
    RISCV32("riscv32",            "qemu-system-riscv32"),
    POWERPC("ppc (PowerPC)",      "qemu-system-ppc"),
    PPC64("ppc64 (PowerPC 64)",   "qemu-system-ppc64"),
    M68K("m68k (Motorola)",       "qemu-system-m68k");

    companion object {
        fun fromLabel(label: String) = entries.find { it.label == label } ?: X86_64
        fun fromBinary(binary: String) = entries.find { it.binary == binary } ?: X86_64
    }
}

enum class MachineType(val value: String, val displayName: String) {
    PC("pc", "Standard PC (i440FX + PIIX)"),
    Q35("q35", "Modern PC (Q35 + ICH9)"),
    VIRT("virt", "Virtual Machine (ARM/RISCV virt)"),
    ISAPC("isapc", "ISA-only PC"),
    MAC99("mac99", "Apple PowerMac G4"),
    G3BEIGE("g3beige", "Apple PowerMac G3"),
    VERSATILEPB("versatilepb", "ARM Versatile/PB"),
    RASPI2("raspi2b", "Raspberry Pi 2B"),
    RASPI3("raspi3b", "Raspberry Pi 3B");

    companion object {
        fun fromValue(v: String) = entries.find { it.value == v } ?: PC
    }
}

enum class Firmware { BIOS, UEFI }

@Parcelize
data class VmConfig(
    val id: Long = System.currentTimeMillis(),
    val name: String = "Новая ВМ",
    val architecture: Architecture = Architecture.X86_64,
    val machineType: MachineType = MachineType.PC,
    val ramMb: Int = 1024,
    val cpuCores: Int = 2,
    val cpuModel: String = "max",
    val firmware: Firmware = Firmware.BIOS,
    val diskPath: String = "",
    val diskFormat: String = "qcow2",
    val isoPath: String = "",
    val bootDevice: String = "disk",
    val vgaDriver: String = "std",
    val networkMode: String = "user",
    val enableKvm: Boolean = false,
    val enableMtcg: Boolean = true,
    val disableTsc: Boolean = false,
    val enableAudio: Boolean = true,
    val audioModel: String = "hda",
    val enableUsbTablet: Boolean = true,
    val extraArgs: String = "",
    val vncDisplay: Int = 1,   // :1 → port 5901
) : Parcelable {
    val vncPort: Int get() = 5900 + vncDisplay
}
