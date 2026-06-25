package com.dingdong.qqofficial;

import com.dingdong.channel.api.BotChannel;
import com.dingdong.channel.api.ChannelEvent;
import com.dingdong.channel.api.MessageSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

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

    public QqOfficialChannel(QqOfficialProperties properties) {
        this.properties = properties;
        this.tokenManager = new QqOfficialTokenManager(properties.getAppId(), properties.getAppSecret());
        this.api = new QqOfficialApi(properties.getAppId(), tokenManager, properties.isSandbox());
        this.idMapper = new QqOfficialIdMapper();
        this.objectMapper = new ObjectMapper();
    }

    @Override public String getChannelId() { return "qqofficial"; }

    @Override
    public void start() {
        log.info("Starting QQ Official channel...");
        tokenManager.start();
        running = true;
        log.info("QQ Official channel started");
    }

    @Override
    public void stop() {
        running = false;
        tokenManager.stop();
        log.info("QQ Official channel stopped");
    }

    @Override public boolean isConnected() { return running && tokenManager.isTokenValid(); }

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
