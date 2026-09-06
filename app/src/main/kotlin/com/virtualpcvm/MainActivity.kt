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
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applySavedLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (LocaleHelper.isFirstLaunch(this)) {
            showLanguageDialog(isFirstLaunch = true)
        }

        loadVms()
        checkStoragePermission()

        adapter = VmAdapter(
            items = vms,
            onStart = { cfg -> launchVm(cfg) },
            onEdit = { cfg -> showEditDialog(cfg) },
            onStop = { cfg ->
                ConfirmationDialogHelper.show(
                    context = this,
                    title = "Остановить ВМ «${cfg.name}»?",
                    message = "Выключение работающей виртуальной машины завершит все выполняющиеся в ней процессы и может привести к потере несохранённых данных внутри гостевой ОС.",
                    details = "ВМ: ${cfg.name} (ID: ${cfg.id})",
                    actionType = ConfirmationDialogHelper.ActionType.SHUTDOWN_VM,
                    confirmText = "Выключить ВМ"
                ) {
                    QemuManager.stop(cfg.id)
                    Toast.makeText(this, "ВМ «${cfg.name}» остановлена", Toast.LENGTH_SHORT).show()
                    adapter.notifyDataSetChanged()
                    binding.realtimeLogView.updateRunningStatus(false)
                }
            },
            onRename = { cfg -> showRenameDialog(cfg) },
            onDelete = { cfg -> confirmDeleteVm(cfg) },
            onOptions = { cfg, anchor -> showCardPopupMenu(cfg, anchor) },
            onShowLogs = { cfg -> showLogsDialog(cfg) },
            onShowSnapshots = { cfg -> showSnapshotDialog(cfg) },
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

        // Start QemuMonitorService background loop and update VM metrics in adapter
        QemuMonitorService.startMonitoring(lifecycleScope)
        lifecycleScope.launch {
            QemuMonitorService.metricsMap.collect { map ->
                adapter.updateMetrics(map)
                updateConsoleAndWarnings(map)
            }
        }

        setupDashboardComponents()
        setupQuickActions()

        refreshQemuBanner()
        checkAndPromptInstall()
    }

    private fun setupDashboardComponents() {
        // Disk Usage Storage Growth Card
        var isDiskExpanded = true
        binding.btnToggleDiskUsage.setOnClickListener {
            isDiskExpanded = !isDiskExpanded
            binding.layoutDiskUsageBody.visibility = if (isDiskExpanded) View.VISIBLE else View.GONE
            binding.btnToggleDiskUsage.text = if (isDiskExpanded) "▼ Свернуть" else "▲ Развернуть"
        }
        binding.headerDiskUsage.setOnClickListener {
            binding.btnToggleDiskUsage.performClick()
        }
        binding.btnRefreshDiskUsage.setOnClickListener {
            binding.chartDiskUsage.scanDisks(vms)
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Статистика хранилища обновлена", Toast.LENGTH_SHORT).show()
        }
        binding.chartDiskUsage.scanDisks(vms)

        // Attach initial VM to real-time log viewer
        val runningVm = vms.firstOrNull { QemuManager.isRunning(it.id) } ?: vms.firstOrNull()
        if (runningVm != null) {
            binding.realtimeLogView.attachVm(runningVm.id, runningVm.name, QemuManager.isRunning(runningVm.id))
        }
    }

    private fun updateConsoleAndWarnings(metrics: Map<Long, QemuMonitorService.Companion.VmMetrics>) {
        val runningVm = vms.firstOrNull { QemuManager.isRunning(it.id) }
        if (runningVm != null) {
            binding.realtimeLogView.attachVm(runningVm.id, runningVm.name, true)

            val metric = metrics[runningVm.id]
            if (metric != null && metric.cpuPercent > 92f) {
                VmStateNotifier.notifyResourceWarning(this, runningVm.name, "CPU ${metric.cpuPercent.toInt()}%")
            }
        }
    }

    private fun setupQuickActions() {
        binding.fabQuickActions.setOnClickListener { v ->
            val runningVm = vms.firstOrNull { QemuManager.isRunning(it.id) }
            val popup = PopupMenu(this, v)
            val isRu = LocaleHelper.getLanguage(this) == LocaleHelper.LANG_RU

            popup.menu.add(0, 1, 0, if (isRu) "⚡ Перезагрузить ВМ (Hard Reset)" else "⚡ Power Cycle (Hard Reset)")
            popup.menu.add(0, 2, 1, if (isRu) "🔄 Принудительный перезапуск" else "🔄 Force Reboot")
            popup.menu.add(0, 3, 2, if (isRu) "🖥 Открыть экран VNC" else "🖥 Open VNC Display")
            popup.menu.add(0, 4, 3, if (isRu) "📸 Мгновенный снимок" else "📸 Take Snapshot")
            popup.menu.add(0, 5, 4, if (isRu) "💻 Развернуть консоль логов" else "💻 Toggle Console Logs")

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        if (runningVm != null && runningVm.monitorPort > 0) {
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                QemuManager.executeMonitorCommand(runningVm.monitorPort, "system_reset")
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, if (isRu) "Команда system_reset отправлена" else "system_reset sent", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            Toast.makeText(this, if (isRu) "Нет активной запущенной ВМ" else "No running VM", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> {
                        if (runningVm != null) {
                            ConfirmationDialogHelper.show(
                                context = this@MainActivity,
                                title = if (isRu) "Перезапустить ВМ «${runningVm.name}»?" else "Restart VM «${runningVm.name}»?",
                                message = if (isRu) "Виртуальная машина будет принудительно остановлена и запущена повторно." else "VM will be stopped and restarted.",
                                actionType = ConfirmationDialogHelper.ActionType.SHUTDOWN_VM,
                                confirmText = if (isRu) "Перезапустить" else "Restart"
                            ) {
                                QemuManager.stop(runningVm.id)
                                launchVm(runningVm)
                            }
                        } else {
                            Toast.makeText(this, if (isRu) "Нет активной запущенной ВМ" else "No running VM", Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> {
                        if (runningVm != null) {
                            openVnc(runningVm)
                        } else {
                            Toast.makeText(this, if (isRu) "Нет активной запущенной ВМ" else "No running VM", Toast.LENGTH_SHORT).show()
                        }
                    }
                    4 -> {
                        if (runningVm != null) {
                            val tag = "snap_${System.currentTimeMillis()}"
                            lifecycleScope.launch {
                                val res = SnapshotManager.createSnapshot(this@MainActivity, runningVm, tag)
                                if (res.isSuccess) {
                                    VmStateNotifier.notifySnapshotSaved(this@MainActivity, runningVm.name, tag)
                                } else {
                                    Toast.makeText(this@MainActivity, res.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            Toast.makeText(this, if (isRu) "Нет активной запущенной ВМ" else "No running VM", Toast.LENGTH_SHORT).show()
                        }
                    }
                    5 -> {
                        binding.realtimeLogView.findViewById<View>(R.id.btnToggleLogs)?.performClick()
                    }
                }
                true
            }
            popup.show()
        }
    }

    private fun saveVms() {
        VmRepository.saveAllVms(this, vms)
    }

    private fun loadVms() {
        vms.clear()
        val loaded = VmRepository.getAllVms(this)
        if (loaded.isNotEmpty()) {
            vms.addAll(loaded)
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
    }

    override fun onResume() {
        super.onResume()
        refreshQemuBanner()
        adapter.notifyDataSetChanged()
        binding.chartDiskUsage.scanDisks(vms)
        AutosaveManager.start(this) { vms }
    }

    override fun onPause() {
        super.onPause()
        AutosaveManager.stop()
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
            R.id.action_guide -> {
                UserGuideDialog.show(this)
                true
            }
            R.id.action_language -> {
                showLanguageDialog(isFirstLaunch = false)
                true
            }
            R.id.action_export_configs -> {
                exportVmConfigs()
                true
            }
            R.id.action_import_configs -> {
                importVmConfigsDialog()
                true
            }
            R.id.action_shortcuts -> {
                ShortcutSettingsDialog.show(this)
                true
            }
            R.id.action_download -> {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://distrowatch.com/"))
                startActivity(intent)
                true
            }
            R.id.action_cleanup -> {
                showCleanupDialog()
                true
            }
            R.id.action_disk_manager -> {
                showDiskManagerDialog()
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
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportVmConfigs() {
        val isRu = LocaleHelper.getLanguage(this) == LocaleHelper.LANG_RU
        val json = VmRepository.exportVmsToJson(vms)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isRu) "Экспорт конфигураций ВМ" else "Export VM Configurations")
            .setMessage(if (isRu) "Сформирован JSON файл для ${vms.size} виртуальных машин.\nВыберите действие:" else "Generated JSON file for ${vms.size} virtual machines.\nChoose action:")
            .setPositiveButton(if (isRu) "Скопировать JSON" else "Copy JSON") { _, _ ->
                val clip = android.content.ClipData.newPlainText("VM Configs", json)
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(this, if (isRu) "JSON скопирован в буфер обмена" else "JSON copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(if (isRu) "Поделиться" else "Share") { _, _ ->
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, json)
                    putExtra(Intent.EXTRA_TITLE, "virtual_pc_vms_backup.json")
                    type = "application/json"
                }
                startActivity(Intent.createChooser(sendIntent, "Export VM Configs"))
            }
            .setNegativeButton(R.string.lang_btn_cancel, null)
            .show()
    }

    private fun importVmConfigsDialog() {
        val isRu = LocaleHelper.getLanguage(this) == LocaleHelper.LANG_RU
        val input = EditText(this).apply {
            hint = if (isRu) "Вставьте содержимое JSON конфигурации..." else "Paste VM configuration JSON here..."
            setPadding(40, 30, 40, 20)
            maxLines = 8
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (isRu) "Импорт конфигураций ВМ" else "Import VM Configurations")
            .setView(input)
            .setPositiveButton(if (isRu) "Импортировать" else "Import") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val imported = VmRepository.parseVmsFromJson(text, assignNewIds = true)
                    if (imported.isNotEmpty()) {
                        vms.addAll(imported)
                        adapter.notifyDataSetChanged()
                        saveVms()
                        Toast.makeText(this, if (isRu) "Успешно импортировано ВМ: ${imported.size}" else "Successfully imported ${imported.size} VMs", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, if (isRu) "Некорректный JSON формат" else "Invalid JSON format", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.lang_btn_cancel, null)
            .show()
    }

    private fun showLanguageDialog(isFirstLaunch: Boolean) {
        val currentLang = LocaleHelper.getLanguage(this)
        var selectedLang = if (currentLang == LocaleHelper.LANG_EN) LocaleHelper.LANG_EN else LocaleHelper.LANG_RU

        val view = layoutInflater.inflate(R.layout.dialog_language_select, null)
        val cardRu = view.findViewById<MaterialCardView>(R.id.cardRussian)
        val cardEn = view.findViewById<MaterialCardView>(R.id.cardEnglish)
        val rbRu = view.findViewById<RadioButton>(R.id.rbRussian)
        val rbEn = view.findViewById<RadioButton>(R.id.rbEnglish)

        fun updateSelection() {
            val isRu = selectedLang == LocaleHelper.LANG_RU
            rbRu.isChecked = isRu
            rbEn.isChecked = !isRu
            val selectedColor = ContextCompat.getColor(this, R.color.primary)
            val normalColor = ContextCompat.getColor(this, android.R.color.darker_gray)
            cardRu.strokeColor = if (isRu) selectedColor else normalColor
            cardEn.strokeColor = if (!isRu) selectedColor else normalColor
        }

        cardRu.setOnClickListener {
            selectedLang = LocaleHelper.LANG_RU
            updateSelection()
        }
        rbRu.setOnClickListener {
            selectedLang = LocaleHelper.LANG_RU
            updateSelection()
        }
        cardEn.setOnClickListener {
            selectedLang = LocaleHelper.LANG_EN
            updateSelection()
        }
        rbEn.setOnClickListener {
            selectedLang = LocaleHelper.LANG_EN
            updateSelection()
        }
        updateSelection()

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lang_select_title)
            .setView(view)
            .setPositiveButton(R.string.lang_btn_confirm) { _, _ ->
                LocaleHelper.setLanguage(this, selectedLang)
            }

        if (isFirstLaunch) {
            builder.setCancelable(false)
        } else {
            builder.setNegativeButton(R.string.lang_btn_cancel, null)
        }

        builder.show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_about)
            .setMessage(
                "Virtual PC (QEMU on Android)\n\n" +
                "• QEMU Architectures: x86_64, aarch64, i386, arm\n" +
                "• Built-in VNC Client with Direct Touch & Trackpad mode\n" +
                "• Hardware acceleration & Multi-threaded TCG\n" +
                "• Full snapshot support (qcow2)\n" +
                "• Language: English / Русский\n" +
                "• Version: 1.2.0"
            )
            .setPositiveButton("OK", null)
            .show()
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

    private fun showDiskManagerDialog() {
        DiskManagerDialog.show(this, lifecycleScope)
    }

    private fun showSystemSettingsDialog() {
        val appSettings = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val btnChangeLang = com.google.android.material.button.MaterialButton(this).apply {
            val cur = if (LocaleHelper.getLanguage(this@MainActivity) == LocaleHelper.LANG_RU) "Русский" else "English"
            text = "${getString(R.string.menu_language)}: $cur"
            setOnClickListener {
                showLanguageDialog(isFirstLaunch = false)
            }
        }
        layout.addView(btnChangeLang)

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
        val isRu = LocaleHelper.getLanguage(this) == LocaleHelper.LANG_RU
        if (bin == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(if (isRu) "Бинарник QEMU не найден" else "QEMU Binary Not Found")
                .setMessage(if (isRu) "Для архитектуры «${cfg.architecture.label}» (${cfg.architecture.binary}) пакет не загружен.\n\nОткрыть менеджер установки?" else "QEMU package for architecture «${cfg.architecture.label}» (${cfg.architecture.binary}) is not downloaded.\n\nOpen installation manager?")
                .setPositiveButton(if (isRu) "Установить" else "Install") { _, _ ->
                    startActivity(Intent(this, InstallActivity::class.java))
                }
                .setNegativeButton(R.string.lang_btn_cancel, null)
                .show()
            return
        }

        VmStateNotifier.notifyBooting(this, cfg.name)

        val logLines = StringBuilder()
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (isRu) "Запуск ВМ «${cfg.name}»" else "Launching VM «${cfg.name}»")
            .setMessage(if (isRu) "Инициализация QEMU..." else "Initializing QEMU...")
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

                progressDialog.setMessage(if (isRu) "Ожидание VNC-сервера на порту ${cfg.vncPort}..." else "Waiting for VNC server on port ${cfg.vncPort}...")
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
                    val extraMsg = if (exitCode != null) "\n(Exit code: $exitCode)" else ""
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(if (isRu) "VNC ещё не готов" else "VNC Not Ready")
                        .setMessage(if (isRu) "Сервер VNC пока не отвечает на 127.0.0.1:${cfg.vncPort}$extraMsg.\n\nВывод QEMU:\n${logLines.takeLast(1200)}" else "VNC server is not yet responding on 127.0.0.1:${cfg.vncPort}$extraMsg.\n\nQEMU output:\n${logLines.takeLast(1200)}")
                        .setPositiveButton(if (isRu) "Открыть VNC всё равно" else "Open VNC Anyway") { _, _ -> openVnc(cfg) }
                        .setNeutralButton(if (isRu) "Посмотреть логи" else "View Logs") { _, _ -> showLogsDialog(cfg) }
                        .setNegativeButton(R.string.lang_btn_cancel, null)
                        .show()
                    return@launch
                }

                VmStateNotifier.notifyRunning(this@MainActivity, cfg.name)
                openVnc(cfg)

            } catch (e: Exception) {
                progressDialog.dismiss()
                adapter.notifyDataSetChanged()
                VmStateNotifier.notifyError(this@MainActivity, cfg.name, e.message ?: "Failed")
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(if (isRu) "Ошибка запуска QEMU" else "QEMU Launch Error")
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

        val hasDisk = cfg.diskPath.isNotBlank() && File(cfg.diskPath).exists()
        val diskDetails = if (hasDisk) {
            val sizeMb = File(cfg.diskPath).length() / (1024 * 1024)
            "Файл диска: ${cfg.diskPath} ($sizeMb МБ)"
        } else "Конфигурация ВМ (без отдельного диска)"

        ConfirmationDialogHelper.show(
            context = this,
            title = "Удалить ВМ «${cfg.name}»?",
            message = "Конфигурация виртуальной машины будет безвозвратно удалена из списка." +
                    (if (hasDisk) " Связанный образ диска также будет стерт с устройства." else ""),
            details = diskDetails,
            actionType = ConfirmationDialogHelper.ActionType.DELETE_VM,
            confirmText = if (hasDisk) "Удалить ВМ и диск" else "Удалить ВМ"
        ) {
            if (hasDisk) {
                try { File(cfg.diskPath).delete() } catch (_: Exception) {}
            }
            val idx = vms.indexOfFirst { it.id == cfg.id }
            if (idx >= 0) {
                vms.removeAt(idx)
                adapter.notifyItemRemoved(idx)
                saveVms()
                binding.chartDiskUsage.scanDisks(vms)
                Toast.makeText(this, "ВМ удалена", Toast.LENGTH_SHORT).show()
            }
        }
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
        val rawLogs = QemuLogger.getLogs(this, cfg.id)
        val logs = if (rawLogs.isNotEmpty()) rawLogs else QemuManager.getLogs(cfg.id)
        val msg = if (logs.isEmpty()) "Журнал пуст. ВМ ещё не запускалась или не вывела сообщений." else logs.takeLast(200).joinToString("\n")

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
            .setNeutralButton("Очистить логи") { _, _ ->
                QemuLogger.clearLogs(this, cfg.id)
                Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Поделиться") { _, _ ->
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, msg)
                    putExtra(Intent.EXTRA_TITLE, "Логи ${cfg.name}")
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, "Экспорт логов"))
            }
            .show()
    }

    private fun shareVmConfig(cfg: VmConfig) {
        val json = JSONObject().apply {
            put("name", cfg.name)
            put("ram", cfg.ramMb)
            put("cpu", cfg.cpuCores)
            put("cpuModel", cfg.cpuModel)
            put("arch", cfg.architecture.name)
            put("machine", cfg.machineType.name)
            put("boot", cfg.bootDevice)
            put("vga", cfg.vgaDriver)
            put("net", cfg.networkMode)
            put("audio", cfg.enableAudio)
            put("kvm", cfg.enableKvm)
            put("extraArgs", cfg.extraArgs)
        }.toString(2)

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_TITLE, "Конфигурация ВМ ${cfg.name}")
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Поделиться конфигурацией «${cfg.name}»"))
    }

    private fun showCardPopupMenu(cfg: VmConfig, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "✏ " + getString(R.string.menu_rename))
        popup.menu.add(0, 2, 1, "⚙ " + getString(R.string.menu_settings))
        popup.menu.add(0, 3, 2, "📋 " + getString(R.string.menu_clone))
        popup.menu.add(0, 4, 3, "📸 " + getString(R.string.menu_snapshots))
        popup.menu.add(0, 5, 4, "📊 " + getString(R.string.menu_monitor))
        popup.menu.add(0, 6, 5, "📜 " + getString(R.string.menu_logs))
        popup.menu.add(0, 7, 6, "📤 " + getString(R.string.menu_share))
        popup.menu.add(0, 8, 7, "🗑 " + getString(R.string.menu_delete))

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showRenameDialog(cfg)
                2 -> showEditDialog(cfg)
                3 -> cloneVm(cfg)
                4 -> showSnapshotDialog(cfg)
                5 -> SystemMonitorDialog.show(this, lifecycleScope, cfg)
                6 -> showLogsDialog(cfg)
                7 -> shareVmConfig(cfg)
                8 -> confirmDeleteVm(cfg)
            }
            true
        }
        popup.show()
    }

    private fun showVmDialog(initial: VmConfig, onSave: (VmConfig) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_vm_form, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etName)
        val sliderRam = view.findViewById<Slider>(R.id.sliderRam)
        val tvRamValue = view.findViewById<TextView>(R.id.tvRamValue)
        val sliderCpu = view.findViewById<Slider>(R.id.sliderCpu)
        val tvCpuValue = view.findViewById<TextView>(R.id.tvCpuValue)
        val etCpuModel = view.findViewById<android.widget.AutoCompleteTextView>(R.id.etCpuModel)
        val sliderDiskSize = view.findViewById<Slider>(R.id.sliderDiskSize)
        val tvDiskSizeValue = view.findViewById<TextView>(R.id.tvDiskSizeValue)
        val etDisk = view.findViewById<TextInputEditText>(R.id.etDisk)
        val etIso = view.findViewById<TextInputEditText>(R.id.etIso)
        val etExtraArgs = view.findViewById<TextInputEditText>(R.id.etExtraArgs)

        val spinArch = view.findViewById<Spinner>(R.id.spinArch)
        val spinMach = view.findViewById<Spinner>(R.id.spinMachine)
        val spinBoot = view.findViewById<Spinner>(R.id.spinBootDevice)
        val spinVga = view.findViewById<Spinner>(R.id.spinVga)
        val spinNetAdapter = view.findViewById<Spinner>(R.id.spinNetworkAdapter)
        val spinInputDevice = view.findViewById<Spinner>(R.id.spinInputDevice)
        val spinMouseMode = view.findViewById<Spinner>(R.id.spinMouseMode)

        val switchAudio = view.findViewById<MaterialSwitch>(R.id.switchAudio)
        val switchKvm = view.findViewById<MaterialSwitch>(R.id.switchKvm)
        val switchMtcg = view.findViewById<MaterialSwitch>(R.id.switchMtcg)
        val spinPreset = view.findViewById<Spinner>(R.id.spinPreset)

        etName.setText(initial.name)
        etCpuModel.setText(initial.cpuModel, false)
        etDisk.setText(initial.diskPath)
        etIso.setText(initial.isoPath)
        etExtraArgs.setText(initial.extraArgs)

        // Setup RAM Slider
        val curRam = initial.ramMb.coerceIn(128, 8192)
        sliderRam.value = curRam.toFloat()
        tvRamValue.text = "$curRam MB"
        sliderRam.addOnChangeListener { _, value, _ ->
            tvRamValue.text = "${value.toInt()} MB"
        }

        // Setup CPU Slider
        val curCpu = initial.cpuCores.coerceIn(1, 8)
        sliderCpu.value = curCpu.toFloat()
        tvCpuValue.text = "$curCpu"
        sliderCpu.addOnChangeListener { _, value, _ ->
            tvCpuValue.text = "${value.toInt()}"
        }

        // Setup Disk Size Slider
        val curDisk = (if (initial.diskSizeGb > 0) initial.diskSizeGb else 10).coerceIn(2, 128)
        sliderDiskSize.value = curDisk.toFloat()
        tvDiskSizeValue.text = "$curDisk GB"
        sliderDiskSize.addOnChangeListener { _, value, _ ->
            tvDiskSizeValue.text = "${value.toInt()} GB"
        }

        switchAudio.isChecked = initial.enableAudio
        switchKvm.isChecked = initial.enableKvm
        switchMtcg?.isChecked = initial.enableMtcg

        val archValues = Architecture.values()
        spinArch.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, archValues.map { it.label })
        spinArch.setSelection(archValues.indexOf(initial.architecture).coerceAtLeast(0))
        
        spinArch.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedArch = archValues[position]
                val cpuOptions = when (selectedArch) {
                    Architecture.X86_64 -> listOf("max", "host", "qemu64", "core2duo", "Nehalem", "SandyBridge", "Haswell", "Broadwell", "Skylake-Client", "Skylake-Server", "Cascadelake-Server", "EPYC", "EPYC-Rome", "EPYC-Milan", "Opteron_G1", "phenom", "KnightsMill")
                    Architecture.I386 -> listOf("max", "host", "qemu32", "pentium", "pentium2", "pentium3", "coreduo", "486", "athlon")
                    Architecture.ARM64 -> listOf("max", "host", "cortex-a53", "cortex-a57", "cortex-a72", "cortex-a76", "neoverse-n1")
                    Architecture.ARM -> listOf("max", "cortex-a15", "cortex-a9", "cortex-a8", "cortex-a7", "arm1176")
                    Architecture.RISCV64, Architecture.RISCV32 -> listOf("max", "rv64", "rv32", "sifive-u54", "sifive-e51")
                    Architecture.POWERPC, Architecture.PPC64 -> listOf("max", "G4", "7400", "POWER8", "POWER9", "e500mc")
                    Architecture.M68K -> listOf("max", "m68040", "m68060", "any")
                }
                etCpuModel.setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, cpuOptions))
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val machValues = MachineType.values()
        spinMach.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, machValues.map { "${it.value} (${it.displayName})" })
        spinMach.setSelection(machValues.indexOf(initial.machineType).coerceAtLeast(0))

        val bootValues = listOf(getString(R.string.vm_boot_hdd), getString(R.string.vm_boot_cdrom))
        spinBoot.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bootValues)
        spinBoot.setSelection(if (initial.bootDevice == "cdrom") 1 else 0)

        val vgaValues = listOf("std (Стандартный)", "virtio (Ускоренный 2D/3D)", "cirrus (Для Windows 9x/XP)", "qxl (SPICE)", "vmware (VMware SVGA)", "ramfb (Только буфер)", "bochs-display", "none (Отключить)")
        val vgaKeys = listOf("std", "virtio", "cirrus", "qxl", "vmware", "ramfb", "bochs-display", "none")
        spinVga.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vgaValues)
        spinVga.setSelection(vgaKeys.indexOf(initial.vgaDriver).coerceAtLeast(0))

        val netAdapters = listOf("rtl8139", "virtio-net-pci", "e1000", "ne2k_pci", "none")
        val netAdapterLabels = listOf(
            "rtl8139 (Universal, WinXP/7)",
            "virtio-net-pci (High-Speed VirtIO)",
            "e1000 (Intel PRO/1000)",
            "ne2k_pci (Legacy 10Mbit)",
            "none (Отключить сеть / Disable Network)"
        )
        spinNetAdapter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, netAdapterLabels)
        val initialNetIndex = if (initial.networkMode == "none") 4 else netAdapters.indexOf(initial.networkAdapter).coerceIn(0, 3)
        spinNetAdapter.setSelection(initialNetIndex)

        val inputDevices = listOf("usb-tablet", "virtio-tablet-pci", "ps2")
        val inputDeviceLabels = listOf(
            "USB Tablet (Touchscreen absolute coordinates)",
            "VirtIO Tablet (Fast input)",
            "PS/2 Mouse (Relative cursor)"
        )
        spinInputDevice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, inputDeviceLabels)
        spinInputDevice.setSelection(inputDevices.indexOf(initial.inputDevice).coerceAtLeast(0))

        val mouseModes = listOf("absolute", "relative")
        val mouseModeLabels = listOf(
            getString(R.string.vnc_mode_absolute),
            getString(R.string.vnc_mode_relative)
        )
        spinMouseMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mouseModeLabels)
        spinMouseMode.setSelection(if (initial.mouseMode.equals("relative", ignoreCase = true)) 1 else 0)

        // Preset selector logic
        val presetList = QemuPresetLibrary.PRESETS
        val presetsStr = listOf("Пользовательский / Custom") + presetList.map { it.name }
        
        spinPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presetsStr)
        spinPreset.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) return // Custom
                
                val preset = presetList[position - 1]
                spinArch.setSelection(archValues.indexOf(preset.architecture))
                spinMach.setSelection(machValues.indexOf(preset.machineType))
                
                sliderRam.value = preset.ramMb.coerceIn(128, 8192).toFloat()
                tvRamValue.text = "${preset.ramMb} MB"
                
                etCpuModel.setText(preset.cpuModel, false)
                
                spinVga.setSelection(vgaKeys.indexOf(preset.vgaDriver).coerceAtLeast(0))
                
                val netIndex = if (preset.networkAdapter == "none") 4 else netAdapters.indexOf(preset.networkAdapter).coerceIn(0, 3)
                spinNetAdapter.setSelection(netIndex)
                
                switchAudio.isChecked = preset.enableAudio
                
                // Advanced Arguments
                etExtraArgs.setText(preset.extraArgs)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Create Disk Button
        val btnCreateDisk = view.findViewById<View>(R.id.btnCreateDisk)
        btnCreateDisk.setOnClickListener {
            val sizeGb = sliderDiskSize.value.toInt()
            val disksDir = File(filesDir, "disks").also { it.mkdirs() }
            val diskFile = File(disksDir, "disk_${System.currentTimeMillis()}.qcow2")

            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, "Создание qcow2 диска $sizeGb ГБ...", Toast.LENGTH_SHORT).show()
                val ok = QemuManager.createDiskImage(this@MainActivity, diskFile, sizeGb, "qcow2") { log ->
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

        // Select ISO Button
        val btnSelectIso = view.findViewById<View>(R.id.btnSelectIso)
        btnSelectIso.setOnClickListener {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appDir = getExternalFilesDir(null)
            val filesList = mutableListOf<File>()
            appDir?.listFiles { f -> f.name.endsWith(".iso", true) || f.name.endsWith(".img", true) }?.let { filesList.addAll(it) }
            downloadsDir?.listFiles { f -> f.name.endsWith(".iso", true) || f.name.endsWith(".img", true) }?.let { filesList.addAll(it) }

            if (filesList.isNotEmpty()) {
                val names = filesList.map { "${it.name} (${it.length() / (1024 * 1024)} MB)" }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.menu_import_iso)
                    .setItems(names) { _, which ->
                        etIso.setText(filesList[which].absolutePath)
                    }
                    .setNeutralButton("Файловый менеджер") { _, _ ->
                        importFileLauncher.launch(arrayOf("*/*"))
                    }
                    .setNegativeButton(R.string.lang_btn_cancel, null)
                    .show()
            } else {
                importFileLauncher.launch(arrayOf("*/*"))
            }
        }

        // Pick QEMU Flags Button
        val btnPickQemuFlags = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPickQemuFlags)
        btnPickQemuFlags?.setOnClickListener {
            QemuFlagPickerDialog.show(this) { selectedFlag ->
                val current = etExtraArgs.text?.toString().orEmpty().trim()
                if (current.isEmpty()) {
                    etExtraArgs.setText(selectedFlag)
                } else {
                    etExtraArgs.setText("$current $selectedFlag")
                }
            }
        }

        var isSaved = false
        val saveAction = {
            if (!isSaved) {
                isSaved = true
                val chosenArch = archValues[spinArch.selectedItemPosition]
                val chosenMach = machValues[spinMach.selectedItemPosition]
                val chosenBoot = if (spinBoot.selectedItemPosition == 1) "cdrom" else "disk"
                val chosenVga = vgaKeys[spinVga.selectedItemPosition]
                val chosenNetItem = netAdapters[spinNetAdapter.selectedItemPosition]
                val (chosenNetMode, chosenNetAdapter) = if (chosenNetItem == "none") {
                    Pair("none", "rtl8139")
                } else {
                    Pair("user", chosenNetItem)
                }
                val chosenInputDevice = inputDevices[spinInputDevice.selectedItemPosition]
                val chosenMouseMode = mouseModes[spinMouseMode.selectedItemPosition]

                onSave(
                    initial.copy(
                        name = etName.text.toString().trim().ifBlank { "ВМ" },
                        ramMb = sliderRam.value.toInt(),
                        cpuCores = sliderCpu.value.toInt(),
                        diskSizeGb = sliderDiskSize.value.toInt(),
                        cpuModel = etCpuModel.text.toString().trim().ifBlank { "max" },
                        architecture = chosenArch,
                        machineType = chosenMach,
                        bootDevice = chosenBoot,
                        vgaDriver = chosenVga,
                        networkMode = chosenNetMode,
                        networkAdapter = chosenNetAdapter,
                        inputDevice = chosenInputDevice,
                        mouseMode = chosenMouseMode,
                        enableAudio = switchAudio.isChecked,
                        enableUsbTablet = chosenInputDevice == "usb-tablet",
                        enableMtcg = switchMtcg?.isChecked ?: true,
                        enableKvm = switchKvm.isChecked,
                        diskPath = etDisk.text.toString().trim(),
                        isoPath = etIso.text.toString().trim(),
                        extraArgs = etExtraArgs.text.toString().trim()
                    )
                )
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (initial.id == 0L || initial.name == "Новая ВМ") getString(R.string.vm_title_new) else "${getString(R.string.menu_settings)} «${initial.name}»")
            .setView(view)
            .setPositiveButton("Закрыть") { _, _ -> saveAction() }
            .setOnDismissListener { saveAction() }
            .show()
    }

    private fun showSnapshotDialog(cfg: VmConfig) {
        SnapshotDialogHelper.show(this, lifecycleScope, cfg) {
            loadVms()
        }
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
    private val onShowSnapshots: (VmConfig) -> Unit,
    private val onLongClick: (VmConfig, View) -> Unit
) : RecyclerView.Adapter<VmAdapter.VH>() {

    var metricsMap: Map<Long, QemuMonitorService.Companion.VmMetrics> = emptyMap()

    fun updateMetrics(newMap: Map<Long, QemuMonitorService.Companion.VmMetrics>) {
        this.metricsMap = newMap
        // Only notify items where metrics or running status actually apply
        items.indices.forEach { index ->
            notifyItemChanged(index, PAYLOAD_METRICS)
        }
    }

    companion object {
        private const val PAYLOAD_METRICS = "payload_metrics"
    }

    fun updateData(newItems: List<VmConfig>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemVmCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemVmCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, pos: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_METRICS)) {
            val cfg = items[pos]
            val running = QemuManager.isRunning(cfg.id)
            val metric = metricsMap[cfg.id]
            holder.b.apply {
                if (running && metric != null && metric.isRunning) {
                    chartMetrics.visibility = View.VISIBLE
                    chartMetrics.updateMetrics(metric.cpuPercent, metric.ramUsedMb, metric.ramTotalMb, true)
                } else {
                    chartMetrics.visibility = View.GONE
                    chartMetrics.clearMetrics()
                }
            }
        } else {
            super.onBindViewHolder(holder, pos, payloads)
        }
    }

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

            // Disk storage usage & growth
            val diskFile = if (cfg.diskPath.isNotBlank()) File(cfg.diskPath) else null
            if (diskFile != null && diskFile.exists()) {
                layoutVmDiskStorage.visibility = View.VISIBLE
                val actualBytes = diskFile.length()
                val actualMb = actualBytes / (1024f * 1024f)
                val virtualGb = cfg.diskSizeGb.coerceAtLeast(1)
                val virtualBytes = virtualGb * 1024L * 1024L * 1024L
                val percent = if (virtualBytes > 0) ((actualBytes.toFloat() / virtualBytes.toFloat()) * 100f).toInt().coerceIn(1, 100) else 0

                val format = if (cfg.diskPath.endsWith(".qcow2", true)) "QCOW2" else cfg.diskFormat.uppercase(java.util.Locale.US)
                tvVmDiskStorage.text = if (actualMb >= 1024f) {
                    String.format(java.util.Locale.US, "💽 %s: %.2f GB / %d GB (%d%%)", format, actualMb / 1024f, virtualGb, percent)
                } else {
                    String.format(java.util.Locale.US, "💽 %s: %.1f MB / %d GB (%d%%)", format, actualMb, virtualGb, percent)
                }
                pbVmDiskGrowth.progress = percent
                pbVmDiskGrowth.progressTintList = android.content.res.ColorStateList.valueOf(
                    if (percent > 85) android.graphics.Color.parseColor("#FF5252") else android.graphics.Color.parseColor("#80D8FF")
                )
            } else {
                layoutVmDiskStorage.visibility = View.GONE
            }

            // Real-time metrics sparkline chart
            val metric = metricsMap[cfg.id]
            if (running && metric != null && metric.isRunning) {
                chartMetrics.visibility = View.VISIBLE
                chartMetrics.updateMetrics(metric.cpuPercent, metric.ramUsedMb, metric.ramTotalMb, true)
            } else {
                chartMetrics.visibility = View.GONE
                chartMetrics.clearMetrics()
            }

            btnStart.isEnabled = !running
            btnStop.isEnabled = running
            btnStart.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onStart(cfg) 
            }
            btnStop.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onStop(cfg) 
            }
            btnRename.setOnClickListener { onRename(cfg) }
            btnEdit.setOnClickListener { onEdit(cfg) }
            btnDelete.setOnClickListener { onDelete(cfg) }
            btnOptions.setOnClickListener { onOptions(cfg, it) }
            btnLogs.setOnClickListener { onShowLogs(cfg) }
            
            val btnSnapshots = root.findViewById<View>(R.id.btnSnapshots)
            btnSnapshots?.setOnClickListener { onShowSnapshots(cfg) }
            
            root.setOnLongClickListener {
                onLongClick(cfg, it)
                true
            }
        }
    }
}
