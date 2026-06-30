package com.dingdong.qqofficial;

import com.dingdong.channel.api.BotChannel;
import com.dingdong.channel.api.ChannelEvent;
import com.dingdong.channel.api.MessageSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * QQ 官方通道实现。
 */
@Slf4j
public class QqOfficialChannel implements BotChannel {

    private final QqOfficialProperties properties;
    private final QqOfficialTokenManager tokenManager;
    private final QqOfficialApi api;
    private final QqOfficialIdMapper idMapper;
    private final ObjectMapper objectMapper;
    private volatile Consumer<ChannelEvent> eventConsumer;
    private volatile boolean running;
    private QqOfficialWsClient wsClient;
    private QqOfficialDispatcher.AgentInvoker agentInvoker;
    private volatile ScheduledExecutorService reconnectScheduler;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private volatile boolean intentionallyReconnecting = false;
    private ScheduledExecutorService healthCheckScheduler;

    public QqOfficialChannel(QqOfficialProperties properties) {
        this.properties = properties;
        this.tokenManager = new QqOfficialTokenManager(properties.getAppId(), properties.getAppSecret());
        this.api = new QqOfficialApi(properties.getAppId(), tokenManager, properties.isSandbox());
        this.idMapper = new QqOfficialIdMapper();
        this.objectMapper = new ObjectMapper();
    }

    @Override public String getChannelId() { return "qqofficial"; }

    public void setAgentInvoker(QqOfficialDispatcher.AgentInvoker agentInvoker) {
        this.agentInvoker = agentInvoker;
    }

    @Override
    public void start() {
        log.info("Starting QQ Official channel...");
        tokenManager.start();

        // 等待 token 就绪
        int retries = 0;
        while (!tokenManager.isTokenValid() && retries < 30) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            retries++;
        }
        if (!tokenManager.isTokenValid()) {
            log.error("QQ Official token not available after 30s, aborting start");
            return;
        }

        // Token 刷新后触发重连（新 token 需要重新建 WS 连接）
        tokenManager.setOnRefreshedCallback(() -> {
            log.info("Token refreshed, reconnecting WebSocket...");
            doReconnect();
        });

        running = true;
        connectWs();

        // 每 5 分钟检查一次 token 健康状态，防止假在线
        healthCheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qq-official-health-check");
            t.setDaemon(true);
            return t;
        });
        healthCheckScheduler.scheduleAtFixedRate(this::checkTokenHealth, 5, 5, TimeUnit.MINUTES);
    }

    private synchronized void connectWs() {
        if (!running) return;
        try {
            String gatewayUrl = api.getGatewayUrl();
            log.info("QQ Official gateway URL: {}", gatewayUrl);

            Consumer<ChannelEvent> consumer = eventConsumer;
            QqOfficialDispatcher dispatcher = new QqOfficialDispatcher(this, consumer, agentInvoker);

            wsClient = new QqOfficialWsClient(new URI(gatewayUrl), tokenManager.getToken(),
                    properties.getIntents(), dispatcher, this::onWsClose);
            wsClient.connect();
            reconnectAttempts.set(0);
            log.info("QQ Official WS connecting...");
        } catch (Exception e) {
            log.error("Failed to connect QQ Official WS", e);
            scheduleReconnect();
        }
    }

    private void onWsClose(int code, String reason) {
        if (!running) return;
        if (intentionallyReconnecting) {
            log.info("WS closed due to intentional reconnect, skipping scheduled reconnect");
            return;
        }
        if ("Heartbeat timeout".equals(reason)) {
            log.warn("QQ Official WS closed due to heartbeat timeout, forcing token refresh and reconnect...");
            doReconnectWithTokenRefresh();
            return;
        }
        log.warn("QQ Official WS closed, scheduling reconnect... code={}, reason={}", code, reason);
        scheduleReconnect();
    }

    private synchronized void scheduleReconnect() {
        if (!running || reconnectScheduler != null) return;
        reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qq-official-reconnect");
            t.setDaemon(true);
            return t;
        });
        int attempt = reconnectAttempts.incrementAndGet();
        long delay = Math.min(attempt * 5L, 60L); // 5s, 10s, 15s... max 60s
        log.info("QQ Official WS reconnect scheduled in {}s (attempt {})", delay, attempt);
        reconnectScheduler.schedule(() -> {
            reconnectScheduler.shutdownNow();
            reconnectScheduler = null;
            connectWs();
        }, delay, TimeUnit.SECONDS);
    }

    private synchronized void doReconnect() {
        intentionallyReconnecting = true;
        try {
            if (wsClient != null) {
                try { wsClient.closeBlocking(); } catch (Exception ignored) {}
            }
            // closeBlocking 会触发 onClose → scheduleReconnect，
            // 取消这个误触发的定时重连，避免短时间内重复连接
            if (reconnectScheduler != null) {
                reconnectScheduler.shutdownNow();
                reconnectScheduler = null;
            }
            connectWs();
        } finally {
            intentionallyReconnecting = false;
        }
    }

    private void doReconnectWithTokenRefresh() {
        try {
            tokenManager.forceRefresh().join();
            log.info("Token refreshed after heartbeat timeout, reconnecting...");
        } catch (Exception e) {
            log.error("Token refresh after heartbeat timeout failed, reconnecting with existing token anyway", e);
        }
        doReconnect();
    }

    private void checkTokenHealth() {
        if (!running) return;
        if (!tokenManager.isTokenValid()) {
            log.warn("Token health check failed: token invalid, forcing refresh and reconnect...");
            try {
                tokenManager.forceRefresh().join();
                doReconnect();
            } catch (Exception e) {
                log.error("Token health check refresh failed", e);
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        tokenManager.setOnRefreshedCallback(null);
        if (healthCheckScheduler != null) {
            healthCheckScheduler.shutdownNow();
            healthCheckScheduler = null;
        }
        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
            reconnectScheduler = null;
        }
        if (wsClient != null) {
            try { wsClient.closeBlocking(); } catch (Exception e) { log.warn("WS close error", e); }
        }
        tokenManager.stop();
        log.info("QQ Official channel stopped");
    }

    @Override public boolean isConnected() { return running && wsClient != null && wsClient.isOpen(); }

    @Override public void setEventConsumer(Consumer<ChannelEvent> consumer) { this.eventConsumer = consumer; }

    @Override
    public MessageSender getMessageSender() {
        return new QqOfficialMessageSender(api, idMapper, properties);
    }

    public QqOfficialApi getApi() { return api; }
    public QqOfficialIdMapper getIdMapper() { return idMapper; }
    public QqOfficialTokenManager getTokenManager() { return tokenManager; }
    public QqOfficialProperties getProperties() { return properties; }
}
