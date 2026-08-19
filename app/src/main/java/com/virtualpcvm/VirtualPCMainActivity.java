package com.virtualpcvm;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** New VirtualPC-VM UI. It talks only to repository/controller layers. */
public final class VirtualPCMainActivity extends AppCompatActivity {
    private VMRepository repository;
    private QemuRuntime runtime;
    private RecyclerView list;
    private LinearLayout empty;
    private final List<VMConfig> configs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_virtual_pc_main);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        repository = new VMRepository(this);
        runtime = new QemuRuntime(this);
        list = findViewById(R.id.vm_list);
        empty = findViewById(R.id.empty_state);
        list.setLayoutManager(new LinearLayoutManager(this));
        refresh();
    }

    private void refresh() {
        configs.clear();
        configs.addAll(repository.listVMs());
        list.setAdapter(new VMAdapter(configs, this::startVm, this::editVm, this::deleteVm));
        empty.setVisibility(configs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void startVm(VMConfig config) {
        if (runtime.isRunning()) {
            Toast.makeText(this, "VM is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            runtime.start(config, new QemuProcess.Listener() {
                @Override public void onStdout(String line) { }
                @Override public void onStderr(String line) { }
                @Override public void onExit(int code) { runOnUiThread(() -> refresh()); }
            });
            Toast.makeText(this, "VM started", Toast.LENGTH_SHORT).show();
            refresh();
        } catch (Throwable e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void editVm(VMConfig config) { showVmDialog(config, true); }

    private void deleteVm(VMConfig config) {
        repository.deleteVM(config.id);
        refresh();
    }

    private void showVmDialog(VMConfig initial, boolean editing) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        form.setPadding(pad, pad, pad, pad);
        scroll.addView(form);

        TextInputEditText name = field(form, "Name", initial.name);
        TextInputEditText os = field(form, "ОС", initial.osType);
        TextInputEditText machine = field(form, "Machine", initial.machine);
        TextInputEditText cpu = field(form, "CPU", initial.cpu);
        TextInputEditText cores = field(form, "CPU count", String.valueOf(initial.cpuCount));
        TextInputEditText ram = field(form, "RAM MB", String.valueOf(initial.ramMb));
        TextInputEditText hdd = field(form, "HDD filesystem path", initial.hdd);
        TextInputEditText iso = field(form, "ISO filesystem path", initial.iso);
        TextInputEditText cdrom = field(form, "CD/DVD filesystem path", initial.cdrom);
        TextInputEditText boot = field(form, "Boot order", initial.bootOrder);
        TextInputEditText video = field(form, "Video", initial.video);
        TextInputEditText vnc = field(form, "VNC port", String.valueOf(initial.vncPort));
        TextInputEditText extra = field(form, "Extra QEMU args (space separated)", join(initial.extraArguments));

        Spinner display = new Spinner(this);
        display.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"VNC", "Headless", "Default"}));
        display.setSelection("headless".equalsIgnoreCase(initial.display) ? 1 : "default".equalsIgnoreCase(initial.display) ? 2 : 0);
        form.addView(display, new LinearLayout.LayoutParams(-1, dp(52)));

        Spinner network = new Spinner(this);
        network.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"user", "off"}));
        network.setSelection("off".equalsIgnoreCase(initial.network) ? 1 : 0);
        form.addView(network, new LinearLayout.LayoutParams(-1, dp(52)));

        String title = editing ? "Редактировать VM" : "Создать VM";
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null);
        AlertDialog shown = dialog.create();
        shown.setOnShowListener(v -> shown.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(x -> {
            try {
                VMConfig config = initial.copy();
                config.name = value(name, "Новая VM");
                config.osType = value(os, "Other");
                config.machine = value(machine, "pc");
                config.cpu = value(cpu, "qemu64");
                config.cpuCount = intValue(cores, 1);
                config.ramMb = intValue(ram, 512);
                config.hdd = value(hdd, "");
                config.iso = value(iso, "");
                config.cdrom = value(cdrom, "");
                config.bootOrder = value(boot, "c");
                config.video = value(video, "std");
                config.vncPort = intValue(vnc, 5901);
                config.display = display.getSelectedItemPosition() == 1 ? "headless" : display.getSelectedItemPosition() == 2 ? "default" : "vnc";
                config.network = network.getSelectedItemPosition() == 1 ? "off" : "user";
                config.extraArguments = splitExtra(value(extra, ""));
                config.validate();
                if (editing) repository.updateVM(config); else repository.createVM(config);
                shown.dismiss();
                refresh();
            } catch (Throwable error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));
        shown.show();
    }

    private TextInputEditText field(LinearLayout parent, String hint, String value) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(hint);
        TextInputEditText input = new TextInputEditText(this);
        input.setText(value == null ? "" : value);
        layout.addView(input);
        parent.addView(layout, new LinearLayout.LayoutParams(-1, dp(64)));
        return input;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String value(TextInputEditText field, String fallback) {
        String value = field.getText() == null ? "" : field.getText().toString().trim();
        return value.isEmpty() ? fallback : value;
    }

    private static int intValue(TextInputEditText field, int fallback) {
        try { return Integer.parseInt(value(field, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static String join(List<String> args) {
        if (args == null || args.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (String arg : args) {
            if (result.length() > 0) result.append(' ');
            result.append(arg);
        }
        return result.toString();
    }

    private static List<String> splitExtra(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;
        for (String part : text.trim().split("\\s+")) result.add(part);
        return result;
    }
}
