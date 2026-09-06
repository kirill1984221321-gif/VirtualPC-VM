package com.virtualpcvm

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SnapshotDialogHelper {

    fun show(
        context: Context,
        scope: CoroutineScope,
        vmConfig: VmConfig,
        onStateChanged: (() -> Unit)? = null
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_snapshots, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etSnapshotName)
        val btnCreate = dialogView.findViewById<MaterialButton>(R.id.btnCreateSnapshot)
        val progress = dialogView.findViewById<ProgressBar>(R.id.progressSnapshots)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvEmptySnapshots)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rvSnapshots)

        rv.layoutManager = LinearLayoutManager(context)

        var dialog: AlertDialog? = null
        var adapter: SnapshotAdapter? = null

        fun reloadList() {
            progress.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            rv.visibility = View.GONE

            scope.launch {
                val list = SnapshotManager.listSnapshots(context, vmConfig)
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    if (list.isEmpty()) {
                        tvEmpty.visibility = View.VISIBLE
                        rv.visibility = View.GONE
                    } else {
                        tvEmpty.visibility = View.GONE
                        rv.visibility = View.VISIBLE
                        if (adapter == null) {
                            adapter = SnapshotAdapter(
                                items = list,
                                onRevert = { snap ->
                                    ConfirmationDialogHelper.show(
                                        context = context,
                                        title = context.getString(R.string.snap_title),
                                        message = context.getString(R.string.snap_revert_confirm, snap.tag),
                                        details = "ВМ: ${vmConfig.name} · Снимок: ${snap.tag}",
                                        actionType = ConfirmationDialogHelper.ActionType.REVERT_SNAPSHOT,
                                        confirmText = context.getString(R.string.snap_revert)
                                    ) {
                                        progress.visibility = View.VISIBLE
                                        scope.launch {
                                            val res = SnapshotManager.revertSnapshot(context, vmConfig, snap.tag)
                                            withContext(Dispatchers.Main) {
                                                progress.visibility = View.GONE
                                                if (res.isSuccess) {
                                                    Toast.makeText(context, res.getOrNull(), Toast.LENGTH_SHORT).show()
                                                    onStateChanged?.invoke()
                                                } else {
                                                    Toast.makeText(context, res.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
                                                }
                                                reloadList()
                                            }
                                        }
                                    }
                                },
                                onRename = { snap ->
                                    val input = android.widget.EditText(context).apply {
                                        setText(snap.tag)
                                        setSelection(snap.tag.length)
                                        setPadding(40, 30, 40, 20)
                                    }
                                    MaterialAlertDialogBuilder(context)
                                        .setTitle(context.getString(R.string.snap_rename_title, snap.tag))
                                        .setView(input)
                                        .setPositiveButton(R.string.vm_btn_save) { _, _ ->
                                            val newName = input.text.toString().trim()
                                            if (newName.isNotBlank() && newName != snap.tag) {
                                                progress.visibility = View.VISIBLE
                                                scope.launch {
                                                    val res = SnapshotManager.renameSnapshot(context, vmConfig, snap.tag, newName)
                                                    withContext(Dispatchers.Main) {
                                                        progress.visibility = View.GONE
                                                        if (res.isSuccess) {
                                                            Toast.makeText(context, res.getOrNull(), Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(context, res.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
                                                        }
                                                        reloadList()
                                                    }
                                                }
                                            }
                                        }
                                        .setNegativeButton(R.string.lang_btn_cancel, null)
                                        .show()
                                },
                                onDelete = { snap ->
                                    ConfirmationDialogHelper.show(
                                        context = context,
                                        title = context.getString(R.string.snap_title),
                                        message = context.getString(R.string.snap_delete_confirm, snap.tag),
                                        details = "Снимок: ${snap.tag} · VM: ${vmConfig.name}",
                                        actionType = ConfirmationDialogHelper.ActionType.DELETE_SNAPSHOT,
                                        confirmText = context.getString(R.string.snap_delete)
                                    ) {
                                        progress.visibility = View.VISIBLE
                                        scope.launch {
                                            val res = SnapshotManager.deleteSnapshot(context, vmConfig, snap.tag)
                                            withContext(Dispatchers.Main) {
                                                progress.visibility = View.GONE
                                                if (res.isSuccess) {
                                                    Toast.makeText(context, res.getOrNull(), Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, res.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
                                                }
                                                reloadList()
                                            }
                                        }
                                    }
                                }
                            )
                            rv.adapter = adapter
                        } else {
                            adapter?.updateData(list)
                        }
                    }
                }
            }
        }

        btnCreate.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                etName.error = context.getString(R.string.snap_name_hint)
                return@setOnClickListener
            }
            progress.visibility = View.VISIBLE
            btnCreate.isEnabled = false
            scope.launch {
                val res = SnapshotManager.createSnapshot(context, vmConfig, name)
                withContext(Dispatchers.Main) {
                    btnCreate.isEnabled = true
                    progress.visibility = View.GONE
                    if (res.isSuccess) {
                        Toast.makeText(context, res.getOrNull(), Toast.LENGTH_SHORT).show()
                        etName.setText("")
                        reloadList()
                    } else {
                        Toast.makeText(context, res.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle("${context.getString(R.string.snap_title)}: ${vmConfig.name}")
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        reloadList()
    }
}
