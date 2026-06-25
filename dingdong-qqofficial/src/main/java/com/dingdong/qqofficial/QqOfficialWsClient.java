package com.dingdong.qqofficial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.function.Consumer;

/**
 * QQ 官方 WebSocket 客户端。
 */
@Slf4j
public class QqOfficialWsClient extends WebSocketClient {

    private final ObjectMapper objectMapper;
    private final Consumer<JsonNode> eventHandler;

    public QqOfficialWsClient(URI serverUri, String token, Consumer<JsonNode> eventHandler) {
        super(serverUri);
        addHeader("Authorization", "QQBot " + token);
        this.objectMapper = new ObjectMapper();
        this.eventHandler = eventHandler;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("QQ Official WS connected: status={}", handshake.getHttpStatus());
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String op = json.path("op").asText();
            if ("dispatch".equals(op) || "message".equals(json.path("t").asText())) {
                eventHandler.accept(json);
            }
        } catch (Exception e) {
            log.warn("Failed to parse WS message", e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.warn("QQ Official WS closed: code={}, reason={}, remote={}", code, reason, remote);
    }

    @Override
    public void onError(Exception ex) {
        log.warn("QQ Official WS error", ex);
    }
}
