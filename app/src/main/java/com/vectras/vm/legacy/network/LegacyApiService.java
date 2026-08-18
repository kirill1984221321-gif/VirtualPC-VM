package com.vectras.vm.legacy.network;

import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Streaming;
import retrofit2.http.Url;

/**
 * HTTP surface retained only for legacy Store/Updater features.
 * The VirtualPC-VM QEMU runtime does not depend on this API.
 */
public interface LegacyApiService {
    @GET
    Call<ResponseBody> getRawJson(@Url String url);

    @Streaming
    @GET
    Call<ResponseBody> downloadFile(@Url String url);

    @POST
    Call<ResponseBody> post(@Url String url, @Body RequestBody body);
}
