package com.virtualpcvm;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure command generation. No process creation and no shell parsing. */
public final class QemuCommandBuilder {
    public List<String> build(VMConfig config, QemuRuntime runtime) {
        config.validate();
        List<String> command = new ArrayList<>();
        command.add(runtime.getBinary().getAbsolutePath());
        command.add("-L");
        command.add(runtime.getFirmwareDir().getAbsolutePath());
        command.add("-machine");
        command.add(config.machine);
        command.add("-cpu");
        command.add(config.cpu);
        command.add("-smp");
        command.add(String.valueOf(config.cpuCount));
        command.add("-m");
        command.add(config.ramMb + "M");
        command.add("-accel");
        command.add("tcg,thread=multi");

        if (config.hdd != null && !config.hdd.isBlank()) {
            command.add("-drive");
            command.add("file=" + config.hdd + ",if=ide,format=auto");
        }
        if (config.iso != null && !config.iso.isBlank()) {
            command.add("-drive");
            command.add("file=" + config.iso + ",media=cdrom,readonly=on");
        }
        if (config.cdrom != null && !config.cdrom.isBlank()) {
            command.add("-drive");
            command.add("file=" + config.cdrom + ",media=cdrom,readonly=on");
        }
        if (config.bootOrder != null && !config.bootOrder.isBlank()) {
            command.add("-boot");
            command.add("order=" + config.bootOrder);
        }

        if (config.video != null && !config.video.isBlank()) {
            command.add("-vga");
            command.add(config.video);
        }

        if ("vnc".equalsIgnoreCase(config.display)) {
            command.add("-display");
            command.add("none");
            command.add("-vnc");
            command.add("127.0.0.1:" + Math.max(0, config.vncPort - 5900));
        } else if ("none".equalsIgnoreCase(config.display) || "headless".equalsIgnoreCase(config.display)) {
            command.add("-display");
            command.add("none");
        }

        if (config.network != null && !config.network.isBlank() && !"off".equalsIgnoreCase(config.network)) {
            if ("user".equalsIgnoreCase(config.network)) {
                command.add("-nic");
                command.add("user,model=virtio");
            } else {
                command.add("-nic");
                command.add(config.network);
            }
        }

        appendExtraArguments(command, config.extraArguments);
        return Collections.unmodifiableList(command);
    }

    private void appendExtraArguments(List<String> command, List<String> extras) {
        if (extras == null) return;
        for (int i = 0; i < extras.size(); i++) {
            String arg = extras.get(i);
            if (arg == null || arg.isBlank()) continue;
            // -L is owned by the runtime and cannot be overridden by a VM profile.
            if ("-L".equals(arg)) {
                if (i + 1 < extras.size()) i++;
                continue;
            }
            command.add(arg);
        }
    }
}
