package com.virtualpcvm

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virtualpcvm.databinding.ActivityMainBinding
import com.virtualpcvm.databinding.ItemVmCardBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val vms = mutableListOf<VmConfig>()
    
    private val prefs by lazy { getSharedPreferences("virtualpcvm_prefs", MODE_PRIVATE) }

    private lateinit var adapter: VmAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        
        loadVms()
        checkStoragePermission()

        adapter = VmAdapter(
            items   = vms,
            onStart = { cfg -> launchVm(cfg) },
            onEdit  = { cfg -> showEditDialog(cfg) },
            onStop  = { cfg ->
                QemuManager.stop(cfg.id)
                Toast.makeText(this, "ВМ «${cfg.name}» остановлена", Toast.LENGTH_SHORT).show()
                adapter.notifyDataSetChanged()
            }
        )
        binding.recyclerVms.layoutManager = LinearLayoutManager(this)
        binding.recyclerVms.adapter = adapter

        binding.fabAddVm.setOnClickListener { showCreateDialog() }

        refreshQemuBanner()
        checkAndPromptInstall()
    }
    
    private fun saveVms() {
        try {
            val jsonArray = org.json.JSONArray()
            for (vm in vms) {
                val obj = org.json.JSONObject()
                obj.put("id", vm.id)
                obj.put("name", vm.name)
                obj.put("arch", vm.architecture.name)
                obj.put("machine", vm.machineType.name)
                obj.put("ram", vm.ramMb)
                obj.put("cpu", vm.cpuCores)
                obj.put("disk", vm.diskPath)
                obj.put("iso", vm.isoPath)
                obj.put("audio", vm.enableAudio)
                jsonArray.put(obj)
            }
            prefs.edit().putString("vms_list", jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun loadVms() {
        vms.clear()
        try {
            val json = prefs.getString("vms_list", null)
            if (json != null) {
                val array = org.json.JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    vms.add(VmConfig(
                        id = obj.optLong("id", System.currentTimeMillis() + i),
                        name = obj.optString("name", "VM"),
                        architecture = Architecture.valueOf(obj.optString("arch", Architecture.X86_64.name)),
                        machineType = MachineType.valueOf(obj.optString("machine", MachineType.PC.name)),
                        ramMb = obj.optInt("ram", 1024),
                        cpuCores = obj.optInt("cpu", 2),
                        diskPath = obj.optString("disk", ""),
                        isoPath = obj.optString("iso", ""),
                        enableAudio = obj.optBoolean("audio", true)
                    ))
                }
            } else {
                // First launch, optionally add an empty state or let it be empty (templates removed)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshQemuBanner()
    }

    private fun checkAndPromptInstall() {
        if (!QemuInstaller.anyInstalled(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Установка QEMU")
                .setMessage("Похоже, QEMU еще не установлен. Хотите установить его сейчас?")
                .setPositiveButton("Установить") { _, _ ->
                    startActivity(Intent(this, InstallActivity::class.java))
                }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    private fun checkStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.parse("package:" + packageName)
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        }
    }

    /* ── QEMU status banner ── */
    private fun refreshQemuBanner() {
        val installed = QemuInstaller.anyInstalled(this)
        binding.bannerQemu.apply {
            android.view.View.VISIBLE.also { visibility = it }
            if (installed) {
                text = "✓ QEMU установлен — нажмите «Старт ВМ» для запуска"
                setBackgroundColor(0xFF1B5E20.toInt())
            } else {
                text = "⚠ QEMU не найден — нажмите здесь для установки"
                setBackgroundColor(0xFFE65100.toInt())
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, InstallActivity::class.java))
                }
            }
            setTextColor(0xFFFFFFFF.toInt())
        }
    }

    /* ── launch VM → wait for VNC → open VNCActivity ── */
    private fun launchVm(cfg: VmConfig) {
        // pre-flight check
        val bin = QemuManager.findBinary(this, cfg.architecture)
        if (bin == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("QEMU не установлен")
                .setMessage("Бинарник для архитектуры «${cfg.architecture.label}» не найден.\n\nОткрыть экран установки?")
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
            .setMessage("Ожидание QEMU...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                // 1. spawn QEMU process
                QemuManager.start(this@MainActivity, cfg) { line ->
                    logLines.appendLine(line)
                    runOnUiThread { progressDialog.setMessage(line.take(90)) }
                }

                // 2. wait up to 8 s for VNC port to open
                progressDialog.setMessage("Ожидание VNC на порту ${cfg.vncPort}...")
                val vncReady = QemuManager.waitForVnc(vmId = cfg.id, port = cfg.vncPort, timeoutMs = 8_000)
                progressDialog.dismiss()

                if (!vncReady) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("VNC недоступен")
                        .setMessage("QEMU запущен, но VNC не ответил.\n\nЛог:\n${logLines.takeLast(1000)}")
                        .setPositiveButton("OK", null)
                        .setNegativeButton("Всё равно открыть VNC") { _, _ -> openVnc(cfg) }
                        .show()
                    return@launch
                }

                // 3. open VNC screen
                openVnc(cfg)

            } catch (e: Exception) {
                progressDialog.dismiss()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Ошибка запуска")
                    .setMessage(e.message ?: "Неизвестная ошибка")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun openVnc(cfg: VmConfig) {
        startActivity(Intent(this, VNCActivity::class.java).apply {
            putExtra(VNCActivity.EXTRA_HOST,    "127.0.0.1")
            putExtra(VNCActivity.EXTRA_PORT,    cfg.vncPort)
            putExtra(VNCActivity.EXTRA_VM_NAME, cfg.name)
            putExtra(VNCActivity.EXTRA_VM_ID,   cfg.id)
        })
    }

    /* ── VM form dialogs ── */
    private fun showCreateDialog() {
        showVmDialog(VmConfig()) { newCfg ->
            vms.add(newCfg)
            adapter.notifyItemInserted(vms.lastIndex)
            saveVms()
        }
    }

    private fun showEditDialog(cfg: VmConfig) {
        val idx = vms.indexOf(cfg)
        showVmDialog(cfg) { updated ->
            if (idx >= 0) { 
                vms[idx] = updated
                adapter.notifyItemChanged(idx)
                saveVms()
            }
        }
    }
    
    private fun deleteVm(cfg: VmConfig) {
        val idx = vms.indexOf(cfg)
        if (idx >= 0) {
            vms.removeAt(idx)
            adapter.notifyItemRemoved(idx)
            saveVms()
        }
    }

    private fun showVmDialog(initial: VmConfig, onSave: (VmConfig) -> Unit) {
        val view     = layoutInflater.inflate(R.layout.dialog_vm_form, null)
        val etName   = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etName)
        val etRam    = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRam)
        val etCpu    = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etCpu)
        val etDisk   = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDisk)
        val etIso    = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etIso)
        val spinArch = view.findViewById<android.widget.Spinner>(R.id.spinArch)
        val spinMach = view.findViewById<android.widget.Spinner>(R.id.spinMachine)

        etName.setText(initial.name)
        etRam.setText(initial.ramMb.toString())
        etCpu.setText(initial.cpuCores.toString())
        etDisk.setText(initial.diskPath)
        etIso.setText(initial.isoPath)

        val archValues = Architecture.values()
        spinArch.adapter = android.widget.ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, archValues.map { it.label })
        spinArch.setSelection(archValues.indexOf(initial.architecture).coerceAtLeast(0))

        val machValues = MachineType.values()
        spinMach.adapter = android.widget.ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, machValues.map { it.value })
        spinMach.setSelection(machValues.indexOf(initial.machineType).coerceAtLeast(0))
        
        val btnCreateDisk = view.findViewById<android.widget.Button>(R.id.btnCreateDisk)
        btnCreateDisk.setOnClickListener {
            val layout = android.widget.LinearLayout(this)
            layout.orientation = android.widget.LinearLayout.VERTICAL
            layout.setPadding(40, 20, 40, 0)
            
            val input = android.widget.EditText(this)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            input.hint = "Размер в ГБ (например, 10)"
            
            val spinFormat = android.widget.Spinner(this)
            val formats = listOf("qcow2", "raw")
            spinFormat.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formats)
            
            layout.addView(input)
            layout.addView(spinFormat)
            
            MaterialAlertDialogBuilder(this)
                .setTitle("Создать виртуальный диск")
                .setView(layout)
                .setPositiveButton("Создать") { _, _ ->
                    val sizeGb = input.text.toString().toIntOrNull() ?: 10
                    val format = formats[spinFormat.selectedItemPosition]
                    val disksDir = java.io.File(filesDir, "disks").also { it.mkdirs() }
                    val diskFile = java.io.File(disksDir, "disk_${System.currentTimeMillis()}.$format")
                    
                    val pd = android.app.ProgressDialog(this).apply {
                        setMessage("Создание диска $sizeGb ГБ ($format)...")
                        setCancelable(false)
                        show()
                    }
                    
                    lifecycleScope.launch {
                        val success = QemuManager.createDiskImage(this@MainActivity, diskFile, sizeGb, format) { log ->
                            android.util.Log.d("QEMU_IMG", log)
                        }
                        pd.dismiss()
                        if (success) {
                            etDisk.setText(diskFile.absolutePath)
                        } else {
                            android.widget.Toast.makeText(this@MainActivity, "Ошибка создания диска", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        
        val btnSelectIso = view.findViewById<android.widget.Button>(R.id.btnSelectIso)
        btnSelectIso.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Подсказка")
                .setMessage("Из-за ограничений Android QEMU не может читать ISO через системный выбор файлов (content://).\n\nПожалуйста, введите полный путь вручную, например:\n/storage/emulated/0/Download/image.iso\n\nИли скопируйте ISO в папку приложения через файловый менеджер.")
                .setPositiveButton("Понятно", null)
                .show()
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (initial.id == 0L) "Новая ВМ" else "Редактировать «${initial.name}»")
            .setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                onSave(initial.copy(
                    name         = etName.text.toString().ifBlank { "ВМ" },
                    ramMb        = etRam.text.toString().toIntOrNull() ?: 1024,
                    cpuCores     = etCpu.text.toString().toIntOrNull() ?: 2,
                    diskPath     = etDisk.text.toString(),
                    isoPath      = etIso.text.toString(),
                    architecture = archValues[spinArch.selectedItemPosition],
                    machineType  = machValues[spinMach.selectedItemPosition],
                ))
            }
            .setNegativeButton("Отмена", null)
            
        if (initial.id != 0L) {
            builder.setNeutralButton("Удалить") { _, _ ->
                deleteVm(initial)
            }
        }
        
        builder.show()
    }
}

/* ── RecyclerView Adapter ── */
class VmAdapter(
    private val items: List<VmConfig>,
    private val onStart: (VmConfig) -> Unit,
    private val onEdit:  (VmConfig) -> Unit,
    private val onStop:  (VmConfig) -> Unit,
) : RecyclerView.Adapter<VmAdapter.VH>() {

    inner class VH(val b: ItemVmCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemVmCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val cfg     = items[pos]
        val running = QemuManager.isRunning(cfg.id)
        holder.b.apply {
            tvVmName.text = cfg.name
            tvVmInfo.text = buildString {
                append("${cfg.architecture.label} · ${cfg.ramMb} MB · ${cfg.cpuCores} ядер")
                if (cfg.enableAudio) append(" · 🔊")
            }
            tvVmArch.text   = cfg.machineType.value
            chipStatus.text = if (running) "Запущена" else "Остановлена"
            chipStatus.setChipBackgroundColorResource(
                if (running) R.color.chip_running else R.color.chip_stopped)
            btnStart.isEnabled = !running
            btnStop.isEnabled  = running
            btnStart.setOnClickListener { onStart(cfg) }
            btnStop.setOnClickListener  { onStop(cfg)  }
            root.setOnLongClickListener { onEdit(cfg); true }
        }
    }
}
