package com.anbui.elephant.retrofit2utils;

import com.vectras.vm.network.AppNetworkUtils;

/**
 * Transitional compatibility adapter for the two remaining legacy Vectras
 * callers. It contains no Elephant implementation and delegates to the
 * application-owned network layer. New VM/runtime code must not use it.
 */
@Deprecated
public final class Retrofit2Utils {
    private Retrofit2Utils() {
    }

    public interface Retrofit2Callback {
        void onResult(boolean isSuccess, String body, int status, Throwable error);
    }

    public static void get(String url, Retrofit2Callback callback) {
        AppNetworkUtils.get(url, callback::onResult);
    }

    public static void post(String url, String jsonRaw, Retrofit2Callback callback) {
        AppNetworkUtils.post(url, jsonRaw, callback::onResult);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onResult(boolean success, String path, Throwable error);
    }

    public static void download(String url, String outputPath, DownloadCallback callback) {
        AppNetworkUtils.download(url, outputPath, new AppNetworkUtils.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                callback.onProgress(percent);
            }

            @Override
            public void onResult(boolean success, String path, Throwable error) {
                callback.onResult(success, path, error);
            }
        });
    }
}
