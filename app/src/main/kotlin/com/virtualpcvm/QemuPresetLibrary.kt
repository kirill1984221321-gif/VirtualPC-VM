package com.virtualpcvm

object QemuPresetLibrary {
    data class Preset(
        val name: String,
        val architecture: Architecture,
        val machineType: MachineType,
        val cpuModel: String,
        val ramMb: Int,
        val vgaDriver: String,
        val networkAdapter: String,
        val audioModel: String,
        val enableAudio: Boolean,
        val extraArgs: String
    )

    val PRESETS = listOf(
        Preset(
            name = "Windows XP (x86_64)",
            architecture = Architecture.X86_64,
            machineType = MachineType.PC,
            cpuModel = "pentium3",
            ramMb = 512,
            vgaDriver = "cirrus",
            networkAdapter = "rtl8139",
            audioModel = "ac97",
            enableAudio = true,
            extraArgs = "-rtc base=localtime -usb -device usb-tablet"
        ),
        Preset(
            name = "Windows 98/2000 (i386)",
            architecture = Architecture.I386,
            machineType = MachineType.PC,
            cpuModel = "pentium",
            ramMb = 256,
            vgaDriver = "cirrus",
            networkAdapter = "ne2k_pci",
            audioModel = "sb16",
            enableAudio = true,
            extraArgs = "-rtc base=localtime -usb -device usb-tablet"
        ),
        Preset(
            name = "Modern Linux (Ubuntu/Debian)",
            architecture = Architecture.X86_64,
            machineType = MachineType.Q35,
            cpuModel = "max",
            ramMb = 2048,
            vgaDriver = "virtio",
            networkAdapter = "virtio",
            audioModel = "hda",
            enableAudio = true,
            extraArgs = "-rtc base=utc -usb -device usb-tablet"
        ),
        Preset(
            name = "Alpine Linux / TinyCore",
            architecture = Architecture.X86_64,
            machineType = MachineType.PC,
            cpuModel = "qemu64",
            ramMb = 256,
            vgaDriver = "std",
            networkAdapter = "virtio",
            audioModel = "hda",
            enableAudio = false,
            extraArgs = "-rtc base=utc"
        ),
        Preset(
            name = "FreeDOS",
            architecture = Architecture.I386,
            machineType = MachineType.ISAPC,
            cpuModel = "486",
            ramMb = 32,
            vgaDriver = "std",
            networkAdapter = "ne2k_isa",
            audioModel = "sb16",
            enableAudio = true,
            extraArgs = "-rtc base=localtime"
        ),
        Preset(
            name = "Ubuntu ARM64 (aarch64)",
            architecture = Architecture.ARM64,
            machineType = MachineType.VIRT,
            cpuModel = "cortex-a57",
            ramMb = 2048,
            vgaDriver = "ramfb", // often needed for ARM virt
            networkAdapter = "virtio",
            audioModel = "hda",
            enableAudio = false,
            extraArgs = "-rtc base=utc -device usb-ehci -device usb-kbd -device usb-mouse"
        )
    )
}
