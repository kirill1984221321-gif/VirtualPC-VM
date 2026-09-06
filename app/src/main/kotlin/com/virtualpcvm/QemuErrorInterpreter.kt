package com.virtualpcvm

import android.content.Context

/**
 * Diagnostic interpreter that translates QEMU exit codes and stderr/stdout logs
 * into human-readable messages with actionable recommendations.
 */
object QemuErrorInterpreter {

    data class DiagnosticResult(
        val errorType: ErrorType,
        val title: String,
        val message: String,
        val recommendation: String,
        val rawDetails: String = ""
    ) {
        val isCleanExit: Boolean
            get() = errorType == ErrorType.GENERIC_ERROR && rawDetails.isBlank()

        val userFriendlyTitle: String
            get() = title

        val likelyCause: String
            get() = message

        val suggestedAction: String
            get() = recommendation

        val category: ErrorType
            get() = errorType
    }

    enum class ErrorType {
        INSUFFICIENT_HOST_RAM,
        DISK_IMAGE_CORRUPTION,
        DISK_LOCKED_BY_ANOTHER_PROCESS,
        DISK_NOT_FOUND,
        KVM_UNAVAILABLE,
        PORT_ALREADY_BOUND,
        PERMISSION_OR_LINKER_RESTRICTION,
        SEGMENTATION_FAULT,
        INVALID_CPU_OR_INSTRUCTION,
        GENERIC_ERROR
    }

    fun interpret(
        exitCode: Int?,
        logs: List<String>,
        isRu: Boolean = true
    ): DiagnosticResult {
        val logText = logs.takeLast(100).joinToString("\n")
        val lowerLog = logText.lowercase()

        // 1. Check for Out of Memory (OOM Killer / Exit 137 / SIGKILL)
        if (exitCode == 137 || lowerLog.contains("cannot allocate memory") ||
            lowerLog.contains("os_mem_prealloc") || lowerLog.contains("out of memory") ||
            lowerLog.contains("failed to allocate") || lowerLog.contains("mmap failed")
        ) {
            return DiagnosticResult(
                errorType = ErrorType.INSUFFICIENT_HOST_RAM,
                title = if (isRu) "Недостаточно оперативной памяти (Host RAM)" else "Insufficient Host RAM",
                message = if (isRu) {
                    "Система Android принудительно завершила QEMU из-за нехватки свободной памяти на устройстве (OOM Killer)."
                } else {
                    "Android system terminated QEMU because the host device ran out of free memory (OOM Killer)."
                },
                recommendation = if (isRu) {
                    "Уменьшите объем выделяемой RAM в настройках ВМ (например, установите 256–512 МБ) и закройте другие приложения."
                } else {
                    "Reduce VM RAM in VM settings (e.g. set 256–512 MB) and close other background apps."
                },
                rawDetails = logText
            )
        }

        // 2. Check for Disk Image Corruption
        if (lowerLog.contains("image is corrupt") || lowerLog.contains("qcow2: image is corrupt") ||
            lowerLog.contains("header is corrupt") || lowerLog.contains("invalid qcow2 magic") ||
            lowerLog.contains("is not a valid") || lowerLog.contains("invalid cluster size")
        ) {
            return DiagnosticResult(
                errorType = ErrorType.DISK_IMAGE_CORRUPTION,
                title = if (isRu) "Повреждение образа диска (Disk Corruption)" else "Disk Image Corruption",
                message = if (isRu) {
                    "Файл образа виртуального диска поврежден или имеет некорректный заголовок qcow2."
                } else {
                    "The virtual disk image file is corrupted or contains an invalid qcow2 header."
                },
                recommendation = if (isRu) {
                    "Создайте новый qcow2 образ диска или восстановите состояние из сохраненного снапшота."
                } else {
                    "Create a new qcow2 disk image or revert to an earlier snapshot."
                },
                rawDetails = logText
            )
        }

        // 3. Disk Locked by Another Process
        if (lowerLog.contains("failed to get \"write\" lock") || lowerLog.contains("is another process using the image") ||
            lowerLog.contains("failed to get 'write' lock") || lowerLog.contains("resource temporarily unavailable")
        ) {
            return DiagnosticResult(
                errorType = ErrorType.DISK_LOCKED_BY_ANOTHER_PROCESS,
                title = if (isRu) "Образ диска заблокирован" else "Disk Image Locked",
                message = if (isRu) {
                    "Файл виртуального диска уже заблокирован другим работающим процессом QEMU."
                } else {
                    "The virtual disk image is already locked by another running QEMU instance."
                },
                recommendation = if (isRu) {
                    "Остановите все фоновые виртуальные машины перед повторным запуском."
                } else {
                    "Stop any other active virtual machines before starting this VM."
                },
                rawDetails = logText
            )
        }

        // 4. Port already in use
        if (lowerLog.contains("address already in use") || lowerLog.contains("bind failed") ||
            lowerLog.contains("cannot bind to") || lowerLog.contains("failed to bind socket")
        ) {
            return DiagnosticResult(
                errorType = ErrorType.PORT_ALREADY_BOUND,
                title = if (isRu) "Порт VNC/Monitor уже занят" else "Port Already in Use",
                message = if (isRu) {
                    "Порт VNC или монитора занят другой службой или предыдущим сеансом QEMU."
                } else {
                    "The specified VNC or monitor TCP port is currently occupied by another process."
                },
                recommendation = if (isRu) {
                    "Подождите 3–5 секунд для освобождения сокета или измените VNC-порт в настройках ВМ."
                } else {
                    "Wait a few seconds for the port to release or change the VNC port in VM settings."
                },
                rawDetails = logText
            )
        }

        // 5. KVM Hardware Virtualization Unavailable
        if (lowerLog.contains("kvm not supported") || lowerLog.contains("failed to initialize kvm") ||
            lowerLog.contains("could not access kvm") || lowerLog.contains("ioctl(kvm_create_vm)") ||
            lowerLog.contains("no such file or directory: /dev/kvm")
        ) {
            return DiagnosticResult(
                errorType = ErrorType.KVM_UNAVAILABLE,
                title = if (isRu) "KVM недоступен на устройстве" else "KVM Hardware Virtualization Unavailable",
                message = if (isRu) {
                    "Аппаратный гипервизор /dev/kvm не поддерживается ядром вашего Android устройства или требует root прав."
                } else {
                    "The /dev/kvm hardware hypervisor is not supported on this Android kernel or requires root access."
                },
                recommendation = if (isRu) {
                    "Отключите переключатель «Аппаратное ускорение KVM» в настройках ВМ для использования программной эмуляции TCG."
                } else {
                    "Disable the 'KVM Hardware Acceleration' switch in VM settings to use TCG software emulation."
                },
                rawDetails = logText
            )
        }

        // 6. Missing Disk File or ISO
        if (lowerLog.contains("could not open disk image") || lowerLog.contains("no such file or directory") ||
            lowerLog.contains("cannot open file")
        ) {
            return DiagnosticResult(
                errorType = ErrorType.DISK_NOT_FOUND,
                title = if (isRu) "Файл диска или ISO не найден" else "Disk or ISO File Not Found",
                message = if (isRu) {
                    "Указанный файл образа диска или установочный ISO-образ недоступен по заданному пути."
                } else {
                    "The specified disk image or ISO installation file does not exist at the given path."
                },
                recommendation = if (isRu) {
                    "Проверьте путь к диску/ISO в настройках ВМ и убедитесь, что файлу предоставлены разрешения."
                } else {
                    "Verify the disk/ISO path in VM settings and ensure file read permissions are granted."
                },
                rawDetails = logText
            )
        }

        // 7. Android W^X restriction or Linker error
        if (exitCode == 127 || lowerLog.contains("permission denied") || lowerLog.contains("w^x") ||
            lowerLog.contains("cannot execute") || lowerLog.contains("linker") || lowerLog.contains("dlopen failed")
        ) {
            return DiagnosticResult(
                errorType = ErrorType.PERMISSION_OR_LINKER_RESTRICTION,
                title = if (isRu) "Ограничение исполнения (Android W^X)" else "Execution Restriction (Android W^X)",
                message = if (isRu) {
                    "Android 10+ блокирует прямой запуск бинарных файлов из директории приложения без системного линковщика."
                } else {
                    "Android 10+ restricts direct binary execution without the system dynamic linker."
                },
                recommendation = if (isRu) {
                    "Приложение автоматически использует /system/bin/linker64. Переустановите пакеты QEMU при необходимости."
                } else {
                    "The app uses /system/bin/linker64. Reinstall QEMU packages if libraries are missing."
                },
                rawDetails = logText
            )
        }

        // 8. Segmentation Fault (Exit 139 / SIGSEGV)
        if (exitCode == 139 || lowerLog.contains("segmentation fault") || lowerLog.contains("sigsegv")) {
            return DiagnosticResult(
                errorType = ErrorType.SEGMENTATION_FAULT,
                title = if (isRu) "Ошибка сегментации (Segmentation Fault)" else "Segmentation Fault (Crash)",
                message = if (isRu) {
                    "Процесс эмулятора аварийно завершился из-за несовместимости инструкций CPU или поврежденной памяти."
                } else {
                    "The emulator process crashed due to incompatible CPU instructions or invalid memory access."
                },
                recommendation = if (isRu) {
                    "Смените модель CPU в настройках ВМ (например, выберите 'pentium3' для x86 или 'cortex-a57' для ARM) и отключите MTTCG."
                } else {
                    "Change CPU model in VM settings (e.g. choose 'pentium3' for x86 or 'cortex-a57' for ARM) and disable MTTCG."
                },
                rawDetails = logText
            )
        }

        // Fallback Generic Diagnosis
        val codeStr = if (exitCode != null) " (код $exitCode)" else ""
        return DiagnosticResult(
            errorType = ErrorType.GENERIC_ERROR,
            title = if (isRu) "Ошибка выполнения QEMU$codeStr" else "QEMU Execution Error$codeStr",
            message = if (isRu) {
                "Процесс виртуальной машины завершился с ошибкой. Проверьте параметры запуска."
            } else {
                "The virtual machine process exited unexpectedly. Check startup configuration."
            },
            recommendation = if (isRu) {
                "Ознакомьтесь с подробными логами в консоли эмулятора для выявления причины."
            } else {
                "Review the detailed emulator logs in the console to inspect the root cause."
            },
            rawDetails = logText
        )
    }
}
