package com.dingdong.qqofficial.event;

import com.dingdong.channel.api.ChannelMessageEvent;
import lombok.Getter;
import lombok.Setter;

/**
 * QQ 官方群聊@消息事件。
 */
@Getter
@Setter
public class QqOfficialGroupMessageEvent extends ChannelMessageEvent {
    private String groupOpenid;
    private String userOpenid;
    private String msgId;
    private boolean atBot;
}
