package com.virtualpcvm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure command generation. No process creation and no shell parsing. */
public final class QemuCommandBuilder {
    public List<String> build(VMConfig config, QemuRuntime runtime) {
        config.validate();
        List<String> command = new ArrayList<>();
        command.add(runtime.getBinary().getAbsolutePath());
        command.add("-L"); command.add(runtime.getFirmwareDir().getAbsolutePath());
        command.add("-machine"); command.add(config.machine);
        command.add("-cpu"); command.add(config.cpu);
        command.add("-smp"); command.add(String.valueOf(config.cpuCount));
        command.add("-m"); command.add(config.ramMb + "M");
        command.add("-accel"); command.add("tcg,thread=multi");

        if (!blank(config.hdd)) {
            command.add("-drive"); command.add("file=" + config.hdd + ",if=ide,format=auto");
        }
        if (!blank(config.iso)) {
            command.add("-drive"); command.add("file=" + config.iso + ",media=cdrom,readonly=on");
        }
        if (!blank(config.cdrom)) {
            command.add("-drive"); command.add("file=" + config.cdrom + ",media=cdrom,readonly=on");
        }
        if (!blank(config.bootOrder)) {
            command.add("-boot"); command.add("order=" + config.bootOrder);
        }
        if (!blank(config.video)) {
            command.add("-vga"); command.add(config.video);
        }

        if ("vnc".equalsIgnoreCase(config.display)) {
            command.add("-display"); command.add("none");
            command.add("-vnc"); command.add("127.0.0.1:" + Math.max(0, config.vncPort - 5900));
        } else if ("none".equalsIgnoreCase(config.display) || "headless".equalsIgnoreCase(config.display)) {
            command.add("-display"); command.add("none");
        }

        if (!blank(config.network) && !"off".equalsIgnoreCase(config.network)) {
            command.add("-nic");
            command.add("user".equalsIgnoreCase(config.network) ? "user,model=virtio" : config.network);
        }
        appendExtraArguments(command, config.extraArguments);
        return Collections.unmodifiableList(command);
    }

    private void appendExtraArguments(List<String> command, List<String> extras) {
        if (extras == null) return;
        for (int i = 0; i < extras.size(); i++) {
            String arg = extras.get(i);
            if (blank(arg)) continue;
            if ("-L".equals(arg)) {
                if (i + 1 < extras.size()) i++;
                continue;
            }
            command.add(arg);
        }
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
