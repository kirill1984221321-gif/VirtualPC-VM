package com.virtualpcvm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.Locale

object VmStateNotifier {

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun isRussian(): Boolean {
        return Locale.getDefault().language.equals("ru", ignoreCase = true)
    }

    fun notifyBooting(context: Context, vmName: String) {
        val msg = if (isRussian()) {
            "🚀 Запуск QEMU: инициализация «$vmName»…"
        } else {
            "🚀 Booting QEMU: initializing «$vmName»…"
        }
        showToast(context, msg, Toast.LENGTH_SHORT)
    }

    fun notifyRunning(context: Context, vmName: String, vncPort: Int = 5901) {
        val msg = if (isRussian()) {
            "✓ ВМ «$vmName» запущена (VNC :$vncPort).\n💡 Доступен встроенный VNC и внешние клиенты (RealVNC, bVNC)."
        } else {
            "✓ VM «$vmName» is running (VNC :$vncPort).\n💡 Built-in VNC and external viewers (RealVNC, bVNC) are available."
        }
        showToast(context, msg, Toast.LENGTH_LONG)
    }

    fun notifyError(context: Context, vmName: String, error: String) {
        val msg = if (isRussian()) {
            "❌ Ошибка ВМ «$vmName»: $error"
        } else {
            "❌ Error in VM «$vmName»: $error"
        }
        showToast(context, msg, Toast.LENGTH_LONG)
    }

    fun notifyStopped(context: Context, vmName: String) {
        val msg = if (isRussian()) {
            "⏹ ВМ «$vmName» остановлена"
        } else {
            "⏹ VM «$vmName» stopped"
        }
        showToast(context, msg, Toast.LENGTH_SHORT)
    }

    fun notifySnapshotSaved(context: Context, vmName: String, snapshotTag: String, isAutosave: Boolean = false) {
        val msg = if (isRussian()) {
            if (isAutosave) {
                "📸 Автосохранение: снимок «$snapshotTag» для «$vmName» успешно создан"
            } else {
                "📸 Снимок «$snapshotTag» для «$vmName» успешно сохранен"
            }
        } else {
            if (isAutosave) {
                "📸 Autosave: snapshot «$snapshotTag» for «$vmName» saved successfully"
            } else {
                "📸 Snapshot «$snapshotTag» for «$vmName» saved successfully"
            }
        }
        showToast(context, msg, Toast.LENGTH_LONG)
    }

    fun notifyResourceWarning(context: Context, vmName: String, reason: String) {
        val msg = if (isRussian()) {
            "⚠️ Предупреждение о ресурсах «$vmName»: $reason"
        } else {
            "⚠️ Resource threshold warning for «$vmName»: $reason"
        }
        showToast(context, msg, Toast.LENGTH_LONG)
    }

    fun notifyVncConnectingAttempt(context: Context, attempt: Int, maxAttempts: Int, backoffMs: Long) {
        val msg = if (isRussian()) {
            "⏳ Подключение к VNC… Попытка $attempt из $maxAttempts (повтор через ${backoffMs}мс)"
        } else {
            "⏳ Connecting to VNC… Attempt $attempt of $maxAttempts (retry in ${backoffMs}ms)"
        }
        showToast(context, msg, Toast.LENGTH_SHORT)
    }

    private fun showToast(context: Context, text: String, duration: Int) {
        mainHandler.post {
            try {
                Toast.makeText(context.applicationContext, text, duration).show()
            } catch (_: Exception) {}
        }
    }
}
