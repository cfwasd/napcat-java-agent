package com.napcat.starter.wechat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * agent-wechat REST API 客户端。
 * 端口 6174，Bearer token 鉴权。
 */
public class AgentWechatClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:6174";

    private final String baseUrl;
    private final String token;
    private final OkHttpClient client;
    private final ObjectMapper mapper;

    public AgentWechatClient(String baseUrl, String token, Duration timeout, ObjectMapper mapper) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL
                : baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        Objects.requireNonNull(timeout, "timeout must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.client = new OkHttpClient.Builder()
                .connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 获取聊天列表。
     */
    public List<AgentWechatChat> listChats(int limit, int offset) throws IOException {
        String url = baseUrl + "/api/chats?limit=" + limit + "&offset=" + offset;
        Request request = buildGet(url);
        return executeForList(request, new TypeReference<List<AgentWechatChat>>() {});
    }

    /**
     * 获取指定聊天的消息列表。
     */
    public List<AgentWechatMessage> listMessages(String chatId, int limit, int offset) throws IOException {
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId must not be blank");
        }
        String encodedChatId = encodePath(chatId);
        String url = baseUrl + "/api/messages/" + encodedChatId + "?limit=" + limit + "&offset=" + offset;
        Request request = buildGet(url);
        return executeForList(request, new TypeReference<List<AgentWechatMessage>>() {});
    }

    /**
     * 获取消息关联媒体。
     */
    public AgentWechatMediaResult getMedia(String chatId, long localId) throws IOException {
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId must not be blank");
        }
        if (localId <= 0) {
            throw new IllegalArgumentException("localId must be positive");
        }
        String encodedChatId = encodePath(chatId);
        Request request = buildGet(baseUrl + "/api/messages/" + encodedChatId + "/media/" + localId);
        return execute(request, AgentWechatMediaResult.class);
    }

    /**
     * 发送文本消息。
     */
    public AgentWechatSendResult sendText(String chatId, String text) throws IOException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        return sendMessage(AgentWechatSendRequest.builder()
                .chatId(chatId)
                .text(text)
                .build());
    }

    /**
     * 发送图片消息。image 可为 URL 或 agent-wechat 支持的本地路径。
     */
    public AgentWechatSendResult sendImage(String chatId, String image) throws IOException {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        return sendMessage(AgentWechatSendRequest.builder()
                .chatId(chatId)
                .image(buildImageInput(image))
                .build());
    }

    /**
     * 发送文件消息。file 可为 agent-wechat 支持的本地路径。
     */
    public AgentWechatSendResult sendFile(String chatId, String file) throws IOException {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file must not be blank");
        }
        return sendMessage(AgentWechatSendRequest.builder()
                .chatId(chatId)
                .file(buildFileInput(file))
                .build());
    }

    private AgentWechatSendResult sendMessage(AgentWechatSendRequest body) throws IOException {
        if (body.getChatId() == null || body.getChatId().isBlank()) {
            throw new IllegalArgumentException("chatId must not be blank");
        }
        String json = mapper.writeValueAsString(body);
        RequestBody requestBody = RequestBody.create(json, JSON);
        Request request = buildPost(baseUrl + "/api/messages/send", requestBody);
        return execute(request, AgentWechatSendResult.class);
    }

    private AgentWechatSendRequest.ImageInput buildImageInput(String image) throws IOException {
        MediaBytes media = readMediaBytes(image);
        String mimeType = media.contentType();
        if (mimeType == null || !mimeType.startsWith("image/")) {
            mimeType = guessImageMimeType(image);
        }
        return AgentWechatSendRequest.ImageInput.builder()
                .data(Base64.getEncoder().encodeToString(media.bytes()))
                .mimeType(mimeType)
                .build();
    }

    private AgentWechatSendRequest.FileInput buildFileInput(String file) throws IOException {
        MediaBytes media = readMediaBytes(file);
        return AgentWechatSendRequest.FileInput.builder()
                .data(Base64.getEncoder().encodeToString(media.bytes()))
                .filename(filenameOf(file))
                .build();
    }

    private MediaBytes readMediaBytes(String source) throws IOException {
        if (isHttpUrl(source)) {
            Request request = new Request.Builder().url(source).get().build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code() + ": " + safeBody(response));
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("empty media response");
                }
                String contentType = response.header("Content-Type");
                return new MediaBytes(body.bytes(), contentType);
            }
        }
        Path path = Path.of(source);
        String contentType = Files.probeContentType(path);
        return new MediaBytes(Files.readAllBytes(path), contentType);
    }

    private boolean isHttpUrl(String source) {
        return source.startsWith("http://") || source.startsWith("https://");
    }

    private String filenameOf(String source) {
        if (isHttpUrl(source)) {
            int queryIndex = source.indexOf('?');
            String noQuery = queryIndex >= 0 ? source.substring(0, queryIndex) : source;
            int slash = noQuery.lastIndexOf('/');
            String name = slash >= 0 ? noQuery.substring(slash + 1) : noQuery;
            return name.isBlank() ? "file" : name;
        }
        return Path.of(source).getFileName().toString();
    }

    private record MediaBytes(byte[] bytes, String contentType) {}

    private String guessImageMimeType(String image) {
        String guessed = URLConnection.guessContentTypeFromName(image);
        if (guessed != null && guessed.startsWith("image/")) {
            return guessed;
        }
        String lower = image.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/png";
    }

    // ---- internal helpers ----

    private Request buildGet(String url) {
        return buildRequest(url, null);
    }

    private Request buildPost(String url, RequestBody body) {
        return buildRequest(url, body);
    }

    private Request buildRequest(String url, RequestBody body) {
        Request.Builder builder = new Request.Builder().url(url);
        if (body != null) {
            builder.post(body);
        } else {
            builder.get();
        }
        String auth = authHeader();
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        return builder.build();
    }

    private String authHeader() {
        return (token != null && !token.isEmpty()) ? "Bearer " + token : null;
    }

    private <T> List<T> executeForList(Request request, TypeReference<List<T>> typeRef) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + safeBody(response));
            }
            ResponseBody body = response.body();
            if (body == null) {
                return Collections.emptyList();
            }
            String bodyStr = body.string();
            if (bodyStr == null || bodyStr.isBlank()) {
                return Collections.emptyList();
            }
            return mapper.readValue(bodyStr, typeRef);
        }
    }

    private <T> T execute(Request request, Class<T> clazz) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + safeBody(response));
            }
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            String bodyStr = body.string();
            if (bodyStr == null || bodyStr.isBlank()) {
                return null;
            }
            return mapper.readValue(bodyStr, clazz);
        }
    }

    private static String safeBody(Response response) {
        try {
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * UTF-8 路径编码，空格编码为 %20（非 +）。
     */
    static String encodePath(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
