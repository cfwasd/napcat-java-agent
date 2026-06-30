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
                .addInterceptor(new TokenRefreshInterceptor(tokenManager))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    private String baseUrl() { return sandbox ? SANDBOX_URL : BASE_URL; }
    private String authHeader() { return "QQBot " + tokenManager.getToken(); }

    public String getGatewayUrl() throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl() + "/gateway")
                .get()
                .header("Authorization", authHeader())
                .header("X-Union-Appid", appId)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Get gateway failed: " + response.code());
            }
            JsonNode json = objectMapper.readTree(response.body().string());
            String url = json.path("url").asText();
            if (url == null || url.isBlank()) {
                throw new IOException("No gateway URL in response: " + json);
            }
            return url;
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger msgSeqCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    public JsonNode sendGroupMessage(String groupOpenid, String content, String msgId) throws IOException {
        return sendMessage("/v2/groups/" + groupOpenid + "/messages", content, msgId);
    }

    public JsonNode sendC2cMessage(String userOpenid, String content, String msgId) throws IOException {
        return sendMessage("/v2/users/" + userOpenid + "/messages", content, msgId);
    }

    private JsonNode sendMessage(String path, String content, String msgId) throws IOException {
        int seq = msgSeqCounter.incrementAndGet();
        String body = objectMapper.writeValueAsString(
                Map.of("content", content, "msg_id", msgId, "msg_seq", seq, "msg_type", 0));
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

    public JsonNode sendGroupMarkdownWithKeyboard(String groupOpenid, String markdownContent,
                                                     String keyboardJson, String msgId) throws IOException {
        return sendMarkdownWithKeyboard("/v2/groups/" + groupOpenid + "/messages", markdownContent, keyboardJson, msgId);
    }

    public JsonNode sendC2cMarkdownWithKeyboard(String userOpenid, String markdownContent,
                                                   String keyboardJson, String msgId) throws IOException {
        return sendMarkdownWithKeyboard("/v2/users/" + userOpenid + "/messages", markdownContent, keyboardJson, msgId);
    }

    private JsonNode sendMarkdownWithKeyboard(String path, String markdownContent,
                                               String keyboardJson, String msgId) throws IOException {
        int seq = msgSeqCounter.incrementAndGet();
        java.util.Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
        bodyMap.put("msg_type", 2);
        bodyMap.put("markdown", Map.of("content", markdownContent));
        bodyMap.put("keyboard", objectMapper.readTree(keyboardJson));
        bodyMap.put("msg_id", msgId);
        bodyMap.put("msg_seq", seq);

        String body = objectMapper.writeValueAsString(bodyMap);
        Request request = new Request.Builder()
                .url(baseUrl() + path)
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .header("Authorization", authHeader())
                .header("X-Union-Appid", appId)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Send markdown+keyboard failed: " + response.code() + " " + respBody);
            }
            return objectMapper.readTree(respBody);
        }
    }

    public JsonNode sendGroupMarkdownMessage(String groupOpenid, String markdownContent, String msgId) throws IOException {
        return sendMarkdownMessage("/v2/groups/" + groupOpenid + "/messages", markdownContent, msgId);
    }

    public JsonNode sendC2cMarkdownMessage(String userOpenid, String markdownContent, String msgId) throws IOException {
        return sendMarkdownMessage("/v2/users/" + userOpenid + "/messages", markdownContent, msgId);
    }

    private JsonNode sendMarkdownMessage(String path, String markdownContent, String msgId) throws IOException {
        int seq = msgSeqCounter.incrementAndGet();
        String body = objectMapper.writeValueAsString(
                Map.of("markdown", Map.of("content", markdownContent), "msg_id", msgId, "msg_seq", seq, "msg_type", 2));
        Request request = new Request.Builder()
                .url(baseUrl() + path)
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .header("Authorization", authHeader())
                .header("X-Union-Appid", appId)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Send markdown message failed: " + response.code() + " " + respBody);
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

    /**
     * OkHttp 拦截器：检测到 401 时自动刷新 token 并重试一次。
     */
    private class TokenRefreshInterceptor implements Interceptor {
        private final QqOfficialTokenManager tokenManager;

        TokenRefreshInterceptor(QqOfficialTokenManager tokenManager) {
            this.tokenManager = tokenManager;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = chain.proceed(request);
            if (response.code() == 401) {
                response.close();
                log.warn("QQ Official API returned 401, forcing token refresh...");
                try {
                    tokenManager.forceRefresh().join();
                    String newToken = tokenManager.getToken();
                    if (newToken == null || newToken.isBlank()) {
                        throw new IOException("Token refresh returned empty token");
                    }
                    Request newRequest = request.newBuilder()
                            .header("Authorization", "QQBot " + newToken)
                            .build();
                    return chain.proceed(newRequest);
                } catch (Exception e) {
                    throw new IOException("Token refresh failed after 401", e);
                }
            }
            return response;
        }
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
        int seq = msgSeqCounter.incrementAndGet();
        String body = objectMapper.writeValueAsString(
                Map.of("msg_type", 7, "media", Map.of("file_info", fileInfo), "msg_id", msgId, "msg_seq", seq));
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
