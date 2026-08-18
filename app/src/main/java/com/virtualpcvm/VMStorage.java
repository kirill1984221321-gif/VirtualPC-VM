package com.virtualpcvm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Own VM JSON files; legacy roms-data.json is not the primary store anymore. */
public final class VMStorage {
    private final File vmRoot;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public VMStorage(File filesDir) {
        vmRoot = new File(filesDir, "vms");
        if (!vmRoot.exists() && !vmRoot.mkdirs()) throw new IllegalStateException("Cannot create VM storage");
    }

    public File getVmDirectory(String id) {
        return new File(vmRoot, id);
    }

    public VMConfig load(String id) throws IOException {
        File json = new File(getVmDirectory(id), "vm.json");
        if (!json.isFile()) throw new IOException("VM configuration not found: " + id);
        VMConfig config;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(json), StandardCharsets.UTF_8)) {
            config = gson.fromJson(reader, VMConfig.class);
        } catch (RuntimeException e) {
            throw new IOException("Corrupt VM configuration: " + id, e);
        }
        if (config == null) throw new IOException("Empty VM configuration: " + id);
        if (config.schemaVersion > VMConfig.CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported VM schema: " + config.schemaVersion);
        }
        config.validate();
        return config;
    }

    public synchronized void save(VMConfig config) throws IOException {
        config.validate();
        File dir = getVmDirectory(config.id);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create VM directory");
        for (String child : new String[]{"disks", "media", "logs"}) {
            File sub = new File(dir, child);
            if (!sub.exists() && !sub.mkdirs()) throw new IOException("Cannot create " + child);
        }

        File target = new File(dir, "vm.json");
        File tmp = new File(dir, "vm.json.tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            gson.toJson(config, writer);
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("Atomic VM configuration replace failed");
        }
    }

    public synchronized void delete(String id) throws IOException {
        File dir = getVmDirectory(id);
        if (!dir.exists()) return;
        deleteRecursively(dir);
    }

    public String[] listIds() {
        File[] dirs = vmRoot.listFiles(File::isDirectory);
        if (dirs == null) return new String[0];
        return Arrays.stream(dirs)
                .filter(dir -> new File(dir, "vm.json").isFile())
                .map(File::getName)
                .sorted()
                .toArray(String[]::new);
    }

    private static void deleteRecursively(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (!file.delete() && file.exists()) throw new IOException("Cannot delete " + file);
    }
}
