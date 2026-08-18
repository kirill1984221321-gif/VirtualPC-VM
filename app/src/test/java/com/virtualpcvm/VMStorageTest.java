package com.virtualpcvm;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VMStorageTest {
    @Test public void saveLoadAndMigrateSchema() throws Exception {
        File root = Files.createTempDirectory("virtualpcvm-storage").toFile();
        try {
            VMStorage storage = new VMStorage(root);
            VMConfig config = new VMConfig();
            storage.save(config);
            VMConfig loaded = storage.load(config.id);
            assertEquals(VMConfig.CURRENT_SCHEMA_VERSION, loaded.schemaVersion);
            assertEquals(config.id, loaded.id);
            assertTrue(new File(storage.getVmDirectory(config.id), "disks").isDirectory());
            assertTrue(new File(storage.getVmDirectory(config.id), "media").isDirectory());
            assertTrue(new File(storage.getVmDirectory(config.id), "logs").isDirectory());
        } finally {
            delete(root);
        }
    }

    @Test public void rejectsIllegalStorageId() throws Exception {
        File root = Files.createTempDirectory("virtualpcvm-storage").toFile();
        try {
            VMStorage storage = new VMStorage(root);
            boolean rejected = false;
            try { storage.getVmDirectory("../outside"); }
            catch (IllegalArgumentException expected) { rejected = true; }
            assertTrue(rejected);
        } finally {
            delete(root);
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
