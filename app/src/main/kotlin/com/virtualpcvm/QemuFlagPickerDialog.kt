package com.virtualpcvm

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

object QemuFlagPickerDialog {

    fun show(context: Context, onFlagSelected: (String) -> Unit) {
        val isRu = Locale.getDefault().language.equals("ru", ignoreCase = true)
        val allFlags = QemuFlagPresets.presets
        var currentList = allFlags.toList()

        val layout = LayoutInflater.from(context).inflate(R.layout.dialog_qemu_flags_picker, null)
        val searchView = layout.findViewById<SearchView>(R.id.searchFlags)
        val rv = layout.findViewById<RecyclerView>(R.id.rvFlags)

        rv.layoutManager = LinearLayoutManager(context)

        var dialog: AlertDialog? = null

        val adapter = FlagAdapter(currentList, isRu) { flagItem ->
            onFlagSelected(flagItem.flag)
            dialog?.dismiss()
        }
        rv.adapter = adapter

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                val q = newText?.trim()?.lowercase().orEmpty()
                currentList = if (q.isEmpty()) {
                    allFlags
                } else {
                    allFlags.filter {
                        it.flag.lowercase().contains(q) ||
                        it.nameEn.lowercase().contains(q) ||
                        it.nameRu.lowercase().contains(q) ||
                        it.category.lowercase().contains(q) ||
                        it.descriptionEn.lowercase().contains(q) ||
                        it.descriptionRu.lowercase().contains(q)
                    }
                }
                adapter.updateData(currentList)
                return true
            }
        })

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (isRu) "Параметры и флаги QEMU" else "QEMU Parameters & Flags")
            .setView(layout)
            .setNegativeButton(R.string.lang_btn_cancel, null)
            .show()
    }

    private class FlagAdapter(
        private var items: List<QemuFlagPreset>,
        private val isRu: Boolean,
        private val onSelect: (QemuFlagPreset) -> Unit
    ) : RecyclerView.Adapter<FlagAdapter.VH>() {

        fun updateData(newItems: List<QemuFlagPreset>) {
            this.items = newItems
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvFlagName)
            val tvCategory: TextView = v.findViewById(R.id.tvFlagCategory)
            val tvCode: TextView = v.findViewById(R.id.tvFlagCode)
            val tvDesc: TextView = v.findViewById(R.id.tvFlagDesc)
            val btnInsert: MaterialButton = v.findViewById(R.id.btnInsertFlag)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_qemu_flag, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = if (isRu) item.nameRu else item.nameEn
            holder.tvCategory.text = item.category
            holder.tvCode.text = item.flag
            holder.tvDesc.text = if (isRu) item.descriptionRu else item.descriptionEn
            holder.btnInsert.setOnClickListener { onSelect(item) }
            holder.itemView.setOnClickListener { onSelect(item) }
        }
    }
}
