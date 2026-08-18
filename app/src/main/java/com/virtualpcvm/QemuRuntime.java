package com.virtualpcvm;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Owns only the embedded QEMU runtime. It never consults Termux or system QEMU. */
public final class QemuRuntime {
    private static final String ASSET_ROOT = "qemu";
    private static final String RUNTIME_NAME = "qemu";
    private static final String EXPECTED_QEMU_SHA256 =
            "8db5b84de1dd197e6a62137139fa8bdaa6b2a8f89bcdb7bf0466f82f292827e0";
    private static final int MIN_LIBRARY_COUNT = 100;
    private static final Object PREPARE_LOCK = new Object();

    private final Context context;

    public QemuRuntime(Context context) { this.context = context.getApplicationContext(); }

    public File getRuntimeDir() { return new File(context.getFilesDir(), RUNTIME_NAME); }
    public File getBinary() { return new File(getRuntimeDir(), "qemu-system-x86_64"); }
    public File getLibraryDir() { return new File(getRuntimeDir(), "lib"); }
    public File getFirmwareDir() { return new File(getRuntimeDir(), "share/qemu"); }

    public File prepare() throws IOException { return prepare(false); }

    public File prepare(boolean force) throws IOException {
        synchronized (PREPARE_LOCK) {
            return prepareLocked(force);
        }
    }

    private File prepareLocked(boolean force) throws IOException {
        if (!force && isValid()) return getRuntimeDir();
        File tmp = new File(context.getFilesDir(), RUNTIME_NAME + ".tmp");
        File backup = new File(context.getFilesDir(), RUNTIME_NAME + ".old");
        deleteRecursively(tmp);
        deleteRecursively(backup);
        if (!tmp.mkdirs() && !tmp.isDirectory()) throw new IOException("Cannot create temporary QEMU runtime");
        try {
            copyTree(context.getAssets(), ASSET_ROOT, tmp);
            validateDir(tmp, true);
            File current = getRuntimeDir();
            if (current.exists() && !current.renameTo(backup)) throw new IOException("Cannot stage existing QEMU runtime");
            if (!tmp.renameTo(current)) {
                if (backup.exists()) backup.renameTo(current);
                throw new IOException("Cannot atomically install QEMU runtime");
            }
            if (!isValid()) {
                deleteRecursively(current);
                if (backup.exists()) backup.renameTo(current);
                throw new IOException("Installed QEMU runtime failed validation");
            }
            deleteRecursively(backup);
            return current;
        } catch (IOException | RuntimeException e) {
            deleteRecursively(tmp);
            throw e;
        }
    }

    public boolean isValid() {
        try { validateDir(getRuntimeDir(), true); return true; }
        catch (Exception ignored) { return false; }
    }

    public int getLibraryCount() {
        File[] files = getLibraryDir().listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File file : files) if (file.isFile() && file.getName().contains(".so")) count++;
        return count;
    }

    public String getVersion() { return "QEMU 11.0.3 / ARM64 host / x86_64 guest"; }

    private void validateDir(File root, boolean checkHash) throws IOException {
        if (!root.isDirectory()) throw new IOException("QEMU runtime directory missing");
        File bin = new File(root, "qemu-system-x86_64");
        File lib = new File(root, "lib");
        File firmware = new File(root, "share/qemu");
        if (!bin.isFile()) throw new IOException("QEMU executable missing");
        if (bin.length() < 20_000_000L) throw new IOException("QEMU executable size is unexpectedly small");
        if (!bin.canExecute() && !bin.setExecutable(true, false)) throw new IOException("QEMU executable permission cannot be set");
        if (!lib.isDirectory()) throw new IOException("QEMU lib directory missing");
        if (!firmware.isDirectory()) throw new IOException("QEMU firmware directory missing");
        if (getFileCount(lib) < MIN_LIBRARY_COUNT) throw new IOException("QEMU runtime library closure is incomplete");
        if (!new File(firmware, "bios.bin").isFile()) throw new IOException("bios.bin missing");
        if (!new File(firmware, "edk2-x86_64-code.fd").isFile()) throw new IOException("edk2-x86_64-code.fd missing");
        if (checkHash) {
            String hash = sha256(bin);
            if (!EXPECTED_QEMU_SHA256.equalsIgnoreCase(hash)) throw new IOException("QEMU SHA-256 mismatch: " + hash);
        }
    }

    private static int getFileCount(File dir) {
        File[] files = dir.listFiles();
        return files == null ? 0 : files.length;
    }

    private static void copyTree(AssetManager assets, String assetPath, File target) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            copyAssetFile(assets, assetPath, target);
            return;
        }
        if (!target.exists() && !target.mkdirs() && !target.isDirectory()) throw new IOException("Cannot create " + target);
        for (String child : children) copyTree(assets, assetPath + "/" + child, new File(target, child));
    }

    private static void copyAssetFile(AssetManager assets, String assetPath, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create asset parent");
        try (InputStream in = new BufferedInputStream(assets.open(assetPath));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        if (target.getName().equals("qemu-system-x86_64")) target.setExecutable(true, false);
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            byte[] bytes = digest.digest();
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) result.append(String.format("%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
