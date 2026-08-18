package com.vectras.vm.legacy.network;

import androidx.annotation.NonNull;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

/**
 * Small, project-owned HTTP compatibility layer for legacy Store/Updater code.
 * It deliberately has no dependency on the old Elephant package.
 */
public final class LegacyNetworkUtils {
    public static final int NETWORK_ERROR = -1;
    public static final int TIMEOUT = -2;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final LegacyApiService API = new Retrofit.Builder()
            .baseUrl("https://dummy.com/")
            .client(CLIENT)
            .build()
            .create(LegacyApiService.class);

    private LegacyNetworkUtils() { }

    public interface CallbackResult {
        void onResult(boolean isSuccess, String body, int status, Throwable error);
    }

    public static void get(String url, CallbackResult callback) {
        API.getRawJson(url).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                try (ResponseBody responseBody = response.body()) {
                    String body = responseBody == null ? "" : responseBody.string();
                    callback.onResult(response.isSuccessful(), body, response.code(), null);
                } catch (Exception e) {
                    callback.onResult(false, "", response.code(), e);
                }
            }

            @Override
            public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                callback.onResult(false, "", t instanceof SocketTimeoutException ? TIMEOUT : NETWORK_ERROR, t);
            }
        });
    }

    public static void post(String url, String jsonRaw, CallbackResult callback) {
        RequestBody body = RequestBody.create(
                jsonRaw,
                MediaType.get("application/json; charset=utf-8")
        );

        API.post(url, body).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                try (ResponseBody responseBody = response.body()) {
                    String body = responseBody == null ? "" : responseBody.string();
                    callback.onResult(response.isSuccessful(), body, response.code(), null);
                } catch (Exception e) {
                    callback.onResult(false, "", response.code(), e);
                }
            }

            @Override
            public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                callback.onResult(false, "", t instanceof SocketTimeoutException ? TIMEOUT : NETWORK_ERROR, t);
            }
        });
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onResult(boolean success, String path, Throwable error);
    }

    public static void download(String url, String outputPath, DownloadCallback callback) {
        API.downloadFile(url).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    callback.onResult(false, null, new IOExceptionResponseException(response.code()));
                    return;
                }

                new Thread(() -> {
                    try (ResponseBody responseBody = body;
                         InputStream in = responseBody.byteStream();
                         OutputStream out = new FileOutputStream(outputPath)) {
                        byte[] buffer = new byte[8192];
                        long total = responseBody.contentLength();
                        long downloaded = 0;
                        int lastPercent = 0;
                        int read;

                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            downloaded += read;
                            if (total > 0) {
                                int percent = (int) (downloaded * 100 / total);
                                if (percent != lastPercent) {
                                    lastPercent = percent;
                                    callback.onProgress(percent);
                                }
                            }
                        }
                        out.flush();
                        callback.onResult(true, outputPath, null);
                    } catch (Exception e) {
                        callback.onResult(false, null, e);
                    }
                }, "legacy-download").start();
            }

            @Override
            public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                callback.onResult(false, null, t);
            }
        });
    }

    private static final class IOExceptionResponseException extends Exception {
        IOExceptionResponseException(int statusCode) {
            super("HTTP response error: " + statusCode);
        }
    }
}
