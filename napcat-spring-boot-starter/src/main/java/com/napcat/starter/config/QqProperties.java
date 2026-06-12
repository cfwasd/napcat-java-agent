package com.napcat.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QQ Bot 配置，对应 napcat.qq.* 配置前缀。
 * 从原 NapCatProperties 中拆分的 QQ 专属配置。
 */
@Data
@ConfigurationProperties(prefix = "napcat.qq")
public class QqProperties {

    /** 是否启用 QQ 模块 */
    private boolean enabled = true;

    private AdapterProperties adapter = new AdapterProperties();
    private BotProperties bot = new BotProperties();

    @Data
    public static class AdapterProperties {
        /** 适配器类型：websocket-client / websocket-server / http-client / http-server */
        private String type = "websocket-client";
        private WsClientProperties websocketClient = new WsClientProperties();
        private WsServerProperties websocketServer = new WsServerProperties();
        private HttpClientProperties httpClient = new HttpClientProperties();
        private HttpServerProperties httpServer = new HttpServerProperties();
    }

    @Data
    public static class WsClientProperties {
        private String url = "ws://127.0.0.1:3001";
        private String token = "";
        private long reconnectInterval = 5000;
        private long heartInterval = 30000;
        private boolean debug = false;
    }

    @Data
    public static class WsServerProperties {
        private String host = "0.0.0.0";
        private int port = 3001;
        private String token = "";
        private boolean debug = false;
    }

    @Data
    public static class HttpClientProperties {
        private String url = "http://127.0.0.1:3000";
        private String token = "";
        private long timeout = 30000;
    }

    @Data
    public static class HttpServerProperties {
        private String host = "0.0.0.0";
        private int port = 8080;
        private String path = "/napcat/webhook";
        private String token = "";
        /** 反向 HTTP Client URL，用于主动调用 NapCat API。为空时仅被动接收上报。 */
        private String apiUrl = "";
        /** 反向 HTTP Client Token */
        private String apiToken = "";
        /** 反向 HTTP Client 超时（毫秒） */
        private long apiTimeout = 30000;
    }

    @Data
    public static class BotProperties {
        private long selfId = 0;
        private String commandPrefix = "";
        private boolean atMeTrigger = true;
        private boolean ignoreSelfMessage = true;
        private List<Long> superUsers = new ArrayList<>();
        /** 关键词唤醒列表。消息包含任一唤醒词时视为触发，无需 @。默认：["机器人", "bot"] */
        private List<String> wakeWords = new ArrayList<>(List.of("机器人", "bot"));
    }
}
