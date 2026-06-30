package com.dingdong.qqofficial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * QQ 官方 WebSocket 客户端。
 * 实现完整的 WebSocket 协议：Hello → Identify → Heartbeat → Dispatch。
 */
@Slf4j
public class QqOfficialWsClient extends WebSocketClient {

    private final ObjectMapper objectMapper;
    private final Consumer<JsonNode> eventHandler;
    private final java.util.function.BiConsumer<Integer, String> closeCallback;
    private final int intents;
    private final String token;
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledFuture<?> heartbeatTimeoutFuture;
    private volatile long lastHeartbeatAckMs = 0;
    private static final long HEARTBEAT_TIMEOUT_MS = 2 * 60 * 1000L; // 2分钟
    private volatile boolean identified;
    private volatile boolean heartbeatTimeout = false;

    public QqOfficialWsClient(URI serverUri, String token, int intents,
                               Consumer<JsonNode> eventHandler,
                               java.util.function.BiConsumer<Integer, String> closeCallback) {
        super(serverUri);
        addHeader("Authorization", "QQBot " + token);
        this.token = token;
        this.intents = intents;
        this.objectMapper = new ObjectMapper();
        this.eventHandler = eventHandler;
        this.closeCallback = closeCallback;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("QQ Official WS connected: status={}", handshake.getHttpStatus());
        heartbeatScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "qq-official-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            int op = json.path("op").asInt(-1);
            String eventType = json.path("t").asText("");
            int seq = json.path("s").asInt(0);

            switch (op) {
                case 0: // Dispatch
                    JsonNode d = json.path("d");
                    if (d != null && !d.isNull() && !d.isMissingNode()) {
                        ((ObjectNode) d).put("__event_type", eventType);
                        eventHandler.accept(d);
                    }
                    break;
                case 10: { // Hello
                    int heartbeatInterval = json.path("d").path("heartbeat_interval").asInt(45000);
                    log.info("QQ Official WS received Hello, heartbeat_interval={}ms, sending Identify...", heartbeatInterval);
                    sendIdentify();
                    startHeartbeat(heartbeatInterval);
                    break;
                }
                case 11: // Heartbeat ACK
                    lastHeartbeatAckMs = System.currentTimeMillis();
                    log.debug("QQ Official WS heartbeat ACK");
                    break;
                default:
                    log.debug("QQ Official WS op={} t={}", op, eventType);
            }
        } catch (Exception e) {
            log.warn("Failed to parse WS message: {}", e.getMessage());
        }
    }

    private void sendIdentify() {
        try {
            ObjectNode d = objectMapper.createObjectNode();
            d.put("token", "QQBot " + token);
            d.put("intents", intents);
            d.putArray("shard").add(0).add(1);
            ObjectNode props = d.putObject("properties");
            props.put("$os", "linux");
            props.put("$browser", "dingdong");
            props.put("$device", "dingdong");

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("op", 2);
            payload.set("d", d);
            send(objectMapper.writeValueAsString(payload));
            identified = true;
            log.info("QQ Official WS Identify sent (intents={})", intents);
        } catch (Exception e) {
            log.error("Failed to send Identify", e);
        }
    }

    private void startHeartbeat(int intervalMs) {
        if (heartbeatScheduler == null) return;
        lastHeartbeatAckMs = System.currentTimeMillis();
        heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("op", 1);
                payload.put("d", seqNumber());
                send(objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                log.warn("Heartbeat send failed", e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        // 每 30 秒检查一次心跳 ACK 是否超时
        heartbeatTimeoutFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
            long elapsed = System.currentTimeMillis() - lastHeartbeatAckMs;
            if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                log.warn("QQ Official WS heartbeat timeout ({}ms > {}ms), closing connection...", elapsed, HEARTBEAT_TIMEOUT_MS);
                heartbeatTimeout = true;
                close(1011, "Heartbeat timeout");
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private int seqNumber() {
        return 0;
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.warn("QQ Official WS closed: code={}, reason={}, remote={}", code, reason, remote);
        identified = false;
        boolean wasTimeout = heartbeatTimeout;
        heartbeatTimeout = false;
        if (heartbeatFuture != null) heartbeatFuture.cancel(false);
        if (heartbeatTimeoutFuture != null) heartbeatTimeoutFuture.cancel(false);
        if (heartbeatScheduler != null) heartbeatScheduler.shutdownNow();
        if (closeCallback != null) {
            try { closeCallback.accept(code, wasTimeout ? "Heartbeat timeout" : reason); } catch (Exception e) {
                log.warn("Close callback error", e);
            }
        }
    }

    @Override
    public void onError(Exception ex) {
        log.warn("QQ Official WS error: {}", ex.getMessage());
    }
}
