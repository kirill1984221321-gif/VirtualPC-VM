package com.virtualpcvm

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

object ShortcutSettingsDialog {

    fun show(context: Context) {
        val isRu = Locale.getDefault().language.equals("ru", ignoreCase = true)
        val shortcuts = ShortcutManager.getShortcuts(context).toMutableList()

        val layout = LayoutInflater.from(context).inflate(R.layout.dialog_shortcuts_mapping, null)
        val rv = layout.findViewById<RecyclerView>(R.id.rvShortcuts)
        val btnReset = layout.findViewById<MaterialButton>(R.id.btnResetDefaults)

        rv.layoutManager = LinearLayoutManager(context)

        lateinit var adapter: ShortcutAdapter
        var currentDialog: AlertDialog? = null

        adapter = ShortcutAdapter(shortcuts, isRu) { item ->
            // Capture key dialog
            showKeyCaptureDialog(context, item, isRu) { newKey ->
                ShortcutManager.saveShortcut(context, item.actionId, newKey)
                item.mappedKeyCode = newKey
                adapter.notifyDataSetChanged()
                Toast.makeText(
                    context,
                    if (isRu) "Назначена клавиша: ${KeyEvent.keyCodeToString(newKey)}" else "Key assigned: ${KeyEvent.keyCodeToString(newKey)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        rv.adapter = adapter

        btnReset.setOnClickListener {
            ShortcutManager.resetDefaults(context)
            val updated = ShortcutManager.getShortcuts(context)
            shortcuts.clear()
            shortcuts.addAll(updated)
            adapter.notifyDataSetChanged()
            Toast.makeText(context, if (isRu) "Сброшено по умолчанию" else "Reset to defaults", Toast.LENGTH_SHORT).show()
        }

        currentDialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (isRu) "Настройка горячих клавиш" else "Keyboard Shortcut Mapping")
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showKeyCaptureDialog(
        context: Context,
        item: CustomShortcut,
        isRu: Boolean,
        onKeySelected: (Int) -> Unit
    ) {
        val message = if (isRu) {
            "Нажмите любую физическую клавишу или выберите из списка для действия «${item.titleRu}»."
        } else {
            "Press any physical key or choose from the list for «${item.titleEn}»."
        }

        val commonKeys = listOf(
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4,
            KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8,
            KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_SYSRQ, KeyEvent.KEYCODE_WINDOW
        )
        val keyNames = commonKeys.map { KeyEvent.keyCodeToString(it).replace("KEYCODE_", "") }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(if (isRu) "Назначить клавишу" else "Assign Key")
            .setMessage(message)
            .setItems(keyNames) { _, which ->
                onKeySelected(commonKeys[which])
            }
            .setNegativeButton(R.string.lang_btn_cancel, null)
            .show()
    }

    private class ShortcutAdapter(
        private val list: List<CustomShortcut>,
        private val isRu: Boolean,
        private val onItemClick: (CustomShortcut) -> Unit
    ) : RecyclerView.Adapter<ShortcutAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvShortcutTitle)
            val btnKey: MaterialButton = v.findViewById(R.id.btnKeyBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_shortcut_row, parent, false)
            return VH(v)
        }

        override fun getItemCount() = list.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvTitle.text = if (isRu) item.titleRu else item.titleEn
            val keyStr = KeyEvent.keyCodeToString(item.mappedKeyCode).replace("KEYCODE_", "")
            holder.btnKey.text = keyStr
            holder.btnKey.setOnClickListener { onItemClick(item) }
            holder.itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
