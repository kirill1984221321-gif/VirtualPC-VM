package com.virtualpcvm;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.button.MaterialButton;

public final class VirtualPcSettingsActivity extends AppCompatActivity {
    private RadioButton system;
    private RadioButton light;
    private RadioButton dark;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedTheme();
        super.onCreate(savedInstanceState);
        setTitle("VirtualPC-VM — Настройки");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("Интерфейс");
        title.setTextSize(22f);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        RadioGroup themes = new RadioGroup(this);
        themes.setOrientation(RadioGroup.VERTICAL);
        system = radio("Системная", "system");
        light = radio("Светлая", "light");
        dark = radio("Тёмная", "dark");
        themes.addView(system);
        themes.addView(light);
        themes.addView(dark);
        String selected = VirtualPcSettings.getTheme(this);
        if ("light".equals(selected)) light.setChecked(true);
        else if ("dark".equals(selected)) dark.setChecked(true);
        else system.setChecked(true);

        themes.setOnCheckedChangeListener((group, checkedId) -> {
            String value = checkedId == light.getId() ? "light" : checkedId == dark.getId() ? "dark" : "system";
            VirtualPcSettings.setTheme(this, value);
            applyTheme(value);
        });
        root.addView(themes);

        TextView qemu = new TextView(this);
        qemu.setText("\nQEMU");
        qemu.setTextSize(22f);
        root.addView(qemu);

        TextView status = new TextView(this);
        status.setText(qemuStatus());
        status.setPadding(0, 12, 0, 12);
        root.addView(status);

        MaterialButton prepare = new MaterialButton(this);
        prepare.setText("Подготовить runtime заново");
        prepare.setOnClickListener(v -> {
            status.setText("Подготовка runtime...");
            new Thread(() -> {
                String result;
                try {
                    QemuRuntime runtime = new QemuRuntime(this);
                    runtime.prepare(true);
                    result = "Готово. " + runtime.getVersion() + "\nБиблиотек: " + runtime.getLibraryCount();
                } catch (Throwable error) {
                    result = "Ошибка: " + error.getMessage();
                }
                String finalResult = result;
                runOnUiThread(() -> status.setText(finalResult));
            }, "qemu-reprepare").start();
        });
        root.addView(prepare);

        MaterialButton diagnostics = new MaterialButton(this);
        diagnostics.setText("Диагностика QEMU");
        diagnostics.setOnClickListener(v -> {
            status.setText("Запуск self-test...");
            new Thread(() -> {
                String report = QemuSelfTest.run(new QemuRuntime(this));
                runOnUiThread(() -> status.setText(report));
            }, "qemu-self-test").start();
        });
        root.addView(diagnostics);

        TextView defaults = new TextView(this);
        defaults.setText("\nVM defaults\nRAM: 512 MB\nCPU: 1\nDisplay: VNC\nNetwork: user");
        defaults.setTextSize(16f);
        root.addView(defaults);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private RadioButton radio(String text, String value) {
        RadioButton button = new RadioButton(this);
        button.setText(text);
        button.setTextSize(17f);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setId(ViewId.next());
        return button;
    }

    private String qemuStatus() {
        QemuRuntime runtime = new QemuRuntime(this);
        return "Runtime: " + (runtime.isValid() ? "готов" : "не подготовлен") +
                "\nКаталог: " + runtime.getRuntimeDir().getAbsolutePath() +
                "\nБиблиотек: " + runtime.getLibraryCount() +
                "\nQEMU: 11.0.3";
    }

    private void applySavedTheme() {
        applyTheme(VirtualPcSettings.getTheme(this));
    }

    private void applyTheme(String value) {
        if ("light".equals(value)) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        else if ("dark".equals(value)) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        else AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    private static final class ViewId {
        private static int id = 1000;
        static synchronized int next() { return id++; }
    }
}
