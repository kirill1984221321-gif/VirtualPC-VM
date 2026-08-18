package com.anbui.elephant.retrofit2utils;

import com.vectras.vm.legacy.network.LegacyNetworkUtils;

/**
 * @deprecated Compatibility facade for untouched legacy imports. New code must
 * use {@link LegacyNetworkUtils}; the QEMU runtime does not depend on this class.
 */
@Deprecated
public final class Retrofit2Utils {
    public static final int NETWORK_ERROR = LegacyNetworkUtils.NETWORK_ERROR;
    public static final int TIMEOUT = LegacyNetworkUtils.TIMEOUT;

    private Retrofit2Utils() { }

    public interface Retrofit2Callback {
        void onResult(boolean isSuccess, String body, int status, Throwable error);
    }

    public static void get(String url, Retrofit2Callback callback) {
        LegacyNetworkUtils.get(url, callback::onResult);
    }

    public static void post(String url, String jsonRaw, Retrofit2Callback callback) {
        LegacyNetworkUtils.post(url, jsonRaw, callback::onResult);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onResult(boolean success, String path, Throwable error);
    }

    public static void download(String url, String outputPath, DownloadCallback callback) {
        LegacyNetworkUtils.download(url, outputPath, new LegacyNetworkUtils.DownloadCallback() {
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
