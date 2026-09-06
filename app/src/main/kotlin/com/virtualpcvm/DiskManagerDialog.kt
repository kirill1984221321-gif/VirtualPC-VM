package com.virtualpcvm

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

object DiskManagerDialog {

    fun show(context: Context, scope: CoroutineScope) {
        val disksDir = File(context.filesDir, "disks")
        if (!disksDir.exists()) disksDir.mkdirs()

        val files = disksDir.listFiles { f -> f.isFile && (f.name.endsWith(".qcow2") || f.name.endsWith(".img") || f.name.endsWith(".raw") || f.name.endsWith(".iso")) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            Toast.makeText(context, "В папке disks нет файлов", Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = object : ArrayAdapter<File>(context, android.R.layout.simple_list_item_2, android.R.id.text1, files) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)
                val file = getItem(position)!!
                text1.text = file.name
                val sizeMb = file.length() / (1024 * 1024)
                text2.text = "$sizeMb MB"
                return view
            }
        }

        val listView = ListView(context)
        listView.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Менеджер дисков (Disk Manager)")
            .setView(listView)
            .setNegativeButton("Закрыть", null)
            .show()

        listView.setOnItemClickListener { _, _, position, _ ->
            val file = files[position]
            showDiskInfoOptions(context, file, scope, dialog)
        }
    }

    private fun showDiskInfoOptions(context: Context, file: File, scope: CoroutineScope, parentDialog: AlertDialog) {
        val info = Qcow2Writer.inspectDisk(file)
        
        val options = mutableListOf("Изменить размер (Resize)", "Удалить (Delete)")
        
        MaterialAlertDialogBuilder(context)
            .setTitle(file.name)
            .setMessage(info.message)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        if (info.format.contains("qcow2", true)) {
                            // Resize QCOW2 via qemu-img
                            showResizeQcow2Dialog(context, file, scope)
                        } else if (info.format.contains("raw", true) || info.format.contains("img", true)) {
                            showResizeRawDialog(context, file, scope)
                        } else {
                            Toast.makeText(context, "Изменение размера для этого формата не поддерживается", Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        val sizeMb = file.length() / (1024 * 1024)
                        ConfirmationDialogHelper.show(
                            context = context,
                            title = "Удалить образ диска?",
                            message = "Вы действительно хотите удалить ${file.name}?\nЭто действие необратимо и приведет к полной потере всех данных на виртуальном диске.",
                            details = "Путь: ${file.absolutePath}\nРазмер на диске: $sizeMb МБ",
                            actionType = ConfirmationDialogHelper.ActionType.WIPE_DISK,
                            confirmText = "Удалить диск"
                        ) {
                            if (file.delete()) {
                                Toast.makeText(context, "Файл диска удален", Toast.LENGTH_SHORT).show()
                                parentDialog.dismiss()
                            } else {
                                Toast.makeText(context, "Ошибка удаления файла", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setPositiveButton("ОК", null)
            .show()
    }

    private fun showResizeRawDialog(context: Context, file: File, scope: CoroutineScope) {
        val input = EditText(context)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "Новый размер в Гигабайтах (GB)"

        MaterialAlertDialogBuilder(context)
            .setTitle("Увеличить размер RAW диска")
            .setMessage("Введите новый размер в гигабайтах (ГБ). Текущий размер: ${file.length() / (1024*1024*1024)} ГБ.")
            .setView(input)
            .setPositiveButton("Изменить") { _, _ ->
                val gb = input.text.toString().toLongOrNull()
                if (gb != null && gb > 0) {
                    val newSizeBytes = gb * 1024L * 1024L * 1024L
                    if (newSizeBytes > file.length()) {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    RandomAccessFile(file, "rw").use { raf ->
                                        raf.setLength(newSizeBytes)
                                    }
                                }
                                Toast.makeText(context, "Размер успешно увеличен до $gb ГБ", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        Toast.makeText(context, "Новый размер должен быть больше текущего", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showResizeQcow2Dialog(context: Context, file: File, scope: CoroutineScope) {
        val input = EditText(context)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "Добавить ГБ (например: 10)"

        MaterialAlertDialogBuilder(context)
            .setTitle("Увеличить размер QCOW2 диска")
            .setMessage("На сколько Гигабайт (ГБ) увеличить размер диска?\n\nПримечание: После изменения размера диска, вам необходимо будет расширить раздел (Partition) внутри самой гостевой ОС.")
            .setView(input)
            .setPositiveButton("Увеличить") { _, _ ->
                val addGb = input.text.toString().toLongOrNull()
                if (addGb != null && addGb > 0) {
                    scope.launch {
                        Toast.makeText(context, "Запуск qemu-img...", Toast.LENGTH_SHORT).show()
                        val qemuImg = QemuManager.findQemuImg(context)
                        if (qemuImg != null) {
                            val success = withContext(Dispatchers.IO) {
                                try {
                                    val cmd = listOf(qemuImg, "resize", file.absolutePath, "+${addGb}G")
                                    val pb = ProcessBuilder(cmd)
                                    val termuxPrefix = QemuInstaller.termuxPrefix(context).absolutePath
                                    val libDir = File(QemuInstaller.termuxPrefix(context), "lib").absolutePath
                                    
                                    pb.environment()["LD_LIBRARY_PATH"] = "$libDir:${context.applicationInfo.nativeLibraryDir}"
                                    pb.environment()["PATH"] = "$termuxPrefix/bin:/system/bin"
                                    
                                    val linker = QemuManager.getSystemLinker()
                                    val proc = if (android.os.Build.VERSION.SDK_INT >= 29 && linker != null) {
                                        ProcessBuilder(listOf(linker) + cmd).also {
                                            it.environment().putAll(pb.environment())
                                        }.start()
                                    } else {
                                        try {
                                            pb.start()
                                        } catch (e: Exception) {
                                            if (linker != null) {
                                                ProcessBuilder(listOf(linker) + cmd).also {
                                                    it.environment().putAll(pb.environment())
                                                }.start()
                                            } else throw e
                                        }
                                    }
                                    
                                    proc.waitFor() == 0
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    false
                                }
                            }
                            
                            if (success) {
                                Toast.makeText(context, "Размер диска успешно увеличен на $addGb ГБ", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Ошибка изменения размера. Проверьте логи QEMU.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, "Не найден бинарный файл qemu-img", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
