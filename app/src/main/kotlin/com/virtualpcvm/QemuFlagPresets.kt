package com.virtualpcvm

data class QemuFlagPreset(
    val category: String,
    val flag: String,
    val nameEn: String,
    val nameRu: String,
    val descriptionEn: String,
    val descriptionRu: String,
    val example: String
)

object QemuFlagPresets {
    val presets: List<QemuFlagPreset> = listOf(
        // Acceleration & CPU
        QemuFlagPreset(
            category = "CPU & Acceleration",
            flag = "-accel tcg,tb-size=512",
            nameEn = "TCG Buffer Size (512MB)",
            nameRu = "Размер буфера трансляции TCG (512MB)",
            descriptionEn = "Increases Translation Cache Block buffer for smoother JIT CPU execution on Android",
            descriptionRu = "Увеличивает буфер кэша JIT трансляции процессора для плавной работы на Android",
            example = "-accel tcg,tb-size=512"
        ),
        QemuFlagPreset(
            category = "CPU & Acceleration",
            flag = "-accel tcg,tb-size=1024",
            nameEn = "TCG Buffer Size (1024MB)",
            nameRu = "Размер буфера трансляции TCG (1024MB)",
            descriptionEn = "Increases Translation Cache Block buffer for very large workloads on Android",
            descriptionRu = "Увеличивает буфер кэша JIT трансляции процессора (до 1024МБ) для тяжелых ВМ",
            example = "-accel tcg,tb-size=1024"
        ),
        QemuFlagPreset(
            category = "CPU & Acceleration",
            flag = "-accel tcg,tb-size=256",
            nameEn = "TCG Buffer Size (256MB)",
            nameRu = "Размер буфера трансляции TCG (256MB)",
            descriptionEn = "Low memory TCG footprint for older or memory-constrained host devices",
            descriptionRu = "Малый буфер TCG для устройств с небольшим объемом оперативной памяти",
            example = "-accel tcg,tb-size=256"
        ),
        QemuFlagPreset(
            category = "CPU & Acceleration",
            flag = "-accel kvm",
            nameEn = "KVM Hardware Acceleration",
            nameRu = "Аппаратное ускорение KVM",
            descriptionEn = "Enables native hardware virtualization acceleration if /dev/kvm is available",
            descriptionRu = "Включает прямое аппаратное ускорение KVM если ядро поддерживает /dev/kvm",
            example = "-accel kvm"
        ),
        QemuFlagPreset(
            category = "CPU & Acceleration",
            flag = "-smp 4,cores=2,threads=2,sockets=1",
            nameEn = "SMP Multi-core Topology",
            nameRu = "Топология ядер SMP (2 ядра, 2 потока)",
            descriptionEn = "Configures symmetric multiprocessing layout with socket, core, and thread count",
            descriptionRu = "Настраивает топологию симметричной многопроцессорной обработки (сокетов, ядер, потоков)",
            example = "-smp 4,cores=2,threads=2,sockets=1"
        ),
        QemuFlagPreset(
            category = "CPU & Acceleration",
            flag = "-cpu host",
            nameEn = "CPU Host Pass-through",
            nameRu = "Проброс функций процессора Host",
            descriptionEn = "Passes through all host CPU features directly (best when KVM is enabled)",
            descriptionRu = "Передает все инструкции хост-процессора напрямую (лучший вариант при KVM)",
            example = "-cpu host"
        ),
        QemuFlagPreset(
            category = "CPU & Acceleration",
            flag = "-overcommit mem-lock=off",
            nameEn = "Overcommit Memory Optimization",
            nameRu = "Оптимизация оверкоммита памяти",
            descriptionEn = "Prevents aggressive memory locking, saving RAM on mobile chipsets",
            descriptionRu = "Предотвращает жесткую блокировку страниц памяти, экономя RAM на смартфонах",
            example = "-overcommit mem-lock=off"
        ),

        // Graphics & Display
        QemuFlagPreset(
            category = "Display & VGA",
            flag = "-vga virtio",
            nameEn = "VirtIO GPU Display",
            nameRu = "VirtIO GPU видеоадаптер",
            descriptionEn = "High performance paravirtualized graphics driver for Linux guests",
            descriptionRu = "Высокоскоростной паравиртуализированный видеодрайвер для Linux систем",
            example = "-vga virtio"
        ),
        QemuFlagPreset(
            category = "Display & VGA",
            flag = "-vga std",
            nameEn = "Standard Bochs VESA VGA",
            nameRu = "Стандартный VGA адаптер (Bochs)",
            descriptionEn = "Universal compatible display adapter suitable for Windows XP/7/10 and Linux",
            descriptionRu = "Универсальный совместимый дисплейный адаптер для Windows XP/7/10 и Linux",
            example = "-vga std"
        ),
        QemuFlagPreset(
            category = "Display & VGA",
            flag = "-vga cirrus",
            nameEn = "Cirrus Logic CL-GD5446",
            nameRu = "Cirrus Logic CL-GD5446 (Legacy)",
            descriptionEn = "Legacy graphics adapter with out-of-the-box drivers in Windows 95/98/2000",
            descriptionRu = "Классический видеоадаптер со встроенными драйверами в Windows 95/98/2000",
            example = "-vga cirrus"
        ),
        QemuFlagPreset(
            category = "Display & VGA",
            flag = "-vga qxl",
            nameEn = "QXL Paravirtual Graphics",
            nameRu = "QXL паравиртуальный видеоадаптер",
            descriptionEn = "SPICE/QXL optimized paravirtual display adapter",
            descriptionRu = "Оптимизированный адаптер для SPICE протокола и виртуальных рабочих столов",
            example = "-vga qxl"
        ),

        QemuFlagPreset(
            category = "Display & VGA",
            flag = "-display none",
            nameEn = "Headless Mode (No Display)",
            nameRu = "Headless режим (без дисплея)",
            descriptionEn = "Disables graphical output entirely. Best used with Serial Console or SSH.",
            descriptionRu = "Полностью отключает графический вывод. Использовать вместе с SSH или Serial консолью.",
            example = "-display none"
        ),
        QemuFlagPreset(
            category = "Display & VGA",
            flag = "-vnc :0,password=on",
            nameEn = "VNC Server with Password",
            nameRu = "VNC сервер с паролем",
            descriptionEn = "Secures the internal VNC server with a password prompt",
            descriptionRu = "Защищает встроенный VNC-сервер запросом пароля",
            example = "-vnc :0,password=on"
        ),

        QemuFlagPreset(
            category = "Display & VGA",
            flag = "-vga vmware",
            nameEn = "VMware SVGA-II",
            nameRu = "VMware SVGA-II видеоадаптер",
            descriptionEn = "VMware compatible virtual graphics card (good for older Windows)",
            descriptionRu = "Совместимая с VMware виртуальная видеокарта",
            example = "-vga vmware"
        ),
        QemuFlagPreset(
            category = "Display & VGA",
            flag = "-device ramfb",
            nameEn = "RAMFB Display",
            nameRu = "RAMFB видеоадаптер",
            descriptionEn = "Simple framebuffer display (Required for many ARM/macOS guests)",
            descriptionRu = "Простой кадровый буфер (требуется для многих ARM и macOS систем)",
            example = "-device ramfb"
        ),
        QemuFlagPreset(
            category = "Display & VGA",
            flag = "-device virtio-vga-gl",
            nameEn = "VirtIO GPU with 3D Acceleration",
            nameRu = "VirtIO GPU с 3D ускорением",
            descriptionEn = "Enables Virgil3D acceleration (requires host OpenGL ES support)",
            descriptionRu = "Включает 3D ускорение Virgil3D (требует поддержки OpenGL ES на хосте)",
            example = "-device virtio-vga-gl -display egl-headless"
        ),

        // Storage & Drives
        QemuFlagPreset(
            category = "Storage & Drives",
            flag = "-drive file=path/to/disk.qcow2,if=virtio,cache=writeback",
            nameEn = "VirtIO High-Speed Disk Drive",
            nameRu = "Высокоскоростной диск VirtIO (Writeback)",
            descriptionEn = "VirtIO block storage with writeback caching for reduced disk I/O latency",
            descriptionRu = "Блочный накопитель VirtIO с кэшированием записи для минимальных задержек I/O",
            example = "-drive file=disk.qcow2,if=virtio,cache=writeback"
        ),
        QemuFlagPreset(
            category = "Storage & Drives",
            flag = "-snapshot",
            nameEn = "Temporary Read-Only Snapshot Mode",
            nameRu = "Временный режим без сохранения (Snapshot)",
            descriptionEn = "Writes all changes to a temporary file; base disk image remains untouched",
            descriptionRu = "Записывает все изменения во временный файл; оригинальный диск остается неизменным",
            example = "-snapshot"
        ),
        QemuFlagPreset(
            category = "Storage & Drives",
            flag = "-boot order=dc,menu=on",
            nameEn = "Interactive Boot Menu (CD first)",
            nameRu = "Интерактивное меню загрузки (сначала CD)",
            descriptionEn = "Enables F12 boot device selector and tries CD-ROM first, then HDD",
            descriptionRu = "Включает выбор загрузочного устройства по F12 (сначала CD, затем диск)",
            example = "-boot order=dc,menu=on"
        ),

        // Network
        QemuFlagPreset(
            category = "Network",
            flag = "-netdev user,id=net0,hostfwd=tcp::2222-:22",
            nameEn = "SSH Port Forwarding (Port 2222 -> 22)",
            nameRu = "Проброс порта SSH (2222 -> 22)",
            descriptionEn = "Forwards local port 2222 to guest SSH port 22 for remote terminal access",
            descriptionRu = "Перенаправляет порт 2222 смартфона на порт 22 ВМ для доступа по SSH",
            example = "-netdev user,id=net0,hostfwd=tcp::2222-:22"
        ),
        QemuFlagPreset(
            category = "Network",
            flag = "-netdev user,id=net0,hostfwd=tcp::8080-:80",
            nameEn = "HTTP Web Port Forwarding (8080 -> 80)",
            nameRu = "Проброс веб-сервера (8080 -> 80)",
            descriptionEn = "Forwards host port 8080 to guest web server on port 80",
            descriptionRu = "Перенаправляет порт 8080 смартфона на веб-сервер ВМ (порт 80)",
            example = "-netdev user,id=net0,hostfwd=tcp::8080-:80"
        ),
        QemuFlagPreset(
            category = "Network",
            flag = "-device virtio-net-pci,netdev=net0",
            nameEn = "VirtIO Network Adapter",
            nameRu = "Сетевой адаптер VirtIO",
            descriptionEn = "High throughput paravirtualized network card with low CPU utilization",
            descriptionRu = "Паравиртуализированная сетевая карта с высокой пропускной способностью",
            example = "-device virtio-net-pci,netdev=net0"
        ),

        // Input & USB
        QemuFlagPreset(
            category = "Input & USB",
            flag = "-device usb-tablet",
            nameEn = "USB Absolute Tablet (Touchscreen)",
            nameRu = "USB планшет (Абсолютные координаты тача)",
            descriptionEn = "Maps touchscreen touches directly to guest OS cursor without drift",
            descriptionRu = "Точно проецирует касания экрана смартфона в координаты курсора ВМ",
            example = "-device usb-tablet"
        ),
        QemuFlagPreset(
            category = "Input & USB",
            flag = "-device virtio-tablet-pci",
            nameEn = "VirtIO Tablet Device",
            nameRu = "VirtIO планшетное устройство ввода",
            descriptionEn = "Ultra fast input event delivery for modern Linux distributions",
            descriptionRu = "Сверхбыстрая передача событий сенсора для современных дистрибутивов Linux",
            example = "-device virtio-tablet-pci"
        ),
        QemuFlagPreset(
            category = "Input & USB",
            flag = "-device usb-kbd",
            nameEn = "USB Hardware Keyboard",
            nameRu = "USB клавиатура",
            descriptionEn = "Emulates standard USB HID keyboard controller",
            descriptionRu = "Эмулирует стандартный контроллер клавиатуры USB HID",
            example = "-device usb-kbd"
        ),

        // Sound & Audio
        QemuFlagPreset(
            category = "Sound & Audio",
            flag = "-device ich9-intel-hda -device hda-duplex",
            nameEn = "Intel High Definition Audio (HDA)",
            nameRu = "Intel High Definition Audio (HDA)",
            descriptionEn = "Modern high-definition audio card for Windows 7/10 and Linux",
            descriptionRu = "Современная звуковая карта высокого качества для Windows 7/10 и Linux",
            example = "-device ich9-intel-hda -device hda-duplex"
        ),
        QemuFlagPreset(
            category = "Sound & Audio",
            flag = "-device AC97",
            nameEn = "AC97 Legacy Sound Card",
            nameRu = "Звуковая карта AC97 (Legacy)",
            descriptionEn = "Standard AC97 sound adapter ideal for Windows 98/2000/XP",
            descriptionRu = "Звуковой адаптер стандарта AC97 для Windows 98/2000/XP",
            example = "-device AC97"
        ),

        QemuFlagPreset(
            category = "Sound & Audio",
            flag = "-device sb16",
            nameEn = "Sound Blaster 16",
            nameRu = "Sound Blaster 16",
            descriptionEn = "Legacy Sound Blaster 16 audio card (Ideal for MS-DOS & Windows 95)",
            descriptionRu = "Историческая звуковая карта SB16 (Идеально для DOS и Win 95)",
            example = "-device sb16"
        ),
        QemuFlagPreset(
            category = "Sound & Audio",
            flag = "-device es1370",
            nameEn = "Ensoniq AudioPCI ES1370",
            nameRu = "Ensoniq AudioPCI ES1370",
            descriptionEn = "PCI Sound card supported by Windows 98 and early Linux",
            descriptionRu = "PCI Звуковая карта для Windows 98 и ранних Linux систем",
            example = "-device es1370"
        ),

        QemuFlagPreset(
            category = "Sound & Audio",
            flag = "-device hda-output,audiodev=snd0",
            nameEn = "HDA Output Only",
            nameRu = "HDA только вывод звука",
            descriptionEn = "Configures Intel HDA without microphone input, useful for older OSes",
            descriptionRu = "Настраивает Intel HDA без микрофона, полезно для старых ОС",
            example = "-device hda-output,audiodev=snd0"
        ),
        QemuFlagPreset(
            category = "Sound & Audio",
            flag = "-device pcspk,audiodev=snd0",
            nameEn = "PC Speaker (Beep)",
            nameRu = "PC Динамик (Пищалка)",
            descriptionEn = "Routes the legacy PC Speaker (Beep) through the audio device",
            descriptionRu = "Выводит звук классического системного динамика (PC Speaker) через аудиосистему",
            example = "-device pcspk,audiodev=snd0"
        ),

        // System & BIOS
        QemuFlagPreset(
            category = "System & BIOS",
            flag = "-rtc base=localtime,clock=host",
            nameEn = "Host Local Time RTC Clock",
            nameRu = "Синхронизация часов RTC со смартфоном",
            descriptionEn = "Synchronizes guest real-time clock with Android host device time",
            descriptionRu = "Синхронизирует системное время ВМ с локальным временем Android-устройства",
            example = "-rtc base=localtime,clock=host"
        ),
        QemuFlagPreset(
            category = "System & BIOS",
            flag = "-serial stdio",
            nameEn = "Redirect Serial Output to Stdout/Logs",
            nameRu = "Перенаправление Serial порта в логи",
            descriptionEn = "Outputs guest serial console to app logs for debugging boot sequence",
            descriptionRu = "Выводит серийную консоль ВМ напрямую в панель логов для отладки",
            example = "-serial stdio"
        ),
        QemuFlagPreset(
            category = "System & BIOS",
            flag = "-no-reboot",
            nameEn = "Exit on Guest Reboot",
            nameRu = "Выход при перезагрузке гостя",
            descriptionEn = "Stops QEMU process cleanly if the guest operating system initiates a reboot",
            descriptionRu = "Завершает процесс QEMU если гостевая ОС перезагружается",
            example = "-no-reboot"
        ),
        QemuFlagPreset(
            category = "System & BIOS",
            flag = "-no-shutdown",
            nameEn = "Keep Process on Poweroff",
            nameRu = "Не завершать процесс при выключении",
            descriptionEn = "Keeps the QEMU instance alive after guest powerdown for status inspection",
            descriptionRu = "Сохраняет процесс активным после выключения гостя для просмотра логов",
            example = "-no-shutdown"
        ),
        // USB & Controllers
        QemuFlagPreset(
            category = "USB & Input",
            flag = "-device qemu-xhci,id=xhci",
            nameEn = "XHCI USB 3.0 Controller",
            nameRu = "XHCI USB 3.0 Контроллер",
            descriptionEn = "Adds a high-speed USB 3.0 host controller (XHCI) to the guest",
            descriptionRu = "Добавляет высокоскоростной хост-контроллер USB 3.0 (XHCI)",
            example = "-device qemu-xhci,id=xhci"
        ),
        QemuFlagPreset(
            category = "USB & Input",
            flag = "-device usb-ehci,id=ehci",
            nameEn = "EHCI USB 2.0 Controller",
            nameRu = "EHCI USB 2.0 Контроллер",
            descriptionEn = "Adds a USB 2.0 host controller for older systems and compatibility",
            descriptionRu = "Добавляет хост-контроллер USB 2.0 для старых ОС",
            example = "-device usb-ehci,id=ehci"
        ),

        // CPU & Hyper-V
        QemuFlagPreset(
            category = "CPU & Acceleration",
            flag = "-cpu max,hv_relaxed,hv_spinlocks=0x1fff,hv_vapic,hv_time",
            nameEn = "Hyper-V Enlightenments (Windows)",
            nameRu = "Оптимизации Hyper-V (для Windows)",
            descriptionEn = "Enables paravirtualized Hyper-V timers and spinlocks to drastically speed up Windows guests",
            descriptionRu = "Включает оптимизации Hyper-V для значительного ускорения работы гостевых ОС Windows",
            example = "-cpu max,hv_relaxed,hv_spinlocks=0x1fff,hv_vapic,hv_time"
        ),
        QemuFlagPreset(
            category = "CPU & Acceleration",
            flag = "-machine kernel_irqchip=on",
            nameEn = "Kernel IRQChip",
            nameRu = "Аппаратный IRQChip в ядре",
            descriptionEn = "Offloads interrupt handling to host kernel (KVM only)",
            descriptionRu = "Переносит обработку прерываний в ядро хоста (только для KVM)",
            example = "-machine kernel_irqchip=on"
        ),

        // Storage & Misc
        QemuFlagPreset(
            category = "Storage & Drives",
            flag = "-device virtio-balloon",
            nameEn = "VirtIO Memory Balloon",
            nameRu = "Управление памятью VirtIO Balloon",
            descriptionEn = "Allows the guest to dynamically return unused RAM to the host",
            descriptionRu = "Позволяет гостевой ОС динамически возвращать неиспользуемую память",
            example = "-device virtio-balloon"
        ),
        QemuFlagPreset(
            category = "System & BIOS",
            flag = "-boot menu=on,splash-time=5000",
            nameEn = "Boot Menu & Splash Delay",
            nameRu = "Загрузочное меню с задержкой (5 сек)",
            descriptionEn = "Shows the BIOS boot menu for 5 seconds on startup (Press ESC or F12)",
            descriptionRu = "Показывает меню загрузки BIOS на 5 секунд при старте (нажмите ESC или F12)",
            example = "-boot menu=on,splash-time=5000"
        ),
        QemuFlagPreset(
            category = "Network",
            flag = "-netdev user,id=net0,smb=/storage/emulated/0/Download",
            nameEn = "Samba Share (Downloads Folder)",
            nameRu = "Общая папка Samba (Загрузки)",
            descriptionEn = "Shares the Android Downloads folder to the guest via SMB (built-in slirp)",
            descriptionRu = "Расшаривает папку Загрузок смартфона в гостевую ОС по протоколу SMB (\\10.0.2.4\\qemu)",
            example = "-netdev user,id=net0,smb=/storage/emulated/0/Download"
        )
    )
}
