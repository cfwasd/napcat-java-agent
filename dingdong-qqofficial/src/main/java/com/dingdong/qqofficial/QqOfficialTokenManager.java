package com.dingdong.qqofficial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * QQ 官方 Token 管理器。
 * 自动获取和刷新 access_token，在过期前 5 分钟自动刷新。
 */
@Slf4j
public class QqOfficialTokenManager {

    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final long REFRESH_MARGIN_MS = 5 * 60 * 1000L;

    private final String appId;
    private final String appSecret;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String accessToken;
    private volatile long expiresAtMs;
    private volatile ScheduledExecutorService scheduler;
    private Runnable onRefreshedCallback;

    public QqOfficialTokenManager(String appId, String appSecret) {
        this(appId, appSecret, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build());
    }

    public QqOfficialTokenManager(String appId, String appSecret, OkHttpClient httpClient) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    public void setOnRefreshedCallback(Runnable callback) {
        this.onRefreshedCallback = callback;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qq-official-token-refresher");
            t.setDaemon(true);
            return t;
        });
        forceRefresh()
            .thenRun(this::scheduleNextRefresh)
            .exceptionally(ex -> {
                log.warn("Initial token refresh failed, retrying in 60s: {}", ex.getMessage());
                scheduler.schedule(this::scheduleNextRefresh, 60, TimeUnit.SECONDS);
                return null;
            });
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public String getToken() {
        return accessToken;
    }

    public boolean isTokenValid() {
        return accessToken != null && System.currentTimeMillis() < expiresAtMs - REFRESH_MARGIN_MS;
    }

    public CompletableFuture<String> forceRefresh() {
        return CompletableFuture.supplyAsync(() -> {
            lock.lock();
            try {
                // QQ 官方机器人 Token API：POST + JSON body (appId + clientSecret)
                String reqBody = objectMapper.writeValueAsString(
                        java.util.Map.of("appId", appId, "clientSecret", appSecret));
                Request request = new Request.Builder()
                        .url(TOKEN_URL)
                        .post(RequestBody.create(reqBody, MediaType.get("application/json")))
                        .build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new RuntimeException("Token request failed: " + response.code());
                    }
                    String respBody = response.body().string();
                    JsonNode json = objectMapper.readTree(respBody);
                    if (json.has("code") && json.get("code").asInt() != 0) {
                        throw new RuntimeException("Token error: " + json.path("message").asText());
                    }
                    this.accessToken = json.get("access_token").asText();
                    int expiresIn = json.get("expires_in").asInt(7200);
                    this.expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L;
                    log.info("QQ official token refreshed, expires in {}s", expiresIn);
                    if (onRefreshedCallback != null) {
                        try { onRefreshedCallback.run(); } catch (Exception e) {
                            log.warn("Token refreshed callback failed", e);
                        }
                    }
                    return this.accessToken;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to refresh token: " + e.getMessage(), e);
            } finally {
                lock.unlock();
            }
        });
    }

    private void scheduleNextRefresh() {
        long delay = Math.max(expiresAtMs - REFRESH_MARGIN_MS - System.currentTimeMillis(), 60000);
        scheduler.schedule(() -> {
            forceRefresh().thenRun(this::scheduleNextRefresh)
                    .exceptionally(ex -> {
                        log.warn("Token refresh failed, retrying in 60s: {}", ex.getMessage());
                        scheduler.schedule(this::scheduleNextRefresh, 60, TimeUnit.SECONDS);
                        return null;
                    });
        }, delay, TimeUnit.MILLISECONDS);
    }
}
