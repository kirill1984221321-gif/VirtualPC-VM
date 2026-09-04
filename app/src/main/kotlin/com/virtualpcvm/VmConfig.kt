package com.virtualpcvm

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class Architecture(val label: String, val binary: String) {
    X86_64("x86_64",  "qemu-system-x86_64"),
    I386("i386",      "qemu-system-i386"),
    ARM("arm",        "qemu-system-arm"),
    ARM64("aarch64",  "qemu-system-aarch64"),
    POWERPC("ppc",    "qemu-system-ppc"),
    MIPS("mips",      "qemu-system-mips"),
    RISCV32("riscv32","qemu-system-riscv32"),
    RISCV64("riscv64","qemu-system-riscv64"),
    SPARC("sparc",    "qemu-system-sparc"),
    SPARC64("sparc64","qemu-system-sparc64");

    companion object {
        fun fromLabel(label: String) = values().find { it.label == label } ?: X86_64
    }
}

enum class MachineType(val value: String) {
    Q35("q35"),
    PC("pc"),
    VIRT("virt"),
    ISAPC("isapc"),
    MAC99("mac99"),
    G3BEIGE("g3beige"),
    VERSATILEPB("versatilepb"),
    RASPI2("raspi2b"),
    RASPI3("raspi3b"),
    MALTA("malta"),
    SIFIVE_U("sifive_u"),
    SUN4M("SS-5"),
    SUN4U("sun4u");
}

enum class Firmware { BIOS, UEFI }

@Parcelize
data class VmConfig(
    val id: Long = System.currentTimeMillis(),
    val name: String = "Новая ВМ",
    val architecture: Architecture = Architecture.ARM64,   // ARM64 = Termux host arch
    val machineType: MachineType = MachineType.VIRT,
    val ramMb: Int = 1024,
    val cpuCores: Int = 2,
    val firmware: Firmware = Firmware.BIOS,
    val diskPath: String = "/storage/emulated/0/MyVMs/disk.img",
    val isoPath: String = "",
    val enableKvm: Boolean = false,
    val enableMtcg: Boolean = true,
    val disableTsc: Boolean = false,
    val enableAudio: Boolean = true,
    val vncDisplay: Int = 1,   // :1 → port 5901
) : Parcelable {
    val vncPort: Int get() = 5900 + vncDisplay
}
