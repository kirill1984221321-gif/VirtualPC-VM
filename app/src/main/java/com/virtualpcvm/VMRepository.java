package com.virtualpcvm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
                // Broken VM configs are skipped from the list; diagnostics can inspect the file.
            }
        }
        return result;
    }

    public VMConfig getVM(String id) throws IOException {
        return storage.load(id);
    }

    public void createVM(VMConfig config) throws IOException {
        if (storage.getVmDirectory(config.id).exists()) throw new IOException("VM already exists: " + config.id);
        storage.save(config);
    }

    public void updateVM(VMConfig config) throws IOException {
        storage.save(config);
    }

    public void saveVM(VMConfig config) throws IOException {
        storage.save(config);
    }

    public void deleteVM(String id) throws IOException {
        storage.delete(id);
    }

    public boolean exists(String id) {
        return storage.getVmDirectory(id).isDirectory();
    }
}
