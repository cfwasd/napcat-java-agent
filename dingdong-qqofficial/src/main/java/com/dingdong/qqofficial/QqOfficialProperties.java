package com.dingdong.qqofficial;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

/**
 * QQ 官方通道配置。
 */
@Getter
@Setter
public class QqOfficialProperties {
    private boolean enabled = false;
    private String appId;
    private String appSecret;
    private boolean sandbox = false;
    private String triggerMode = "private-all-group-mention-or-wake";
    private List<String> wakeWords;
    private String commandPrefix = "/";
}
