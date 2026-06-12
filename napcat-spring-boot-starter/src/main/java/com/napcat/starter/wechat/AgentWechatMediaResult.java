package com.napcat.starter.wechat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * agent-wechat 媒体获取结果。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentWechatMediaResult {

    @JsonProperty("type")
    private String type;

    private String data;
    private String url;
    private String format;
    private String filename;
}
