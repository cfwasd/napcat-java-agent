package com.napcat.starter.wechat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * agent-wechat Chat 对象。
 * 字段 camelCase，与 agent-wechat REST API 返回一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentWechatChat {

    private String id;
    private String username;
    private String name;
    private String remark;
    private String lastMessagePreview;
    private String lastMessageSender;
    private String lastActivityAt;
    private int unreadCount;

    @JsonProperty("isGroup")
    private boolean isGroup;

    private Long lastMsgLocalId;
}
