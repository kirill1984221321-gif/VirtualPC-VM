package com.anbui.elephant.retrofit2utils;

import com.vectras.vm.network.AppNetworkUtils;

/**
 * Transitional compatibility facade for two legacy Vectras callers that have
 * not yet been migrated. It contains no Elephant implementation and performs
 * real network operations through the application-owned AppNetworkUtils.
 * New VirtualPC-VM code must not import this package.
 */
@Deprecated
public final class Retrofit2Utils {
    public static final int NETWORK_ERROR = AppNetworkUtils.NETWORK_ERROR;
    public static final int TIMEOUT = AppNetworkUtils.TIMEOUT;

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
