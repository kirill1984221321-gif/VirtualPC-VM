package com.virtualpcvm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Process boundary for the embedded QEMU executable. */
public final class QemuProcess {
    public interface Listener {
        void onStdout(String line);
        void onStderr(String line);
        void onExit(int code);
    }

    private final QemuRuntime runtime;
    private volatile Process process;
    private volatile int exitCode = Integer.MIN_VALUE;
    private final StringBuilder stdout = new StringBuilder();
    private final StringBuilder stderr = new StringBuilder();

    public QemuProcess(QemuRuntime runtime) {
        this.runtime = runtime;
    }

    public synchronized void start(List<String> command, Listener listener) throws IOException {
        if (isRunning()) throw new IllegalStateException("QEMU process is already running");
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("Empty QEMU command");
        if (!command.get(0).equals(runtime.getBinary().getAbsolutePath())) {
            throw new IllegalArgumentException("QEMU command must use the embedded runtime executable");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(runtime.getRuntimeDir());
        // Explicitly remove any inherited Termux-style loader environment.
        builder.environment().remove("LD_LIBRARY_PATH");
        builder.environment().remove("LD_PRELOAD");
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        process = builder.start();
        exitCode = Integer.MIN_VALUE;

        Thread outThread = stream(process.getInputStream(), true, listener);
        Thread errThread = stream(process.getErrorStream(), false, listener);
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();

        Thread waiter = new Thread(() -> {
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                code = -1;
            }
            exitCode = code;
            if (listener != null) listener.onExit(code);
        }, "qemu-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    public synchronized void stop() {
        Process current = process;
        if (current == null) return;
        current.destroy();
        try {
            if (!current.waitFor(2, TimeUnit.SECONDS)) current.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    public synchronized void forceStop() {
        Process current = process;
        if (current != null) current.destroyForcibly();
    }

    public boolean isRunning() {
        Process current = process;
        if (current == null) return false;
        try {
            current.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    public int getExitCode() {
        return exitCode;
    }

    public synchronized String getStdout() {
        return stdout.toString();
    }

    public synchronized String getStderr() {
        return stderr.toString();
    }

    public long getPid() {
        Process current = process;
        return current == null ? -1L : current.pid();
    }

    private Thread stream(java.io.InputStream stream, boolean isStdout, Listener listener) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (this) {
                        if (isStdout) stdout.append(line).append('\n');
                        else stderr.append(line).append('\n');
                    }
                    if (listener != null) {
                        if (isStdout) listener.onStdout(line);
                        else listener.onStderr(line);
                    }
                }
            } catch (IOException ignored) {
                // Process shutdown can close the stream while this reader is blocked.
            }
        }, isStdout ? "qemu-stdout" : "qemu-stderr");
    }
}
