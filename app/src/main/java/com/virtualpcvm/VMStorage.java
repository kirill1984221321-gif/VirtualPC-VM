package com.virtualpcvm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Owns VM JSON files and private VM directories. */
public final class VMStorage {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final File vmRoot;
    private final File canonicalRoot;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public VMStorage(File filesDir) {
        vmRoot = new File(filesDir, "vms");
        if (!vmRoot.exists() && !vmRoot.mkdirs()) throw new IllegalStateException("Cannot create VM storage");
        try {
            canonicalRoot = vmRoot.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve VM storage", e);
        }
    }

    public File getVmDirectory(String id) {
        validateId(id);
        File result = new File(vmRoot, id);
        try {
            File canonical = result.getCanonicalFile();
            String root = canonicalRoot.getPath() + File.separator;
            if (!canonical.getPath().startsWith(root)) throw new IllegalArgumentException("VM path escapes storage");
            return canonical;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid VM id", e);
        }
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
        if (!id.equals(config.id)) throw new IOException("VM id mismatch: " + id);
        if (config.schemaVersion > VMConfig.CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported VM schema: " + config.schemaVersion);
        }
        if (config.schemaVersion < VMConfig.CURRENT_SCHEMA_VERSION) migrateSchema(config);
        try {
            config.validate();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid VM configuration: " + id, e);
        }
        return config;
    }

    public synchronized void save(VMConfig config) throws IOException {
        if (config == null) throw new IllegalArgumentException("VM config is null");
        config.validate();
        config.schemaVersion = VMConfig.CURRENT_SCHEMA_VERSION;
        File dir = getVmDirectory(config.id);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create VM directory");
        for (String child : new String[]{"disks", "media", "logs"}) {
            File sub = new File(dir, child);
            if (!sub.exists() && !sub.mkdirs()) throw new IOException("Cannot create " + child);
        }

        File target = new File(dir, "vm.json");
        File tmp = new File(dir, "vm.json.tmp");
        File backup = new File(dir, "vm.json.bak");
        if (tmp.exists() && !tmp.delete()) throw new IOException("Cannot remove stale VM temporary file");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            gson.toJson(config, writer);
        }
        if (backup.exists() && !backup.delete()) throw new IOException("Cannot remove stale VM backup");

        boolean backedUp = target.exists() && target.renameTo(backup);
        if (target.exists() && !backedUp) {
            tmp.delete();
            throw new IOException("Cannot stage existing VM configuration");
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            if (backedUp) backup.renameTo(target);
            throw new IOException("Atomic VM configuration replace failed");
        }
        if (backup.exists() && !backup.delete()) throw new IOException("Cannot remove VM backup");
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
                .filter(dir -> SAFE_ID.matcher(dir.getName()).matches())
                .filter(dir -> new File(dir, "vm.json").isFile())
                .map(File::getName)
                .sorted()
                .toArray(String[]::new);
    }

    public File getVmRoot() { return canonicalRoot; }

    public static boolean isValidId(String id) {
        return id != null && SAFE_ID.matcher(id).matches() && !".".equals(id) && !"..".equals(id);
    }

    private static void migrateSchema(VMConfig config) {
        if (config.schemaVersion == 1) {
            config.architecture = "x86_64";
            config.schemaVersion = 2;
        }
    }

    private static void validateId(String id) {
        if (!isValidId(id)) throw new IllegalArgumentException("Illegal VM id");
    }

    private static void deleteRecursively(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (!file.delete() && file.exists()) throw new IOException("Cannot delete " + file);
    }
}
