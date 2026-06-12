package com.napcat.starter.wechat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * agent-wechat Message 对象。
 * 字段 camelCase，与 agent-wechat REST API 返回一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentWechatMessage {

    private long localId;
    private long serverId;
    private String chatId;
    private String sender;
    private String senderName;
    private int type;
    private String content;
    private String timestamp;

    @JsonProperty("isMentioned")
    private Boolean mentioned;

    @JsonProperty("isSelf")
    private Boolean self;
}
