package com.vectras.vm.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.vectras.vm.AppConfig;
import com.vectras.vm.logger.VectrasStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ShellExecutor {
    private static final String TAG = "ShellExecutor";

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private volatile Process shellExecutorProcess;
    private volatile Future<?> processFuture;

    public void exec(String command) {
        if (command == null || command.trim().isEmpty()) {
            VectrasStatus.logInfo(TAG + " > Empty command ignored");
            return;
        }

        stopCurrentProcess();

        final String requestedCommand = command;
        processFuture = executorService.submit(() -> runCommand(requestedCommand));
    }

    private void runCommand(String command) {
        String logPath = AppConfig.maindirpath + "shell-executor.log";
        File logFile = new File(logPath);
        File parent = logFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            Log.w(TAG, "Unable to create shell executor log directory: " + parent);
        }

        Process process = null;
        try (FileWriter logWriter = new FileWriter(logFile, true)) {
            logWriter.write("Running command: " + command + "\n");
            logWriter.flush();

            // Run one command instead of leaving an interactive shell open forever.
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            shellExecutorProcess = process;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logWriter.write(line);
                    logWriter.write('\n');
                    logWriter.flush();
                    Log.d(TAG, line);
                    postStatus(line);
                }
            }

            int exitCode = process.waitFor();
            String result = "Command exited with code " + exitCode;
            logWriter.write(result + "\n");
            logWriter.flush();
            Log.d(TAG, result);
            postStatus(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "ShellExecutor interrupted", e);
            VectrasStatus.logInfo(TAG + " > ShellExecutor interrupted");
        } catch (IOException e) {
            Log.e(TAG, "Error starting ShellExecutor", e);
            VectrasStatus.logInfo(TAG + " > " + e);
        } finally {
            if (process != null) {
                process.destroy();
            }
            if (shellExecutorProcess == process) {
                shellExecutorProcess = null;
            }
        }
    }

    private void postStatus(String line) {
        new Handler(Looper.getMainLooper()).post(
                () -> VectrasStatus.logInfo(TAG + " > " + line));
    }

    public void stop() {
        stopCurrentProcess();
        executorService.shutdownNow();
    }

    private void stopCurrentProcess() {
        Process process = shellExecutorProcess;
        if (process != null) {
            process.destroy();
        }

        Future<?> future = processFuture;
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }
}
