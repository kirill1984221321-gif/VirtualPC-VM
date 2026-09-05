# VirtualPC-VM 🐧💻

**VirtualPC-VM** is an advanced, high-performance, and lightweight PC emulator application built natively for Android devices. Powered by a customized mobile deployment of **QEMU (Quick Emulator)**, this project empowers users to engineer standalone Micro-Virtual Machines (MicroVMs) and boot fully fledged desktop or server operating systems—including various Linux distributions, Windows, and lightweight retro platforms—directly from `.iso`, `.qcow2`, `.img`, or `.vhd` disk images.

The entire system design, native binary compatibility bridging, and core state management architecture of this application have been heavily co-developed, patched, and debugged using autonomous **Multi-Step AI Agents** driving continuous continuous integration, self-correction loops, and strict automated quality control.

---

## 📸 Project Visuals & Screenshots
*The mobile emulator successfully initializing and booting straight into the native iPXE Boot environment and BIOS sequence inside an isolated Android application runtime container:*

---

## ✨ Comprehensive Feature Matrix

### 🛠️ 1. Native Virtualization & Hardware Execution Layer
* **Full Cross-Architecture Support:** Out-of-the-box system initialization and binary compatibility layers for native host execution across `aarch64` (ARM64), `armv7` (32-bit ARM), `x86_64`, and legacy `x86` hardware platforms.
* **Streamlined Mobile MicroVM Backend:** Stripped of bloated legacy hardware subsystems, unneeded timers, and redundant PIC/APIC interrupt routines to maintain low-overhead execution cycles on smartphone chipsets.
* **Dynamic Hardware Allocator:** Modular interface options mapping directly to underlying QEMU execution parameters (`-smp` for CPU core scaling, `-m` for granular RAM byte management, and dynamic `-cpu` matching).

### 📺 2. Graphics Rendering, Framebuffer Control & UX
* **Infinite VNC Loop Termination:** Re-engineered the underlying virtual network computing and socket loop cycles (`127.0.0.1:5901`), completely solving the infinite "Connecting to VNC server..." interface freeze.
* **AOSP-Compliant Material Layout:** Built from the ground up using native Android clean design guidelines. Zero heavy external drawing asset overhead—optimized for fluid UI interactions and low memory footprints.
* **Developer Power-Toolbar Overlay:** Integrated an interactive system shortcut overlay inside the emulation viewport, enabling quick keycode transmission for crucial hotkeys: `Ctrl+Alt+Del`, `Alt+Tab`, `Escape`, and the `Super/Meta` Windows Key.

### 📊 3. Storage I/O & Diagnostics Dashboard
* **Batch Import via Storage Access Framework (SAF):** Implemented concurrent file selection loops utilizing native Android storage pickers (`Intent.EXTRA_ALLOW_MULTIPLE`). Easily import multiple heavy disk configurations simultaneously into app-isolated sandboxed paths.
* **Real-Time Terminal Diagnostics:** Accessible "View Logs" dashboard layout feeding real-time standard output (`stdout`) and standard error (`stderr`) streams directly from the background QEMU execution process into a readable bottom-sheet interface window.

---

## 🗺️ High-Level Technical Architecture & File Map

The following directory structural tree represents the decoupled architecture managed by the Android build pipelines and automated self-correction agents:

```text
VirtualPC-VM/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/virtualpcvm/
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainActivity.kt        # Primary AOSP layout logic & view lifecycles
│   │   │   │   │   └── VncView.kt             # Viewport handling, touch matrices & keyboard macros
│   │   │   │   ├── core/
│   │   │   │   │   ├── QemuRunner.kt          # Background process builders and runtime flags
│   │   │   │   │   └── StorageManager.kt      # SAF async tasks and scoped file migration paths
│   │   │   │   └── model/
│   │   │   │       └── VirtualMachine.kt      # State models mapping hardware profile states
│   │   │   └── res/layout/                    # Minimalist frontend XML view configurations
│   └── build.gradle                           # Android 12+ (API 31/33+) modern compilation profiles
├── scripts/
│   └── kill_qemu.sh                           # Emergency background runtime process clearing utilities
└── docs/
    └── bios_screen.png                        # Main repository presentation screenshot
```

---

## 📊 Emulation Compatibility Reference

| Target Operating System | Recommended Machine Type | Suggested Base Memory | Status |
| :--- | :--- | :--- | :--- |
| **Alpine Linux (Virtual)** | `microvm` / `virt` | 256 MB - 512 MB | ⭐ Fully Verified |
| **Arch Linux / Debian CLI** | `standard (pc)` | 512 MB - 1024 MB | ✅ Operational |
| **Windows XP / 2000** | `standard (pc)` | 512 MB | ✅ Operational |
| **Windows 7 / Ubuntu GUI** | `standard (pc)` | 2048 MB+ | ⚠️ Experiencing UI Lag |

---

## ⚠️ Known Constraints & Technical Caveats
* **Experimental VNC Subsystem:** While the underlying screen-loop execution crash is resolved, heavy desktop interface tasks can produce visual frame latency or artifact indexing over the local network bridge. For optimal interactive fluid speeds, launching a secondary **External VNC Client** routing directly into your specified port is highly recommended.

---

## 🚀 Deployment, Installation, and Test Procedures

Follow these precise steps to get your virtual system running:

1. Navigate directly to the right-hand panel of this repository and open the **[Releases](https://github.com/kirill1984221321-gif/VirtualPC-VM/releases/)** tab.
2. Locate and download the latest compiled application binary asset package: `app-release.apk`.
3. Install the APK package on any compatible target device running **Android 12 or newer**.
4. Launch the application launcher, configure a new virtual machine card (assigning memory and cores), and click **Start VM**. The backend daemon will safely unpack the binary infrastructure and initiate the boot sequencing immediately into the iPXE console framework.

---

## 🇷🇺 Русскоязычное описание проекта (Brief Overview)

**VirtualPC-VM** — это производительный и оптимизированный мобильный эмулятор ПК для операционных систем Android, работающий на базе низкоуровневых бинарных сборок **QEMU**. Проект позволяет создавать изолированные микро-виртуальные машины и запускать образы дисков (ISO, QCOW2) Windows и Linux систем напрямую на вашем смартфоне.

Архитектурная логика, кросс-компиляция модулей и фиксы утечек памяти были отлажены автономными ИИ-агентами на этапе жесткого автоматического контроля качества (*Quality Control*).

### Главные особенности сборки:
* 🛠️ Полная кросс-архитектурная поддержка бинарных модулей под `aarch64`, `x86_64`, `armv7`, `x86`.
* 📺 Успешное устранение критического бага бесконечного зависания экрана («*Infinite VNC Connection*»).
* 📊 Удобная панель логирования QEMU потоков в реальном времени и пакетный импорт тяжелых ISO файлов.
* ⚠️ **Примечание по стабильности:** Встроенный в приложение графический VNC-просмотрщик работает стабильно в консольном режиме, но для тяжелых графических оболочек рекомендуется подключаться через внешние сторонние VNC-клиенты.

---

## 🤝 Open-Source Contributing and Code Standards
Contributions regarding QEMU process optimization flags, memory optimizations, or native C/C++ cross-compilation enhancements for mobile chipsets are welcome. Feel free to open detailed bug reports inside the **Issues** tab or submit structure-compliant **Pull Requests**!

*Main Project Maintainer & Developer: [@kirill1984221321-gif](https://github.com)*
<img width="668" height="1253" alt="Screenshot_2026_0905_112031" src="https://github.com/user-attachments/assets/1f04f22b-97c6-4747-8688-3f37ef261476" />
