package com.virtualpcvm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Repository facade: UI never reads VM JSON or files directly. */
public final class VMRepository {
    private final VMStorage storage;

    public VMRepository(VMStorage storage) {
        this.storage = storage;
    }

    public synchronized List<VMConfig> listVMs() {
        List<VMConfig> result = new ArrayList<>();
        for (String id : storage.listIds()) {
            try {
                result.add(storage.load(id));
            } catch (IOException ignored) {
                // Keep broken entries out of the normal UI list; diagnostics can inspect vm.json directly.
            }
        }
        result.sort(Comparator.comparing(config -> config.name.toLowerCase(Locale.ROOT)));
        return result;
    }

    public VMConfig getVM(String id) throws IOException {
        return storage.load(id);
    }

    public synchronized void createVM(VMConfig config) throws IOException {
        validateForRepository(config);
        if (storage.getVmDirectory(config.id).exists()) throw new IOException("VM already exists: " + config.id);
        ensureUniqueName(config, null);
        storage.save(config);
    }

    public synchronized void updateVM(VMConfig config) throws IOException {
        validateForRepository(config);
        if (!storage.getVmDirectory(config.id).isDirectory()) throw new IOException("VM does not exist: " + config.id);
        ensureUniqueName(config, config.id);
        storage.save(config);
    }

    public synchronized void saveVM(VMConfig config) throws IOException {
        validateForRepository(config);
        ensureUniqueName(config, storage.exists(config.id) ? config.id : null);
        storage.save(config);
    }

    public synchronized void deleteVM(String id) throws IOException {
        if (!VMStorage.isValidId(id)) throw new IOException("Illegal VM id");
        storage.delete(id);
    }

    public boolean exists(String id) {
        if (!VMStorage.isValidId(id)) return false;
        return storage.getVmDirectory(id).isDirectory();
    }

    public List<String> listBrokenVmIds() {
        List<String> broken = new ArrayList<>();
        for (String id : storage.listIds()) {
            try { storage.load(id); }
            catch (IOException ignored) { broken.add(id); }
        }
        return broken;
    }

    private void validateForRepository(VMConfig config) throws IOException {
        if (config == null) throw new IOException("VM config is null");
        try { config.validate(); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid VM configuration", e); }
    }

    private void ensureUniqueName(VMConfig config, String exceptId) throws IOException {
        String wanted = config.name.trim().toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        for (VMConfig existing : listVMs()) {
            if (existing.id.equals(exceptId)) continue;
            if (!seen.add(existing.id)) continue;
            if (existing.name.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                throw new IOException("VM name already exists: " + config.name);
            }
        }
    }
}
