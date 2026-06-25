package com.dingdong.qqofficial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * QQ 官方机器人 API 客户端。
 */
@Slf4j
public class QqOfficialApi {

    private static final String BASE_URL = "https://api.sgroup.qq.com";
    private static final String SANDBOX_URL = "https://sandbox.api.sgroup.qq.com";

    private final String appId;
    private final QqOfficialTokenManager tokenManager;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean sandbox;

    public QqOfficialApi(String appId, QqOfficialTokenManager tokenManager, boolean sandbox) {
        this.appId = appId;
        this.tokenManager = tokenManager;
        this.sandbox = sandbox;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    private String baseUrl() { return sandbox ? SANDBOX_URL : BASE_URL; }
    private String authHeader() { return "QQBot " + tokenManager.getToken(); }

    public JsonNode sendGroupMessage(String groupOpenid, String content, String msgId) throws IOException {
        return sendMessage("/v2/groups/" + groupOpenid + "/messages", content, msgId);
    }

    public JsonNode sendC2cMessage(String userOpenid, String content, String msgId) throws IOException {
        return sendMessage("/v2/users/" + userOpenid + "/messages", content, msgId);
    }

    private JsonNode sendMessage(String path, String content, String msgId) throws IOException {
        String body = objectMapper.writeValueAsString(
                Map.of("content", content, "msg_id", msgId, "msg_type", 0));
        Request request = new Request.Builder()
                .url(baseUrl() + path)
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .header("Authorization", authHeader())
                .header("X-Union-Appid", appId)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Send message failed: " + response.code() + " " + respBody);
            }
            return objectMapper.readTree(respBody);
        }
    }

    public String uploadGroupFile(String groupOpenid, int fileType, String url) throws IOException {
        return uploadFile("/v2/groups/" + groupOpenid + "/files", fileType, url);
    }

    public String uploadC2cFile(String userOpenid, int fileType, String url) throws IOException {
        return uploadFile("/v2/users/" + userOpenid + "/files", fileType, url);
    }

    private String uploadFile(String path, int fileType, String url) throws IOException {
        String body = objectMapper.writeValueAsString(Map.of("file_type", fileType, "url", url));
        Request request = new Request.Builder()
                .url(baseUrl() + path)
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .header("Authorization", authHeader())
                .header("X-Union-Appid", appId)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Upload file failed: " + response.code() + " " + respBody);
            }
            JsonNode json = objectMapper.readTree(respBody);
            return json.path("file_info").asText();
        }
    }

    public void sendGroupMedia(String groupOpenid, String fileInfo, String msgId) throws IOException {
        sendMedia("/v2/groups/" + groupOpenid + "/messages", fileInfo, msgId);
    }

    public void sendC2cMedia(String userOpenid, String fileInfo, String msgId) throws IOException {
        sendMedia("/v2/users/" + userOpenid + "/messages", fileInfo, msgId);
    }

    private void sendMedia(String path, String fileInfo, String msgId) throws IOException {
        String body = objectMapper.writeValueAsString(
                Map.of("msg_type", 7, "file_info", fileInfo, "msg_id", msgId));
        Request request = new Request.Builder()
                .url(baseUrl() + path)
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .header("Authorization", authHeader())
                .header("X-Union-Appid", appId)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String respBody = response.body() != null ? response.body().string() : "";
                throw new IOException("Send media failed: " + response.code() + " " + respBody);
            }
        }
    }
}
