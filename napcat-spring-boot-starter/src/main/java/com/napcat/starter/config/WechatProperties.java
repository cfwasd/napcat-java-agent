package com.napcat.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 微信 Bot 配置，对应 napcat.wechat.*。
 */
@Data
@ConfigurationProperties(prefix = "napcat.wechat")
public class WechatProperties {

    /** 是否启用微信模块 */
    private boolean enabled = false;

    /** agent-wechat REST API 地址，默认端口 6174 */
    private String apiBaseUrl = "http://127.0.0.1:6174";

    /** agent-wechat token；为空时尝试读取 tokenFile */
    private String token = "";

    /** agent-wechat CLI 默认 token 文件 */
    private String tokenFile = System.getProperty("user.home") + "/.config/agent-wechat/token";

    /** HTTP 请求超时（毫秒） */
    private long apiTimeout = 30000;

    /** 轮询间隔（毫秒） */
    private long pollIntervalMs = 1000;

    /** 每次拉取聊天数量 */
    private int chatLimit = 50;

    /** 每个聊天每次拉取消息数量 */
    private int messageLimit = 20;

    /** 是否回复群聊消息 */
    private boolean replyToGroupMessages = true;

    /** 是否忽略自己发送的消息 */
    private boolean ignoreSelfMessages = true;

    /** 触发模式：all / wake-word / mention-or-wake / private-all-group-mention-or-wake */
    private String triggerMode = "private-all-group-mention-or-wake";

    /** 微信消息唤醒词 */
    private List<String> wakeWords = new ArrayList<>(List.of("小助手", "bot", "机器人"));
}
