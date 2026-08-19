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
    private final Map<String, VMController> controllers = new HashMap<>();
    private VmAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedTheme();
        super.onCreate(savedInstanceState);
        setTitle("VirtualPC-VM");

        runtime = new QemuRuntime(this);
        repository = new VMRepository(new VMStorage(getFilesDir()));
        buildUi();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("VirtualPC-VM");
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_manage);
        toolbar.setNavigationOnClickListener(v -> startActivity(new Intent(this, VirtualPcSettingsActivity.class)));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));

        TextView runtimeBanner = new TextView(this);
        runtimeBanner.setPadding(dp(20), dp(8), dp(20), dp(8));
        runtimeBanner.setText("Embedded QEMU 11.0.3 • x86_64 guest");
        root.addView(runtimeBanner);

        FrameContainer content = new FrameContainer(this);
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setPadding(dp(12), dp(12), dp(12), dp(90));
        list.setClipToPadding(false);
        content.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));

        empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        TextView emptyText = new TextView(this);
        emptyText.setText("Виртуальных машин пока нет\nСоздайте первую VM");
        emptyText.setTextSize(18f);
        emptyText.setGravity(Gravity.CENTER);
        empty.addView(emptyText);
        content.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));

        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        ExtendedFloatingActionButton add = new ExtendedFloatingActionButton(this);
        add.setText("Создать виртуальную машину");
        add.setIconResource(android.R.drawable.ic_input_add);
        add.setOnClickListener(v -> showVmDialog(new VMConfig(), false));
        LinearLayout addBar = new LinearLayout(this);
        addBar.setGravity(Gravity.END);
        addBar.setPadding(dp(16), dp(8), dp(16), dp(16));
        addBar.addView(add);
        root.addView(addBar);

        setContentView(root);

        adapter = new VmAdapter();
        list.setAdapter(adapter);
    }

    private void refresh() {
        configs.clear();
        configs.addAll(repository.listVMs());
        controllers.keySet().removeIf(id -> configs.stream().noneMatch(config -> config.id.equals(id)));
        for (VMConfig config : configs) controllers.computeIfAbsent(config.id, id -> new VMController(runtime));
        if (adapter != null) adapter.notifyDataSetChanged();
        if (empty != null) empty.setVisibility(configs.isEmpty() ? View.VISIBLE : View.GONE);
        if (list != null) list.setVisibility(configs.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showVmDialog(VMConfig initial, boolean editing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), 0, dp(8), 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        TextInputEditText name = field(form, "Название", initial.name);
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
        TextInputEditText edit = new TextInputEditText(this);
        edit.setSingleLine(false);
        edit.setText(value);
        layout.addView(edit);
        layout.setPadding(0, dp(4), 0, dp(4));
        parent.addView(layout, new LinearLayout.LayoutParams(-1, -2));
        return edit;
    }

    private static String value(TextInputEditText edit, String fallback) {
        String text = edit.getText() == null ? "" : edit.getText().toString().trim();
        return text.isEmpty() ? fallback : text;
    }

    private static int intValue(TextInputEditText edit, int fallback) {
        try { return Integer.parseInt(value(edit, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private static List<String> splitExtra(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;
        for (String token : text.trim().split("\\s+")) if (!token.isBlank()) result.add(token);
        return result;
    }

    private static String join(List<String> args) {
        if (args == null) return "";
        return String.join(" ", args);
    }

    private void launch(VMConfig config) {
        VMController controller = controllers.computeIfAbsent(config.id, id -> new VMController(runtime));
        controller.start(config, new VMController.Listener() {
            @Override public void onStateChanged(VMController.State state) {
                runOnUiThread(() -> adapter.notifyDataSetChanged());
            }
            @Override public void onLog(String line) {
                if (VirtualPcSettings.isDebug(VirtualPCMainActivity.this)) android.util.Log.d("VirtualPC-VM", line);
            }
            @Override public void onError(Throwable error) {
                runOnUiThread(() -> Toast.makeText(VirtualPCMainActivity.this, "QEMU: " + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private final class VmAdapter extends RecyclerView.Adapter<VmAdapter.Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(VirtualPCMainActivity.this);
            card.setRadius(dp(20));
            card.setUseCompatPadding(true);
            LinearLayout box = new LinearLayout(VirtualPCMainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(16), dp(20), dp(16));
            card.addView(box);
            return new Holder(card, box);
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            VMConfig cfg = configs.get(position);
            VMController controller = controllers.computeIfAbsent(cfg.id, id -> new VMController(runtime));
            h.box.removeAllViews();

            TextView title = new TextView(VirtualPCMainActivity.this);
            title.setText(cfg.name);
            title.setTextSize(20f);
            h.box.addView(title);

            TextView info = new TextView(VirtualPCMainActivity.this);
            info.setText(cfg.osType + " • " + cfg.architecture + " • " + cfg.ramMb + " MB • " + cfg.cpuCount + " CPU • " + cfg.machine + "\n" + cfg.hdd);
            info.setPadding(0, dp(8), 0, dp(12));
            h.box.addView(info);

            TextView state = new TextView(VirtualPCMainActivity.this);
            state.setText("Состояние: " + controller.getState());
            h.box.addView(state);

            LinearLayout actions = new LinearLayout(VirtualPCMainActivity.this);
            actions.setGravity(Gravity.END);
            MaterialButton start = new MaterialButton(VirtualPCMainActivity.this);
            start.setText("Запустить");
            start.setOnClickListener(v -> launch(cfg));
            start.setEnabled(!controller.isRunning());
            MaterialButton stop = new MaterialButton(VirtualPCMainActivity.this);
            stop.setText("Остановить");
            stop.setOnClickListener(v -> requestStop(cfg));
            stop.setEnabled(controller.isRunning());
            MaterialButton settings = new MaterialButton(VirtualPCMainActivity.this);
            settings.setText("Настройки");
            settings.setOnClickListener(v -> showVmDialog(cfg, true));
            actions.addView(start);
            actions.addView(stop);
            actions.addView(settings);
            h.box.addView(actions);

            h.card.setOnLongClickListener(v -> {
                showDeleteDialog(cfg);
                return true;
            });
        }

        @Override public int getItemCount() { return configs.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final LinearLayout box;
            Holder(MaterialCardView card, LinearLayout box) { super(card); this.card = card; this.box = box; }
        }
    }

    private void requestStop(VMConfig config) {
        VMController controller = controllers.get(config.id);
        if (controller == null || !controller.isRunning()) return;
        if (!VirtualPcSettings.confirmStop(this)) {
            controller.stop(null);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Остановить VM?")
                .setMessage(config.name)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Остановить", (d, w) -> controller.stop(null))
                .show();
    }

    private void showDeleteDialog(VMConfig config) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Удалить VM?")
                .setMessage(config.name + "\nКонфигурация, каталоги VM и связанные данные будут удалены.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, w) -> {
                    try {
                        VMController controller = controllers.get(config.id);
                        if (controller != null) controller.forceStop(null);
                        repository.deleteVM(config.id);
                        controllers.remove(config.id);
                        refresh();
                    } catch (Exception error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void applySavedTheme() {
        String theme = VirtualPcSettings.getTheme(this);
        if ("light".equals(theme)) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        else if ("dark".equals(theme)) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        else AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class FrameContainer extends android.widget.FrameLayout {
        FrameContainer(android.content.Context context) { super(context); }
    }
}
