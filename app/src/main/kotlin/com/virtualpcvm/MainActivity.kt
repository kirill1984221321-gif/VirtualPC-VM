package com.virtualpcvm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.virtualpcvm.databinding.ActivityMainBinding
import com.virtualpcvm.databinding.ItemVmCardBinding
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vms = mutableListOf<VmConfig>()
    private val prefs by lazy { getSharedPreferences("virtualpcvm_prefs", MODE_PRIVATE) }
    private lateinit var adapter: VmAdapter

    private val importFileLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            importFiles(uris)
        }
    }

    private fun importFiles(uris: List<android.net.Uri>) {
        Toast.makeText(this, "Начинаем импорт ${uris.size} файлов...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val qemuDir = File(filesDir, "qemu-bins")
                if (!qemuDir.exists()) qemuDir.mkdirs()
                
                val importedPaths = mutableListOf<String>()

                for (uri in uris) {
                    var fileName = "imported_file_${System.currentTimeMillis()}"
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                        }
                    }
                    
                    val destFile = File(qemuDir, fileName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    importedPaths.add(destFile.absolutePath)
                }
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val joinedPaths = importedPaths.joinToString("\n")
                    val clip = android.content.ClipData.newPlainText("QEMU Files", joinedPaths)
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "Успешно импортировано файлов: ${uris.size}\nПути скопированы в буфер обмена!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        loadVms()
        checkStoragePermission()

        adapter = VmAdapter(
            items = vms,
            onStart = { cfg -> launchVm(cfg) },
            onEdit = { cfg -> showEditDialog(cfg) },
            onStop = { cfg ->
                QemuManager.stop(cfg.id)
                Toast.makeText(this, "ВМ «${cfg.name}» остановлена", Toast.LENGTH_SHORT).show()
                adapter.notifyDataSetChanged()
            },
            onRename = { cfg -> showRenameDialog(cfg) },
            onDelete = { cfg -> confirmDeleteVm(cfg) },
            onOptions = { cfg, anchor -> showCardPopupMenu(cfg, anchor) },
            onShowLogs = { cfg -> showLogsDialog(cfg) },
            onLongClick = { cfg, anchor -> showCardPopupMenu(cfg, anchor) }
        )
        binding.recyclerVms.layoutManager = LinearLayoutManager(this)
        binding.recyclerVms.adapter = adapter

        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                val filter = newText?.lowercase() ?: ""
                if (filter.isEmpty()) {
                    adapter.updateData(vms)
                } else {
                    val filtered = vms.filter { it.name.lowercase().contains(filter) }
                    adapter.updateData(filtered)
                }
                return true
            }
        })

        binding.fabAddVm.setOnClickListener { showCreateDialog() }

        refreshQemuBanner()
        checkAndPromptInstall()
    }

    private fun saveVms() {
        try {
            val jsonArray = JSONArray()
            for (vm in vms) {
                val obj = JSONObject().apply {
                    put("id", vm.id)
                    put("name", vm.name)
                    put("arch", vm.architecture.name)
                    put("machine", vm.machineType.name)
                    put("ram", vm.ramMb)
                    put("cpu", vm.cpuCores)
                    put("cpuModel", vm.cpuModel)
                    put("disk", vm.diskPath)
                    put("diskFormat", vm.diskFormat)
                    put("iso", vm.isoPath)
                    put("bootDevice", vm.bootDevice)
                    put("vgaDriver", vm.vgaDriver)
                    put("networkMode", vm.networkMode)
                    put("audio", vm.enableAudio)
                    put("audioModel", vm.audioModel)
                    put("usbTablet", vm.enableUsbTablet)
                    put("mtcg", vm.enableMtcg)
                    put("kvm", vm.enableKvm)
                    put("extraArgs", vm.extraArgs)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString("vms_list", jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving VMs", e)
        }
    }

    private fun loadVms() {
        vms.clear()
        try {
            val json = prefs.getString("vms_list", null)
            if (json != null) {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    vms.add(
                        VmConfig(
                            id = obj.optLong("id", System.currentTimeMillis() + i),
                            name = obj.optString("name", "VM"),
                            architecture = try {
                                Architecture.valueOf(obj.optString("arch", Architecture.X86_64.name))
                            } catch (_: Exception) { Architecture.X86_64 },
                            machineType = try {
                                MachineType.valueOf(obj.optString("machine", MachineType.PC.name))
                            } catch (_: Exception) { MachineType.PC },
                            ramMb = obj.optInt("ram", 1024),
                            cpuCores = obj.optInt("cpu", 2),
                            cpuModel = obj.optString("cpuModel", "max"),
                            diskPath = obj.optString("disk", ""),
                            diskFormat = obj.optString("diskFormat", "qcow2"),
                            isoPath = obj.optString("iso", ""),
                            bootDevice = obj.optString("bootDevice", "disk"),
                            vgaDriver = obj.optString("vgaDriver", "std"),
                            networkMode = obj.optString("networkMode", "user"),
                            enableAudio = obj.optBoolean("audio", true),
                            audioModel = obj.optString("audioModel", "hda"),
                            enableUsbTablet = obj.optBoolean("usbTablet", true),
                            enableMtcg = obj.optBoolean("mtcg", true),
                            enableKvm = obj.optBoolean("kvm", false),
                            extraArgs = obj.optString("extraArgs", "")
                        )
                    )
                }
            } else {
                // Add friendly default template for quick start
                vms.add(
                    VmConfig(
                        id = 1001L,
                        name = "Alpine Linux (x86_64)",
                        architecture = Architecture.X86_64,
                        machineType = MachineType.PC,
                        ramMb = 512,
                        cpuCores = 2,
                        cpuModel = "max"
                    )
                )
                saveVms()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading VMs", e)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshQemuBanner()
        adapter.notifyDataSetChanged()
    }

    private fun checkAndPromptInstall() {
        if (!QemuInstaller.anyInstalled(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Установка пакетов QEMU")
                .setMessage("Бинарные файлы QEMU ещё не установлены.\nХотите открыть менеджер загрузки QEMU?")
                .setPositiveButton("Установить") { _, _ ->
                    startActivity(Intent(this, InstallActivity::class.java))
                }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.add(0, 1001, 0, "Импорт файла (.iso/.qcow2)")
            .setIcon(android.R.drawable.ic_menu_add)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == 1001) {
            importFileLauncher.launch(arrayOf("*/*"))
            return true
        }
        return when (item.itemId) {
            R.id.action_download -> {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://distrowatch.com/"))
                startActivity(intent)
                true
            }
            R.id.action_cleanup -> {
                showCleanupDialog()
                true
            }
            R.id.action_install -> {
                startActivity(Intent(this, InstallActivity::class.java))
                true
            }
            R.id.action_settings -> {
                showSystemSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showCleanupDialog() {
        val disksDir = File(filesDir, "disks")
        val diskFiles = disksDir.listFiles()?.toList() ?: emptyList()
        val inUsePaths = vms.map { it.diskPath }.toSet()
        val orphaned = diskFiles.filter { it.absolutePath !in inUsePaths }

        if (orphaned.isEmpty()) {
            Toast.makeText(this, "Неиспользуемые образы дисков не найдены.", Toast.LENGTH_SHORT).show()
            return
        }

        val totalSize = orphaned.sumOf { it.length() } / (1024 * 1024)

        MaterialAlertDialogBuilder(this)
            .setTitle("Очистка неиспользуемых дисков")
            .setMessage("Найдено ${orphaned.size} файлов дисков, не привязанных к ВМ.\nЗанимаемое место: $totalSize МБ.\n\nУдалить их?")
            .setPositiveButton("Очистить") { _, _ ->
                var deleted = 0
                for (f in orphaned) {
                    if (f.delete()) deleted++
                }
                Toast.makeText(this, "Освобождено $totalSize МБ (удалено файлов: $deleted)", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showSystemSettingsDialog() {
        val appSettings = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val swAutoInstall = MaterialSwitch(this).apply {
            text = "Авто-проверка пакетов QEMU при старте"
            isChecked = appSettings.getBoolean("auto_install", true)
        }

        val swCleanOnExit = MaterialSwitch(this).apply {
            text = "Очищать кэш логов при выходе"
            isChecked = appSettings.getBoolean("clean_exit", false)
        }

        layout.addView(swAutoInstall)
        layout.addView(swCleanOnExit)

        MaterialAlertDialogBuilder(this)
            .setTitle("Системные параметры")
            .setView(layout)
            .setPositiveButton("Сохранить") { _, _ ->
                appSettings.edit()
                    .putBoolean("auto_install", swAutoInstall.isChecked)
                    .putBoolean("clean_exit", swCleanOnExit.isChecked)
                    .apply()
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        val appSettings = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        if (appSettings.getBoolean("clean_exit", false)) {
            QemuManager.clearAllLogs()
        }
    }

    private fun refreshQemuBanner() {
        val installed = QemuInstaller.anyInstalled(this)
        binding.bannerQemu.apply {
            visibility = View.VISIBLE
            if (installed) {
                text = "✓ QEMU готов к работе — выберите виртуальную машину"
                setBackgroundColor(0xFF1B5E20.toInt())
                setOnClickListener(null)
            } else {
                text = "⚠ Пакеты QEMU не установлены — нажмите для установки"
                setBackgroundColor(0xFFE65100.toInt())
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, InstallActivity::class.java))
                }
            }
            setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun launchVm(cfg: VmConfig) {
        val bin = QemuManager.findBinary(this, cfg.architecture)
        if (bin == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Бинарник QEMU не найден")
                .setMessage("Для архитектуры «${cfg.architecture.label}» (${cfg.architecture.binary}) пакет не загружен.\n\nОткрыть менеджер установки?")
                .setPositiveButton("Установить") { _, _ ->
                    startActivity(Intent(this, InstallActivity::class.java))
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }

        val logLines = StringBuilder()
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Запуск ВМ «${cfg.name}»")
            .setMessage("Инициализация QEMU...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                QemuManager.start(this@MainActivity, cfg) { line ->
                    logLines.appendLine(line)
                    runOnUiThread {
                        progressDialog.setMessage(line.take(90))
                    }
                }

                progressDialog.setMessage("Ожидание VNC-сервера на порту ${cfg.vncPort}...")
                val vncReady = QemuManager.waitForVnc(
                    vmId = cfg.id,
                    host = "127.0.0.1",
                    port = cfg.vncPort,
                    timeoutMs = 12_000
                )
                progressDialog.dismiss()
                adapter.notifyDataSetChanged()

                if (!vncReady) {
                    val exitCode = QemuManager.getExitCode(cfg.id)
                    val extraMsg = if (exitCode != null) "\n(Процесс завершился с кодом $exitCode)" else ""
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("VNC ещё не готов")
                        .setMessage("Сервер VNC пока не отвечает на 127.0.0.1:${cfg.vncPort}$extraMsg.\n\nВывод QEMU:\n${logLines.takeLast(1200)}")
                        .setPositiveButton("Открыть VNC всё равно") { _, _ -> openVnc(cfg) }
                        .setNeutralButton("Посмотреть логи") { _, _ -> showLogsDialog(cfg) }
                        .setNegativeButton("Отмена", null)
                        .show()
                    return@launch
                }

                openVnc(cfg)

            } catch (e: Exception) {
                progressDialog.dismiss()
                adapter.notifyDataSetChanged()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Ошибка запуска QEMU")
                    .setMessage("${e.message}\n\n${logLines.takeLast(600)}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun openVnc(cfg: VmConfig) {
        startActivity(Intent(this, VNCActivity::class.java).apply {
            putExtra(VNCActivity.EXTRA_HOST, "127.0.0.1")
            putExtra(VNCActivity.EXTRA_PORT, cfg.vncPort)
            putExtra(VNCActivity.EXTRA_MONITOR_PORT, cfg.monitorPort)
            putExtra(VNCActivity.EXTRA_VM_NAME, cfg.name)
            putExtra(VNCActivity.EXTRA_VM_ID, cfg.id)
        })
    }

    private fun showCreateDialog() {
        showVmDialog(VmConfig(id = System.currentTimeMillis())) { newCfg ->
            vms.add(newCfg)
            adapter.notifyItemInserted(vms.lastIndex)
            saveVms()
        }
    }

    private fun showEditDialog(cfg: VmConfig) {
        val idx = vms.indexOfFirst { it.id == cfg.id }
        if (idx < 0) return
        showVmDialog(cfg) { updated ->
            vms[idx] = updated
            adapter.notifyItemChanged(idx)
            saveVms()
        }
    }

    private fun showRenameDialog(cfg: VmConfig) {
        val input = EditText(this).apply {
            setText(cfg.name)
            setSelection(cfg.name.length)
            setPadding(40, 30, 40, 20)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Переименовать виртуальную машину")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    val idx = vms.indexOfFirst { it.id == cfg.id }
                    if (idx >= 0) {
                        vms[idx] = cfg.copy(name = newName)
                        adapter.notifyItemChanged(idx)
                        saveVms()
                        Toast.makeText(this, "ВМ переименована в «$newName»", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDeleteVm(cfg: VmConfig) {
        val isRun = QemuManager.isRunning(cfg.id)
        if (isRun) {
            Toast.makeText(this, "Сначала остановите ВМ перед удалением", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить ВМ «${cfg.name}»?")
            .setMessage("Конфигурация ВМ будет удалена из списка.")
            .setPositiveButton("Удалить ВМ") { _, _ ->
                val idx = vms.indexOfFirst { it.id == cfg.id }
                if (idx >= 0) {
                    vms.removeAt(idx)
                    adapter.notifyItemRemoved(idx)
                    saveVms()
                    Toast.makeText(this, "ВМ удалена", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Удалить с диском") { _, _ ->
                if (cfg.diskPath.isNotBlank()) {
                    try { File(cfg.diskPath).delete() } catch (_: Exception) {}
                }
                val idx = vms.indexOfFirst { it.id == cfg.id }
                if (idx >= 0) {
                    vms.removeAt(idx)
                    adapter.notifyItemRemoved(idx)
                    saveVms()
                    Toast.makeText(this, "ВМ и образ диска удалены", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun cloneVm(cfg: VmConfig) {
        val newCfg = cfg.copy(
            id = System.currentTimeMillis(),
            name = "${cfg.name} (Копия)"
        )
        vms.add(newCfg)
        adapter.notifyItemInserted(vms.lastIndex)
        saveVms()
        Toast.makeText(this, "ВМ скопирована как «${newCfg.name}»", Toast.LENGTH_SHORT).show()
    }

    private fun showLogsDialog(cfg: VmConfig) {
        val logs = QemuManager.getLogs(cfg.id)
        val msg = if (logs.isEmpty()) "Журнал пуст. ВМ ещё не запускалась или не вывела сообщений." else logs.takeLast(100).joinToString("\n")

        val sv = android.widget.ScrollView(this).apply {
            setPadding(30, 20, 30, 10)
        }
        val tv = android.widget.TextView(this).apply {
            text = msg
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        sv.addView(tv)

        MaterialAlertDialogBuilder(this)
            .setTitle("Логи ВМ «${cfg.name}»")
            .setView(sv)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun showCardPopupMenu(cfg: VmConfig, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "✏ Переименовать ВМ")
        popup.menu.add(0, 2, 1, "⚙ Настройки параметров")
        popup.menu.add(0, 3, 2, "📋 Дублировать ВМ")
        popup.menu.add(0, 4, 3, "📸 Снапшоты (qcow2)")
        popup.menu.add(0, 5, 4, "📜 Журнал вывода")
        popup.menu.add(0, 6, 5, "🗑 Удалить ВМ")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showRenameDialog(cfg)
                2 -> showEditDialog(cfg)
                3 -> cloneVm(cfg)
                4 -> showSnapshotDialog(cfg)
                5 -> showLogsDialog(cfg)
                6 -> confirmDeleteVm(cfg)
            }
            true
        }
        popup.show()
    }

    private fun showVmDialog(initial: VmConfig, onSave: (VmConfig) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_vm_form, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etName)
        val etRam = view.findViewById<TextInputEditText>(R.id.etRam)
        val etCpu = view.findViewById<TextInputEditText>(R.id.etCpu)
        val etCpuModel = view.findViewById<TextInputEditText>(R.id.etCpuModel)
        val etDisk = view.findViewById<TextInputEditText>(R.id.etDisk)
        val etIso = view.findViewById<TextInputEditText>(R.id.etIso)
        val etExtraArgs = view.findViewById<TextInputEditText>(R.id.etExtraArgs)

        val spinArch = view.findViewById<Spinner>(R.id.spinArch)
        val spinMach = view.findViewById<Spinner>(R.id.spinMachine)
        val spinBoot = view.findViewById<Spinner>(R.id.spinBootDevice)
        val spinVga = view.findViewById<Spinner>(R.id.spinVga)
        val spinNet = view.findViewById<Spinner>(R.id.spinNetwork)

        val switchAudio = view.findViewById<MaterialSwitch>(R.id.switchAudio)
        val switchKvm = view.findViewById<MaterialSwitch>(R.id.switchKvm)
        val switchUsbTablet = view.findViewById<MaterialSwitch>(R.id.switchUsbTablet)
        val switchMtcg = view.findViewById<MaterialSwitch>(R.id.switchMtcg)
        val spinPreset = view.findViewById<Spinner>(R.id.spinPreset)

        etName.setText(initial.name)
        etRam.setText(initial.ramMb.toString())
        etCpu.setText(initial.cpuCores.toString())
        etCpuModel.setText(initial.cpuModel)
        etDisk.setText(initial.diskPath)
        etIso.setText(initial.isoPath)
        etExtraArgs.setText(initial.extraArgs)

        switchAudio.isChecked = initial.enableAudio
        switchKvm.isChecked = initial.enableKvm
        switchUsbTablet?.isChecked = initial.enableUsbTablet
        switchMtcg?.isChecked = initial.enableMtcg

        val archValues = Architecture.values()
        spinArch.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, archValues.map { it.label })
        spinArch.setSelection(archValues.indexOf(initial.architecture).coerceAtLeast(0))

        val machValues = MachineType.values()
        spinMach.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, machValues.map { "${it.value} (${it.displayName})" })
        spinMach.setSelection(machValues.indexOf(initial.machineType).coerceAtLeast(0))

        val bootValues = listOf("Диск (HDD)", "CD-ROM (ISO)")
        spinBoot.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bootValues)
        spinBoot.setSelection(if (initial.bootDevice == "cdrom") 1 else 0)

        val vgaValues = listOf("std (Стандартный)", "virtio (Ускоренный)", "cirrus (Совместимый)", "none (Отключить)")
        val vgaKeys = listOf("std", "virtio", "cirrus", "none")
        spinVga.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vgaValues)
        spinVga.setSelection(vgaKeys.indexOf(initial.vgaDriver).coerceAtLeast(0))

        val netValues = listOf("Пользовательский NAT (rtl8139/virtio)", "Отключить сеть")
        val netKeys = listOf("user", "none")
        spinNet.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, netValues)
        spinNet.setSelection(netKeys.indexOf(initial.networkMode).coerceAtLeast(0))

        // Preset selector logic
        val presets = listOf(
            "Пользовательский",
            "Windows XP (x86 32-bit)",
            "Windows 7 / 10 (x86_64)",
            "Windows 11 (x86_64 Q35)",
            "Ubuntu / Debian (ARM64 Virt)",
            "Alpine Linux (x86_64)",
            "Debian GNU/Linux (RISC-V 64)",
            "Mac OS 9 (PowerPC Mac99)"
        )
        spinPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets)
        spinPreset.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    1 -> { // Win XP
                        spinArch.setSelection(archValues.indexOf(Architecture.I386))
                        spinMach.setSelection(machValues.indexOf(MachineType.PC))
                        etRam.setText("512"); etCpu.setText("1"); etCpuModel.setText("pentium3")
                        spinVga.setSelection(0) // std
                    }
                    2 -> { // Win 7/10
                        spinArch.setSelection(archValues.indexOf(Architecture.X86_64))
                        spinMach.setSelection(machValues.indexOf(MachineType.Q35))
                        etRam.setText("2048"); etCpu.setText("2"); etCpuModel.setText("max")
                        spinVga.setSelection(0)
                    }
                    3 -> { // Win 11
                        spinArch.setSelection(archValues.indexOf(Architecture.X86_64))
                        spinMach.setSelection(machValues.indexOf(MachineType.Q35))
                        etRam.setText("4096"); etCpu.setText("4"); etCpuModel.setText("max")
                        spinVga.setSelection(0)
                    }
                    4 -> { // Ubuntu ARM64
                        spinArch.setSelection(archValues.indexOf(Architecture.ARM64))
                        spinMach.setSelection(machValues.indexOf(MachineType.VIRT))
                        etRam.setText("2048"); etCpu.setText("2"); etCpuModel.setText("cortex-a57")
                        spinVga.setSelection(1) // virtio
                    }
                    5 -> { // Alpine Linux
                        spinArch.setSelection(archValues.indexOf(Architecture.X86_64))
                        spinMach.setSelection(machValues.indexOf(MachineType.PC))
                        etRam.setText("512"); etCpu.setText("1"); etCpuModel.setText("max")
                        spinVga.setSelection(0)
                    }
                    6 -> { // RISC-V
                        spinArch.setSelection(archValues.indexOf(Architecture.RISCV64))
                        spinMach.setSelection(machValues.indexOf(MachineType.VIRT))
                        etRam.setText("1024"); etCpu.setText("2"); etCpuModel.setText("max")
                        spinVga.setSelection(1)
                    }
                    7 -> { // Mac OS 9 PPC
                        spinArch.setSelection(archValues.indexOf(Architecture.POWERPC))
                        spinMach.setSelection(machValues.indexOf(MachineType.MAC99))
                        etRam.setText("512"); etCpu.setText("1"); etCpuModel.setText("g4")
                        spinVga.setSelection(0)
                    }
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Create Disk Button
        val btnCreateDisk = view.findViewById<View>(R.id.btnCreateDisk)
        btnCreateDisk.setOnClickListener {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 0)
            }
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = "Размер в ГБ (например 10)"
                setText("10")
            }
            val spinFormat = Spinner(this)
            val formats = listOf("qcow2", "raw")
            spinFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formats)

            layout.addView(input)
            layout.addView(spinFormat)

            MaterialAlertDialogBuilder(this)
                .setTitle("Создать виртуальный диск")
                .setView(layout)
                .setPositiveButton("Создать") { _, _ ->
                    val sizeGb = input.text.toString().toIntOrNull() ?: 10
                    val format = formats[spinFormat.selectedItemPosition]
                    val disksDir = File(filesDir, "disks").also { it.mkdirs() }
                    val diskFile = File(disksDir, "disk_${System.currentTimeMillis()}.$format")

                    lifecycleScope.launch {
                        Toast.makeText(this@MainActivity, "Создание диска $sizeGb ГБ...", Toast.LENGTH_SHORT).show()
                        val ok = QemuManager.createDiskImage(this@MainActivity, diskFile, sizeGb, format) { log ->
                            Log.d("QEMU_IMG", log)
                        }
                        if (ok) {
                            etDisk.setText(diskFile.absolutePath)
                            Toast.makeText(this@MainActivity, "Диск создан: ${diskFile.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Ошибка создания диска", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // Select ISO Button
        val btnSelectIso = view.findViewById<View>(R.id.btnSelectIso)
        btnSelectIso.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Выбор ISO образа")
                .setMessage("Укажите абсолютный путь к файлу ISO, например:\n/storage/emulated/0/Download/os.iso\n\nИли поместите ISO в папку приложения:\n${getExternalFilesDir(null)?.absolutePath}")
                .setPositiveButton("Понятно", null)
                .show()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (initial.id == 0L || initial.name == "Новая ВМ") "Параметры новой ВМ" else "Настройки «${initial.name}»")
            .setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                val chosenArch = archValues[spinArch.selectedItemPosition]
                val chosenMach = machValues[spinMach.selectedItemPosition]
                val chosenBoot = if (spinBoot.selectedItemPosition == 1) "cdrom" else "disk"
                val chosenVga = vgaKeys[spinVga.selectedItemPosition]
                val chosenNet = netKeys[spinNet.selectedItemPosition]

                onSave(
                    initial.copy(
                        name = etName.text.toString().trim().ifBlank { "ВМ" },
                        ramMb = etRam.text.toString().toIntOrNull() ?: 1024,
                        cpuCores = etCpu.text.toString().toIntOrNull() ?: 2,
                        cpuModel = etCpuModel.text.toString().trim().ifBlank { "max" },
                        architecture = chosenArch,
                        machineType = chosenMach,
                        bootDevice = chosenBoot,
                        vgaDriver = chosenVga,
                        networkMode = chosenNet,
                        enableAudio = switchAudio.isChecked,
                        enableUsbTablet = switchUsbTablet?.isChecked ?: true,
                        enableMtcg = switchMtcg?.isChecked ?: true,
                        enableKvm = switchKvm.isChecked,
                        diskPath = etDisk.text.toString().trim(),
                        isoPath = etIso.text.toString().trim(),
                        extraArgs = etExtraArgs.text.toString().trim()
                    )
                )
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showSnapshotDialog(cfg: VmConfig) {
        if (!cfg.diskPath.endsWith(".qcow2")) {
            Toast.makeText(this, "Снапшоты поддерживаются только для дисков формата qcow2", Toast.LENGTH_LONG).show()
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }

        val input = EditText(this).apply {
            hint = "Имя снапшота (например snap1)"
        }
        layout.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("Снапшоты (qcow2)")
            .setView(layout)
            .setPositiveButton("Создать") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    lifecycleScope.launch {
                        val ok = QemuManager.manageSnapshot(this@MainActivity, File(cfg.diskPath), name, "-c") { log ->
                            Log.d("QEMU_SNAP", log)
                        }
                        Toast.makeText(this@MainActivity, if (ok) "Снапшот создан" else "Ошибка создания", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Восстановить") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    lifecycleScope.launch {
                        val ok = QemuManager.manageSnapshot(this@MainActivity, File(cfg.diskPath), name, "-a") { log ->
                            Log.d("QEMU_SNAP", log)
                        }
                        Toast.makeText(this@MainActivity, if (ok) "Снапшот восстановлен" else "Ошибка отката", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton("Отмена", null)
            .show()
    }
}

/* ── RecyclerView Adapter ── */
class VmAdapter(
    private var items: List<VmConfig>,
    private val onStart: (VmConfig) -> Unit,
    private val onEdit: (VmConfig) -> Unit,
    private val onStop: (VmConfig) -> Unit,
    private val onRename: (VmConfig) -> Unit,
    private val onDelete: (VmConfig) -> Unit,
    private val onOptions: (VmConfig, View) -> Unit,
    private val onShowLogs: (VmConfig) -> Unit,
    private val onLongClick: (VmConfig, View) -> Unit
) : RecyclerView.Adapter<VmAdapter.VH>() {

    fun updateData(newItems: List<VmConfig>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemVmCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemVmCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val cfg = items[pos]
        val running = QemuManager.isRunning(cfg.id)
        holder.b.apply {
            tvVmName.text = cfg.name
            tvVmInfo.text = buildString {
                append("${cfg.architecture.label} · ${cfg.ramMb} MB · ${cfg.cpuCores} CPU")
                if (cfg.enableAudio) append(" · 🔊")
                if (cfg.enableKvm) append(" · ⚡KVM")
            }
            tvVmArch.text = cfg.machineType.value
            chipStatus.text = if (running) "Запущена" else "Остановлена"
            chipStatus.setChipBackgroundColorResource(
                if (running) R.color.chip_running else R.color.chip_stopped
            )
            btnStart.isEnabled = !running
            btnStop.isEnabled = running
            btnStart.setOnClickListener { onStart(cfg) }
            btnStop.setOnClickListener { onStop(cfg) }
            btnRename.setOnClickListener { onRename(cfg) }
            btnEdit.setOnClickListener { onEdit(cfg) }
            btnDelete.setOnClickListener { onDelete(cfg) }
            btnOptions.setOnClickListener { onOptions(cfg, it) }
            btnLogs.setOnClickListener { onShowLogs(cfg) }
            root.setOnLongClickListener {
                onLongClick(cfg, it)
                true
            }
        }
    }
}
