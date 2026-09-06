package com.virtualpcvm

import android.content.Context
import android.view.KeyEvent

data class CustomShortcut(
    val actionId: String,
    val titleEn: String,
    val titleRu: String,
    val defaultKeyCode: Int,
    var mappedKeyCode: Int = defaultKeyCode
)

object ShortcutManager {
    private const val PREFS_NAME = "shortcut_settings"

    const val ACTION_PAUSE = "action_pause"
    const val ACTION_SNAPSHOT = "action_snapshot"
    const val ACTION_FULLSCREEN = "action_fullscreen"
    const val ACTION_CTRL_ALT_DEL = "action_cad"
    const val ACTION_ALT_TAB = "action_alt_tab"
    const val ACTION_ALT_F4 = "action_alt_f4"
    const val ACTION_WIN_KEY = "action_win"
    const val ACTION_SEND_TEXT = "action_send_text"
    const val ACTION_STOP = "action_stop"
    const val ACTION_LOGS = "action_logs"

    fun getShortcuts(context: Context): List<CustomShortcut> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultList = listOf(
            CustomShortcut(ACTION_PAUSE, "Pause / Resume VM", "Пауза / Возобновить ВМ", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            CustomShortcut(ACTION_SNAPSHOT, "Take Instant Snapshot", "Мгновенный снимок (Snapshot)", KeyEvent.KEYCODE_CAMERA),
            CustomShortcut(ACTION_FULLSCREEN, "Toggle VNC Fullscreen", "Полноэкранный режим VNC", KeyEvent.KEYCODE_F11),
            CustomShortcut(ACTION_CTRL_ALT_DEL, "Send Ctrl+Alt+Del", "Отправить Ctrl+Alt+Del", KeyEvent.KEYCODE_SYSRQ),
            CustomShortcut(ACTION_ALT_TAB, "Send Alt+Tab (Switch App)", "Переключение окон (Alt+Tab)", KeyEvent.KEYCODE_TAB),
            CustomShortcut(ACTION_ALT_F4, "Close Window (Alt+F4)", "Закрыть окно (Alt+F4)", KeyEvent.KEYCODE_ESCAPE),
            CustomShortcut(ACTION_WIN_KEY, "Windows / Super Key", "Клавиша Windows (Пуск)", KeyEvent.KEYCODE_WINDOW),
            CustomShortcut(ACTION_SEND_TEXT, "Open Quick Text Input", "Ввод произвольного текста", KeyEvent.KEYCODE_T),
            CustomShortcut(ACTION_STOP, "Stop VM", "Остановить ВМ", KeyEvent.KEYCODE_MEDIA_STOP),
            CustomShortcut(ACTION_LOGS, "Toggle Logs Overlay", "Открыть / скрыть логи", KeyEvent.KEYCODE_L)
        )

        for (sc in defaultList) {
            sc.mappedKeyCode = prefs.getInt(sc.actionId, sc.defaultKeyCode)
        }
        return defaultList
    }

    fun saveShortcut(context: Context, actionId: String, keyCode: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(actionId, keyCode).apply()
    }

    fun resetDefaults(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    fun getActionForEvent(context: Context, event: KeyEvent): String? {
        val list = getShortcuts(context)
        return list.firstOrNull { it.mappedKeyCode == event.keyCode }?.actionId
    }
}
