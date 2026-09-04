package com.virtualpcvm

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.virtualpcvm.databinding.ActivityInstallBinding
import kotlinx.coroutines.launch

/**
 * Full-screen QEMU installation activity.
 * Downloads QEMU .deb packages from the Termux APT repository,
 * extracts the binaries, and makes them executable.
 */
class InstallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInstallBinding
    private var installing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Установка QEMU"

        binding.btnInstall.setOnClickListener { startInstall() }
        binding.btnClose.setOnClickListener   { finish() }

        checkStatus()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    /* ── check current status ── */
    private fun checkStatus() {
        val installed = QemuInstaller.anyInstalled(this)
        if (installed) {
            binding.tvStatus.text = "✓ QEMU уже установлен"
            binding.tvStatus.setTextColor(0xFF1B5E20.toInt())
            binding.btnInstall.text = "Переустановить"
        } else {
            binding.tvStatus.text = "QEMU не найден — нажмите «Установить»"
            binding.tvStatus.setTextColor(0xFFE65100.toInt())
        }
    }

    /* ── start installation ── */
    private fun startInstall() {
        if (installing) return
        installing = true

        binding.btnInstall.isEnabled = false
        binding.btnClose.isEnabled   = false
        binding.logView.text = ""
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStep.visibility = View.VISIBLE

        lifecycleScope.launch {
            QemuInstaller.install(this@InstallActivity) { progress ->
                runOnUiThread {
                    binding.progressBar.progress = progress.percent
                    binding.tvStep.text = progress.step

                    if (progress.log.isNotBlank()) {
                        val current = binding.logView.text.toString()
                        val newLine = progress.log.trimEnd()
                        binding.logView.text = if (current.isBlank()) newLine
                                               else "$current\n$newLine"
                        // scroll to bottom
                        binding.scrollLog.post {
                            binding.scrollLog.fullScroll(View.FOCUS_DOWN)
                        }
                    }

                    when {
                        progress.isDone -> {
                            installing = false
                            binding.btnInstall.isEnabled = true
                            binding.btnClose.isEnabled   = true
                            binding.tvStatus.text = "✓ Установка завершена!"
                            binding.tvStatus.setTextColor(0xFF1B5E20.toInt())
                            Toast.makeText(this@InstallActivity,
                                "QEMU успешно установлен", Toast.LENGTH_LONG).show()
                        }
                        progress.error != null -> {
                            installing = false
                            binding.btnInstall.isEnabled = true
                            binding.btnClose.isEnabled   = true
                            binding.tvStatus.text = "✗ Ошибка: ${progress.error}"
                            binding.tvStatus.setTextColor(0xFFB71C1C.toInt())
                        }
                    }
                }
            }
        }
    }
}
