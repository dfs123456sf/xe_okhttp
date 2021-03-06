package com.okhttplib.help;

import android.os.Message;
import android.os.NetworkOnMainThreadException;
import android.text.TextUtils;

import com.okhttplib.HttpInfo;
import com.okhttplib.annotation.RequestMethod;
import com.okhttplib.bean.RequestMessage;
import com.okhttplib.bean.UploadFileInfo;
import com.okhttplib.callback.OnResultCallBack;
import com.okhttplib.Configuration;
import com.okhttplib.handler.HttpMainHandler;
import com.okhttplib.manage.BasicCallManage;

import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 网络请求执行者（网络请求的真正执行者）
 * 负责网络请求访问
 * 分别提供同步和异步请求
 */

public class HttpPerformer extends BasicPerformer {

    HttpPerformer(Configuration config) {
        super(config);
    }

    @Override
    protected OkHttpClient buildOkHttpClient(Configuration config) {
        if (config.getOkHttpClient() == null) {
            synchronized (config) {
                if (config.getOkHttpClient() == null) {
                    config.setOkHttpClient(newOkHttpClientBuilder(config).build());
                }
            }
        }
        return config.getOkHttpClient();
    }

    @Override
    public void doRequestAsync(final HttpCommand command) {
        final OnResultCallBack callBack = command.getCallBack();
        final HttpInfo info = command.getInfo();
        if (!checkUrl(info.getUrl())) {
            updateInfo(info, HttpInfo.CHECK_URL);
            sendMessage(info, callBack);
            return;
        }
        Call call = checkHttpClient(command).newCall(buildRequest(info, command.getRequestMethod()));
        BasicCallManage.putCall(info.getCallTag(), call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                updateInfo(info, HttpInfo.NET_FAILURE, e.getMessage());
                sendMessage(info, callBack);
                BasicCallManage.removeCall(info.getCallTag(), call);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                doResponse(command, response);
                sendMessage(info, callBack);
                BasicCallManage.removeCall(info.getCallTag(), call);
            }
        });
    }

    @Override
    public void doRequestSync(HttpCommand command) {
        final OnResultCallBack callBack = command.getCallBack();
        final HttpInfo info = command.getInfo();
        Call call = null;
        if (!checkUrl(info.getUrl())) {
            if (callBack != null)
                callBack.onResponse(updateInfo(info, HttpInfo.CHECK_URL));
            return;
        }
        try {
            call = checkHttpClient(command).newCall(buildRequest(info, command.getRequestMethod()));
            BasicCallManage.putCall(info.getCallTag(), call);
            Response response = call.execute();
            doResponse(command, response);
        } catch (SocketTimeoutException e) {
            if (null != e.getMessage()) {
                if (e.getMessage().contains("failed to connect to"))
                    updateInfo(info, HttpInfo.CONNECTION_TIME_OUT);
                if (e.getMessage().equals("timeout"))
                    updateInfo(info, HttpInfo.READ_OR_WRITE_TIME_OUT);
            }
            updateInfo(info, HttpInfo.READ_OR_WRITE_TIME_OUT);
        } catch (NetworkOnMainThreadException e) {
            updateInfo(info, HttpInfo.NETWORK_ON_MAIN_THREAD);
        } catch (Exception e) {
            updateInfo(info, HttpInfo.ON_RESULT);
        } finally {
            if (callBack != null)
                callBack.onResponse(info);
            BasicCallManage.removeCall(info.getCallTag(), call);
        }
    }

    /**
     * 發送消息，確保異步訪問返回的消息可以在主綫程中更新
     *
     * @param info
     * @param callBack
     */
    private void sendMessage(HttpInfo info, OnResultCallBack callBack) {
        if (callBack != null) {
            Message msg = new RequestMessage(HttpMainHandler.RESPONSE_HTTP, info, callBack).build();
            HttpMainHandler.getInstance().sendMessage(msg);
        }
    }

//    /**
//     * 检验 request，判读使用默认 request 还是重定义 request
//     *
//     * @param command
//     * @return
//     */
//    private Request checkRequest(HttpCommand command) {
//        Request request = null;
//        if (command.getUploadPerformer() != null) {
//            request = command.getUploadPerformer().buildFileRequest(command.getInfo());
//        }
//        return request == null ? buildRequest(command.getInfo(), command.getRequestMethod()) : request;
//    }

    /**
     * 检验 OkHttpClient，判读使用默认 OkHttpClient 还是重定义 OkHttpClient
     *
     * @param command
     * @return
     */
    private OkHttpClient checkHttpClient(HttpCommand command) {
        OkHttpClient client = null;
        if (command.getPerformer() != null) {
            client = command.getPerformer().getOkHttpClient();
        }
        client = client == null ? getOkHttpClient() : client;
        return client;
    }

    /**
     * 构建 默认的request
     *
     * @param info
     * @param method
     * @return
     */
    private Request buildRequest(HttpInfo info, @RequestMethod int method) {
        Request.Builder builder = new Request.Builder();
        HashMap<String, String> params = info.getParams();
        if (paramsInterceptor != null) {
            //使用clone技术(原型模式)目的是防止原始数据被篡改
            params = paramsInterceptor.intercept(params == null ? null : (HashMap<String, String>) params.clone());
        }
        switch (method) {
            case RequestMethod.POST:
                FormBody.Builder fBuilder = new FormBody.Builder();
                if (params != null && !params.isEmpty()) {
                    for (String key : params.keySet()) {
                        String value = params.get(key);
                        value = value == null ? "" : value;
                        fBuilder.add(key, value);
                    }
                }
                builder.url(info.getUrl()).post(fBuilder.build());
                break;
            case RequestMethod.GET:
                StringBuilder urlIntegral = new StringBuilder();
                urlIntegral.append(info.getUrl());
                if (params != null && !params.isEmpty()) {
                    if (urlIntegral.indexOf("?") < 0)
                        urlIntegral.append("?");
                    boolean isFirst = urlIntegral.toString().endsWith("?");
                    for (String key : params.keySet()) {
                        String value = params.get(key);
                        value = value == null ? "" : value;
                        if (isFirst) {
                            urlIntegral.append(key).append("=").append(value);
                            isFirst = false;
                        } else {
                            urlIntegral.append("&").append(key).append("=").append(value);
                        }
                    }
                }
                builder.url(urlIntegral.toString()).get();
                break;
            case RequestMethod.FORM:
                MultipartBody.Builder mbBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
                if (params != null && !params.isEmpty()) {
                    for (String key : info.getParams().keySet()) {
                        String value = info.getParams().get(key);
                        value = value == null ? "" : value;
                        mbBuilder.addFormDataPart(key, value);
                    }
                }
                //配置上传文件(需要上传的实体)
                if (info.getUploadFiles() != null) {
                    for (UploadFileInfo fileInfo : info.getUploadFiles()) {
                        String filePath = fileInfo.getFileAbsolutePath();
                        File file = new File(filePath);

                        if (TextUtils.isEmpty(filePath))
                            throw new IllegalArgumentException("file path is illegal argument");

                        mbBuilder.addFormDataPart(fileInfo.getUploadFormat(),
                                file.getName(),
                                RequestBody.create(MediaType.parse(mediaTypeInterceptor.intercept(filePath)), file));
                    }
                }
                builder.url(info.getUrl()).post(mbBuilder.build());
                break;
            default:
                builder.url(info.getUrl()).get();
                break;
        }
        //配置http head
        setRequestHeads(info, builder);
        return builder.build();
    }
}
