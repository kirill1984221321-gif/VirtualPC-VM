package com.vectras.vm.network;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Small application-owned HTTP helper used only by optional Store/Updater
 * features. VM/QEMU runtime code does not depend on this class.
 */
public final class AppNetworkUtils {
    public static final int NETWORK_ERROR = -1;
    public static final int TIMEOUT = -2;

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppNetworkUtils() {
    }

    public interface CallbackResult {
        void onResult(boolean isSuccess, String body, int status, Throwable error);
    }

    public static void get(String url, CallbackResult callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = open(url, "GET");
                int status = connection.getResponseCode();
                String body = readBody(connection, status);
                postResult(callback, status >= 200 && status < 300, body, status, null);
            } catch (Throwable error) {
                postResult(callback, false, "", mapError(error), error);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "app-http-get").start();
    }

    public static void post(String url, String jsonRaw, CallbackResult callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = open(url, "POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                byte[] payload = jsonRaw == null ? new byte[0] : jsonRaw.getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }

                int status = connection.getResponseCode();
                String body = readBody(connection, status);
                postResult(callback, status >= 200 && status < 300, body, status, null);
            } catch (Throwable error) {
                postResult(callback, false, "", mapError(error), error);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "app-http-post").start();
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onResult(boolean success, String path, Throwable error);
    }

    public static void download(String url, String outputPath, DownloadCallback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            File outputFile = new File(outputPath);
            try {
                connection = open(url, "GET");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    callback.onResult(false, null, new IOExceptionResponseException(status));
                    return;
                }

                File parent = outputFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IllegalStateException("Unable to create output directory: " + parent);
                }

                long total = connection.getContentLengthLong();
                long downloaded = 0;
                int lastPercent = -1;

                try (InputStream input = connection.getInputStream();
                     OutputStream output = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        downloaded += read;

                        if (total > 0) {
                            int percent = (int) (downloaded * 100L / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                callback.onProgress(percent);
                            }
                        }
                    }
                    output.flush();
                }

                callback.onResult(true, outputPath, null);
            } catch (Throwable error) {
                if (outputFile.exists()) {
                    // A partial download must never be mistaken for a valid tool image.
                    outputFile.delete();
                }
                callback.onResult(false, null, error);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "app-http-download").start();
    }

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private static String readBody(HttpURLConnection connection, int status) throws Exception {
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) {
            return "";
        }

        try (InputStream stream = input) {
            StringBuilder body = new StringBuilder();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                body.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return body.toString();
        }
    }

    private static int mapError(Throwable error) {
        return error instanceof SocketTimeoutException ? TIMEOUT : NETWORK_ERROR;
    }

    private static void postResult(CallbackResult callback, boolean success, String body, int status, Throwable error) {
        MAIN.post(() -> callback.onResult(success, body, status, error));
    }

    private static final class IOExceptionResponseException extends Exception {
        IOExceptionResponseException(int statusCode) {
            super("HTTP response error: " + statusCode);
        }
    }
}
